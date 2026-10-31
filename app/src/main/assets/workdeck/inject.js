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
      var go = function (iconUrl) {
        if (permission() !== 'granted') { delete registry[id]; fire(self, 'error'); return; }
        var payload = {
          id: id, title: self.title, body: self.body, tag: self.tag, icon: iconUrl || '',
          requireInteraction: self.requireInteraction, silent: self.silent, renotify: self.renotify,
          origin: location.origin, sw: self.__wdSw
        };
        call('notify', JSON.stringify(payload));
        fire(self, 'show');
      };
      if (icon.indexOf('blob:') === 0) {
        blobToDataUrl(icon, 262144).then(go, function () { go(''); });
      } else {
        setTimeout(function () { go(icon.indexOf('data:') === 0 && icon.length > 350000 ? '' : icon); }, 0);
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

    if (navigator.permissions && navigator.permissions.query) {
      var origQuery = navigator.permissions.query;
      hidden(navigator.permissions, 'query', function (desc) {
        if (desc && desc.name === 'notifications') {
          var p = permission();
          var st;
          try { st = Object.create(PermissionStatus.prototype); } catch (e) { st = {}; }
          own(st, 'state', p === 'default' ? 'prompt' : p);
          own(st, 'name', 'notifications');
          hidden(st, 'onchange', null);
          hidden(st, 'addEventListener', function () {});
          hidden(st, 'removeEventListener', function () {});
          hidden(st, 'dispatchEvent', function () { return true; });
          return Promise.resolve(st);
        }
        return origQuery.apply(this, arguments);
      });
    }
  }

  // Badging API -> unread counter in the tab bar and status notification.
  hidden(Navigator.prototype, 'setAppBadge', function (n) {
    var v = (n === undefined) ? -1 : Math.max(0, Math.floor(Number(n) || 0));
    if (isTop) call('badge', v);
    return Promise.resolve();
  });
  hidden(Navigator.prototype, 'clearAppBadge', function () {
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

  // ------------------------------------------------------------------ calls
  if (navigator.mediaDevices && typeof MediaDevices !== 'undefined' && MediaDevices.prototype.getUserMedia) {
    var origGUM = MediaDevices.prototype.getUserMedia;
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
    hidden(MediaDevices.prototype, 'getUserMedia', function () {
      return origGUM.apply(this, arguments).then(function (stream) {
        try { stream.getAudioTracks().forEach(track); } catch (e) {}
        return stream;
      });
    });
    if (typeof MediaStreamTrack !== 'undefined') {
      var origClone = MediaStreamTrack.prototype.clone;
      hidden(MediaStreamTrack.prototype, 'clone', function () {
        var c = origClone.apply(this, arguments);
        if (this.__wdTracked) track(c);
        return c;
      });
    }
  }

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

  log('installed in ' + location.origin + (isTop ? ' (top)' : ' (frame)'));
})();
