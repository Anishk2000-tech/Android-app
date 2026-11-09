/*
 * Tests for app/src/main/assets/workdeck/inject.js and sw-shim.js in real Chromium
 * (the engine inside Android System WebView), with a mocked native bridge.
 *
 * Run:  node tests/js/inject.test.js        (needs the `playwright` package + Chromium)
 */
'use strict';
const fs = require('fs');
const http = require('http');
const path = require('path');
const assert = require('assert');
const { chromium } = require('playwright');

const ROOT = path.resolve(__dirname, '..', '..');
const INJECT = fs.readFileSync(path.join(ROOT, 'app/src/main/assets/workdeck/inject.js'), 'utf8');
const SHIM = fs.readFileSync(path.join(ROOT, 'app/src/main/assets/workdeck/sw-shim.js'), 'utf8');
const TOKEN = 'test-token-0123456789abcdef';

function script(cfg) {
  const full = Object.assign({
    token: TOKEN, site: 'teams', desktop: true, desktopLayout: true, desktopWidth: 1024,
    notifications: true, fullVersion: '130.0.6723.102', androidVersion: '14', model: 'Pixel 7', debug: false,
    brands: [['Google Chrome', '130', '130.0.6723.102'], ['Chromium', '130', '130.0.6723.102'], ['Not?A_Brand', '99', '99.0.0.0']],
  }, cfg || {});
  return INJECT.split('__WORKDECK_CONFIG__').join(JSON.stringify(full));
}

// Mock of the Java object WebView exposes as window.WorkDeckBridge.
const BRIDGE_MOCK = `
  window.__calls = [];
  window.__perm = 'granted';
  window.__files = {};
  window.__media = { mic: true, camera: true, cameras: ['front', 'back'], routes: ['speaker', 'earpiece', 'bluetooth'], route: '' };
  window.WorkDeckBridge = {
    _rec(name, args) { window.__calls.push({ name: name, args: Array.prototype.slice.call(args) }); },
    permission(t) { this._rec('permission', arguments); return t === '${TOKEN}' ? window.__perm : 'denied'; },
    requestPermission(t) { this._rec('requestPermission', arguments); },
    notify(t, json) { this._rec('notify', arguments); },
    closeNotification(t, id) { this._rec('closeNotification', arguments); },
    badge(t, n) { this._rec('badge', arguments); },
    mailUnread(t, n, baseline) { this._rec('mailUnread', arguments); },
    fileStart(t, name, mime, size) { this._rec('fileStart', arguments); window.__files.f1 = { name: name, mime: mime, chunks: [] }; return 'f1'; },
    fileChunk(t, id, b64) { window.__files[id].chunks.push(b64); },
    fileEnd(t, id, status) { this._rec('fileEnd', arguments); },
    mediaDelta(t, d) { this._rec('mediaDelta', arguments); },
    mediaInfo(t) { return t === '${TOKEN}' ? JSON.stringify(window.__media) : ''; },
    setRoute(t, r) { this._rec('setRoute', arguments); },
    looking(t) { return window.__looking || '0'; },
    drawn(t) { return window.__drawn || '1'; },
    setMic(t, m) { this._rec('setMic', arguments); },
    rtcDelta(t, d) { this._rec('rtcDelta', arguments); },
    videoDelta(t, d) { this._rec('videoDelta', arguments); },
    composeFocus(t, st) { this._rec('composeFocus', arguments); },
    attachDone(t, method, n, seen) { this._rec('attachDone', arguments); },
    attachRead(t, id, off) {
      const f = (window.__attachData || {})[id];
      if (!f) return null;
      const o = Number(off);
      if (o >= f.length) return '';
      const part = f.slice(o, o + 5); // tiny chunks: exercises the chunked reader
      return btoa(part);
    },
    callSignal(t, type, detail) { this._rec('callSignal', arguments); },
    log(t, m) { this._rec('log', arguments); },
  };
`;

const PAGES = {
  '/mobile.html': `<!doctype html><html><head>
      <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
      <title>(3) Chat | Test</title></head><body><h1>test</h1>
      <div role="dialog"><button aria-label="Decline call">x</button><button aria-label="Accept call with audio">ok</button></div>
      </body></html>`,
  '/noviewport.html': `<!doctype html><html><head><title>t</title></head><body>no viewport</body></html>`,
  '/late.html': `<!doctype html><html><head><title>t</title></head><body><script>
      var m = document.createElement('meta'); m.name = 'viewport'; m.content = 'width=device-width, user-scalable=0';
      document.head.appendChild(m);</script></body></html>`,
  '/teams.html': `<!doctype html><html><head><title>Chat | Teams</title></head><body>
      <div role="tree"><div role="treeitem" aria-label="Alice Smith, Hey are you free for a call?, 10:42, Unread">Alice</div></div>
      </body></html>`,
  '/outlook.html': `<!doctype html><html><head><title>Mail - Test User - Outlook</title></head><body>
      <div role="tree" id="folders">
        <div role="treeitem" title="Favorites">Favorites</div>
        <div role="treeitem" data-folder-name="inbox" title="Inbox"><span>Inbox</span><span id="count">3</span></div>
        <div role="treeitem" title="Sent Items"><span>Sent Items</span></div>
      </div>
      <div role="listbox">
        <div role="option" aria-label="Unread, Carol White, Budget approval, 9:15 AM, Please approve the Q3 budget">
          <div>Carol White</div><div>Budget approval</div><div>9:15 AM</div><div>Please approve the Q3 budget</div></div>
        <div role="option" aria-label="Dan Brown, Lunch?, Yesterday"><div>Dan Brown</div><div>Lunch?</div></div>
      </div></body></html>`,
  '/sw.html': `<!doctype html><html><head><title>sw</title></head><body>sw page</body></html>`,
  '/sw.js': `${SHIM}\nself.addEventListener('install', e => self.skipWaiting());
      self.addEventListener('activate', e => e.waitUntil(self.clients.claim()));
      self.addEventListener('message', e => { if (e.data === 'notify') self.registration.showNotification('From SW', { body: 'sw body', tag: 'swtag', icon: '/i.png' }); });`,
};

function startServer() {
  return new Promise((resolve) => {
    const srv = http.createServer((req, res) => {
      const body = PAGES[req.url.split('?')[0]];
      if (!body) { res.writeHead(404); res.end(); return; }
      res.writeHead(200, { 'Content-Type': req.url.endsWith('.js') ? 'application/javascript' : 'text/html' });
      res.end(body);
    });
    srv.listen(0, '127.0.0.1', () => resolve(srv));
  });
}

async function newPage(browser, base, cfg, file) {
  const ctx = await browser.newContext({ viewport: { width: 412, height: 860 } });
  await ctx.addInitScript(BRIDGE_MOCK);
  await ctx.addInitScript(script(cfg));
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e)));
  await page.goto(base + (file || '/mobile.html'));
  return { ctx, page, errors };
}

const tests = [];
function test(name, fn) { tests.push({ name, fn }); }

