/*
 * End-to-end checks against the REAL Android System WebView inside WorkDeck,
 * running on an emulator (GitHub Actions). Talks to the WebView through the
 * Chrome DevTools Protocol (debug builds enable WebView debugging), and to
 * Android through adb.
 *
 * Usage: node tests/android/cdp-test.js <outDir>      (Node 22+: global fetch/WebSocket)
 */
'use strict';
const { execSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const OUT = process.argv[2] || 'ci-out';
const PKG = 'com.workdeck.app';
let failures = 0;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const sh = (c) => { try { return execSync(c, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], timeout: 60000 }); } catch (e) { return String(e.stdout || '') + String(e.stderr || ''); } };
function check(ok, what, detail) {
  const line = (ok ? 'PASS  ' : 'FAIL  ') + what + (detail !== undefined ? '  -> ' + JSON.stringify(detail).slice(0, 300) : '');
  console.log(line);
  try { fs.appendFileSync(path.join(OUT, 'summary.txt'), line + '\n'); } catch (e) {}
  if (!ok) failures++;
}
const notifications = () => sh('adb shell dumpsys notification --noredact');
const ui = (args) => { const r = sh('python3 scripts/ci/ui.py ' + args).trim(); console.log('  ui ' + args + ': ' + r.split('\n').pop()); return r; };
async function waitFor(fn, ms) { for (let t = 0; t < ms; t += 1000) { if (fn()) return true; await sleep(1000); } return fn(); }
const diag = (what) => {
  const lines = sh('adb logcat -d -s WorkDeck/Notifier WorkDeck/Main WorkDeck/Bridge').split('\n').filter((l) => l.trim()).slice(-10);
  const act = (sh('adb shell dumpsys activity activities').match(/(topResumedActivity|mResumedActivity)[^\n]*/) || [''])[0];
  const text = '  [diag ' + what + '] resumed: ' + act.trim().slice(0, 120) + '\n' + lines.map((l) => '    ' + l.trim().slice(0, 160)).join('\n');
  console.log(text);
  try { fs.appendFileSync(path.join(OUT, 'summary.txt'), text + '\n'); } catch (e) {}
};
const wdNotifs = () => notifications().split('NotificationRecord').filter((r) => r.includes('pkg=com.workdeck.app'));
const shot = (name) => sh(`adb exec-out screencap -p > ${path.join(OUT, name + '.png')} && python3 scripts/ci/thumb.py ${path.join(OUT, name + '.png')} ${name} > ${path.join(OUT, name + '.thumb')}`);

async function connect(wsUrl) {
  const ws = new WebSocket(wsUrl);
  await new Promise((res, rej) => {
    const t = setTimeout(() => rej(new Error('DevTools connect timeout')), 10000);
    ws.onopen = () => { clearTimeout(t); res(); };
    ws.onerror = (e) => { clearTimeout(t); rej(e); };
  });
  let id = 0;
  const pending = new Map();
  const handlers = {};
  ws.onmessage = (ev) => {
    const m = JSON.parse(ev.data);
    if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); }
    else if (m.method && handlers[m.method]) handlers[m.method](m.params || {});
  };
  return {
    on(method, fn) { handlers[method] = fn; },
    send(method, params) {
      return new Promise((res) => { const i = ++id; pending.set(i, res); ws.send(JSON.stringify({ id: i, method, params: params || {} })); });
    },
    async eval(expr, timeoutMs) {
      const r = await Promise.race([
        this.send('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true }),
        sleep(timeoutMs || 30000).then(() => ({ result: { exceptionDetails: { exception: { description: 'timeout' } } } })),
      ]);
      if (!r.result) return { __error: JSON.stringify(r.error) };
      if (r.result.exceptionDetails) return { __error: r.result.exceptionDetails.exception && r.result.exceptionDetails.exception.description };
      return r.result.result.value;
    },
    close() { ws.close(); },
  };
}

