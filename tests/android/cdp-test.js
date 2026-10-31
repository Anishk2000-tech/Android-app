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
  console.log((ok ? 'PASS  ' : 'FAIL  ') + what + (detail !== undefined ? '  -> ' + JSON.stringify(detail) : ''));
  if (!ok) failures++;
}
const notifications = () => sh('adb shell dumpsys notification --noredact');
const ui = (args) => sh('python3 scripts/ci/ui.py ' + args);
const shot = (name) => sh(`adb exec-out screencap -p > ${path.join(OUT, name + '.png')}`);

async function connect(wsUrl) {
  const ws = new WebSocket(wsUrl);
  await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
  let id = 0;
  const pending = new Map();
  ws.onmessage = (ev) => {
    const m = JSON.parse(ev.data);
    if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); }
  };
  return {
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

async function pages() {
  const unix = sh('adb shell cat /proc/net/unix');
  const m = unix.match(/@(webview_devtools_remote_\d+)/);
  if (!m) return [];
  sh(`adb forward tcp:9222 localabstract:${m[1]}`);
  for (let i = 0; i < 10; i++) {
    try {
      const list = await (await fetch('http://127.0.0.1:9222/json/list', { signal: AbortSignal.timeout(10000) })).json();
      return list.filter((p) => p.type === 'page');
    } catch (e) { await sleep(1000); }
  }
  return [];
}

async function pageFor(pred) {
  const list = await pages();
  const p = list.find(pred);
  return p ? connect(p.webSocketDebuggerUrl) : null;
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
  check(!/get the (teams )?app|download the app/i.test(id.text || ''), 'Microsoft did not show a "get the app" page', id.text);

  // 2. Web notification -> Android notification (app in background)
  sh('adb shell input keyevent KEYCODE_HOME');
  await sleep(2500);
  await teams.eval(`new Notification('WorkDeck CI message', { body: 'Hello from the CI test', tag: 'ci-msg' }), 'ok'`);
  await sleep(3000);
  let n = notifications();
  check(n.includes('WorkDeck CI message'), 'page notification shows as an Android notification');
  check(/channel=teams|Channel\{mId='teams'|mId='teams'/.test(n), 'posted on the "Teams messages" channel');

  // 3. Incoming-call style notification -> call alert channel; closing it from the page cancels it
  await teams.eval(`window.__ciCall = new Notification('CI Caller', { body: 'Incoming call', requireInteraction: true }), 'ok'`);
  await sleep(3000);
  n = notifications();
  check(n.includes('CI Caller') && /calls/.test(n), 'incoming call shows a call alert');
  shot('03-call-alert');
  await teams.eval(`window.__ciCall.close(), 'ok'`);
  await sleep(2000);
  check(!notifications().includes('CI Caller'), 'call alert disappears when the page closes it');

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
  ui('tap "Show Outlook"');
  await sleep(2500);
  ui('tap "Switch desktop / mobile view"');
  await sleep(12000);
  const outlook = await pageFor(isOutlook);
  if (outlook) {
    const o = await outlook.eval(`({ ua: navigator.userAgent, platform: navigator.platform, mobile: navigator.userAgentData && navigator.userAgentData.mobile, url: location.href })`);
    console.log(JSON.stringify(o));
    check(/Android/.test(o.ua) && !/; wv\)/.test(o.ua), 'Outlook mobile view uses Chrome-for-Android identity (no "wv")', o.ua);
    check(o.mobile === true, 'userAgentData.mobile is true in mobile view', o.mobile);
    outlook.close();
  } else {
    check(false, 'Outlook page reachable over DevTools');
  }
  shot('06-outlook-mobile');
  ui('tap "Switch desktop / mobile view"');
  await sleep(3000);
  ui('tap "Show Teams"');

  console.log(failures ? `\n${failures} WebView check(s) FAILED` : '\nall WebView checks passed');
  process.exit(failures ? 1 : 0);
})().catch((e) => { console.log('cdp-test crashed: ' + (e && e.stack || e)); process.exit(1); });