test('desktop identity: platform, userAgentData, window.chrome', async (b, base) => {
  const { ctx, page, errors } = await newPage(b, base, {});
  const r = await page.evaluate(async () => ({
    platform: navigator.platform,
    mobile: navigator.userAgentData.mobile,
    uaPlatform: navigator.userAgentData.platform,
    brands: navigator.userAgentData.brands.map((x) => x.brand),
    high: await navigator.userAgentData.getHighEntropyValues(['platformVersion', 'architecture', 'bitness', 'fullVersionList']),
    json: JSON.stringify(navigator.userAgentData),
    chrome: typeof window.chrome === 'object' && typeof window.chrome.loadTimes === 'function',
  }));
  assert.strictEqual(r.platform, 'Win32');
  assert.strictEqual(r.mobile, false);
  assert.strictEqual(r.uaPlatform, 'Windows');
  assert.deepStrictEqual(r.brands, ['Google Chrome', 'Chromium', 'Not?A_Brand']);
  assert.strictEqual(r.high.platformVersion, '15.0.0');
  assert.strictEqual(r.high.architecture, 'x86');
  assert.strictEqual(r.high.bitness, '64');
  assert.strictEqual(r.high.fullVersionList[0].version, '130.0.6723.102');
  assert.ok(r.json.includes('"platform":"Windows"'));
  assert.ok(r.chrome);
  assert.deepStrictEqual(errors, []);
  await ctx.close();
});

test('mobile identity (Outlook mobile view): Chrome for Android, no Win32', async (b, base) => {
  const { ctx, page } = await newPage(b, base, { site: 'outlook', desktop: false, desktopLayout: false });
  const r = await page.evaluate(() => ({ p: navigator.platform, m: navigator.userAgentData.mobile, up: navigator.userAgentData.platform }));
  assert.notStrictEqual(r.p, 'Win32');
  assert.strictEqual(r.m, true);
  assert.strictEqual(r.up, 'Android');
  await ctx.close();
});

test('desktop layout rewrites the viewport meta to the desktop width', async (b, base) => {
  const { ctx, page } = await newPage(b, base, { desktopWidth: 1280 });
  const r = await page.evaluate(() => ({ c: document.querySelector('meta[name=viewport]').content, w: window.innerWidth }));
  assert.strictEqual(r.c, 'width=1280, user-scalable=yes');
  await ctx.close();
});

test('desktop layout adds a viewport meta when the page has none', async (b, base) => {
  const { ctx, page } = await newPage(b, base, {}, '/noviewport.html');
  const c = await page.evaluate(() => document.querySelector('meta[name=viewport]').content);
  assert.strictEqual(c, 'width=1024, user-scalable=yes');
  await ctx.close();
});

test('mobile view keeps the layout but re-enables pinch zoom (incl. late-inserted meta)', async (b, base) => {
  for (const file of ['/mobile.html', '/late.html']) {
    const { ctx, page } = await newPage(b, base, { desktopLayout: false }, file);
    await page.waitForTimeout(50);
    const c = await page.evaluate(() => document.querySelector('meta[name=viewport]').content);
    assert.ok(/width=device-width/.test(c), c);
    assert.ok(/user-scalable=yes/.test(c), c);
    assert.ok(!/maximum-scale=1\b/.test(c), c);
    await ctx.close();
  }
});

test('Notification API: permission, constructor, show event, payload to bridge', async (b, base) => {
  const { ctx, page, errors } = await newPage(b, base, {});
  const r = await page.evaluate(async () => {
    const out = { perm: Notification.permission, name: Notification.name, max: Notification.maxActions };
    out.req = await Notification.requestPermission();
    const n = new Notification('Alice', { body: 'Hello there', tag: 'chat-1', icon: '/avatar.png', requireInteraction: false });
    out.isEventTarget = n instanceof EventTarget;
    out.title = n.title; out.body = n.body;
    await new Promise((res) => { n.onshow = () => res(); });
    out.calls = window.__calls.filter((c) => c.name === 'notify').map((c) => ({ token: c.args[0], p: JSON.parse(c.args[1]) }));
    return out;
  });
  assert.strictEqual(r.perm, 'granted');
  assert.strictEqual(r.name, 'Notification');
  assert.strictEqual(r.req, 'granted');
  assert.ok(r.isEventTarget);
  assert.strictEqual(r.calls.length, 1);
  assert.strictEqual(r.calls[0].token, 'test-token-0123456789abcdef');
  const p = r.calls[0].p;
  assert.strictEqual(p.title, 'Alice');
  assert.strictEqual(p.body, 'Hello there');
  assert.strictEqual(p.tag, 'chat-1');
  assert.ok(p.icon.endsWith('/avatar.png') && p.icon.startsWith('http://127.0.0.1'), p.icon);
  assert.ok(p.id && p.id.length > 3);
  assert.deepStrictEqual(errors, []);
  await ctx.close();
});

test('Notification click from Android reaches onclick + click listeners; close() informs native', async (b, base) => {
  const { ctx, page } = await newPage(b, base, {});
  const r = await page.evaluate(async () => {
    const n = new Notification('Bob', { body: 'Ping' });
    await new Promise((res) => setTimeout(res, 20));
    const id = JSON.parse(window.__calls.find((c) => c.name === 'notify').args[1]).id;
    let onclick = 0, listener = 0;
    n.onclick = () => onclick++;
    n.addEventListener('click', () => listener++);
    const handled = window.__wdNotificationClicked(id);
    const unknown = window.__wdNotificationClicked('nope');
    n.close();
    const closed = window.__calls.filter((c) => c.name === 'closeNotification').map((c) => c.args[1]);
    return { handled, unknown, onclick, listener, closed, id };
  });
  assert.strictEqual(r.handled, true);
  assert.strictEqual(r.unknown, false);
  assert.strictEqual(r.onclick, 1);
  assert.strictEqual(r.listener, 1);
  assert.deepStrictEqual(r.closed, [r.id]);
  await ctx.close();
});

test('denied permission: no native notification, error event fires', async (b, base) => {
  const { ctx, page } = await newPage(b, base, {});
  const r = await page.evaluate(async () => {
    window.__perm = 'denied';
    const n = new Notification('x');
    const ev = await new Promise((res) => { n.onerror = () => res('error'); setTimeout(() => res('timeout'), 500); });
    return { ev, perm: Notification.permission, notifies: window.__calls.filter((c) => c.name === 'notify').length };
  });
  assert.strictEqual(r.ev, 'error');
  assert.strictEqual(r.perm, 'denied');
  assert.strictEqual(r.notifies, 0);
  await ctx.close();
});

test('requestPermission while "default" asks native and resolves after the grant', async (b, base) => {
  const { ctx, page } = await newPage(b, base, {});
  const r = await page.evaluate(async () => {
    window.__perm = 'default';
    const pr = Notification.requestPermission();
    const asked = window.__calls.some((c) => c.name === 'requestPermission');
    setTimeout(() => { window.__perm = 'granted'; window.__wdPermissionResult(); }, 50);
    return { asked, result: await pr };
  });
  assert.strictEqual(r.asked, true);
  assert.strictEqual(r.result, 'granted');
  await ctx.close();
});

