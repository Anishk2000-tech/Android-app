/* Evaluates one JS expression in WorkDeck's Teams WebView page over the DevTools
 * protocol and prints the JSON result. Usage: node tests/android/cdp-eval.js "<expr>" */
'use strict';
const { execSync } = require('child_process');
const expr = process.argv[2];
const sh = (c) => execSync(c, { encoding: 'utf8' });
const safe = (u) => { try { return decodeURIComponent(u); } catch (e) { return u; } };
(async () => {
  const m = sh('adb shell cat /proc/net/unix').match(/@(webview_devtools_remote_\d+)/);
  if (!m) { console.log('null'); return; }
  sh(`adb forward tcp:9222 localabstract:${m[1]}`);
  setTimeout(() => { console.log('null'); process.exit(0); }, 30000);
  const list = (await (await fetch('http://127.0.0.1:9222/json/list', { signal: AbortSignal.timeout(10000) })).json()).filter((p) => p.type === 'page');
  const page = list.find((p) => /teams/i.test(safe(p.url))) || list[0];
  if (!page) { console.log('null'); return; }
  const ws = new WebSocket(page.webSocketDebuggerUrl);
  await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
  ws.onmessage = (ev) => {
    const r = JSON.parse(ev.data);
    if (r.id === 1) {
      console.log(JSON.stringify(r.result && r.result.result ? r.result.result.value : null));
      ws.close();
      process.exit(0);
    }
  };
  ws.send(JSON.stringify({ id: 1, method: 'Runtime.evaluate', params: { expression: expr, returnByValue: true, awaitPromise: true } }));
})().catch((e) => { console.log('null'); process.exit(0); });
