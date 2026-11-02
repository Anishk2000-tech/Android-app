/*
 * WorkDeck page integration script.
 *
 * Runs at document start in Microsoft pages (Teams, Outlook, sign-in) inside the
 * Android WebView and fills the gaps between Android WebView and desktop Chrome:
 *   - presents a consistent desktop-Chrome identity (desktop view only)
 *   - forces a desktop-width layout / keeps pinch-zoom enabled
 *   - implements the Web Notification API (missing in Android WebView) by
 *     forwarding notifications to native Android notifications
 *   - Badging API (unread counts), blob downloads, call/microphone tracking
 *
 * The CFG placeholder below is replaced natively with a JSON object. The bridge
 * token lives only inside this closure; native code rejects calls without it.
 */
(function () {
  'use strict';
  var CFG = __WORKDECK_CONFIG__;
  if (window.__wdInstalled) return;
  try {
    Object.defineProperty(window, '__wdInstalled', { value: true });
  } catch (e) {
    return;
  }

  var bridge = null;
  try { bridge = window.WorkDeckBridge || null; } catch (e) { bridge = null; }
  var TOKEN = CFG.token;
  var isTop = (function () { try { return window.top === window; } catch (e) { return false; } })();

  function call(method) {
    if (!bridge || typeof bridge[method] !== 'function') return null;
    var args = [TOKEN];
    for (var i = 1; i < arguments.length; i++) args.push(arguments[i] == null ? '' : String(arguments[i]));
    try { return bridge[method].apply(bridge, args); } catch (e) { return null; }
  }
  function log(msg) { if (CFG.debug) call('log', msg); }
  function hidden(obj, prop, value) {
    try {
      Object.defineProperty(obj, prop, { value: value, configurable: true, writable: true, enumerable: false });
      return true;
    } catch (e) { return false; }
  }
  function getter(obj, prop, fn) {
    try {
      Object.defineProperty(obj, prop, { get: fn, configurable: true, enumerable: true });
      return true;
    } catch (e) { return false; }
  }
  function own(obj, prop, value) {
    try { Object.defineProperty(obj, prop, { value: value, configurable: true, enumerable: true }); } catch (e) {}
  }

  // ------------------------------------------------------------------ on screen or not
  // WorkDeck keeps pages that are not on screen fully awake (the engine would otherwise
  // slow them down until messages arrive late). The page itself is still told the truth:
  // "hidden" whenever the user can't see it, so Teams/Outlook keep raising notifications.
  // Nothing is drawn off screen, so animation frames never come; web apps also use them
  // to update the page (new messages, the incoming-call screen), so they are emulated
  // with timers while the page isn't drawn.
  var drawnCache = true, drawnAt = 0, drawnPushed = false;
  var isDrawn = function () {
    if (!drawnPushed) {
      var now = Date.now();
      if (now - drawnAt > 1000) {
        var v = call('drawn');
        drawnCache = v !== '0';
        drawnAt = now;
      }
    }
    return drawnCache;
  };
  var pendingFrames = {};
  var armFrameFallbacks = function () {
    Object.keys(pendingFrames).forEach(function (id) {
      var e = pendingFrames[id];
      if (e && !e.t) e.t = setTimeout(e.fallback, 200);
    });
  };
  try {
    var visDesc = Object.getOwnPropertyDescriptor(Document.prototype, 'visibilityState');
    var hidDesc = Object.getOwnPropertyDescriptor(Document.prototype, 'hidden');
    if (visDesc && visDesc.get && hidDesc && hidDesc.get) {
      getter(Document.prototype, 'visibilityState', function () { return isDrawn() ? visDesc.get.call(this) : 'hidden'; });
      getter(Document.prototype, 'hidden', function () { return isDrawn() ? hidDesc.get.call(this) : true; });
    }
  } catch (e) {}
  if (isTop) {
    hidden(window, '__wdDrawn', function (on) {
      var was = isDrawn();
      drawnCache = !!on;
      drawnPushed = true;
      if (was !== drawnCache) {
        try { document.dispatchEvent(new Event('visibilitychange')); } catch (e) {}
      }
      if (!drawnCache) armFrameFallbacks();
    });
  }
  if (typeof window.requestAnimationFrame === 'function') {
    var origRAF = window.requestAnimationFrame;
    var origCAF = window.cancelAnimationFrame;
    var frameSeq = 1;
    hidden(window, 'requestAnimationFrame', function (cb) {
      var id = frameSeq++;
      var entry = { done: false, t: 0, r: 0 };
      var run = function (ts) {
        if (entry.done) return;
        entry.done = true;
        delete pendingFrames[id];
        if (entry.t) clearTimeout(entry.t);
        cb(ts);
      };
      entry.fallback = function () {
        entry.t = 0;
        try { origCAF.call(window, entry.r); } catch (e) {}
        run(performance.now());
      };
      entry.r = origRAF.call(window, run);
      pendingFrames[id] = entry;
      if (!isDrawn()) entry.t = setTimeout(entry.fallback, 200);
      return id;
    });
    hidden(window, 'cancelAnimationFrame', function (id) {
      var e = pendingFrames[id];
      if (!e) return;
      e.done = true;
      delete pendingFrames[id];
      if (e.t) clearTimeout(e.t);
      try { origCAF.call(window, e.r); } catch (x) {}
    });
  }

  // ------------------------------------------------------------------ identity
  function installUAData(low, high) {
    var proto = (typeof NavigatorUAData !== 'undefined' && NavigatorUAData.prototype) || Object.prototype;
    var data = Object.create(proto);
    var brands = Object.freeze(low.brands.map(function (b) { return Object.freeze(b); }));
    own(data, 'brands', brands);
    own(data, 'mobile', low.mobile);
    own(data, 'platform', low.platform);
    hidden(data, 'getHighEntropyValues', function (hints) {
      var out = { brands: brands, mobile: low.mobile, platform: low.platform };
      (hints || []).forEach(function (h) { if (Object.prototype.hasOwnProperty.call(high, h)) out[h] = high[h]; });
      return Promise.resolve(out);
    });
    hidden(data, 'toJSON', function () { return { brands: brands, mobile: low.mobile, platform: low.platform }; });
    getter(Navigator.prototype, 'userAgentData', function () { return data; });
  }

  var brandList = (CFG.brands || []).map(function (b) { return { brand: b[0], version: b[1] }; });
  var fullList = (CFG.brands || []).map(function (b) { return { brand: b[0], version: b[2] }; });

  if (CFG.desktop) {
    getter(Navigator.prototype, 'platform', function () { return 'Win32'; });
    installUAData(
      { brands: brandList, mobile: false, platform: 'Windows' },
      { architecture: 'x86', bitness: '64', model: '', platformVersion: '15.0.0', uaFullVersion: CFG.fullVersion,
        fullVersionList: fullList, wow64: false, formFactors: ['Desktop'] });
    if (!window.chrome) {
      hidden(window, 'chrome', {
        app: {
          isInstalled: false,
          InstallState: { DISABLED: 'disabled', INSTALLED: 'installed', NOT_INSTALLED: 'not_installed' },
          RunningState: { CANNOT_RUN: 'cannot_run', READY_TO_RUN: 'ready_to_run', RUNNING: 'running' },
          getDetails: function () { return null; },
          getIsInstalled: function () { return false; }
        },
        csi: function () { return {}; },
        loadTimes: function () { return {}; }
      });
    }
  } else if (brandList.length) {
    // Mobile view: present as Chrome for Android rather than "Android WebView".
    installUAData(
      { brands: brandList, mobile: true, platform: 'Android' },
      { architecture: '', bitness: '', model: CFG.model || '', platformVersion: CFG.androidVersion || '',
        uaFullVersion: CFG.fullVersion, fullVersionList: fullList, wow64: false, formFactors: ['Mobile'] });
  }

  // ------------------------------------------------------------------ viewport
  if (isTop) {
    var desired = CFG.desktop && CFG.desktopLayout ? ('width=' + CFG.desktopWidth + ', user-scalable=yes') : null;
    var fixContent = function (c) {
      if (desired) return desired;
      c = c || '';
      return c.replace(/user-scalable\s*=\s*(no|0)/ig, 'user-scalable=yes')
              .replace(/maximum-scale\s*=\s*[0-9.]+/ig, 'maximum-scale=5');
    };
    var isViewport = function (n) {
      return n && n.nodeName === 'META' && String(n.getAttribute('name') || '').toLowerCase() === 'viewport';
    };
    var fixMeta = function (m) {
      var c = m.getAttribute('content');
      var n = fixContent(c);
      if (c !== n) m.setAttribute('content', n);
    };
    var scan = function (root) {
      if (!root || !root.querySelectorAll) return;
      var list = root.querySelectorAll('meta');
      for (var i = 0; i < list.length; i++) if (isViewport(list[i])) fixMeta(list[i]);
    };
    var onMutations = function (muts) {
      for (var i = 0; i < muts.length; i++) {
        var m = muts[i];
        if (m.type === 'attributes') {
          if (isViewport(m.target)) fixMeta(m.target);
          continue;
        }
        for (var j = 0; j < m.addedNodes.length; j++) {
          var n = m.addedNodes[j];
          if (isViewport(n)) fixMeta(n);
          else if (n.nodeName === 'HEAD' || n.nodeName === 'HTML') scan(n);
        }
      }
    };
    var mo = new MutationObserver(onMutations);
    try { mo.observe(document, { childList: true, subtree: true, attributes: true, attributeFilter: ['content', 'name'] }); } catch (e) {}
    document.addEventListener('DOMContentLoaded', function () {
      scan(document);
      if (desired && document.head && !document.querySelector('meta[name="viewport" i]')) {
        var meta = document.createElement('meta');
        meta.setAttribute('name', 'viewport');
        meta.setAttribute('content', desired);
        document.head.appendChild(meta);
      }
      // After parsing, only <head> can still change: watch just that (cheap).
      mo.disconnect();
      if (document.head) {
        try { mo.observe(document.head, { childList: true, subtree: true, attributes: true, attributeFilter: ['content', 'name'] }); } catch (e) {}
      }
    });
  }

  // ------------------------------------------------------------------ helpers
  function absUrl(u) {
    if (!u) return '';
    try { return new URL(String(u), location.href).href; } catch (e) { return ''; }
  }
  function blobToDataUrl(url, maxBytes) {
    return fetch(url).then(function (r) { return r.blob(); }).then(function (b) {
      if (maxBytes && b.size > maxBytes) return '';
      return new Promise(function (resolve) {
        var fr = new FileReader();
        fr.onload = function () { resolve(String(fr.result || '')); };
        fr.onerror = function () { resolve(''); };
        fr.readAsDataURL(b);
      });
    }).catch(function () { return ''; });
  }

  // ------------------------------------------------------------------ notifications
  var registry = Object.create(null);
  var seq = 0;
  var pendingPermission = [];

  function permission() {
    var p = call('permission');
    return (p === 'granted' || p === 'denied') ? p : 'default';
  }
  function fire(n, type) {
    var ev;
    try { ev = new Event(type, { cancelable: true }); } catch (e) { return; }
    var handler = n['on' + type];
    if (typeof handler === 'function') {
      try { handler.call(n, ev); } catch (err) { setTimeout(function () { throw err; }); }
    }
    try { n.dispatchEvent(ev); } catch (e) {}
  }

  var WDNotification = class Notification extends EventTarget {
    constructor(title, options) {
      super();
      if (arguments.length === 0) {
        throw new TypeError("Failed to construct 'Notification': 1 argument required, but only 0 present.");
      }
      options = options || {};
      this.title = String(title);
      this.body = options.body == null ? '' : String(options.body);
      this.tag = options.tag == null ? '' : String(options.tag);
      this.icon = options.icon ? absUrl(options.icon) : '';
      this.image = options.image ? absUrl(options.image) : '';
      this.badge = options.badge ? absUrl(options.badge) : '';
      this.data = options.data === undefined ? null : options.data;
      this.dir = options.dir || 'auto';
      this.lang = options.lang || '';
      this.renotify = !!options.renotify;
      this.requireInteraction = !!options.requireInteraction;
      this.silent = options.silent === true;
      this.timestamp = typeof options.timestamp === 'number' ? options.timestamp : Date.now();
      this.actions = Array.isArray(options.actions) ? options.actions.slice(0, 2) : [];
      this.vibrate = [];
      this.onclick = null;
      this.onshow = null;
      this.onerror = null;
      this.onclose = null;
      var id = 'n' + (++seq) + '-' + Date.now().toString(36);
      hidden(this, '__wdId', id);
      hidden(this, '__wdSw', !!options.__wdSw);
      registry[id] = this;
      var self = this;
      var icon = this.icon;
      // Post to Android right away (not after a timer): the page may navigate away
      // immediately after creating the notification. Events still fire asynchronously.
      var send = function (iconUrl) {
        if (permission() !== 'granted') { delete registry[id]; return false; }
        var payload = {
          id: id, title: self.title, body: self.body, tag: self.tag, icon: iconUrl || '',
          requireInteraction: self.requireInteraction, silent: self.silent, renotify: self.renotify,
          origin: location.origin, sw: self.__wdSw
        };
        call('notify', JSON.stringify(payload));
        return true;
      };
      var after = function (sent) { setTimeout(function () { fire(self, sent ? 'show' : 'error'); }, 0); };
      if (icon.indexOf('blob:') === 0) {
        blobToDataUrl(icon, 262144).then(function (d) { after(send(d)); }, function () { after(send('')); });
      } else {
        after(send(icon.indexOf('data:') === 0 && icon.length > 350000 ? '' : icon));
      }
    }
    close() {
      var id = this.__wdId;
      if (!registry[id]) return;
      delete registry[id];
      call('closeNotification', id);
      fire(this, 'close');
    }
    static get permission() { return permission(); }
    static get maxActions() { return 2; }
    static requestPermission(callback) {
      return new Promise(function (resolve) {
        var done = false;
        var finish = function (result) {
          if (done) return;
          done = true;
          if (typeof callback === 'function') { try { callback(result); } catch (e) {} }
          resolve(result);
        };
        var p = permission();
        if (p !== 'default') { finish(p); return; }
        pendingPermission.push(function () { finish(permission()); });
        call('requestPermission');
        var polls = 0;
        var poll = function () {
          if (done) return;
          var now = permission();
          if (now !== 'default' || ++polls >= 60) { finish(now); return; }
          setTimeout(poll, 1000);
        };
        setTimeout(poll, 1000);
      });
    }
  };

  if (CFG.notifications) {
    hidden(window, 'Notification', WDNotification);

    if (typeof ServiceWorkerRegistration !== 'undefined' && ServiceWorkerRegistration.prototype) {
      hidden(ServiceWorkerRegistration.prototype, 'showNotification', function (title, options) {
        try {
          var o = Object.assign({}, options || {});
          o.__wdSw = true;
          new WDNotification(title, o);
          return Promise.resolve();
        } catch (e) { return Promise.reject(e); }
      });
      hidden(ServiceWorkerRegistration.prototype, 'getNotifications', function (filter) {
        var tag = filter && filter.tag;
        return Promise.resolve(Object.keys(registry).map(function (k) { return registry[k]; })
          .filter(function (n) { return n.__wdSw && (!tag || n.tag === tag); }));
      });
    }

    // Notifications forwarded by the service-worker shim (see SwShim.java).
    if (isTop && navigator.serviceWorker && navigator.serviceWorker.addEventListener) {
      navigator.serviceWorker.addEventListener('message', function (e) {
        var d = e && e.data;
        if (!d || !d.__wdNotify) return;
        var p = d.__wdNotify;
        try {
          new WDNotification(p.title || '', { body: p.body, tag: p.tag, icon: p.icon, data: p.data,
            requireInteraction: p.requireInteraction, silent: p.silent, __wdSw: true });
        } catch (x) {}
      });
    }

  }

  // Badging API -> unread counter in the tab bar and status notification.
  var pageBadges = false;
  hidden(Navigator.prototype, 'setAppBadge', function (n) {
    var v = (n === undefined) ? -1 : Math.max(0, Math.floor(Number(n) || 0));
    pageBadges = true;
    if (isTop) call('badge', v);
    return Promise.resolve();
  });
  hidden(Navigator.prototype, 'clearAppBadge', function () {
    pageBadges = true;
    if (isTop) call('badge', 0);
    return Promise.resolve();
  });

  // ------------------------------------------------------------------ downloads
  // Pages often revoke blob: URLs right after starting a download; keep them
  // alive for a minute so the native download path can still read them.
  if (window.URL && URL.revokeObjectURL) {
    var origRevoke = URL.revokeObjectURL;
    hidden(URL, 'revokeObjectURL', function (u) {
      var self = this;
      setTimeout(function () { try { origRevoke.call(self, u); } catch (e) {} }, 60000);
    });
  }
  var downloadNames = Object.create(null);
  document.addEventListener('click', function (e) {
    var a = e.target && e.target.closest ? e.target.closest('a[href]') : null;
    if (a && (a.hasAttribute('download') || /^(blob|data):/.test(a.href))) {
      downloadNames[a.href] = a.getAttribute('download') || '';
    }
  }, true);
  if (typeof HTMLAnchorElement !== 'undefined') {
    var origClick = HTMLAnchorElement.prototype.click;
    hidden(HTMLAnchorElement.prototype, 'click', function () {
      try { if (this.href && (this.hasAttribute('download') || /^(blob|data):/.test(this.href))) downloadNames[this.href] = this.getAttribute('download') || ''; } catch (e) {}
      return origClick.apply(this, arguments);
    });
  }
  // Called natively for blob:/data: downloads. Streams the file in chunks.
  hidden(window, '__wdDownload', function (url, mime) {
    var name = downloadNames[url] || '';
    var CHUNK = 768 * 1024;
    fetch(url).then(function (r) { return r.blob(); }).then(function (blob) {
      var id = call('fileStart', name, mime || blob.type || '', blob.size);
      if (!id) return;
      var offset = 0;
      var next = function () {
        if (offset >= blob.size) { call('fileEnd', id, 'ok'); return; }
        var fr = new FileReader();
        fr.onload = function () {
          var s = String(fr.result || '');
          var comma = s.indexOf(',');
          call('fileChunk', id, comma >= 0 ? s.substring(comma + 1) : '');
          offset += CHUNK;
          next();
        };
        fr.onerror = function () { call('fileEnd', id, 'error'); };
        fr.readAsDataURL(blob.slice(offset, offset + CHUNK));
      };
      next();
    }).catch(function (e) { call('fileEnd', '', 'error:' + e); });
  });

  // ------------------------------------------------------------------ permissions & media devices
  // Android WebView has no saved camera/microphone permission state, so on its own it
  // tells pages microphone/camera are "denied" and hides device names and ids from
  // enumerateDevices(). Teams then believes there is no microphone, camera or speaker.
  // WorkDeck answers from Android's real state instead, hands out stable device ids,
  // maps them back to real constraints in getUserMedia(), and turns Teams' speaker
  // choice into Android audio routing.
  var mediaInfo = function () {
    var raw = call('mediaInfo');
    if (!raw) return null;
    try { return JSON.parse(raw); } catch (e) { return null; }
  };
  // Android WebView has no Permissions API at all (navigator.permissions is undefined),
  // so pages like Teams can't learn that the mic/camera are usable. Provide one.
  if (typeof window.PermissionStatus === 'undefined') {
    hidden(window, 'PermissionStatus', class PermissionStatus extends EventTarget {});
  }
  if (!navigator.permissions) {
    var PermissionsCtor = (typeof window.Permissions === 'function') ? window.Permissions : class Permissions {};
    if (typeof window.Permissions === 'undefined') hidden(window, 'Permissions', PermissionsCtor);
    var permsObj = Object.create(PermissionsCtor.prototype);
    hidden(permsObj, 'query', function (desc) {
      if (!desc || typeof desc.name !== 'string') return Promise.reject(new TypeError("Failed to execute 'query' on 'Permissions'"));
      return Promise.resolve(fakeStatus(desc.name, 'prompt'));
    });
    getter(Navigator.prototype, 'permissions', function () { return permsObj; });
  }
  function fakeStatus(name, state) {
    var st;
    try { st = Object.create(PermissionStatus.prototype); } catch (e) { st = {}; }
    own(st, 'state', state);
    own(st, 'status', state);
    own(st, 'name', name);
    hidden(st, 'onchange', null);
    hidden(st, 'addEventListener', function () {});
    hidden(st, 'removeEventListener', function () {});
    hidden(st, 'dispatchEvent', function () { return true; });
    return st;
  }
  if (navigator.permissions && navigator.permissions.query) {
    var origQuery = navigator.permissions.query;
    hidden(navigator.permissions, 'query', function (desc) {
      var name = desc && desc.name;
      if (CFG.notifications && (name === 'notifications' || name === 'push')) {
        var p = permission();
        return Promise.resolve(fakeStatus(name, p === 'default' ? 'prompt' : p));
      }
      if (name === 'microphone' || name === 'camera') {
        var info = mediaInfo();
        if (info) return Promise.resolve(fakeStatus(name, (name === 'camera' ? info.camera : info.mic) ? 'granted' : 'prompt'));
      }
      if (name === 'speaker-selection') return Promise.resolve(fakeStatus(name, 'granted'));
      return origQuery.apply(this, arguments);
    });
  }

  var ROUTE_IDS = { 'wd-route-speaker': 'SPEAKER', 'wd-route-earpiece': 'EARPIECE', 'wd-route-bluetooth': 'BLUETOOTH', 'wd-route-wired': 'WIRED' };
  // Microphones Teams can pick. Android sends call audio to one device at a time, so a
  // Bluetooth or headset microphone also means that device's speaker (and vice versa).
  var MIC_IDS = { 'wd-mic-phone': 'phone', 'wd-mic-bluetooth': 'bluetooth', 'wd-mic-wired': 'wired' };
  var micLabel = function (id, info) {
    if (id === 'wd-mic-bluetooth') return info && info.btName ? 'Bluetooth: ' + info.btName : 'Bluetooth headset microphone';
    if (id === 'wd-mic-wired') return 'Headset microphone';
    if (id === 'wd-mic-phone') return 'Phone microphone';
    return 'Automatic (Bluetooth or headset when connected)';
  };
  var ROUTE_LABELS = { speaker: 'Phone speaker (loud)', earpiece: 'Phone earpiece', bluetooth: 'Bluetooth', wired: 'Headset' };
  var makeDevice = function (kind, id, label, group) {
    var proto = Object.prototype;
    try {
      if (kind !== 'audiooutput' && typeof InputDeviceInfo !== 'undefined') proto = InputDeviceInfo.prototype;
      else if (typeof MediaDeviceInfo !== 'undefined') proto = MediaDeviceInfo.prototype;
    } catch (e) {}
    var d = Object.create(proto);
    own(d, 'deviceId', id);
    own(d, 'kind', kind);
    own(d, 'label', label);
    own(d, 'groupId', group);
    hidden(d, 'toJSON', function () { return { deviceId: id, kind: kind, label: label, groupId: group }; });
    if (kind !== 'audiooutput') {
      hidden(d, 'getCapabilities', function () {
        return kind === 'videoinput' ? { deviceId: id, facingMode: [id === 'wd-camera-back' ? 'environment' : 'user'] } : { deviceId: id };
      });
    }
    return d;
  };
  var augmentDevices = function (list) {
    var info = mediaInfo();
    if (!info) return list;
    var real = { audioinput: [], videoinput: [] };
    (list || []).forEach(function (d) { if (real[d.kind] && d.deviceId && d.label) real[d.kind].push(d); });
    var out = [];
    var routes = info.routes || [];
    // Always WorkDeck's own microphone list: picking one routes Android call audio, which
    // the engine's own (often unlabelled or partial) list can't do reliably.
    if (info.mic) {
      out.push(makeDevice('audioinput', 'default', micLabel('default', info), 'wd-phone'));
      out.push(makeDevice('audioinput', 'wd-mic-phone', micLabel('wd-mic-phone', info), 'wd-phone'));
      if (routes.indexOf('bluetooth') >= 0) out.push(makeDevice('audioinput', 'wd-mic-bluetooth', micLabel('wd-mic-bluetooth', info), 'wd-route-bluetooth'));
      if (routes.indexOf('wired') >= 0) out.push(makeDevice('audioinput', 'wd-mic-wired', micLabel('wd-mic-wired', info), 'wd-route-wired'));
    } else if (real.audioinput.length) {
      out = out.concat(real.audioinput);
    } else {
      out.push(makeDevice('audioinput', '', '', ''));
    }
    if (real.videoinput.length) out = out.concat(real.videoinput);
    else if (info.camera) {
      (info.cameras || []).forEach(function (c) {
        out.push(makeDevice('videoinput', 'wd-camera-' + c, c === 'back' ? 'Back camera' : (c === 'front' ? 'Front camera' : 'Camera'), 'wd-camera-' + c));
      });
    } else if ((info.cameras || []).length) out.push(makeDevice('videoinput', '', '', ''));
    if (info.mic) {
      out.push(makeDevice('audiooutput', 'default', 'Automatic (phone chooses)', 'wd-phone'));
      routes.forEach(function (r) {
        var label = r === 'bluetooth' && info.btName ? 'Bluetooth: ' + info.btName : (ROUTE_LABELS[r] || r);
        out.push(makeDevice('audiooutput', 'wd-route-' + r, label, r === 'speaker' || r === 'earpiece' ? 'wd-phone' : 'wd-route-' + r));
      });
    } else {
      out.push(makeDevice('audiooutput', '', '', ''));
    }
    return out;
  };
  var idOf = function (v) {
    if (v == null) return null;
    if (typeof v === 'string') return v;
    if (Array.isArray(v)) return v.length ? idOf(v[0]) : null;
    if (typeof v === 'object') return idOf(v.exact) || idOf(v.ideal);
    return null;
  };
  var isOurId = function (id) { return id === 'default' || id === '' || (typeof id === 'string' && id.indexOf('wd-') === 0); };
  var fixTrack = function (c, isVideo) {
    if (!c || typeof c !== 'object') return c;
    var t = Object.assign({}, c);
    var id = t.deviceId !== undefined ? idOf(t.deviceId) : null;
    if (id !== null && isOurId(id)) {
      delete t.deviceId;
      if (isVideo && id === 'wd-camera-back') t.facingMode = { ideal: 'environment' };
      else if (isVideo && id === 'wd-camera-front') t.facingMode = { ideal: 'user' };
    }
    if (Array.isArray(t.advanced)) {
      t.advanced = t.advanced.map(function (a) {
        var b = Object.assign({}, a);
        if (b.deviceId !== undefined && isOurId(idOf(b.deviceId))) delete b.deviceId;
        return b;
      });
    }
    return Object.keys(t).length ? t : true;
  };
  var fixConstraints = function (c) {
    if (!c || typeof c !== 'object') return c;
    var out = Object.assign({}, c);
    if (out.audio) out.audio = fixTrack(out.audio, false);
    if (out.video) out.video = fixTrack(out.video, true);
    return out;
  };

  if (navigator.mediaDevices && typeof MediaDevices !== 'undefined' && MediaDevices.prototype.getUserMedia) {
    var origGUM = MediaDevices.prototype.getUserMedia;
    var origEnum = MediaDevices.prototype.enumerateDevices;
    var track = function (t) {
      if (!t || t.__wdTracked || t.kind !== 'audio') return;
      hidden(t, '__wdTracked', true);
      call('mediaDelta', 1);
      var ended = false;
      var end = function () { if (ended) return; ended = true; call('mediaDelta', -1); };
      try { t.addEventListener('ended', end); } catch (e) {}
      var origStop = t.stop;
      hidden(t, 'stop', function () { end(); return origStop.apply(this, arguments); });
    };
    var reportId = function (t, id, label) {
      if (!t || !id || !t.getSettings) return;
      var orig = t.getSettings;
      hidden(t, 'getSettings', function () { var s = orig.apply(this, arguments) || {}; s.deviceId = id; return s; });
      if (label) { try { Object.defineProperty(t, 'label', { value: label, configurable: true, enumerable: true }); } catch (e) {} }
    };
    hidden(MediaDevices.prototype, 'getUserMedia', function (constraints) {
      var self = this;
      var fixed = fixConstraints(constraints);
      var audioId = constraints && constraints.audio && idOf(constraints.audio.deviceId);
      var videoId = constraints && constraints.video && idOf(constraints.video.deviceId);
      // Teams picked one of our microphones: route Android call audio to that device.
      if (audioId && MIC_IDS[audioId]) call('setMic', MIC_IDS[audioId]);
      return origGUM.call(self, fixed).catch(function (err) {
        // Constraints a phone can't meet (resolution, frame rate, stale ids): retry relaxed.
        var name = err && err.name || '';
        if (!/Overconstrained|NotFound|NotReadable|Constraint/i.test(name) || !fixed) throw err;
        var relaxed = {};
        if (fixed.audio) relaxed.audio = true;
        if (fixed.video) relaxed.video = (fixed.video && fixed.video.facingMode) ? { facingMode: fixed.video.facingMode } : true;
        return origGUM.call(self, relaxed);
      }).then(function (stream) {
        try {
          stream.getAudioTracks().forEach(function (t) {
            track(t);
            var aid = audioId && isOurId(audioId) ? audioId : 'default';
            if (isOurId(audioId || 'default')) reportId(t, aid || 'default', micLabel(aid, mediaInfo()));
          });
          if (videoId && isOurId(videoId)) stream.getVideoTracks().forEach(function (t) { reportId(t, videoId); });
        } catch (e) {}
        return stream;
      });
    });
    if (origEnum) {
      hidden(MediaDevices.prototype, 'enumerateDevices', function () {
        return origEnum.apply(this, arguments).then(augmentDevices, function () { return augmentDevices([]); });
      });
    }
    if (typeof MediaStreamTrack !== 'undefined') {
      var origClone = MediaStreamTrack.prototype.clone;
      hidden(MediaStreamTrack.prototype, 'clone', function () {
        var c = origClone.apply(this, arguments);
        if (this.__wdTracked) track(c);
        return c;
      });
    }
    if (isTop) {
      // Called natively when a Bluetooth/wired headset connects or disconnects.
      hidden(window, '__wdDevicesChanged', function () {
        try { navigator.mediaDevices.dispatchEvent(new Event('devicechange')); } catch (e) {}
      });
    }
  }

  // Speaker selection (setSinkId) -> Android audio route. Always succeeds, so a
  // phone-only "speaker" never makes Teams give up on audio output.
  var patchSink = function (proto) {
    if (!proto) return;
    var orig = proto.setSinkId;
    hidden(proto, 'setSinkId', function (id) {
      var el = this;
      var sid = typeof id === 'string' ? id : '';
      if (ROUTE_IDS[sid]) call('setRoute', ROUTE_IDS[sid]);
      try { Object.defineProperty(el, 'sinkId', { value: sid, configurable: true, enumerable: true }); } catch (e) {}
      if (typeof orig === 'function') {
        try { return Promise.resolve(orig.call(el, '')).then(function () {}, function () {}); } catch (e) {}
      }
      return Promise.resolve();
    });
  };
  try { if (typeof HTMLMediaElement !== 'undefined') patchSink(HTMLMediaElement.prototype); } catch (e) {}
  try { if (typeof AudioContext !== 'undefined') patchSink(AudioContext.prototype); } catch (e) {}

  // Ringtone heuristic: a looping sound with a ring-like name while hidden.
  if (typeof HTMLMediaElement !== 'undefined') {
    var origPlay = HTMLMediaElement.prototype.play;
    hidden(HTMLMediaElement.prototype, 'play', function () {
      try {
        var src = String(this.currentSrc || this.src || '');
        if (this.loop && /ring|incoming|call/i.test(src)) call('callSignal', 'ringing', src.slice(0, 200));
      } catch (e) {}
      return origPlay.apply(this, arguments);
    });
  }

  // Best-effort helpers used by the "Answer"/"Decline" notification actions.
  function findButton(patterns) {
    var nodes = document.querySelectorAll('button,[role="button"]');
    for (var p = 0; p < patterns.length; p++) {
      for (var i = 0; i < nodes.length; i++) {
        var el = nodes[i];
        var label = [el.getAttribute('aria-label'), el.getAttribute('title'), el.getAttribute('data-tid'),
          el.textContent].join(' ');
        if (patterns[p].test(label) && (el.offsetWidth || el.offsetHeight || el.getClientRects().length)) return el;
      }
    }
    return null;
  }
  if (isTop) {
    hidden(window, '__wdCallAction', function (action) {
      var patterns = action === 'decline'
        ? [/\bdecline\b/i, /\breject\b/i]
        : action === 'video'
          ? [/accept.*video|video.*accept/i, /\baccept\b|\banswer\b/i]
          : [/accept.*audio|audio.*accept|answer.*audio/i, /\baccept\b|\banswer\b/i];
      var tries = 0;
      return new Promise(function (resolve) {
        var attempt = function () {
          var b = findButton(patterns);
          if (b) { b.click(); resolve(true); return; }
          if (++tries >= 20) { resolve(false); return; }
          setTimeout(attempt, 250);
        };
        attempt();
      });
    });
    // Teams "open in app?" launcher: choose the web app automatically.
    hidden(window, '__wdUseWebApp', function () {
      var b = findButton([/continue on this browser|use the web app|join on the web|use web app/i]);
      if (b) { b.click(); return true; }
      return false;
    });
    hidden(window, '__wdNotificationClicked', function (id) {
      var n = registry[id];
      if (!n) return false;
      try { window.focus(); } catch (e) {}
      fire(n, 'click');
      return true;
    });
    hidden(window, '__wdPermissionResult', function () {
      var list = pendingPermission.splice(0, pendingPermission.length);
      list.forEach(function (fn) { try { fn(); } catch (e) {} });
    });
  }

  // Incoming call shown on screen (Accept/Decline buttons) while Teams is in the
  // background: Teams doesn't always raise a browser notification for calls, so watch
  // for its call screen and let Android ring. DOM changes are not timer-throttled in
  // hidden pages, so this reacts immediately.
  if (isTop && CFG.site === 'teams' && typeof MutationObserver !== 'undefined') {
    var ringingUi = false;
    var scanQueued = false;
    var ACCEPT = /\b(accept|answer)\b/i;
    var DECLINE = /\b(decline|reject|dismiss)\b/i;
    var CALLISH = /call|audio|video/i;
    var labelOf = function (el) {
      return [el.getAttribute('aria-label'), el.getAttribute('title'), el.getAttribute('data-tid')].join(' ');
    };
    var scanCall = function () {
      scanQueued = false;
      // Only while the user isn't looking at Teams (asked natively: older WebViews
      // don't always report the page as hidden when the app is in the background).
      if (!ringingUi && call('looking') === '1') return;
      var acc = null, dec = null;
      var btns = document.querySelectorAll('button[aria-label],button[title],button[data-tid],[role="button"][aria-label]');
      for (var i = 0; i < btns.length; i++) {
        var l = labelOf(btns[i]);
        if (!acc && ACCEPT.test(l) && CALLISH.test(l)) acc = btns[i];
        else if (!dec && DECLINE.test(l)) dec = btns[i];
      }
      var now = !!(acc && dec);
      if (now === ringingUi) return;
      ringingUi = now;
      var who = '';
      if (now) {
        var box = acc.closest('[role="dialog"],[role="alertdialog"],[role="alert"],[role="region"]') || acc.parentElement;
        who = (box && box.innerText || '').split('\n').map(function (x) { return x.trim(); })
          .filter(function (x) { return x && !ACCEPT.test(x) && !DECLINE.test(x); }).slice(0, 2).join(' - ').slice(0, 120);
      }
      call('callSignal', now ? 'ringing_ui' : 'ringing_end', who);
    };
    var queueScan = function () { if (!scanQueued) { scanQueued = true; Promise.resolve().then(scanCall); } };
    var startCallWatch = function () {
      try { new MutationObserver(queueScan).observe(document.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ['aria-label', 'hidden', 'style'] }); } catch (e) {}
    };
    if (document.documentElement) startCallWatch(); else document.addEventListener('DOMContentLoaded', startCallWatch);
    document.addEventListener('visibilitychange', queueScan);
  }

  // ------------------------------------------------------------------ Outlook unread mail
  // Outlook on the web shows no unread count in its title and only raises browser
  // notifications when they are switched on in Outlook's own settings. So read the
  // Inbox unread number from the folder pane (or, with the pane closed, count the
  // unread messages in the list) and report it; Android then notifies about new mail.
  // DOM changes are not timer-throttled in hidden pages, so this reacts to new mail
  // right away.
  var INBOX_NAME = /^\s*(inbox|posteingang|bo[iî]te de r[eé]ception|bandeja de entrada|caixa de entrada|posta in arrivo|postvak in|inkorgen|indbakke|innboks|saapuneet)/i;
  var UNREAD_N = /(\d{1,6})\s*(unread|ungelesen|non lus?|no le[ií]dos?|n[aã]o lidos?|non lett[ie]|ongelezen|ol[aä]sta|ul[æe]ste)/i;
  function mailFolderUnread() {
    var items = document.querySelectorAll('[role="treeitem"]');
    var best = -1;
    for (var i = 0; i < items.length && i < 800; i++) {
      var it = items[i];
      var fname = String(it.getAttribute('data-folder-name') || '').toLowerCase();
      var title = it.getAttribute('title') || '';
      var label = it.getAttribute('aria-label') || '';
      var text = String(it.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 80);
      if (fname !== 'inbox' && !INBOX_NAME.test(title) && !INBOX_NAME.test(label) && !INBOX_NAME.test(text)) continue;
      var n = 0, m = UNREAD_N.exec(label + ' ' + title + ' ' + text);
      if (m) {
        n = +m[1];
      } else {
        // Visible text is the folder name followed by the number, e.g. "Inbox 5" / "Inbox5".
        var name = (INBOX_NAME.exec(text) || INBOX_NAME.exec(title) || [''])[0];
        var rest = text.slice(name.length);
        if (!name && fname === 'inbox') rest = text.replace(/^[^\d]*/, '');
        m = /^\s*(\d{1,6})(?!\d)/.exec(rest);
        if (m) n = +m[1];
      }
      if (n > best) best = n;
    }
    return best;
  }
  function mailListUnread() {
    var rows = document.querySelectorAll('[role="option"][aria-label],[role="row"][aria-label],[role="listitem"][aria-label]');
    if (!rows.length) return -1;
    var n = 0;
    for (var i = 0; i < rows.length && i < 500; i++) {
      if (/^\s*unread\b|[,;]\s*unread\b/i.test(rows[i].getAttribute('aria-label') || '')) n++;
    }
    return n;
  }
  if (isTop && CFG.site === 'outlook' && /outlook/i.test(location.hostname) && typeof MutationObserver !== 'undefined') {
    var mailReported = null, mailSource = '', mailFirstAt = 0, mailLast = 0, mailTrailing = null;
    var scanMail = function () {
      if (pageBadges) return; // the page reports its own count via the Badging API
      var n = mailFolderUnread(), src = 'folder';
      if (n < 0) { n = mailListUnread(); src = 'list'; }
      if (n < 0) return;
      var now = Date.now();
      // The first readings (and readings after switching between pane and list) are a
      // starting point, not news: Outlook fills in its counts while loading.
      var baseline = mailReported === null || src !== mailSource || now - mailFirstAt < 20000;
      if (mailReported === null || src !== mailSource) mailFirstAt = now;
      if (n === mailReported && src === mailSource) return;
      mailReported = n;
      mailSource = src;
      log('outlook unread ' + n + ' (' + src + (baseline ? ', baseline' : '') + ')');
      call('mailUnread', n, baseline ? '1' : '0');
    };
    var queueMail = function () {
      var now = Date.now();
      if (now - mailLast >= 1500) {
        mailLast = now;
        Promise.resolve().then(scanMail);
      } else if (!mailTrailing) {
        mailTrailing = setTimeout(function () { mailTrailing = null; mailLast = Date.now(); scanMail(); }, 1600 - (now - mailLast));
      }
    };
    var startMailWatch = function () {
      try {
        new MutationObserver(queueMail).observe(document.documentElement, { childList: true, subtree: true,
          characterData: true, attributes: true, attributeFilter: ['aria-label', 'title'] });
      } catch (e) {}
      queueMail();
      setInterval(queueMail, 60000);
    };
    window.addEventListener('load', queueMail);
    if (document.documentElement) startMailWatch(); else document.addEventListener('DOMContentLoaded', startMailWatch);
  }

  // ------------------------------------------------------------------ attach phone files
  // WorkDeck's 📎 button / Share-to-WorkDeck hand phone files to the message box the way
  // dragging files onto it does (both Teams and Outlook attach dropped files). The files
  // are read from Android through the bridge in chunks.
  if (isTop) {
    var lastCompose = null, composeOn = false, composeTimer = null;
    var composeOf = function (el) {
      if (!el || el.nodeType !== 1) return null;
      if (el.tagName === 'TEXTAREA') return el;
      if (el.isContentEditable) return el.closest('[contenteditable]:not([contenteditable="false"])') || el;
      return null;
    };
    var setCompose = function (on) {
      clearTimeout(composeTimer);
      if (on) {
        if (!composeOn) { composeOn = true; call('composeFocus', '1'); }
        return;
      }
      composeTimer = setTimeout(function () { if (composeOn) { composeOn = false; call('composeFocus', '0'); } }, 1500);
    };
    document.addEventListener('focusin', function (e) {
      var c = composeOf(e.target);
      if (c) { lastCompose = c; setCompose(true); } else { setCompose(false); }
    }, true);
    document.addEventListener('focusout', function (e) { if (composeOf(e.target)) setCompose(false); }, true);

    var visible = function (el) {
      if (!el || !el.isConnected) return false;
      var r = el.getBoundingClientRect();
      return r.width > 0 && r.height > 0;
    };
    var findCompose = function () {
      if (lastCompose && visible(lastCompose)) return lastCompose;
      var all = [].slice.call(document.querySelectorAll('[contenteditable="true"],[contenteditable=""],textarea')).filter(visible);
      var named = all.filter(function (el) {
        return /message|reply|compose|body|type|chat|nachricht|mensaje/i.test((el.getAttribute('aria-label') || '') + ' ' + (el.getAttribute('placeholder') || '') + ' ' + (el.getAttribute('data-tid') || ''));
      });
      var pool = named.length ? named : all;
      return pool.length ? pool[pool.length - 1] : null;
    };
    var readAttachment = async function (f) {
      var parts = [], off = 0;
      for (;;) {
        var b64 = call('attachRead', f.id, off);
        if (b64 === null || b64 === undefined) throw new Error('cannot read ' + f.name);
        if (!b64) break;
        var bin = atob(b64), arr = new Uint8Array(bin.length);
        for (var i = 0; i < bin.length; i++) arr[i] = bin.charCodeAt(i);
        parts.push(arr);
        off += arr.length;
        await new Promise(function (r) { setTimeout(r, 0); });
      }
      return new File(parts, f.name, { type: f.type || 'application/octet-stream', lastModified: Date.now() });
    };
    var transfer = function (files) {
      var dt = new DataTransfer();
      files.forEach(function (f) { dt.items.add(f); });
      return dt;
    };
    var fireDrag = function (target, type, dt) {
      var ev;
      try {
        ev = new DragEvent(type, { bubbles: true, cancelable: true, composed: true, dataTransfer: dt });
      } catch (e) {
        ev = new Event(type, { bubbles: true, cancelable: true });
        try { Object.defineProperty(ev, 'dataTransfer', { value: dt }); } catch (x) {}
      }
      target.dispatchEvent(ev);
      return ev.defaultPrevented;
    };
    var tryDrop = function (target, files) {
      var dt = transfer(files);
      fireDrag(target, 'dragenter', dt);
      fireDrag(target, 'dragover', dt);
      return fireDrag(target, 'drop', dt);
    };
    var tryPaste = function (target, files) {
      var dt = transfer(files), ev;
      try { ev = new ClipboardEvent('paste', { bubbles: true, cancelable: true, composed: true, clipboardData: dt }); } catch (e) { return false; }
      if (!ev.clipboardData) { try { Object.defineProperty(ev, 'clipboardData', { value: dt }); } catch (x) { return false; } }
      target.dispatchEvent(ev);
      return ev.defaultPrevented;
    };
    var tryInput = function (files) {
      var inputs = [].slice.call(document.querySelectorAll('input[type="file"]'));
      if (!inputs.length) return false;
      var inp = inputs[inputs.length - 1];
      for (var i = 0; i < inputs.length; i++) if (inputs[i].multiple) inp = inputs[i];
      try { inp.files = transfer(files).files; } catch (e) { return false; }
      inp.dispatchEvent(new Event('input', { bubbles: true }));
      inp.dispatchEvent(new Event('change', { bubbles: true }));
      return true;
    };
    var namesShown = function (files) {
      var text = (document.body && document.body.innerText) || '';
      return files.some(function (f) {
        var base = f.name.replace(/\.[^.]+$/, '');
        return base.length >= 3 && text.indexOf(base.slice(0, 40)) >= 0;
      });
    };
    var waitShown = async function (files, already) {
      if (already) return false;
      for (var t = 0; t < 8; t++) {
        await new Promise(function (r) { setTimeout(r, 400); });
        if (namesShown(files)) return true;
      }
      return false;
    };
    hidden(window, '__wdInsertText', function (text) {
      var target = findCompose();
      if (!target) return false;
      try { target.focus(); } catch (e) {}
      if (target.tagName === 'TEXTAREA') {
        target.setRangeText(String(text), target.selectionStart, target.selectionEnd, 'end');
        target.dispatchEvent(new Event('input', { bubbles: true }));
        return true;
      }
      return document.execCommand('insertText', false, String(text));
    });
    hidden(window, '__wdAttach', function (json) {
      var list;
      try { list = JSON.parse(json) || []; } catch (e) { list = []; }
      (async function () {
        var files = [];
        for (var i = 0; i < list.length; i++) {
          try { files.push(await readAttachment(list[i])); } catch (e) { log('attach: ' + e.message); }
        }
        if (!files.length) { call('attachDone', 'readfailed', 0, '0'); return; }
        var target = findCompose();
        var before = namesShown(files);
        var method = 'none', seen = false;
        if (target) {
          try { target.focus(); } catch (e) {}
          if (tryDrop(target, files)) {
            method = 'drop';
            seen = await waitShown(files, before);
          } else if (tryPaste(target, files)) {
            method = 'paste';
            seen = await waitShown(files, before);
          }
        }
        if (method === 'none' && tryInput(files)) {
          method = 'input';
          seen = await waitShown(files, before);
        }
        call('attachDone', method, files.length, seen ? '1' : '0');
      })();
      return true;
    });
  }

  // Pages that use Chrome's file-open dialog API (missing in Android WebView) get the
  // classic file chooser instead, which Android serves with its own file picker.
  if (typeof window.showOpenFilePicker !== 'function') {
    hidden(window, 'showOpenFilePicker', function (opts) {
      opts = opts || {};
      return new Promise(function (resolve, reject) {
        var input = document.createElement('input');
        input.type = 'file';
        input.multiple = !!opts.multiple;
        input.style.display = 'none';
        var accept = [];
        (opts.types || []).forEach(function (t) {
          var a = (t && t.accept) || {};
          Object.keys(a).forEach(function (m) { accept.push(m); [].concat(a[m] || []).forEach(function (ext) { accept.push(ext); }); });
        });
        if (accept.length && !opts.excludeAcceptAllOption) input.accept = '';
        else if (accept.length) input.accept = accept.join(',');
        var done = false;
        var finish = function () {
          if (done) return;
          done = true;
          var files = [].slice.call(input.files || []);
          input.remove();
          if (!files.length) { reject(new DOMException('The user aborted a request.', 'AbortError')); return; }
          resolve(files.map(function (f) {
            return { kind: 'file', name: f.name, getFile: function () { return Promise.resolve(f); },
              isSameEntry: function () { return Promise.resolve(false); },
              queryPermission: function () { return Promise.resolve('granted'); },
              requestPermission: function () { return Promise.resolve('granted'); } };
          }));
        };
        input.addEventListener('change', finish);
        input.addEventListener('cancel', finish);
        (document.body || document.documentElement).appendChild(input);
        input.click();
      });
    });
  }

  // Best-effort "who wrote" for unread-count notifications: the first unread item in the
  // chat / mail list, read from its accessibility label.
  if (isTop) {
    hidden(window, '__wdUnreadPreview', function () {
      try {
        if (CFG.site === 'outlook') {
          // Newest unread mail: first unread row of the message list (sender, subject, preview).
          var rows = document.querySelectorAll('[role="option"][aria-label],[role="row"][aria-label],[role="listitem"][aria-label]');
          for (var r = 0; r < rows.length && r < 300; r++) {
            if (!/^\s*unread\b|[,;]\s*unread\b/i.test(rows[r].getAttribute('aria-label') || '')) continue;
            var lines = String(rows[r].innerText || rows[r].textContent || '').split('\n')
              .map(function (x) { return x.trim(); })
              .filter(function (x) { return x && x.length > 1 && !/^(unread|\d{1,2}:\d{2}( ?[ap]m)?)$/i.test(x); });
            if (lines.length) return { title: lines[0].slice(0, 80), body: lines.slice(1, 3).join(' - ').slice(0, 240) };
          }
        }
        var noise = /^(unread|unread message|new|has unread messages?|\d+ unread( messages?)?|chat|message|selected|pinned|muted)$/i;
        var cands = document.querySelectorAll('[aria-label*="nread" i]');
        for (var i = 0; i < cands.length && i < 300; i++) {
          var el = cands[i];
          var lab = String(el.getAttribute('aria-label') || '').replace(/\s+/g, ' ').trim();
          if (lab.length < 8 || lab.length > 800) continue;
          var role = el.getAttribute('role') || '';
          if (!/treeitem|option|listitem|row|link|button|gridcell/.test(role) &&
              !(el.closest && el.closest('[role="tree"],[role="listbox"],[role="list"],[role="grid"],[role="treegrid"]'))) continue;
          var parts = lab.split(/,|;| - |·/).map(function (x) { return x.trim(); })
            .filter(function (x) { return x && !noise.test(x); });
          if (!parts.length) continue;
          return { title: parts[0].slice(0, 80), body: parts.slice(1, 4).join(' - ').slice(0, 240) };
        }
      } catch (e) {}
      return null;
    });
  }

  // A company sign-in policy (Conditional Access) refused this browser, e.g. "You can't get
  // there from here - You must use Microsoft Edge to access this resource". Many companies
  // only refuse phone browsers; native then switches this tab to a computer identity.
  if (isTop) {
    var blockedSent = false;
    var checkBlocked = function () {
      if (blockedSent) return;
      try {
        var conf = window.$Config || {};
        var code = String(conf.iErrorCode || conf.strServiceExceptionMessage || '');
        var onLogin = /(^|\.)login\./i.test(location.hostname) || !!window.$Config;
        if (!onLogin) return;
        var t = (document.body && document.body.innerText || '').slice(0, 4000);
        var edge = /must use (the )?Microsoft Edge|Microsoft Edge (to access|is required)|Launch in Edge/i.test(t);
        var blocked = edge || /can.t get there from here|AADSTS53\d{3}/i.test(t) || /^53\d{3}\b|AADSTS53\d{3}/.test(code);
        if (blocked) {
          blockedSent = true;
          call('callSignal', 'access_blocked', edge ? 'edge-required' : 'policy');
        }
      } catch (e) {}
    };
    document.addEventListener('DOMContentLoaded', function () { setTimeout(checkBlocked, 300); });
    window.addEventListener('load', function () { setTimeout(checkBlocked, 800); setTimeout(checkBlocked, 3000); });
  }

  // Microsoft says the browser is too old -> ask native to suggest a WebView update.
  if (isTop) {
    var checkUnsupported = function () {
      try {
        var t = (document.body && document.body.innerText || '').slice(0, 4000);
        if (/browser (version )?(isn't|is not|isn\u2019t) supported|unsupported browser|browser is no longer supported/i.test(t)) {
          call('callSignal', 'unsupported_browser', location.host);
        }
      } catch (e) {}
    };
    window.addEventListener('load', function () { setTimeout(checkUnsupported, 4000); setTimeout(checkUnsupported, 15000); });
  }

  log('installed in ' + location.origin + (isTop ? ' (top)' : ' (frame)'));
})();