test('permissions.query("notifications") reflects native state; other queries untouched', async (b, base) => {
  const { ctx, page } = await newPage(b, base, {});
  const r = await page.evaluate(async () => {
    const a = await navigator.permissions.query({ name: 'notifications' });
    window.__perm = 'default';
    const c = await navigator.permissions.query({ name: 'notifications' });
    let geoOk = true;
    try { await navigator.permissions.query({ name: 'geolocation' }); } catch (e) { geoOk = false; }
    return { a: a.state, c: c.state, geoOk };
  });
  assert.strictEqual(r.a, 'granted');
  assert.strictEqual(r.c, 'prompt');
  assert.strictEqual(r.geoOk, true);
  await ctx.close();
});

test('ServiceWorkerRegistration.showNotification (page side) is forwarded', async (b, base) => {
  const { ctx, page } = await newPage(b, base, {});
  const r = await page.evaluate(async () => {
    const res = ServiceWorkerRegistration.prototype.showNotification.call({}, 'Meeting', { body: 'starts now', tag: 'm1' });
    await res;
    await new Promise((r2) => setTimeout(r2, 20));
    const list = await ServiceWorkerRegistration.prototype.getNotifications.call({}, { tag: 'm1' });
    return { calls: window.__calls.filter((c) => c.name === 'notify').map((c) => JSON.parse(c.args[1])), n: list.length };
  });
  assert.strictEqual(r.calls.length, 1);
  assert.strictEqual(r.calls[0].title, 'Meeting');
  assert.strictEqual(r.calls[0].sw, true);
  assert.strictEqual(r.n, 1);
  await ctx.close();
});

test('service-worker shim: SW showNotification -> page -> native bridge', async (b, base) => {
  const { ctx, page, errors } = await newPage(b, base, {}, '/sw.html');
  const r = await page.evaluate(async () => {
    const reg = await navigator.serviceWorker.register('/sw.js');
    await navigator.serviceWorker.ready;
    if (!navigator.serviceWorker.controller) {
      await new Promise((res) => navigator.serviceWorker.addEventListener('controllerchange', res, { once: true }));
    }
    navigator.serviceWorker.controller.postMessage('notify');
    for (let i = 0; i < 50; i++) {
      const n = window.__calls.filter((c) => c.name === 'notify');
      if (n.length) return JSON.parse(n[0].args[1]);
      await new Promise((res) => setTimeout(res, 100));
    }
    return null;
  });
  assert.ok(r, 'no notification forwarded from the service worker');
  assert.strictEqual(r.title, 'From SW');
  assert.strictEqual(r.body, 'sw body');
  assert.strictEqual(r.tag, 'swtag');
  assert.ok(r.icon.endsWith('/i.png'));
  assert.deepStrictEqual(errors, []);
  await ctx.close();
});

test('Badging API reports unread counts to native (top frame)', async (b, base) => {
  const { ctx, page } = await newPage(b, base, {});
  const r = await page.evaluate(async () => {
    await navigator.setAppBadge(7);
    await navigator.clearAppBadge();
    await navigator.setAppBadge();
    return window.__calls.filter((c) => c.name === 'badge').map((c) => c.args[1]);
  });
  assert.deepStrictEqual(r, ['7', '0', '-1']);
  await ctx.close();
});

test('getUserMedia mic tracks are counted up and down (call detection)', async (b, base) => {
  const { ctx, page } = await newPage(b, base, {});
  const r = await page.evaluate(async () => {
    const s = await navigator.mediaDevices.getUserMedia({ audio: true });
    const after = window.__calls.filter((c) => c.name === 'mediaDelta').map((c) => c.args[1]);
    const clone = s.getAudioTracks()[0].clone();
    s.getTracks().forEach((t) => t.stop());
    clone.stop();
    clone.stop();
    const all = window.__calls.filter((c) => c.name === 'mediaDelta').map((c) => c.args[1]);
    return { after, all };
  });
  assert.deepStrictEqual(r.after, ['1']);
  assert.deepStrictEqual(r.all, ['1', '1', '-1', '-1']);
  await ctx.close();
});

test('a call connection that sends audio is reported while it lasts (not a receive-only one); camera tracks too', async (b, base) => {
  const { ctx, page, errors } = await newPage(b, base, {});
  const r = await page.evaluate(async () => {
    const wait = (ms) => new Promise((res) => setTimeout(res, ms));
    const deltas = (name) => window.__calls.filter((c) => c.name === name).map((c) => c.args[1]);
    // Two connections in this page talk to each other (a call needs no server for this).
    const connect = async (a, b) => {
      a.onicecandidate = (e) => e.candidate && b.addIceCandidate(e.candidate);
      b.onicecandidate = (e) => e.candidate && a.addIceCandidate(e.candidate);
      const offer = await a.createOffer();
      await a.setLocalDescription(offer);
      await b.setRemoteDescription(offer);
      const answer = await b.createAnswer();
      await b.setLocalDescription(answer);
      await a.setRemoteDescription(answer);
      for (let i = 0; i < 50 && a.connectionState !== 'connected'; i++) await wait(100);
      await wait(400);
    };
    const mic = await navigator.mediaDevices.getUserMedia({ audio: true });
    const caller = new RTCPeerConnection();
    const callee = new RTCPeerConnection();
    caller.addTrack(mic.getAudioTracks()[0], mic);
    callee.addTransceiver('audio', { direction: 'recvonly' });
    await connect(caller, callee);
    const during = deltas('rtcDelta');
    caller.close();
    callee.close();
    const after = deltas('rtcDelta');
    // A receive-only connection (watching a live event) is not a call.
    const viewer = new RTCPeerConnection();
    const source = new RTCPeerConnection();
    viewer.addTransceiver('audio', { direction: 'recvonly' });
    source.addTrack(mic.getAudioTracks()[0], mic);
    await connect(viewer, source);
    const viewerCounted = deltas('rtcDelta').length - after.length;
    viewer.close(); source.close();
    mic.getTracks().forEach((t) => t.stop());
    const cam = await navigator.mediaDevices.getUserMedia({ video: true });
    const camOn = deltas('videoDelta');
    cam.getTracks().forEach((t) => t.stop());
    class MyPC extends RTCPeerConnection { hello() { return 'hi'; } }
    const sub = new MyPC();
    const ok = { inst: caller instanceof RTCPeerConnection, sub: sub.hello() === 'hi' && sub instanceof RTCPeerConnection,
      cert: typeof RTCPeerConnection.generateCertificate === 'function', name: RTCPeerConnection.name };
    sub.close();
    return { during, after, viewerCounted, camOn, camAll: deltas('videoDelta'), ok };
  });
  assert.deepStrictEqual(r.during, ['1'], 'a connected call that sends audio is reported');
  assert.deepStrictEqual(r.after, ['1', '-1'], 'and its end');
  // Both ends live in this page: only the end that sends audio counts, not the receive-only one.
  assert.strictEqual(r.viewerCounted, 1, 'a receive-only connection (watching a live event) is not a call');
  assert.deepStrictEqual(r.camOn, ['1']);
  assert.deepStrictEqual(r.camAll, ['1', '-1'], 'camera on / off');
  assert.deepStrictEqual(r.ok, { inst: true, sub: true, cert: true, name: 'RTCPeerConnection' });
  assert.deepStrictEqual(errors, []);
  await ctx.close();
});