let devPort = 9300;
async function pages() {
  const unix = sh('adb shell cat /proc/net/unix');
  const m = unix.match(/@(webview_devtools_remote_\d+)/);
  if (!m) return [];
  for (let i = 0; i < 5; i++) {
    // A fresh forwarded port each try: a stuck adb forward must not hang the whole test.
    const port = devPort++;
    sh(`adb forward tcp:${port} localabstract:${m[1]}`);
    try {
      const list = await (await fetch(`http://127.0.0.1:${port}/json/list`, { signal: AbortSignal.timeout(8000) })).json();
      return list.filter((p) => p.type === 'page');
    } catch (e) {
      console.log('  DevTools list failed on port ' + port + ': ' + String(e && e.message || e).slice(0, 80));
      sh(`adb forward --remove tcp:${port}`);
      await sleep(1500);
    }
  }
  return [];
}

async function pageFor(pred) {
  const list = await pages();
  const p = list.find(pred);
  try {
    return p ? await connect(p.webSocketDebuggerUrl) : null;
  } catch (e) {
    console.log('  could not connect to ' + p.url.slice(0, 80) + ': ' + (e && e.message));
    return null;
  }
}

/** Bottom bar: tap an account, then pick Teams or Outlook in its drop-up. */
async function openApp(account, app) {
  if (/tapped/.test(ui('has "isn\'t responding" && python3 scripts/ci/ui.py tap "Close app"'))) await sleep(1500);
  ui(`tap "=${account}"`);
  await sleep(1500);
  ui(`tap "^${app}"`); // "Teams" or "Teams  (3)" in the drop-up
  await sleep(3000);
}

/** Every WebView page with a DevTools connection and the account marker set on it. */
async function connectAll() {
  const out = [];
  for (const p of await pages()) {
    try {
      const c = await connect(p.webSocketDebuggerUrl);
      const acct = await c.eval('window.__ciAcct || null', 8000);
      out.push({ p, c, acct: acct && !acct.__error ? acct : null });
    } catch (e) {}
  }
  return out;
}

