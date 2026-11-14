/* Local MBTiles only. No telemetry, remote fonts, geolocation API or route inference. */
(function () {
    'use strict';
    var map, marker, position, key = '', following = true, timer, unhealthy = false;
    function unavailable(code) { unhealthy = true; OfflineMap.unavailable(typeof code === 'number' ? code : 3); }
    function style(p) {
        var day = p.day;
        var road = day ? '#aeb7c0' : '#555e6a', major = day ? '#7c8794' : '#85909e';
        return {
            version: 8, glyphs: 'https://offline.mg4.invalid/fonts/{fontstack}/{range}.pbf',
            sources: { local: { type:'vector', tiles:['https://offline.mg4.invalid/tiles/' + p.id + '/{z}/{x}/{y}?revision=' + p.revision], minzoom:p.minZoom, maxzoom:p.maxZoom } },
            layers: [
                { id:'background', type:'background', paint:{'background-color':day?'#eef0f2':'#1a1d22'} },
                { id:'water', type:'fill', source:'local', 'source-layer':'water_polygons', paint:{'fill-color':day?'#b6d5e5':'#20364e'} },
                { id:'waterways', type:'line', source:'local', 'source-layer':'water_lines', paint:{'line-color':day?'#b6d5e5':'#20364e','line-width':2} },
                { id:'roads', type:'line', source:'local', 'source-layer':'streets', layout:{'line-cap':'round','line-join':'round'}, paint:{'line-color':road,'line-width':['interpolate',['linear'],['zoom'],10,1,14,3,17,8]} },
                { id:'major', type:'line', source:'local', 'source-layer':'streets', filter:['in','kind','motorway','trunk','primary'], layout:{'line-cap':'round','line-join':'round'}, paint:{'line-color':major,'line-width':['interpolate',['linear'],['zoom'],10,2,14,5,17,12]} },
                { id:'names', type:'symbol', source:'local', 'source-layer':'street_labels', minzoom:12,
                    layout:{'text-field':['coalesce',['get','name'],['get','ref'],''],'text-font':['Noto Sans Regular'],'text-size':12,'text-max-width':12},
                    paint:{'text-color':day?'#343b43':'#c6cbd2','text-halo-color':day?'#eef0f2':'#1a1d22','text-halo-width':1.5} }
            ]
        };
    }
    function move() {
        if (!map || !position) return;
        marker.getElement().style.borderBottomColor = position.day ? '#111318' : '#f2f3f5';
        marker.getElement().style.filter = position.day ? 'drop-shadow(0 0 2px white)' : 'drop-shadow(0 0 2px black)';
        marker.setLngLat([position.lon,position.lat]).setRotation(position.bearing);
        if (following) map.jumpTo({center:[position.lon,position.lat]});
    }
    window.recenterMap = function () { following = true; move(); };
    window.updatePosition = function (p) {
        position = p;
        try {
            var nextKey = p.id + ':' + p.revision;
            if (!map) {
                maplibregl.setWorkerUrl('https://offline.mg4.invalid/worker.js');
                maplibregl.setWorkerCount(1);
                map = new maplibregl.Map({container:'map',style:style(p),center:[p.lon,p.lat],zoom:14, minZoom:10,maxZoom:17,
                    maxTileCacheSize:32, pixelRatio:1, fadeDuration:0, antialias:false,
                    refreshExpiredTiles:false, collectResourceTiming:false,
                    attributionControl:false, pitchWithRotate:false, dragRotate:false, touchPitch:false, renderWorldCopies:false});
                var el = document.createElement('div'); el.className='position';
                marker = new maplibregl.Marker({element:el,rotationAlignment:'map'}).setLngLat([p.lon,p.lat]).addTo(map);
                map.on('dragstart',function () { following=false; });
                map.on('error',function (event) { unavailable(event.sourceId === 'local' ? 2 : 3); });
                map.getCanvas().addEventListener('webglcontextlost',function () { unavailable(1); });
                map.on('idle',function () {
                    if (!unhealthy && map.isStyleLoaded() && map.isSourceLoaded('local')) { clearTimeout(timer); OfflineMap.visible(key); }
                });
                key = nextKey;
                timer = setTimeout(function () { unavailable(4); },15000);
            } else if (key !== nextKey) {
                key = nextKey; map.setStyle(style(p)); following = true;
                clearTimeout(timer); timer=setTimeout(function () { unavailable(4); },15000);
            }
            move();
            if (!unhealthy && map.isStyleLoaded() && map.isSourceLoaded('local') && map.areTilesLoaded()) OfflineMap.visible(key);
        } catch (e) { unavailable(); }
    };
    window.addEventListener('error', function (event) {
        var name = event.error && event.error.name;
        unavailable(name === 'ReferenceError' ? 5 : name === 'TypeError' ? 6 : 3);
    });
    if (typeof maplibregl === 'undefined' || typeof maplibregl.Map !== 'function') unavailable(7);
    else OfflineMap.ready();
}());
