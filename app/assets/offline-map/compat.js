/* EN: Supply missing APIs for Chromium/WebView 66 before MapLibre runs in the page and worker.
 * IT: Fornisci le API mancanti di Chromium/WebView 66 prima di MapLibre nella pagina e nel worker. */
(function () {
    'use strict';
    var root = typeof self !== 'undefined' ? self : window;
    if (typeof root.globalThis === 'undefined') root.globalThis = root;
    // EN: Older engines need a transfer array instead of the newer {transfer: ...} options object.
    // IT: I motori meno recenti richiedono un array di trasferimento invece del nuovo oggetto {transfer: ...}.
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
