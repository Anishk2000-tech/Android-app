/*
 * Device test for the single-app editions (OMC Teams, OMC Outlook, CRAFTWW Teams,
 * CRAFTWW Outlook) on an Android emulator, over the WebView DevTools protocol + uiautomator.
 *
 *   node tests/android/solo-test.js OUT_DIR      (the apps must be installed already)
 *
 * Prints PASS/FAIL lines (also appended to OUT_DIR/../summary.txt); exit code 1 on failure.
 */
'use strict';
const { execSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const OUT = process.argv[2] || 'ci-out/solo';
fs.mkdirSync(OUT, { recursive: true });
const SUMMARY = path.join(path.dirname(OUT), 'summary.txt');
const APPS = {
  omcTeams: { pkg: 'com.workdeck.omc.teams', name: 'OMC Teams', teams: true },
  omcOutlook: { pkg: 'com.workdeck.omc.outlook', name: 'OMC Outlook', teams: false },
  craftwwTeams: { pkg: 'com.workdeck.craftww.teams', name: 'CRAFTWW Teams', teams: true },
  craftwwOutlook: { pkg: 'com.workdeck.craftww.outlook', name: 'CRAFTWW Outlook', teams: false },
};
const MAIN = 'com.workdeck.app.ui.MainActivity';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const sh = (c) => { try { return execSync(c, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], timeout: 90000 }); } catch (e) { return String(e.stdout || '') + String(e.stderr || ''); } };
let failures = 0;
function check(what, ok, detail) {
  const line = (ok ? 'PASS  ' : 'FAIL  ') + '[solo] ' + what + (detail !== undefined ? '  -> ' + JSON.stringify(detail).slice(0, ok ? 300 : 1200) : '');
  console.log(line);
  fs.appendFileSync(SUMMARY, line + '\n');
  if (!ok) failures++;
}
async function waitFor(fn, ms) { for (let t = 0; t < ms; t += 1000) { if (fn()) return true; await sleep(1000); } return fn(); }
const ui = (args) => sh(`python3 scripts/ci/ui.py ${args}`);
const texts = (name) => { dismiss(); const t = ui('texts'); fs.writeFileSync(path.join(OUT, name + '.txt'), t); return t; };
const shot = (name) => sh(`adb exec-out screencap -p > ${path.join(OUT, name + '.png')} && python3 scripts/ci/thumb.py ${path.join(OUT, name + '.png')} solo-${name} > ${path.join(OUT, name + '.thumb')}`);
const has = (label) => sh(`python3 scripts/ci/ui.py has "${label}" >/dev/null 2>&1 && echo YES`).includes('YES');
function dismiss() {
  for (let i = 0; i < 3; i++) {
    if (has("isn't responding")) ui(has('OMC isn') || has('CRAFTWW isn') || has('WorkDeck isn') ? 'tap "=Wait"' : 'tap "Close app"');
    else if (has('Please update Android System WebView')) {
      // Old emulator WebViews (Chrome 91 on Android 12) are too old for Teams: WorkDeck says so.
      if (!/tapped/.test(ui('tap "=CANCEL"'))) ui('tap "=Cancel"');
    }
    else return;
  }
}
const callLog = () => sh('adb logcat -d -s WorkDeck/Call');
const mainLog = () => sh('adb logcat -d -s WorkDeck/Main');
const topPackage = () => {
  const d = sh('adb shell dumpsys activity activities');
  const m = d.match(/topResumedActivity=ActivityRecord\{[^ ]+ [^ ]+ ([^/ ]+)\//) || d.match(/mResumedActivity: ActivityRecord\{[^ ]+ [^ ]+ ([^/ ]+)\//);
  return m ? m[1] : '';
};

// ---------------------------------------------------------------- DevTools
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
  const send = (method, params) => new Promise((res) => { const i = ++id; pending.set(i, res); ws.send(JSON.stringify({ id: i, method, params: params || {} })); });
  return {
    send,
    on(method, fn) { handlers[method] = fn; },
    async eval(expr, timeoutMs, gesture) {
      const r = await Promise.race([
        send('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true, userGesture: !!gesture }),
        sleep(timeoutMs || 30000).then(() => ({ result: { exceptionDetails: { exception: { description: 'timeout' } } } })),
      ]);
      if (!r.result) return { __error: JSON.stringify(r.error) };
      if (r.result.exceptionDetails) return { __error: r.result.exceptionDetails.exception && r.result.exceptionDetails.exception.description };
      return r.result.result.value;
    },
    close() { ws.close(); },
  };
}
let devPort = 9400;
async function pagesOf(pkg) {
  const pid = sh(`adb shell pidof ${pkg}`).trim().split(/\s+/)[0];
  if (!pid) return [];
  const m = sh('adb shell cat /proc/net/unix').match(new RegExp('@(webview_devtools_remote_' + pid + ')\\b'));
  if (!m) return [];
  for (let i = 0; i < 4; i++) {
    const port = devPort++;
    sh(`adb forward tcp:${port} localabstract:${m[1]}`);
    try {
      const list = await (await fetch(`http://127.0.0.1:${port}/json/list`, { signal: AbortSignal.timeout(8000) })).json();
      return list.filter((p) => p.type === 'page');
    } catch (e) {
      sh(`adb forward --remove tcp:${port}`);
      await sleep(1500);
    }
  }
  return [];
}
/** The app's page whose URL matches (and has WorkDeck's script), connected. */
async function page(pkg, re, ms) {
  for (let t = 0; t < (ms || 30000); t += 3000) {
    const p = (await pagesOf(pkg)).find((x) => re.test(x.url || ''));
    if (p) {
      try {
        const c = await connect(p.webSocketDebuggerUrl);
        const ok = await c.eval('typeof window.__wdInstalled', 8000);
        if (ok === 'boolean') { c.url = p.url; return c; }
        c.close();
      } catch (e) { /* retry */ }
    }
    await sleep(3000);
  }
  return null;
}

// ---------------------------------------------------------------- steps
async function firstRun(key) {
  const app = APPS[key];
  sh(`adb shell am start -W -n ${app.pkg}/${MAIN}`);
  await sleep(5000);
  const t = texts(key + '-setup');
  shot(key + '-setup');
  check(`${app.name}: first launch shows its own permissions screen`, t.includes(app.name) && /Allow everything/.test(t), t.split('\n').slice(0, 4).join(' | '));
  check(`${app.name}: ${app.teams ? 'asks for microphone + camera (calls)' : 'no microphone / camera (no calls in Outlook)'}`,
    app.teams ? /Microphone/.test(t) && /Camera/.test(t) : !/Microphone/.test(t) && !/Camera/.test(t));
  ui(`tap "=Start ${app.name}" 6`);
  await sleep(9000);
}

async function mainScreen(key, expectWindows) {
  const app = APPS[key];
  const t = texts(key + '-main');
  shot(key + '-main');
  check(`${app.name}: only the page and the Aa control on screen (no bars, accounts or menus)`,
    /Text size and zoom/.test(t) && !/Switch desktop \/ mobile view/.test(t) && !/Add account/.test(t) && !/More options/.test(t),
    t.split('\n').filter((l) => /account|Switch|Text size|More/.test(l)).join(' | '));
  const p = await page(app.pkg, /microsoft|office|live\.com|outlook/i, 45000);
  if (!p) { check(false, `${app.name}: its page is reachable over DevTools`); return null; }
  const id = await p.eval(`({ ua: navigator.userAgent, w: window.innerWidth, url: location.href,
      unsupported: /browser version isn.t supported|browser isn.t supported/i.test(document.body ? document.body.innerText : '') })`, 10000);
  console.log('  ' + JSON.stringify(id));
  if (id && id.unsupported) {
    // Teams' own "browser not supported" page (old emulator WebView) has no mobile layout at all.
    console.log(`  NOTE: ${app.name}: Microsoft shows its "browser not supported" page on this old emulator WebView - layout check skipped`);
  } else {
    check(`${app.name}: mobile view (phone-width layout)`, id && id.w > 0 && id.w < 700, id && id.w);
  }
  check(`${app.name}: presents ${expectWindows ? 'a computer browser (Windows)' : 'a phone browser (Android)'}`,
    id && (expectWindows ? /Windows NT/.test(id.ua) : /Android/.test(id.ua) && !/Windows/.test(id.ua)), id && id.ua.slice(0, 70));
  const notif = sh('adb shell dumpsys notification --noredact');
  check(`${app.name}: its running notification names it ("${app.name} is connected")`, notif.includes(`${app.name} is connected`));
  return p;
}

async function textSize(key) {
  const app = APPS[key];
  ui('tap "Text size and zoom"');
  await sleep(1500);
  let t = texts(key + '-zoom');
  const before = (t.match(/(\d+)%/) || [])[1];
  ui('tap "=A+"');
  await sleep(1200);
  t = texts(key + '-zoom2');
  const after = (t.match(/(\d+)%/) || [])[1];
  shot(key + '-zoom');
  check(`${app.name}: Aa opens text size / zoom; A+ makes text bigger`, before && after && Number(after) > Number(before), [before, after]);
  ui('tap "=A\u2212"');
  await sleep(800);
  ui('tap "Text size and zoom"');
  await sleep(800);
}

const CALL = `(async () => {
  const wait = (ms) => new Promise((res) => setTimeout(res, ms));
  const connectPair = async (a, b) => {
    a.onicecandidate = (e) => e.candidate && b.addIceCandidate(e.candidate);
    b.onicecandidate = (e) => e.candidate && a.addIceCandidate(e.candidate);
    const o = await a.createOffer(); await a.setLocalDescription(o); await b.setRemoteDescription(o);
    const an = await b.createAnswer(); await b.setLocalDescription(an); await a.setRemoteDescription(an);
    for (let i = 0; i < 60 && a.connectionState !== 'connected'; i++) await wait(100);
  };
  // Teams re-applies its saved microphone when a call starts.
  window.__ciMic = await navigator.mediaDevices.getUserMedia({ audio: { deviceId: { exact: 'wd-mic-phone' } } });
  window.__ciA = new RTCPeerConnection(); window.__ciB = new RTCPeerConnection();
  window.__ciA.addTrack(window.__ciMic.getAudioTracks()[0], window.__ciMic);
  window.__ciB.addTransceiver('audio', { direction: 'recvonly' });
  await connectPair(window.__ciA, window.__ciB);
  return window.__ciA.connectionState;
})()`;

async function calls(key, p) {
  const app = APPS[key];
  sh('adb logcat -c');
  const st = await p.eval(CALL, 30000);
  console.log('  call connection: ' + JSON.stringify(st));
  const started = await waitFor(() => /inCall=true/.test(callLog()), 12000);
  const log = callLog();
  check(`${app.name}: a call (connection sending the microphone) is detected`, started && /call connections 1/.test(log),
    log.split('\n').filter((l) => /inCall|connections/.test(l)).slice(-3).join(' | '));
  check(`${app.name}: Teams' saved microphone at call start is not taken as the user's choice`, /page microphone phone \(saved preference, not applied\)/.test(log),
    (log.match(/page microphone[^\n]*/) || [''])[0]);
  check(`${app.name}: call audio goes to a device of this phone`, /route=(SPEAKER|EARPIECE|BLUETOOTH|WIRED)/.test(log), (log.match(/route=\w+/g) || []).slice(-1)[0]);
  await sleep(1500);
  const t = texts(key + '-incall');
  shot(key + '-incall');
  check(`${app.name}: during a call a button shows where the sound comes from`, /Call audio: tap to choose/.test(t) && /\n(Speaker|Earpiece|Bluetooth|Headset)\n/.test('\n' + t + '\n'));
  ui('tap "Call audio: tap to choose"');
  await sleep(1500);
  const chooser = texts(key + '-route-chooser');
  check(`${app.name}: tapping it offers the call's audio devices`, /Speaker/.test(chooser), chooser.split('\n').slice(0, 6).join(' | '));
  sh('adb shell input keyevent KEYCODE_BACK');
  await sleep(800);
  // Teams closes the microphone (e.g. switching devices): the call - and its audio device - go on.
  sh('adb logcat -c');
  await p.eval(`window.__ciMic.getTracks().forEach((t) => t.stop()), 'ok'`, 8000);
  await sleep(7000);
  check(`${app.name}: the call goes on while Teams closes the microphone`, !/inCall=false/.test(callLog()));
  await p.eval(`window.__ciA.close(), window.__ciB.close(), 'ok'`, 8000);
  const ended = await waitFor(() => /inCall=false/.test(callLog()), 12000);
  check(`${app.name}: the call ends when the connection closes`, ended);
  await sleep(1500);
  const after = texts(key + '-aftercall');
  check(`${app.name}: the call-audio button goes away after the call`, !/Call audio: tap to choose/.test(after));
}

async function links(key, p) {
  const app = APPS[key];
  const click = (url) => `(() => { const a = document.createElement('a'); a.href = '${url}'; a.target = '_blank'; a.rel = 'noopener';
      document.body.appendChild(a); a.click(); a.remove(); return 'ok'; })()`;
  await p.eval(click('https://example.com/'), 8000, true);
  await sleep(6000);
  let t = texts(key + '-link');
  shot(key + '-link');
  check(`${app.name}: a link opens inside the app (same sign-in) with a × to close it`, /Close this tab/.test(t) && /example\.com/.test(t),
    t.split('\n').filter((l) => /Close|example/.test(l)).join(' | '));
  sh('adb shell input keyevent KEYCODE_BACK');
  await sleep(2500);
  t = texts(key + '-link-closed');
  check(`${app.name}: back closes the link and returns to ${app.teams ? 'Teams' : 'Outlook'}`, !/Close this tab/.test(t));
}

// Teams' page failing with net::ERR_FAILED (seen right after a fresh sign-in): the app
// reloads, then clears the site's service worker / stored files and loads again, and only
// then shows its own error screen with Retry - which works once the page loads again.
async function selfHeal(p) {
  sh('adb logcat -c');
  let failing = true;
  await p.send('Fetch.enable', { patterns: [{ urlPattern: 'https://teams.microsoft.com/*', requestStage: 'Request' }] });
  p.on('Fetch.requestPaused', (e) => {
    if (failing && e.resourceType === 'Document') p.send('Fetch.failRequest', { requestId: e.requestId, errorReason: 'Failed' });
    else p.send('Fetch.continueRequest', { requestId: e.requestId });
  });
  await p.send('Page.navigate', { url: 'https://teams.microsoft.com/v2/' });
  const healed = await waitFor(() => /removed https:\/\/teams\.microsoft\.com's service worker/.test(sh('adb logcat -d -s WorkDeck/Tab')), 20000);
  const log = sh('adb logcat -d -s WorkDeck/Tab');
  check('Teams page fails (net::ERR_FAILED): the app reloads, then clears Teams\' service worker and stored files by itself',
    /ERR_FAILED loading https:\/\/teams\.microsoft\.com\/v2\/ \(attempt 1\)/.test(log) && healed,
    log.split('\n').filter((l) => /attempt|removed|page (started|finished|check)|error page/.test(l))
      .map((l) => l.replace(/^.*WorkDeck\/Tab: /, '')).slice(0, 14).join(' | ')
      + ' || page: ' + JSON.stringify(await p.eval('location.href + " / " + document.title', 4000)));
  const swOff = await waitFor(() => /service workers off for this site/.test(sh('adb logcat -d -s WorkDeck/Tab')), 20000);
  check('failing again: the app turns Teams\' service worker off and loads Teams again', swOff);
  await sleep(7000);
  let t = texts('omcTeams-error');
  shot('omcTeams-error');
  check('still failing after that: the app\'s own error screen with Retry (not a dead end)', /Can.t load this page/.test(t) && /ERR_FAILED/.test(t) && /\nRetry\n/.test('\n' + t + '\n'),
    t.split('\n').slice(0, 5).join(' | '));
  failing = false;
  ui('tap "=Retry"');
  await sleep(8000);
  t = texts('omcTeams-retried');
  check('Retry loads Teams again once it works', !/Can.t load this page/.test(t), t.split('\n').slice(0, 3).join(' | '));
  await p.send('Fetch.disable');
}

async function siblingLink(p) {
  // An Outlook link in OMC Teams opens in the OMC Outlook app (same account).
  await p.eval(`(() => { const a = document.createElement('a'); a.href = 'https://outlook.office.com/mail/'; a.target = '_blank';
      document.body.appendChild(a); a.click(); a.remove(); return 'ok'; })()`, 8000, true);
  const opened = await waitFor(() => topPackage() === APPS.omcOutlook.pkg, 10000);
  shot('sibling');
  check('OMC Teams: an Outlook link opens in the OMC Outlook app', opened, topPackage());
}

(async () => {
  for (const key of ['omcTeams', 'omcOutlook', 'craftwwOutlook', 'craftwwTeams']) await firstRun(key);

  // OMC Teams: the full set.
  sh(`adb shell am start -W -n ${APPS.omcTeams.pkg}/${MAIN}`);
  await sleep(4000);
  let p = await mainScreen('omcTeams', true);
  await textSize('omcTeams');
  if (p) {
    await calls('omcTeams', p);
    await links('omcTeams', p);
    await selfHeal(p);
    await siblingLink(p);
    p.close();
  }

  // OMC Outlook: computer identity (OMC blocks phone browsers), phone layout.
  sh(`adb shell am start -W -n ${APPS.omcOutlook.pkg}/${MAIN}`);
  await sleep(4000);
  p = await mainScreen('omcOutlook', true);
  if (p) p.close();

  // CRAFTWW Outlook: a phone browser (switches by itself if a policy blocks it).
  sh(`adb shell am start -W -n ${APPS.craftwwOutlook.pkg}/${MAIN}`);
  await sleep(4000);
  p = await mainScreen('craftwwOutlook', false);
  if (p) p.close();

  // CRAFTWW Teams: its own app, own sign-in.
  sh(`adb shell am start -W -n ${APPS.craftwwTeams.pkg}/${MAIN}`);
  await sleep(4000);
  p = await mainScreen('craftwwTeams', true);
  if (p) p.close();

  const running = Object.values(APPS).filter((a) => sh(`adb shell pidof ${a.pkg}`).trim()).length;
  check('all four apps run side by side (and next to WorkDeck)', running === 4 && !!sh('adb shell pidof com.workdeck.app').trim(), running);
  console.log(failures ? `\n${failures} single-app check(s) FAILED` : '\nall single-app checks passed');
  process.exit(failures ? 1 : 0);
})().catch((e) => { console.log('solo-test crashed: ' + (e && e.stack || e)); process.exit(1); });