test('blob download is streamed to native in base64 chunks with the right name', async (b, base) => {
  const { ctx, page } = await newPage(b, base, {});
  const r = await page.evaluate(async () => {
    const bytes = new Uint8Array(2 * 1024 * 1024 + 123);
    for (let i = 0; i < bytes.length; i++) bytes[i] = (i * 31) & 0xff;
    const url = URL.createObjectURL(new Blob([bytes], { type: 'application/pdf' }));
    const a = document.createElement('a');
    a.href = url; a.download = 'report.pdf';
    document.body.appendChild(a);
    a.addEventListener('click', (e) => e.preventDefault());
    a.click();
    URL.revokeObjectURL(url); // pages do this immediately; WorkDeck must still read it
    window.__wdDownload(url, 'application/pdf');
    for (let i = 0; i < 100; i++) {
      if (window.__calls.some((c) => c.name === 'fileEnd')) break;
      await new Promise((res) => setTimeout(res, 50));
    }
    const f = window.__files.f1;
    const bin = f.chunks.map((c) => atob(c)).join('');
    let same = bin.length === bytes.length;
    for (let i = 0; same && i < bytes.length; i += 997) same = bin.charCodeAt(i) === bytes[i];
    const end = window.__calls.find((c) => c.name === 'fileEnd');
    return { name: f.name, mime: f.mime, chunks: f.chunks.length, same, status: end && end.args[2] };
  });
  assert.strictEqual(r.name, 'report.pdf');
  assert.strictEqual(r.mime, 'application/pdf');
  assert.ok(r.chunks >= 3, 'expected chunking, got ' + r.chunks);
  assert.strictEqual(r.same, true);
  assert.strictEqual(r.status, 'ok');
  await ctx.close();
});

test('call actions click the right buttons (accept audio / decline)', async (b, base) => {
  const { ctx, page } = await newPage(b, base, {});
  const r = await page.evaluate(async () => {
    const clicked = [];
    document.querySelectorAll('button').forEach((btn) => btn.addEventListener('click', () => clicked.push(btn.getAttribute('aria-label'))));
    const a = await window.__wdCallAction('accept');
    const d = await window.__wdCallAction('decline');
    return { a, d, clicked };
  });
  assert.strictEqual(r.a, true);
  assert.strictEqual(r.d, true);
  assert.deepStrictEqual(r.clicked, ['Accept call with audio', 'Decline call']);
  await ctx.close();
});

test('ringtone heuristic fires for looping ring sounds only', async (b, base) => {
  const { ctx, page } = await newPage(b, base, {});
  const r = await page.evaluate(async () => {
    const a1 = new Audio('/media/ringtone_call.mp3'); a1.loop = true; a1.play().catch(() => {});
    const a2 = new Audio('/media/notification.mp3'); a2.loop = false; a2.play().catch(() => {});
    const a3 = new Audio('/media/music.mp3'); a3.loop = true; a3.play().catch(() => {});
    return window.__calls.filter((c) => c.name === 'callSignal').map((c) => c.args[1] + ':' + c.args[2].split('/').pop());
  });
  assert.deepStrictEqual(r, ['ringing:ringtone_call.mp3']);
  await ctx.close();
});

test('script is idempotent and does not leak the token into page globals', async (b, base) => {
  const { ctx, page, errors } = await newPage(b, base, {});
  await page.evaluate(script({}));
  const r = await page.evaluate(() => {
    const leaks = Object.getOwnPropertyNames(window).filter((k) => {
      try { return typeof window[k] === 'string' && window[k].indexOf('test-token') >= 0; } catch (e) { return false; }
    });
    const n = new Notification('once');
    return new Promise((res) => setTimeout(() => res({ leaks, notifies: window.__calls.filter((c) => c.name === 'notify').length }), 50));
  });
  assert.deepStrictEqual(r.leaks, []);
  assert.strictEqual(r.notifies, 1, 'double injection must not double-post');
  assert.deepStrictEqual(errors, []);
  await ctx.close();
});

test('works without the native bridge (e.g. a non-Microsoft frame): no exceptions', async (b, base) => {
  const ctx = await b.newContext();
  await ctx.addInitScript(script({}));
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e)));
  await page.goto(base + '/mobile.html');
  const r = await page.evaluate(async () => {
    const p = Notification.permission;
    const n = new Notification('x');
    await navigator.setAppBadge(1);
    return p;
  });
  assert.strictEqual(r, 'default');
  assert.deepStrictEqual(errors, []);
  await ctx.close();
});

test('mic/camera permission queries follow Android (not WebView\'s blanket "denied")', async (b, base) => {
  const { ctx, page } = await newPage(b, base, {});
  const r = await page.evaluate(async () => {
    const out = {};
    for (const n of ['microphone', 'camera', 'speaker-selection', 'push']) out[n] = (await navigator.permissions.query({ name: n })).state;
    window.__media.mic = false; window.__media.camera = false;
    out.micOff = (await navigator.permissions.query({ name: 'microphone' })).state;
    out.camOff = (await navigator.permissions.query({ name: 'camera' })).state;
    return out;
  });
  assert.deepStrictEqual(r, { microphone: 'granted', camera: 'granted', 'speaker-selection': 'granted', push: 'granted', micOff: 'prompt', camOff: 'prompt' });
  await ctx.close();
});

// What Android WebView itself returns: one nameless, id-less entry per kind.
const WEBVIEW_ENUMERATE = `MediaDevices.prototype.enumerateDevices = function () {
  return Promise.resolve(['audioinput', 'videoinput', 'audiooutput'].map((k) => ({ kind: k, deviceId: '', label: '', groupId: '' })));
};`;

test('Permissions API is provided when WebView lacks it (mic/camera/notifications answered)', async (b, base) => {
  const ctx = await b.newContext();
  await ctx.addInitScript(`Object.defineProperty(Navigator.prototype, 'permissions', { get: () => undefined, configurable: true });
    delete window.Permissions; delete window.PermissionStatus;`);
  await ctx.addInitScript(BRIDGE_MOCK);
  await ctx.addInitScript(script({}));
  const page = await ctx.newPage();
  await page.goto(base + '/mobile.html');
  const r = await page.evaluate(async () => {
    const out = { has: !!navigator.permissions, ctor: typeof Permissions, statusCtor: typeof PermissionStatus };
    for (const n of ['microphone', 'camera', 'notifications', 'geolocation']) out[n] = (await navigator.permissions.query({ name: n })).state;
    out.isStatus = (await navigator.permissions.query({ name: 'camera' })) instanceof PermissionStatus;
    return out;
  });
  assert.deepStrictEqual(r, { has: true, ctor: 'function', statusCtor: 'function', microphone: 'granted', camera: 'granted',
    notifications: 'granted', geolocation: 'prompt', isStatus: true });
  await ctx.close();
});