(async () => {
  const list = await pages();
  console.log('WebView pages:\n' + list.map((p) => '  ' + p.url.slice(0, 140) + '  [' + p.title + ']').join('\n'));
  check(list.length >= 2, 'both Teams and Outlook WebViews are loaded', list.length);
  const safe = (u) => { try { return decodeURIComponent(u); } catch (e) { return u; } };
  const isTeams = (p) => /teams/i.test(safe(p.url));
  const isOutlook = (p) => /outlook/i.test(safe(p.url)) && !isTeams(p);
  let teams = await pageFor(isTeams) || await pageFor(() => true);
  if (!teams) { console.log('no WebView page reachable over DevTools'); process.exit(1); }

  // 1. Identity + integration script inside a real Microsoft page
  const id = await teams.eval(`({ url: location.href, title: document.title, ua: navigator.userAgent,
      platform: navigator.platform, uaData: navigator.userAgentData && navigator.userAgentData.platform,
      mobile: navigator.userAgentData && navigator.userAgentData.mobile,
      injected: typeof window.__wdCallAction, notif: typeof Notification, perm: typeof Notification !== 'undefined' && Notification.permission,
      text: (document.body && document.body.innerText || '').slice(0, 200) })`);
  console.log(JSON.stringify(id, null, 2));
  check(/Windows NT 10\.0/.test(id.ua || '') && !/; wv\)|Android/.test(id.ua || ''), 'Teams tab presents as desktop Chrome on Windows', id.ua);
  check(id.platform === 'Win32', 'navigator.platform is Win32', id.platform);
  check(id.uaData === 'Windows' && id.mobile === false, 'userAgentData says Windows / not mobile', [id.uaData, id.mobile]);
  check(id.injected === 'function', 'WorkDeck document-start script is injected in the Microsoft page', id.injected);
  check(id.perm === 'granted', 'Notification.permission is granted (POST_NOTIFICATIONS granted)', id.perm);
  const unsupported = /isn.t supported|not supported/i.test(id.text || '');
  console.log(unsupported ? '  NOTE: Teams says this (emulator) WebView version is too old - real phones update it via Play Store'
    : '  Teams accepted the browser version');
  check(!/get the (teams )?app/i.test(id.text || '') || unsupported, 'Microsoft did not show a mobile "get the app" page', id.text);

  // 2. Web notification -> Android notification (app in background)
  sh('adb shell input keyevent KEYCODE_HOME');
  await sleep(2500);
  const resumed = sh('adb shell dumpsys activity activities').match(/(topResumedActivity|mResumedActivity)[^\n]*/);
  console.log('  resumed activity after HOME: ' + (resumed ? resumed[0] : '?'));
  await teams.eval(`new Notification('WorkDeck CI message', { body: 'Hello from the CI test', tag: 'ci-msg' }), 'ok'`);
  const shown = await waitFor(() => wdNotifs().some((r) => r.includes('WorkDeck CI message')), 10000);
  check(shown, 'page notification shows as an Android notification');
  check(wdNotifs().some((r) => r.includes('WorkDeck CI message') && r.includes('channel=teams')), 'posted on the "Teams messages" channel');
  if (!shown) diag('page notification');
  let n;

  // 3. Incoming-call style notification -> call alert channel; closing it from the page cancels it
  await teams.eval(`window.__ciCall = new Notification('CI Caller', { body: 'Incoming call', requireInteraction: true }), 'ok'`);
  await waitFor(() => wdNotifs().some((r) => r.includes('CI Caller')), 10000);
  check(wdNotifs().some((r) => r.includes('CI Caller') && r.includes('channel=calls')), 'incoming call shows a call alert (calls channel)');
  shot('03-call-alert');
  await teams.eval(`window.__ciCall.close(), 'ok'`);
  await waitFor(() => !wdNotifs().some((r) => r.includes('CI Caller')), 8000);
  const lingering = wdNotifs().filter((r) => r.includes('CI Caller'));
  check(lingering.length === 0, 'call alert disappears when the page closes it');
  if (lingering.length) {
    console.log('  lingering record: ' + lingering[0].slice(0, 500).replace(/\n/g, ' '));
    console.log('  logcat: ' + sh('adb logcat -d -s WorkDeck/Notifier WorkDeck/Main').split('\n').slice(-12).join('\n  '));
  }

  // 3b. Unread count goes up but the site sends no notification -> WorkDeck posts one.
  // (Wait out the 12 s window in which a real site notification suppresses the fallback.)
  await sleep(13000);
  await teams.eval(`document.title = 'Chat | Microsoft Teams', 'ok'`);
  await sleep(1500);
  await teams.eval(`document.title = '(3) Chat | Microsoft Teams', 'ok'`);
  const unreadShown = await waitFor(() => wdNotifs().some((r) => r.includes('3 unread in Teams')), 12000);
  let unreadOk = unreadShown;
  if (!unreadShown && /taken as baseline/.test(sh('adb logcat -d -s WorkDeck/Notifier'))) {
    // The (sign-in) page had just reloaded, so that count was treated as a baseline: try again.
    console.log('  page had just reloaded; retrying the unread check');
    await sleep(22000);
    await teams.eval(`document.title = '(5) Chat | Microsoft Teams', 'ok'`);
    unreadOk = await waitFor(() => wdNotifs().some((r) => r.includes('5 unread in Teams')), 12000);
  }
  check(unreadOk, 'unread count rising (no site notification) posts an "N unread in Teams" notification');
  if (!unreadOk) diag('unread');
  await teams.eval(`document.title = 'Chat | Microsoft Teams', 'ok'`);
  const unreadGone = await waitFor(() => !wdNotifs().some((r) => r.includes('unread in Teams')), 12000);
  check(unreadGone, 'unread notification removed when the count drops to 0');

  // 3c. Teams' own incoming-call screen in the background -> ringing call alert
  await teams.eval(`(function () { var d = document.createElement('div'); d.id = 'ci-call'; d.setAttribute('role', 'alertdialog');
    d.innerHTML = '<div>CI Video Caller</div><div>is calling you</div><button aria-label="Accept video call">v</button>' +
      '<button aria-label="Accept audio call">a</button><button aria-label="Decline call">x</button>';
    document.body.appendChild(d); return 'ok'; })()`);
  let screenRing = await waitFor(() => wdNotifs().some((r) => r.includes('CI Video Caller') && r.includes('channel=calls')), 10000);
  if (!screenRing) {
    // The page may have been replaced (e.g. an outdated emulator WebView's reloading "unsupported" page): retry once.
    await teams.eval(`(function () { if (document.getElementById('ci-call')) return 'present'; var d = document.createElement('div'); d.id = 'ci-call';
      d.setAttribute('role', 'alertdialog'); d.innerHTML = '<div>CI Video Caller</div><button aria-label="Accept video call">v</button><button aria-label="Decline call">x</button>';
      document.body.appendChild(d); return 'ok'; })()`);
    screenRing = await waitFor(() => wdNotifs().some((r) => r.includes('CI Video Caller') && r.includes('channel=calls')), 10000);
  }
  check(screenRing, 'Teams call screen (Accept/Decline) in background rings with a call alert');
  if (!screenRing) diag('call screen');
  await teams.eval(`(document.getElementById('ci-call') || { remove() {} }).remove(), 'ok'`);
  const screenGone = await waitFor(() => !wdNotifs().some((r) => r.includes('CI Video Caller')), 10000);
  check(screenGone, 'call alert stops when Teams removes its call screen');

  // 4. Microphone through WebRTC getUserMedia + call detection + background survival
  sh(`adb shell am start -n ${PKG}/.ui.MainActivity`);
  await sleep(3000);
  const mic = await teams.eval(`navigator.mediaDevices.getUserMedia({ audio: true }).then(
      (s) => { window.__ciStream = s; return 'ok:' + s.getAudioTracks().length + ':' + s.getAudioTracks()[0].readyState; },
      (e) => 'ERR:' + e.name + ':' + e.message)`);
  check(String(mic).startsWith('ok:1:live'), 'getUserMedia({audio}) is granted with a live mic track', mic);
  await sleep(7000);
  n = notifications();
  check(n.includes('Teams call in progress'), 'call detected: "call in progress" notification with audio controls');
  const audio = sh('adb shell dumpsys audio');
  const mode = (audio.match(/mode\s*=?\s*:?\s*(\w+)/i) || [])[0];
  console.log('audio mode line: ' + mode);
  shot('04-in-call');
  sh('adb shell input keyevent KEYCODE_HOME');
  await sleep(6000);
  const stillMic = await teams.eval(`window.__ciStream.getAudioTracks()[0].readyState`);
  check(stillMic === 'live', 'mic track stays live with the app in the background', stillMic);
  check(notifications().includes('Teams call in progress'), 'call state kept while in background');
  const svc = sh(`adb shell dumpsys activity services ${PKG}`);
  const types = (svc.match(/foregroundServiceType=0x[0-9a-f]+|types=[^\n]+/i) || [''])[0];
  console.log('service types: ' + types);
  await teams.eval(`window.__ciStream.getTracks().forEach((t) => t.stop()), 'ok'`);
  await sleep(10000);
  check(!notifications().includes('Teams call in progress'), 'call state cleared after the mic is released');

  // 4b. Teams-facing media state (the fix for "mic/camera/speaker not detected")
  sh(`adb shell am start -n ${PKG}/.ui.MainActivity`);
  await sleep(3000);
  const native = await teams.eval(`({ permissionsApi: typeof Navigator.prototype.permissions, provided: typeof navigator.permissions })`, 10000);
  console.log('  Permissions API in this WebView: ' + JSON.stringify(native));
  const fixed = await teams.eval(`Promise.all(['microphone', 'camera'].map((n) => navigator.permissions.query({ name: n }).then((st) => st.state)))`, 10000);
  check(Array.isArray(fixed) && fixed[0] === 'granted' && fixed[1] === 'granted', 'Teams now sees microphone + camera as granted', fixed);
  const devs = await teams.eval(`navigator.mediaDevices.enumerateDevices().then((l) => l.map((d) => d.kind + ':' + d.label))`, 15000);
  const dl = Array.isArray(devs) ? devs : [];
  check(dl.some((d) => /^audioinput:./.test(d)), 'Teams sees a named phone microphone', devs);
  check(dl.some((d) => /^videoinput:(Front|Back) camera$/.test(d)), 'Teams sees the phone camera(s)', devs);
  check(dl.includes('audiooutput:Phone speaker (loud)'), 'Teams sees speaker outputs', devs);
  const cam = await teams.eval(`Promise.race([
      navigator.mediaDevices.getUserMedia({ audio: { deviceId: { exact: 'default' } }, video: { deviceId: { exact: 'wd-camera-front' } } })
        .then((st) => { window.__ciCamStream = st; return 'ok:' + st.getAudioTracks().length + 'a+' + st.getVideoTracks().length + 'v'; },
              (e) => 'ERR:' + e.name + ':' + e.message),
      new Promise((r) => setTimeout(() => r('no answer after 20s'), 20000))])`, 30000);
  check(String(cam).startsWith('ok:1a+1v'), 'mic + camera open with the device ids Teams was given', cam);
  if (!String(cam).startsWith('ok')) {
    // Emulated cameras can be flaky: still test routing with an audio-only call.
    await teams.eval(`navigator.mediaDevices.getUserMedia({ audio: { deviceId: { exact: 'default' } } }).then((st) => { window.__ciCamStream = st; return 'ok'; }, (e) => 'ERR:' + e.name)`, 20000);
  }
  await sleep(6000);
  const sink = await teams.eval(`(async () => { const a = new Audio(); await a.setSinkId('wd-route-earpiece'); return a.sinkId; })()`);
  await sleep(2500);
  const routeLog = sh('adb logcat -d -s WorkDeck/Call').split('\n').filter((l) => /route[= ]/.test(l)).pop() || '';
  check(sink === 'wd-route-earpiece' && /route=EARPIECE/.test(routeLog), 'choosing "Phone earpiece" in Teams switches Android call audio', [sink, routeLog.trim().slice(-45)]);
  await teams.eval(`window.__ciCamStream && window.__ciCamStream.getTracks().forEach((t) => t.stop()), 'ok'`);
  await sleep(8000);

  // 5. Zoom buttons change the page scale
  sh(`adb shell am start -n ${PKG}/.ui.MainActivity`);
  await sleep(2000);
  const before = await teams.eval('window.visualViewport ? window.visualViewport.scale : 0');
  ui('tap "Zoom controls"');
  await sleep(800);
  ui('tap "Zoom in"');
  await sleep(1200);
  ui('tap "Zoom in"');
  await sleep(1500);
  const after = await teams.eval('window.visualViewport ? window.visualViewport.scale : 0');
  check(after > before * 1.1, 'zoom-in button enlarges the page', [before, after]);
  shot('05-zoomed');
  ui('tap "Fit"');
  await sleep(1000);
  ui('tap "Zoom controls"');
  teams.close();

  // 6. Desktop/mobile switch on Outlook: mobile view uses the phone (Android) identity
  await openApp('CRAFTWW', 'Outlook');
  ui('tap "Switch desktop / mobile view"');
  let o = null;
  for (let i = 0; i < 12; i++) {
    await sleep(3000);
    const outlook = await pageFor(isOutlook);
    if (!outlook) continue;
    o = await outlook.eval(`({ ua: navigator.userAgent, platform: navigator.platform, mobile: navigator.userAgentData && navigator.userAgentData.mobile, url: location.href })`);
    outlook.close();
    if (o && /Android/.test(o.ua || '') && o.mobile === true) break;
  }
  if (o) {
    console.log(JSON.stringify(o));
    check(/Android/.test(o.ua) && !/; wv\)/.test(o.ua), 'Outlook mobile view uses Chrome-for-Android identity (no "wv")', o.ua);
    check(o.mobile === true, 'userAgentData.mobile is true in mobile view', o.mobile);
  } else {
    check(false, 'Outlook page reachable over DevTools');
  }
  shot('06-outlook-mobile');
  ui('tap "Switch desktop / mobile view"');
  await sleep(3000);
  await openApp('CRAFTWW', 'Teams');

  // 7. Outlook: new mail raises the Inbox unread count -> Android notification.
  // A stand-in Outlook inbox is served at a real Outlook address through DevTools
  // (the CI has no Microsoft account), so WorkDeck's script runs on it like on Outlook.
  sh('adb shell input keyevent KEYCODE_HOME');
  await sleep(1500);
  const ol = await pageFor(isOutlook);
  if (!ol) {
    check(false, 'Outlook page reachable for the new-mail test');
  } else {
    const FAKE = 'https://outlook.office.com/mail/wd-ci-inbox';
    const html = `<!doctype html><html><head><title>Mail - CI - Outlook</title></head><body>
      <div role="tree"><div role="treeitem" data-folder-name="inbox" title="Inbox"><span>Inbox</span><span id="count">2</span></div>
      <div role="treeitem" title="Sent Items"><span>Sent Items</span></div></div>
      <div role="listbox" id="mails"><div role="option" aria-label="Unread, Old Sender, Earlier mail, 9:00 AM"><div>Old Sender</div><div>Earlier mail</div></div></div>
      </body></html>`;
    const fe = await ol.send('Fetch.enable', { patterns: [{ urlPattern: FAKE + '*', requestStage: 'Request' }] });
    ol.on('Fetch.requestPaused', (p) => ol.send('Fetch.fulfillRequest', { requestId: p.requestId, responseCode: 200,
      responseHeaders: [{ name: 'Content-Type', value: 'text/html; charset=utf-8' }], body: Buffer.from(html).toString('base64') }));
    if (fe.error) console.log('  NOTE: DevTools request interception unavailable: ' + JSON.stringify(fe.error));
    await ol.send('Page.navigate', { url: FAKE });
    await sleep(4000);
    const st = await ol.eval(`({ url: location.href, injected: typeof window.__wdUnreadPreview, count: (document.getElementById('count') || {}).textContent })`, 10000);
    check(st && st.url === FAKE && st.injected === 'function' && st.count === '2', 'stand-in Outlook inbox loaded with the WorkDeck script', st);
    await sleep(24000); // past the "Outlook is still loading" window, in which counts are a baseline
    await ol.eval(`(function () { var row = document.createElement('div'); row.setAttribute('role', 'option');
      row.setAttribute('aria-label', 'Unread, CI Sender, Quarterly numbers, 10:01 AM, Here are the numbers');
      row.innerHTML = '<div>CI Sender</div><div>Quarterly numbers</div><div>10:01 AM</div><div>Here are the numbers</div>';
      var list = document.getElementById('mails'); list.insertBefore(row, list.firstChild);
      document.getElementById('count').textContent = '3'; return 'ok'; })()`, 10000);
    const mailShown = await waitFor(() => wdNotifs().some((r) => r.includes('3 unread in Outlook')), 15000);
    check(mailShown, 'new Outlook mail (Inbox unread count up) posts an Android notification');
    const rec = wdNotifs().find((r) => r.includes('3 unread in Outlook')) || '';
    check(rec.includes('channel=outlook'), 'posted on the "Outlook mail" channel');
    check(rec.includes('CI Sender'), 'notification names the sender of the newest mail');
    if (!mailShown) diag('outlook new mail');
    shot('07-outlook-mail');
    await ol.eval(`document.getElementById('count').textContent = '', 'ok'`, 10000);
    const mailGone = await waitFor(() => !wdNotifs().some((r) => r.includes('unread in Outlook')), 15000);
    check(mailGone, 'Outlook notification goes away when the Inbox has no unread mail');
    await ol.send('Fetch.disable');
    ol.close();
  }

  // 8. Second account (OMC): its own WebView profile (separate sign-in) and notifications
  const profiles = /multiProfile=true/.test(sh('adb logcat -d -s WorkDeck/WVSupport'));
  console.log('  WebView multi-profile support: ' + profiles);
  for (const x of await connectAll()) { await x.c.eval('window.__ciAcct = "CRAFTWW"', 8000); x.c.close(); }
  sh(`adb shell am start -W -n ${PKG}/.ui.MainActivity`);
  await sleep(4000);
  await openApp('OMC', 'Teams');
  await sleep(15000);
  shot('08-omc');
  const all = await connectAll();
  const fresh = all.filter((x) => x.acct !== 'CRAFTWW');
  console.log('  pages after opening OMC:\n' + all.map((x) => '    ' + (x.acct || 'new') + '  ' + safe(x.p.url).slice(0, 100)).join('\n'));
  check(fresh.length >= 1, 'opening OMC creates its own WebView', fresh.length);
  if (fresh.length && profiles) {
    const omc = fresh.find((x) => isTeams(x.p)) || fresh[0];
    const craft = (all.find((x) => x.acct === 'CRAFTWW' && isTeams(x.p)) || all.find((x) => x.acct === 'CRAFTWW'));
    const LOGIN = 'https://login.microsoftonline.com/';
    const hasCi = (r) => ((r && r.result && r.result.cookies) || []).some((c) => c.name === 'wdci');
    await craft.c.send('Network.setCookie', { name: 'wdci', value: 'craftww', url: LOGIN, secure: true });
    const inCraft = await craft.c.send('Network.getCookies', { urls: [LOGIN] });
    const inOmc = await omc.c.send('Network.getCookies', { urls: [LOGIN] });
    check(hasCi(inCraft), 'a sign-in cookie set in CRAFTWW is stored', (inCraft.result || inCraft.error || {}).cookies ? 'ok' : inCraft);
    check(!hasCi(inOmc), 'OMC cannot see CRAFTWW cookies (separate sign-in per account)');
    await craft.c.send('Network.deleteCookies', { name: 'wdci', url: LOGIN });
    sh('adb shell input keyevent KEYCODE_HOME');
    await sleep(2500);
    await omc.c.eval(`new Notification('OMC CI message', { body: 'from the second account', tag: 'ci-omc' }), 'ok'`, 10000);
    const omcShown = await waitFor(() => wdNotifs().some((r) => r.includes('OMC CI message')), 10000);
    check(omcShown, 'OMC page notification shows as an Android notification');
    const orec = wdNotifs().find((r) => r.includes('OMC CI message')) || '';
    check(orec.includes('OMC \u00b7 Teams'), 'notification is labelled with its account ("OMC \u00b7 Teams")', (orec.match(/android\.subText=[^\n]*/) || [''])[0]);
    if (!omcShown) diag('OMC notification');
  } else if (fresh.length) {
    const txt = await fresh[0].c.eval('document.body ? document.body.innerText : ""', 8000);
    console.log('  NOTE: this emulator WebView cannot keep accounts apart (phones get it via a WebView update)');
    check(/update Android System WebView/i.test(String(txt)), 'without profile support, OMC asks for a WebView update instead of mixing sign-ins', String(txt).slice(0, 120));
  }
  for (const x of all) x.c.close();
  sh(`adb shell am start -n ${PKG}/.ui.MainActivity`);
  await sleep(2500);
  await openApp('CRAFTWW', 'Teams');

  console.log(failures ? `\n${failures} WebView check(s) FAILED` : '\nall WebView checks passed');
  process.exit(failures ? 1 : 0);
})().catch((e) => { console.log('cdp-test crashed: ' + (e && e.stack || e)); process.exit(1); });
