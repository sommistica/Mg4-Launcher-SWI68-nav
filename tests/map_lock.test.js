// EN: Exercise lock transitions and GPS updates against the actual map controller.
// IT: Verifica transizioni del blocco e aggiornamenti GPS sul controller reale della mappa.
const vm = require('vm');
const fs = require('fs');
const assert = require('assert');
let map;
const lockStates = [];
function handler() {
    return { enabled: true, enable() { this.enabled = true; }, disable() { this.enabled = false; },
        disableRotation() { this.rotation = false; } };
}
const context = {
    window: { addEventListener() {} }, document: { createElement() { return { style: {} }; } },
    setTimeout() { return 1; }, clearTimeout() {},
    OfflineMap: { ready() {}, visible() {}, locked(value) { lockStates.push(value); },
        unavailable(code) { throw Error('Map failed: ' + code); } },
    maplibregl: {
        setWorkerUrl() {}, setWorkerCount() {},
        Map: function (options) {
            map = this; this.options = options; this.zoom = options.zoom; this.camera = null;
            ['dragPan', 'scrollZoom', 'boxZoom', 'doubleClickZoom', 'touchZoomRotate'].forEach(k => this[k] = handler());
            this.stop = () => { this.stopped = true; };
            this.setZoom = value => { this.zoom = value; };
            this.getMaxZoom = () => options.maxZoom;
            this.jumpTo = value => { this.camera = value; };
            this.on = () => {};
            this.getCanvas = () => ({ addEventListener() {} });
            this.isStyleLoaded = this.isSourceLoaded = this.areTilesLoaded = () => true;
            this.setStyle = () => {};
        },
        Marker: function ({ element }) {
            this.getElement = () => element;
            this.setLngLat = value => { this.position = value; return this; };
            this.setRotation = this.addTo = () => this;
        }
    }
};
vm.runInNewContext(fs.readFileSync('app/assets/offline-map/map.js', 'utf8'), context);
const api = context.window;
const fix = { id: 'test', revision: 1, lat: 10, lon: 20, bearing: 45, day: true };
function gestures(enabled) {
    ['dragPan', 'scrollZoom', 'boxZoom', 'doubleClickZoom', 'touchZoomRotate'].forEach(k => assert.equal(map[k].enabled, enabled, k));
    assert.equal(map.touchZoomRotate.rotation, false);
}
api.updatePosition(fix);
gestures(false);
assert.equal(lockStates.at(-1), true);
assert.equal(map.zoom, 17);
assert.equal(map.options.keyboard, false);
assert.equal(map.options.dragRotate, false);
api.toggleMapLock();
gestures(true);
assert.equal(lockStates.at(-1), false);
// EN: A panned, zoomed-out view stays still as fixes and map packages change.
// IT: Una vista spostata e ridotta resta ferma quando cambiano posizione e pacchetto cartografico.
const explored = { center: [21, 11], bearing: 45 };
map.camera = explored; map.zoom = 11;
api.updatePosition({ ...fix, lon: 22, bearing: 90 });
assert.strictEqual(map.camera, explored);
api.updatePosition({ ...fix, id: 'next', lon: 23, bearing: 100 });
assert.strictEqual(map.camera, explored);
assert.equal(map.zoom, 11);
gestures(true);
api.recenterMap();
gestures(false);
assert.equal(lockStates.at(-1), true);
assert.equal(map.zoom, 17);
assert.equal(JSON.stringify(map.camera), JSON.stringify({ center: [23, 10], bearing: 100 }));
api.updatePosition({ ...fix, id: 'next', lon: 24 });
assert.equal(map.camera.center[0], 24);
api.toggleMapLock();
map.zoom = 12; map.camera = explored;
api.toggleMapLock();
gestures(false);
assert.equal(map.zoom, 17);
assert.equal(map.camera.center[0], 24);
assert.equal(lockStates.at(-1), true);
console.log('Map lock, exploration, GPS following and recenter checks passed');
