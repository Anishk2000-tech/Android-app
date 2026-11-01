/* Evaluates one JS expression in every WorkDeck WebView page over the DevTools protocol
 * and prints the first non-null JSON result. Usage: node tests/android/cdp-eval.js "<expr>" */
'use strict';
const { execSync } = require('child_process');
const expr = process.argv[2];
const sh = (c) => execSync(c, { encoding: 'utf8' });
function evalIn(page) {
  return new Promise((resolve) => {
    const ws = new WebSocket(page.webSocketDebuggerUrl);
    const t = setTimeout(() => { try { ws.close(); } catch (e) {} resolve(null); }, 10000);
    ws.onerror = () => { clearTimeout(t); resolve(null); };
    ws.onopen = () => ws.send(JSON.stringify({ id: 1, method: 'Runtime.evaluate', params: { expression: expr, returnByValue: true, awaitPromise: true } }));
    ws.onmessage = (ev) => {
      const r = JSON.parse(ev.data);
      if (r.id !== 1) return;
      clearTimeout(t);
      ws.close();
      resolve(r.result && r.result.result && r.result.result.value !== undefined ? r.result.result.value : null);
    };
  });
}
(async () => {
  const m = sh('adb shell cat /proc/net/unix').match(/@(webview_devtools_remote_\d+)/);
  if (!m) { console.log('null'); return; }
  sh(`adb forward tcp:9222 localabstract:${m[1]}`);
  setTimeout(() => { console.log('null'); process.exit(0); }, 60000);
  const list = (await (await fetch('http://127.0.0.1:9222/json/list', { signal: AbortSignal.timeout(10000) })).json()).filter((p) => p.type === 'page');
  let first = null;
  for (const p of list) {
    const v = await evalIn(p);
    if (first === null && v !== null) first = v;
  }
  console.log(JSON.stringify(first));
  process.exit(0);
})().catch(() => { console.log('null'); process.exit(0); });