test('enumerateDevices lists phone mic, front/back cameras and Android audio routes', async (b, base) => {
  const ctx = await b.newContext();
  await ctx.addInitScript(WEBVIEW_ENUMERATE);
  await ctx.addInitScript(BRIDGE_MOCK);
  await ctx.addInitScript(script({}));
  const page = await ctx.newPage();
  await page.goto(base + '/mobile.html');
  const r = await page.evaluate(async () => {
    const list = await navigator.mediaDevices.enumerateDevices();
    const out = list.map((d) => d.kind + '|' + d.deviceId + '|' + d.label);
    const info = list.find((d) => d.kind === 'audioinput');
    const types = { mdi: info instanceof MediaDeviceInfo, json: JSON.stringify(info), caps: typeof info.getCapabilities };
    window.__media.mic = false; window.__media.camera = false;
    const locked = (await navigator.mediaDevices.enumerateDevices()).map((d) => d.kind + '|' + d.deviceId + '|' + d.label);
    return { out, types, locked };
  });
  assert.deepStrictEqual(r.out, [
    'audioinput|default|Automatic (Bluetooth or headset when connected)', 'audioinput|wd-mic-phone|Phone microphone',
    'audioinput|wd-mic-bluetooth|Bluetooth headset microphone',
    'videoinput|wd-camera-front|Front camera', 'videoinput|wd-camera-back|Back camera',
    'audiooutput|default|Automatic (phone chooses)', 'audiooutput|wd-route-speaker|Phone speaker (loud)',
    'audiooutput|wd-route-earpiece|Phone earpiece', 'audiooutput|wd-route-bluetooth|Bluetooth']);
  assert.strictEqual(r.types.mdi, true);
  assert.ok(r.types.json.includes('"label":"Automatic (Bluetooth or headset when connected)"'));
  assert.strictEqual(r.types.caps, 'function');
  assert.deepStrictEqual(r.locked, ['audioinput||', 'videoinput||', 'audiooutput||'], 'before permission: ids/labels hidden like a browser');
  await ctx.close();
});

test('Bluetooth / headset microphones are offered and picking one routes Android call audio', async (b, base) => {
  const { ctx, page } = await newPage(b, base, {});
  const r = await page.evaluate(async () => {
    window.__media.routes = ['speaker', 'earpiece', 'wired', 'bluetooth'];
    window.__media.btName = 'Galaxy Buds2';
    const list = await navigator.mediaDevices.enumerateDevices();
    const mics = list.filter((d) => d.kind === 'audioinput').map((d) => d.deviceId + '|' + d.label + '|' + d.groupId);
    const outs = list.filter((d) => d.kind === 'audiooutput').map((d) => d.deviceId + '|' + d.label + '|' + d.groupId);
    const s = await navigator.mediaDevices.getUserMedia({ audio: { deviceId: { exact: 'wd-mic-bluetooth' } } });
    const t = s.getAudioTracks()[0];
    const picked = { id: t.getSettings().deviceId, label: t.label };
    s.getTracks().forEach((x) => x.stop());
    const s2 = await navigator.mediaDevices.getUserMedia({ audio: { deviceId: 'wd-mic-phone' } });
    s2.getTracks().forEach((x) => x.stop());
    const s3 = await navigator.mediaDevices.getUserMedia({ audio: true });
    s3.getTracks().forEach((x) => x.stop());
    return { mics, outs, picked, setMic: window.__calls.filter((c) => c.name === 'setMic').map((c) => c.args[1]) };
  });
  assert.deepStrictEqual(r.mics, [
    'default|Automatic (Bluetooth or headset when connected)|wd-phone', 'wd-mic-phone|Phone microphone|wd-phone',
    'wd-mic-bluetooth|Bluetooth: Galaxy Buds2|wd-route-bluetooth', 'wd-mic-wired|Headset microphone|wd-route-wired']);
  assert.ok(r.outs.includes('wd-route-bluetooth|Bluetooth: Galaxy Buds2|wd-route-bluetooth'), 'mic and speaker of a headset share a group');
  assert.deepStrictEqual(r.picked, { id: 'wd-mic-bluetooth', label: 'Bluetooth: Galaxy Buds2' });
  assert.deepStrictEqual(r.setMic, ['bluetooth', 'phone'], 'plain getUserMedia({audio:true}) must not change the route');
  await ctx.close();
});

test('real, labelled devices (desktop-like engines) are passed through, plus Android routes', async (b, base) => {
  const { ctx, page } = await newPage(b, base, {});
  const r = await page.evaluate(async () => {
    await navigator.mediaDevices.getUserMedia({ audio: true }).then((s) => s.getTracks().forEach((t) => t.stop()));
    const list = await navigator.mediaDevices.enumerateDevices();
    return list.map((d) => d.kind + '|' + (d.deviceId.startsWith('wd-') || d.deviceId === 'default' ? d.deviceId : 'real') + '|' + !!d.label);
  });
  assert.ok(r.includes('audioinput|wd-mic-phone|true'), JSON.stringify(r));
  assert.ok(r.some((x) => /^videoinput\|(real|wd-camera-front)\|true$/.test(x)), JSON.stringify(r));
  assert.ok(r.includes('audiooutput|wd-route-earpiece|true'), JSON.stringify(r));
  await ctx.close();
});

test('getUserMedia accepts the device ids WorkDeck handed out (incl. back camera)', async (b, base) => {
  const { ctx, page } = await newPage(b, base, {});
  const r = await page.evaluate(async () => {
    const s = await navigator.mediaDevices.getUserMedia({
      audio: { deviceId: { exact: 'default' }, echoCancellation: true },
      video: { deviceId: { exact: 'wd-camera-back' }, width: { ideal: 1280 } } });
    const a = s.getAudioTracks()[0].getSettings().deviceId;
    const v = s.getVideoTracks()[0].getSettings().deviceId;
    s.getTracks().forEach((t) => t.stop());
    const s2 = await navigator.mediaDevices.getUserMedia({ audio: { deviceId: 'wd-route-speaker' } });
    s2.getTracks().forEach((t) => t.stop());
    return { a, v, tracks: s.getTracks().length, s2: s2.getAudioTracks().length };
  });
  assert.deepStrictEqual(r, { a: 'default', v: 'wd-camera-back', tracks: 2, s2: 1 });
  await ctx.close();
});

test('choosing a speaker in Teams (setSinkId) switches Android audio routing', async (b, base) => {
  const { ctx, page } = await newPage(b, base, {});
  const r = await page.evaluate(async () => {
    const el = new Audio();
    await el.setSinkId('wd-route-earpiece');
    const after = el.sinkId;
    await el.setSinkId('default');
    const ac = new AudioContext();
    if (ac.setSinkId) await ac.setSinkId('wd-route-bluetooth');
    return { after, routes: window.__calls.filter((c) => c.name === 'setRoute').map((c) => c.args[1]) };
  });
  assert.strictEqual(r.after, 'wd-route-earpiece');
  assert.deepStrictEqual(r.routes, ['EARPIECE', 'BLUETOOTH'], '"default" must not override the user\'s route');
  await ctx.close();
});

