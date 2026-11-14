/* Small ES2019 API shims for the MG head unit's Chromium/WebView 66.
 * Loaded before MapLibre in both the page and its dedicated worker. */
(function () {
    'use strict';
    var root = typeof self !== 'undefined' ? self : window;
    if (typeof root.globalThis === 'undefined') root.globalThis = root;
    // Older engines accept a transfer array, not the newer {transfer: ...} options.
    function transferList(value) {
        return value && !Array.isArray(value) && typeof value === 'object' ? value.transfer || [] : value || [];
    }
    function patchPort(prototype) {
        if (!prototype || !prototype.postMessage) return;
        var original = prototype.postMessage;
        prototype.postMessage = function (message, options) { return original.call(this, message, transferList(options)); };
    }
    if (typeof Worker !== 'undefined') patchPort(Worker.prototype);
    if (typeof MessagePort !== 'undefined') patchPort(MessagePort.prototype);
    if (typeof document === 'undefined' && typeof root.postMessage === 'function') {
        var send = root.postMessage;
        root.postMessage = function (message, options) { return send.call(root, message, transferList(options)); };
    }
    if (!Array.prototype.flat) Object.defineProperty(Array.prototype, 'flat', {
        configurable:true, writable:true,
        value:function (depth) {
            var out = [], limit = depth === undefined ? 1 : Math.max(0, Math.floor(Number(depth) || 0));
            function append(input, remaining) {
                for (var i = 0; i < input.length; i++) if (i in input) {
                    var value = input[i];
                    if (remaining > 0 && Array.isArray(value)) append(value, remaining - 1);
                    else out.push(value);
                }
            }
            append(Object(this), limit); return out;
        }
    });
    if (!Array.prototype.flatMap) Object.defineProperty(Array.prototype, 'flatMap', {
        configurable:true, writable:true,
        value:function (callback, thisArg) { return Array.prototype.map.call(this, callback, thisArg).flat(); }
    });
}());
