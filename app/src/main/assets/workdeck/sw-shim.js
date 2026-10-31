/* WorkDeck service-worker shim (prepended to Microsoft service-worker scripts).
 * Android WebView cannot display notifications from a service worker, so
 * showNotification() is forwarded to the open page, where WorkDeck's page
 * script turns it into a native Android notification. */
(function () {
  try {
    if (typeof ServiceWorkerRegistration === 'undefined') return;
    var P = ServiceWorkerRegistration.prototype;
    var abs = function (u) {
      if (!u) return '';
      try { return new URL(String(u), self.location.href).href; } catch (e) { return ''; }
    };
    var forward = function (title, options) {
      var o = options || {};
      var payload = {
        title: String(title),
        body: o.body == null ? '' : String(o.body),
        tag: o.tag == null ? '' : String(o.tag),
        icon: abs(o.icon),
        requireInteraction: !!o.requireInteraction,
        silent: o.silent === true
      };
      try { payload.data = JSON.parse(JSON.stringify(o.data === undefined ? null : o.data)); } catch (e) { payload.data = null; }
      return self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then(function (list) {
        list.forEach(function (c) { try { c.postMessage({ __wdNotify: payload }); } catch (e) {} });
      });
    };
    Object.defineProperty(P, 'showNotification', { value: forward, configurable: true, writable: true });
    Object.defineProperty(P, 'getNotifications', { value: function () { return Promise.resolve([]); }, configurable: true, writable: true });
    if (typeof self.Notification === 'undefined') {
      Object.defineProperty(self, 'Notification', { value: { permission: 'granted' }, configurable: true, writable: true });
    } else {
      try { Object.defineProperty(self.Notification, 'permission', { get: function () { return 'granted'; }, configurable: true }); } catch (e) {}
    }
  } catch (e) {}
})();