test('Teams incoming-call screen in the background raises a ringing signal (RSVP buttons do not)', async (b, base) => {
  const ctx = await b.newContext();
  await ctx.addInitScript("Object.defineProperty(document, 'visibilityState', { get: () => 'hidden' });");
  await ctx.addInitScript(BRIDGE_MOCK);
  await ctx.addInitScript(script({}));
  const page = await ctx.newPage();
  await page.goto(base + '/teams.html');
  const r = await page.evaluate(async () => {
    const tick = () => new Promise((res) => setTimeout(res, 50));
    const sig = () => window.__calls.filter((c) => c.name === 'callSignal').map((c) => c.args[1] + ':' + c.args[2]);
    const rsvp = document.createElement('div');
    rsvp.innerHTML = '<button aria-label="Accept">Accept</button><button aria-label="Decline">Decline</button>';
    document.body.appendChild(rsvp);
    await tick();
    const afterRsvp = sig().length;
    const toast = document.createElement('div');
    toast.setAttribute('role', 'alertdialog');
    toast.innerHTML = '<div>Bob Jones</div><div>is calling you</div>' +
      '<button aria-label="Accept video call"></button><button aria-label="Accept audio call"></button><button aria-label="Decline call"></button>';
    document.body.appendChild(toast);
    await tick();
    const ringing = sig();
    window.__looking = '1';
    const t2 = document.createElement('div');
    t2.innerHTML = '<button aria-label="Accept audio call"></button>';
    document.body.appendChild(t2);
    await tick();
    const whileLooking = sig().length;
    t2.remove();
    window.__looking = '0';
    toast.remove();
    await tick();
    return { afterRsvp, ringing, whileLooking, all: sig() };
  });
  assert.strictEqual(r.afterRsvp, 0, 'meeting RSVP Accept/Decline must not ring');
  assert.strictEqual(r.whileLooking, 1, 'no extra signals while the call is already ringing');
  assert.deepStrictEqual(r.ringing, ['ringing_ui:Bob Jones - is calling you']);
  assert.deepStrictEqual(r.all, ['ringing_ui:Bob Jones - is calling you', 'ringing_end:']);
  await ctx.close();
});

test('unread preview reads sender and message from the chat list', async (b, base) => {
  const { ctx, page } = await newPage(b, base, {}, '/teams.html');
  const r = await page.evaluate(() => window.__wdUnreadPreview());
  assert.deepStrictEqual(r, { title: 'Alice Smith', body: 'Hey are you free for a call? - 10:42' });
  await ctx.close();
});

test('Outlook: Inbox unread count is reported (first reading = baseline), list fallback, preview', async (b, base) => {
  const host = base.replace('127.0.0.1', 'outlook.wd-test');
  const { ctx, page, errors } = await newPage(b, host, { site: 'outlook' }, '/outlook.html');
  const r = await page.evaluate(async () => {
    const wait = (ms) => new Promise((res) => setTimeout(res, ms));
    const mail = () => window.__calls.filter((c) => c.name === 'mailUnread').map((c) => c.args[1] + '/' + c.args[2]);
    await wait(1900); // readings are throttled to one per 1.5 s while the page loads
    const first = mail();
    // Pretend Outlook finished loading a while ago: later changes are real news.
    const real = Date.now.bind(Date);
    let skew = 60000;
    Date.now = () => real() + skew;
    document.getElementById('count').textContent = '4';
    await wait(150);
    const more = mail();
    skew += 5000;
    document.getElementById('count').firstChild.data = '4'; // same number: nothing new
    await wait(150);
    const same = mail();
    skew += 5000;
    document.getElementById('count').textContent = '';
    await wait(150);
    const read = mail();
    skew += 5000;
    document.getElementById('folders').remove(); // folder pane closed: count unread rows
    await wait(150);
    const list = mail();
    skew += 30000;
    const tree = document.createElement('div');
    tree.setAttribute('role', 'tree');
    tree.innerHTML = '<div role="treeitem" aria-label="Inbox, 12 unread">Inbox12</div>';
    document.body.insertBefore(tree, document.body.firstChild);
    await wait(150);
    const labelled = mail();
    return { first, more, same, read, list, labelled, preview: window.__wdUnreadPreview() };
  });
  assert.deepStrictEqual(r.first, ['3/1'], 'first count is a baseline, not new mail');
  assert.deepStrictEqual(r.more, ['3/1', '4/0'], 'new mail raises the count');
  assert.strictEqual(r.same.length, 2, 'unchanged count is not reported again');
  assert.deepStrictEqual(r.read.slice(2), ['0/0'], 'reading the mail clears the count');
  assert.deepStrictEqual(r.list.slice(3), ['1/1'], 'list fallback starts a new baseline');
  assert.deepStrictEqual(r.labelled.slice(4), ['12/1'], 'aria-label "N unread" form is understood');
  assert.deepStrictEqual(r.preview, { title: 'Carol White', body: 'Budget approval - Please approve the Q3 budget' });
  assert.deepStrictEqual(errors, []);
  await ctx.close();
});

test('Outlook unread scraper stays off in Teams and when the page badges itself', async (b, base) => {
  const host = base.replace('127.0.0.1', 'outlook.wd-test');
  const t = await newPage(b, base, { site: 'teams' }, '/outlook.html');
  const teams = await t.page.evaluate(async () => {
    await new Promise((res) => setTimeout(res, 1900));
    return window.__calls.filter((c) => c.name === 'mailUnread').length;
  });
  await t.ctx.close();
  assert.strictEqual(teams, 0);
  const o = await newPage(b, host, { site: 'outlook' }, '/outlook.html');
  const badged = await o.page.evaluate(async () => {
    await new Promise((res) => setTimeout(res, 1900));
    navigator.setAppBadge(7);
    Date.now = ((real) => () => real() + 60000)(Date.now.bind(Date));
    document.getElementById('count').textContent = '9';
    await new Promise((res) => setTimeout(res, 150));
    return window.__calls.filter((c) => c.name === 'mailUnread' || c.name === 'badge').map((c) => c.name + ':' + c.args[1]);
  });
  await o.ctx.close();
  assert.deepStrictEqual(badged, ['mailUnread:3', 'badge:7']);
});

const COMPOSE_PAGE = (handler) => `<!doctype html><html><head><title>Chat | Teams</title></head><body>
  <div id="list"></div>
  <div id="pane"><div id="box" contenteditable="true" role="textbox" aria-label="Type a message"></div></div>
  <script>
    window.__got = [];
    const show = (files) => Promise.all([...files].map(async (f) => {
      window.__got.push({ name: f.name, type: f.type, text: await f.text() });
      const chip = document.createElement('div'); chip.textContent = f.name; document.getElementById('list').appendChild(chip);
    }));
    ${handler}
  </script></body></html>`;

test('attach: phone files are dropped into the message box (Teams/Outlook take dropped files)', async (b, base) => {
  PAGES['/compose-drop.html'] = COMPOSE_PAGE(`document.getElementById('pane').addEventListener('drop', (e) => { e.preventDefault(); show(e.dataTransfer.files); });
    document.getElementById('pane').addEventListener('dragover', (e) => e.preventDefault());`);
  const { ctx, page, errors } = await newPage(b, base, {}, '/compose-drop.html');
  const r = await page.evaluate(async () => {
    window.__attachData = { a1: 'hello report contents', b2: '%PDF-1.4 fake' };
    document.getElementById('box').focus();
    window.__wdAttach(JSON.stringify([{ id: 'a1', name: 'report-q3.txt', type: 'text/plain', size: 21 },
      { id: 'b2', name: 'contract.pdf', type: 'application/pdf', size: 13 }]));
    for (let i = 0; i < 40 && !window.__calls.some((c) => c.name === 'attachDone'); i++) await new Promise((res) => setTimeout(res, 100));
    return { got: window.__got, done: window.__calls.filter((c) => c.name === 'attachDone').map((c) => c.args.slice(1).join(',')),
      focus: window.__calls.filter((c) => c.name === 'composeFocus').map((c) => c.args[1]) };
  });
  assert.deepStrictEqual(r.got, [{ name: 'report-q3.txt', type: 'text/plain', text: 'hello report contents' },
    { name: 'contract.pdf', type: 'application/pdf', text: '%PDF-1.4 fake' }]);
  assert.deepStrictEqual(r.done, ['drop,2,1']);
  assert.deepStrictEqual(r.focus, ['1'], 'typing in the message box tells WorkDeck to show its attach button');
  assert.deepStrictEqual(errors, []);
  await ctx.close();
});

test('attach: falls back to paste, then to the page\'s file input; reports when there is no message box', async (b, base) => {
  PAGES['/compose-paste.html'] = COMPOSE_PAGE(`document.getElementById('box').addEventListener('paste', (e) => { e.preventDefault(); show(e.clipboardData.files); });`);
  PAGES['/compose-input.html'] = `<!doctype html><html><body><div id="list"></div>
    <input type="file" id="up" multiple style="display:none">
    <script>window.__got = []; document.getElementById('up').addEventListener('change', (e) => {
      [...e.target.files].forEach((f) => { window.__got.push(f.name); const d = document.createElement('div'); d.textContent = f.name; document.getElementById('list').appendChild(d); });
    });</script></body></html>`;
  PAGES['/compose-none.html'] = '<!doctype html><html><body><p>nothing to attach to</p></body></html>';
  const run = async (file) => {
    const { ctx, page } = await newPage(b, base, {}, file);
    const r = await page.evaluate(async () => {
      window.__attachData = { x: 'photo-bytes' };
      window.__wdAttach(JSON.stringify([{ id: 'x', name: 'holiday-photo.jpg', type: 'image/jpeg', size: 11 }]));
      for (let i = 0; i < 50 && !window.__calls.some((c) => c.name === 'attachDone'); i++) await new Promise((res) => setTimeout(res, 100));
      return { got: (window.__got || []).map((g) => g.name || g), done: window.__calls.filter((c) => c.name === 'attachDone').map((c) => c.args.slice(1).join(',')) };
    });
    await ctx.close();
    return r;
  };
  const paste = await run('/compose-paste.html');
  assert.deepStrictEqual(paste, { got: ['holiday-photo.jpg'], done: ['paste,1,1'] });
  const input = await run('/compose-input.html');
  assert.deepStrictEqual(input, { got: ['holiday-photo.jpg'], done: ['input,1,1'] });
  const none = await run('/compose-none.html');
  assert.deepStrictEqual(none, { got: [], done: ['none,1,0'] });
});

test('shared text is typed into the message box', async (b, base) => {
  PAGES['/compose-text.html'] = COMPOSE_PAGE('');
  const { ctx, page } = await newPage(b, base, {}, '/compose-text.html');
  const r = await page.evaluate(() => { window.__wdInsertText('Minutes from today: https://example.com/x'); return document.getElementById('box').textContent; });
  assert.strictEqual(r, 'Minutes from today: https://example.com/x');
  await ctx.close();
});

test('showOpenFilePicker (missing in Android WebView) opens the normal file chooser', async (b, base) => {
  PAGES['/picker.html'] = `<!doctype html><html><body><button id="go">pick</button><script>
    document.getElementById('go').addEventListener('click', async () => {
      try {
        const [h] = await window.showOpenFilePicker({ types: [{ accept: { 'application/pdf': ['.pdf'] } }] });
        const f = await h.getFile(); window.__picked = h.kind + ':' + h.name + ':' + (await f.text());
      } catch (e) { window.__picked = 'error:' + e.name; }
    });</script></body></html>`;
  // Android WebView has no showOpenFilePicker; desktop Chromium does, so remove it first.
  const ctx = await b.newContext();
  await ctx.addInitScript('delete window.showOpenFilePicker;');
  await ctx.addInitScript(BRIDGE_MOCK);
  await ctx.addInitScript(script({}));
  const page = await ctx.newPage();
  await page.goto(base + '/picker.html');
  const [chooser] = await Promise.all([page.waitForEvent('filechooser'), page.click('#go')]);
  await chooser.setFiles({ name: 'minutes.pdf', mimeType: 'application/pdf', buffer: Buffer.from('pdf-bytes') });
  await page.waitForFunction(() => window.__picked);
  assert.strictEqual(await page.evaluate(() => window.__picked), 'file:minutes.pdf:pdf-bytes');
  await ctx.close();
});

test('a page that is not on screen reports itself hidden and still gets (emulated) frames', async (b, base) => {
  const ctx = await b.newContext();
  // No real frames are drawn for an off-screen WebView: simulate that.
  await ctx.addInitScript("window.__drawn = '0'; window.requestAnimationFrame = function () { return 7; }; window.cancelAnimationFrame = function () {};");
  await ctx.addInitScript(BRIDGE_MOCK.replace("window.__calls = [];", "window.__calls = []; window.__drawn = '0';"));
  await ctx.addInitScript(script({}));
  const page = await ctx.newPage();
  await page.goto(base + '/mobile.html');
  const r = await page.evaluate(async () => {
    const wait = (ms) => new Promise((res) => setTimeout(res, ms));
    const events = [];
    document.addEventListener('visibilitychange', () => events.push(document.visibilityState));
    const offscreen = { vis: document.visibilityState, hidden: document.hidden };
    let fired = 0, canceled = 0;
    requestAnimationFrame((ts) => { fired = typeof ts === 'number' ? 1 : -1; });
    const id = requestAnimationFrame(() => { canceled = 1; });
    cancelAnimationFrame(id);
    await wait(500);
    window.__wdDrawn(true);
    const shown = { vis: document.visibilityState, hidden: document.hidden };
    window.__wdDrawn(false);
    return { offscreen, shown, fired, canceled, events, after: document.visibilityState };
  });
  assert.deepStrictEqual(r.offscreen, { vis: 'hidden', hidden: true }, 'off screen: the page is told it is hidden');
  assert.strictEqual(r.fired, 1, 'animation frames still come off screen (emulated)');
  assert.strictEqual(r.canceled, 0, 'cancelAnimationFrame cancels an emulated frame');
  assert.deepStrictEqual(r.shown, { vis: 'visible', hidden: false });
  assert.deepStrictEqual(r.events, ['visible', 'hidden'], 'visibilitychange fires when WorkDeck shows / hides the tab');
  assert.strictEqual(r.after, 'hidden');
  await ctx.close();
});

test('while the user is not looking, Teams cannot tell otherwise: hidden, not focused, nothing "on screen"', async (b, base) => {
  // Teams marks messages as read ("Seen") when it believes the user is looking at the chat.
  PAGES['/chat.html'] = `<!doctype html><html><head><title>Chat | Teams</title></head><body>
      <div id="m1" style="height:80px">new message</div><div id="m2" style="height:80px">another</div></body></html>`;
  const ctx = await b.newContext();
  await ctx.addInitScript(BRIDGE_MOCK.replace("window.__calls = [];", "window.__calls = []; window.__drawn = '0';"));
  await ctx.addInitScript(script({}));
  const page = await ctx.newPage();
  await page.goto(base + '/chat.html');
  await page.bringToFront();
  const r = await page.evaluate(async () => {
    const wait = (ms) => new Promise((res) => setTimeout(res, ms));
    const log = [];
    window.addEventListener('focus', () => log.push('focus'));
    window.onblur = () => log.push('blur');
    const seen = [];
    let sameObserver = null;
    const io = new IntersectionObserver((entries, obs) => {
      sameObserver = obs === io;
      entries.forEach((e) => seen.push(e.target.id + ':' + e.isIntersecting));
    });
    io.observe(document.getElementById('m1'));
    io.observe(document.getElementById('m2'));
    await wait(400);
    const away = { focus: document.hasFocus(), vis: document.visibilityState, seen: seen.length };
    window.dispatchEvent(new FocusEvent('focus')); // what the engine sends when the page gets focus
    const focusWhileAway = log.slice();
    io.unobserve(document.getElementById('m2')); // the page stops watching one while away
    window.__wdDrawn(true);
    await wait(400);
    const back = { focus: document.hasFocus(), vis: document.visibilityState, seen: seen.slice(), sameObserver: sameObserver, log: log.slice() };
    window.__wdDrawn(false);
    const left = { focus: document.hasFocus(), vis: document.visibilityState, log: log.slice() };
    return { away, focusWhileAway, back, left, isIO: io instanceof IntersectionObserver, name: IntersectionObserver.name };
  });
  assert.deepStrictEqual(r.away, { focus: false, vis: 'hidden', seen: 0 }, 'away: hidden, unfocused, no message reported on screen');
  assert.deepStrictEqual(r.focusWhileAway, [], 'a focus event while away does not reach the page');
  assert.strictEqual(r.back.vis, 'visible');
  assert.strictEqual(r.back.focus, true, 'looking again: focused');
  assert.deepStrictEqual(r.back.seen, ['m1:true'], 'looking again: the message is reported on screen (the unobserved one is not)');
  assert.strictEqual(r.back.sameObserver, true, 'callback gets its own observer');
  assert.deepStrictEqual(r.back.log, ['focus'], 'looking again: the page gets its focus event');
  assert.deepStrictEqual(r.left, { focus: false, vis: 'hidden', log: ['focus', 'blur'] }, 'looking away: blur');
  assert.strictEqual(r.isIO, true, 'instanceof IntersectionObserver still holds');
  assert.strictEqual(r.name, 'IntersectionObserver');
  await ctx.close();
});

test('IntersectionObserver keeps working normally while the user looks (incl. subclasses), and without the bridge pages count as not looked at', async (b, base) => {
  const { ctx, page, errors } = await newPage(b, base, {}, '/chat.html');
  const r = await page.evaluate(async () => {
    const wait = (ms) => new Promise((res) => setTimeout(res, ms));
    class Watch extends IntersectionObserver {
      constructor(cb) { super(cb, { threshold: [0, 1] }); this.tagged = 'yes'; }
      hello() { return 'hi'; }
    }
    const seen = [];
    const w = new Watch((entries) => entries.forEach((e) => seen.push(e.target.id)));
    w.observe(document.getElementById('m1'));
    await wait(300);
    let threw = false;
    try { IntersectionObserver(() => {}); } catch (e) { threw = e instanceof TypeError; }
    const d = new IntersectionObserver(() => {});
    d.observe(document.getElementById('m2'));
    d.disconnect();
    return { seen, hello: w.hello(), tagged: w.tagged, inst: w instanceof IntersectionObserver, sub: w instanceof Watch, threw, focus: document.hasFocus() };
  });
  assert.deepStrictEqual(r, { seen: ['m1'], hello: 'hi', tagged: 'yes', inst: true, sub: true, threw: true, focus: true });
  assert.deepStrictEqual(errors, []);
  await ctx.close();
  const ctx2 = await b.newContext();
  await ctx2.addInitScript(script({})); // no WorkDeck bridge at all
  const p2 = await ctx2.newPage();
  await p2.goto(base + '/chat.html');
  const v = await p2.evaluate(() => [document.visibilityState, document.hasFocus()]);
  assert.deepStrictEqual(v, ['hidden', false], 'unknown = not looked at (never mark as read by mistake)');
  await ctx2.close();
});

test('company sign-in policy pages ("You must use Microsoft Edge") are recognised; ordinary pages are not', async (b, base) => {
  PAGES['/ca-blocked.html'] = `<!doctype html><html><head><title>Sign in to your account</title>
    <script>window.$Config = { iErrorCode: 53005, strServiceExceptionMessage: 'AADSTS53005: Application needs to enforce Intune protection policies.' };</script>
    </head><body><div>ONE WORKPLACE</div><div>user@omc.com</div><h1>You can't get there from here</h1>
    <p>You must use Microsoft Edge to access this resource.</p><a href="microsoft-edge:https://outlook.office.com/mail/">Launch in Edge</a></body></html>`;
  PAGES['/chat-mentions.html'] = `<!doctype html><html><body><p>Bob: you can't get there from here, take the bus</p></body></html>`;
  const signals = async (file) => {
    const { ctx, page } = await newPage(b, base, { site: 'outlook', desktop: false, desktopLayout: false }, file);
    await page.waitForTimeout(1500);
    const r = await page.evaluate(() => window.__calls.filter((c) => c.name === 'callSignal').map((c) => c.args[1] + ':' + c.args[2]));
    await ctx.close();
    return r;
  };
  assert.deepStrictEqual(await signals('/ca-blocked.html'), ['access_blocked:edge-required']);
  assert.deepStrictEqual(await signals('/chat-mentions.html'), [], 'a chat message with the same words is not a sign-in block');
  // Older WebViews add WorkDeck's script only once the page has loaded (no document-start
  // scripts): the policy page must still be recognised.
  const ctx = await b.newContext();
  const page = await ctx.newPage();
  await page.goto(base + '/ca-blocked.html');
  await page.waitForLoadState('load');
  await page.evaluate(BRIDGE_MOCK);
  await page.addScriptTag({ content: script({ site: 'outlook', desktop: false, desktopLayout: false }) });
  await page.waitForTimeout(1200);
  const late = await page.evaluate(() => window.__calls.filter((c) => c.name === 'callSignal').map((c) => c.args[1] + ':' + c.args[2]));
  await ctx.close();
  assert.deepStrictEqual(late, ['access_blocked:edge-required'], 'recognised when the script arrives after the page loaded');
});

(async () => {
  const srv = await startServer();
  const base = 'http://127.0.0.1:' + srv.address().port;
  const browser = await chromium.launch({
    args: ['--host-resolver-rules=MAP outlook.wd-test 127.0.0.1', '--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream', '--autoplay-policy=no-user-gesture-required'],
  });
  let failed = 0;
  for (const t of tests) {
    try {
      await Promise.race([
        t.fn(browser, base),
        new Promise((_, rej) => setTimeout(() => rej(new Error('test timed out after 20s')), 20000)),
      ]);
      console.log('PASS  ' + t.name);
    } catch (e) {
      failed++;
      console.log('FAIL  ' + t.name + '\n      ' + String(e && e.stack || e).split('\n').slice(0, 4).join('\n      '));
    }
  }
  await browser.close();
  srv.close();
  console.log(`\n${tests.length - failed}/${tests.length} passed`);
  process.exit(failed ? 1 : 0);
})();
