package com.mg4.launcher.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.*;
import android.view.Gravity;
import android.view.View;
import android.webkit.*;
import android.widget.FrameLayout;
import android.widget.TextView;
import com.mg4.launcher.L;
import com.mg4.launcher.R;
import com.mg4.launcher.svc.OfflineMaps;
import com.mg4.launcher.svc.MapDiagnostics;
import com.mg4.launcher.svc.MapLocationPolicy;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * EN: Show local map tiles behind navigation cards; use a decorative fallback when the map is unavailable.
 * Location stays in memory and is never exposed to a remote WebView origin.
 * IT: Mostra tile locali dietro le schede di navigazione; usa uno sfondo decorativo se la mappa non e disponibile.
 * La posizione resta in memoria e non viene mai esposta a un'origine WebView remota.
 */
public final class OfflineMapView extends FrameLayout {
    private enum Fallback { DISABLED, STORAGE, NO_MAPS, PAUSED, PERMISSION, NO_GPS, GPS_OFF, WAITING_GPS, STALE_GPS, NO_COVERAGE, LOADING, RENDERER }
    private Fallback fallback = Fallback.DISABLED;
    private final Bars.MapDecor decor;
    private static final String HOST = "offline.mg4.invalid";
    private final Theme theme;
    private final Prefs prefs;
    private final OfflineMaps maps;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final LocationManager locations;
    private final TextView badge, attribution, recenter;
    private WebView web;
    private Location fix;
    private boolean foreground = true, tracking, loaded, shown, failed, detached, releaseScheduled, mapReady;
    private int request, rendererId, failureCount;
    private long lastRevision = -1, nextMemorySample, retryAt;
    private String source = "", mapName = "";
    private Runnable displayChanged;
    private final OfflineMaps.Listener mapListener = this::refresh;

    public OfflineMapView(Context c, Theme t, Prefs p) {
        super(c); theme = t; prefs = p; maps = OfflineMaps.get(c);
        locations = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
        MapDiagnostics.renderer(MapDiagnostics.Renderer.NOT_CREATED);
        fallback = p.offlineMaps() ? Fallback.LOADING : Fallback.DISABLED;
        decor = new Bars.MapDecor(c);
        decor.setColors(t.map, t.road, t.roadMinor);
        addView(decor, new LayoutParams(-1, -1));
        badge = Ui.text(c, "", 14, t.text, Ui.MEDIUM);
        badge.setPadding(dp(12), dp(8), dp(12), dp(8)); badge.setBackground(Ui.round(t.overlay, dp(12)));
        LayoutParams bl = new LayoutParams(-2, -2, Gravity.TOP | Gravity.START); bl.setMargins(dp(20), dp(20), 0, 0);
        addView(badge, bl); badge.setVisibility(GONE);
        attribution = Ui.text(c, "© OpenStreetMap contributors · Librescoot · MapLibre", 11, t.sub, Ui.REGULAR);
        attribution.setBackground(Ui.round(t.overlay, dp(8))); attribution.setPadding(dp(6), dp(4), dp(6), dp(4));
        LayoutParams al = new LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END); al.setMargins(0, 0, dp(12), dp(8));
        addView(attribution, al); attribution.setVisibility(GONE);
        attribution.setContentDescription(L.s(R.string.maps_licences));
        attribution.setOnClickListener(v -> {
            View content = getRootView().findViewById(android.R.id.content);
            showLicences(content instanceof FrameLayout ? (FrameLayout) content : this, theme);
        });
        // EN: Use a translated text label and content-sized width so Recenter cannot be mistaken for zoom.
        // IT: Usa un'etichetta tradotta e larghezza adattata al testo per non confondere Ricentra con lo zoom.
        recenter = Ui.text(c, L.s(R.string.maps_recenter), 18, t.text, Ui.MEDIUM); recenter.setGravity(Gravity.CENTER);
        recenter.setSingleLine(true);
        recenter.setPadding(dp(20), 0, dp(20), 0);
        recenter.setContentDescription(L.s(R.string.maps_recenter)); recenter.setBackground(Ui.round(t.overlay, dp(26)));
        LayoutParams rl = new LayoutParams(-2, dp(52), Gravity.BOTTOM | Gravity.END); rl.setMargins(0, 0, dp(20), dp(40));
        addView(recenter, rl); recenter.setVisibility(GONE);
        recenter.setOnClickListener(v -> { if (web != null) web.evaluateJavascript("window.recenterMap()", null); });
    }
    public static void showLicences(FrameLayout parent, Theme theme) {
        Confirm.notice(parent, theme, L.s(R.string.maps_licences), "",
                new String[]{"© OpenStreetMap contributors · ODbL 1.0",
                        "https://www.openstreetmap.org/copyright",
                        "https://opendatacommons.org/licenses/odbl/1-0/",
                        "Map packages: Librescoot / OpenStreetMap",
                        "MapLibre GL JS: BSD 3-Clause · Noto Sans: SIL OFL 1.1",
                        "Full renderer and font licences are bundled with the app."}, L.s(R.string.btn_ok), null);
    }
    private int dp(int n) { return Ui.dp(getContext(), n); }
    public void setDisplayListener(Runnable l) { displayChanged = l; }
    public boolean showingMap() { return shown; }
    public String fallbackText() {
        int resource;
        switch (fallback) {
            case STORAGE: resource = R.string.maps_reason_storage; break;
            case NO_MAPS: resource = R.string.maps_reason_no_maps; break;
            case PAUSED: resource = R.string.maps_reason_paused; break;
            case PERMISSION: resource = R.string.maps_reason_permission; break;
            case NO_GPS: resource = R.string.maps_reason_no_gps; break;
            case GPS_OFF: resource = R.string.maps_reason_gps_off; break;
            case WAITING_GPS: resource = R.string.maps_reason_waiting; break;
            case STALE_GPS: resource = R.string.maps_reason_stale; break;
            case NO_COVERAGE: resource = R.string.maps_reason_coverage; break;
            case LOADING: resource = R.string.maps_reason_loading; break;
            case RENDERER: resource = R.string.maps_reason_renderer; break;
            default: resource = R.string.maps_reason_disabled;
        }
        return L.s(resource);
    }
    private void fallback(Fallback reason) {
        if (fallback == reason) return;
        fallback = reason;
        if (displayChanged != null) displayChanged.run();
    }
    private Fallback locationFallback() {
        boolean permitted = getContext().checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED
                || getContext().checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        if (!permitted) return Fallback.PERMISSION;
        if (locations == null) return Fallback.NO_GPS;
        try {
            java.util.List<String> all = locations.getAllProviders();
            if (!all.contains(LocationManager.GPS_PROVIDER) && !all.contains(LocationManager.NETWORK_PROVIDER)) return Fallback.NO_GPS;
            java.util.List<String> enabled = locations.getProviders(true);
            if (!enabled.contains(LocationManager.GPS_PROVIDER) && !enabled.contains(LocationManager.NETWORK_PROVIDER)) return Fallback.GPS_OFF;
        } catch (Exception ignored) { return Fallback.NO_GPS; }
        return fix == null ? Fallback.WAITING_GPS : !fresh(fix) ? Fallback.STALE_GPS : null;
    }
    public void setForeground(boolean value) { foreground = value; refresh(); }

    // EN: JavaScript is needed for MapLibre, but all resources are served locally and remote loads are blocked.
    // IT: JavaScript serve a MapLibre, ma tutte le risorse sono locali e i caricamenti remoti sono bloccati.
    @SuppressLint("SetJavaScriptEnabled")
    private void createWeb() {
        if (web != null || failed || detached) return;
        try {
            fallback(Fallback.LOADING);
            final int renderer = ++rendererId;
            web = new WebView(getContext()); web.setAlpha(0f); web.setVisibility(VISIBLE);
            MapDiagnostics.renderer(MapDiagnostics.Renderer.LOADING);
            android.content.pm.PackageInfo webPackage = WebView.getCurrentWebViewPackage();
            MapDiagnostics.version(webPackage == null ? null : webPackage.versionName);
            web.setBackgroundColor(theme.map);
            WebSettings s = web.getSettings(); s.setJavaScriptEnabled(true);
            s.setAllowFileAccess(false); s.setAllowContentAccess(false);
            s.setAllowFileAccessFromFileURLs(false); s.setAllowUniversalAccessFromFileURLs(false);
            s.setGeolocationEnabled(false); s.setDomStorageEnabled(false);
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW); s.setBlockNetworkLoads(true);
            s.setSaveFormData(false); s.setMediaPlaybackRequiresUserGesture(true);
            web.addJavascriptInterface(new Object() {
                @JavascriptInterface public void ready() { main.post(() -> { if (renderer != rendererId) return; loaded = true; MapDiagnostics.renderer(MapDiagnostics.Renderer.READY); update(); }); }
                @JavascriptInterface public void visible(String key) { main.post(() -> { if (renderer == rendererId && !failed && eligible() && fresh(fix) && !source.isEmpty() && source.equals(key)) showPosition(true); }); }
                @JavascriptInterface public void unavailable(int reason) { main.post(() -> { if (renderer != rendererId) return;
                    MapDiagnostics.scriptFailure(reason); rendererFailed(); }); }
            }, "OfflineMap");
            web.setWebChromeClient(new WebChromeClient() {
                @Override public boolean onConsoleMessage(ConsoleMessage m) { return true; } // no coordinates in logcat
            });
            web.setWebViewClient(new WebViewClient() {
                @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return true; }
                @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) { return resource(r.getUrl()); }
                @Override public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) {
                    if (v == web) { MapDiagnostics.failure(MapDiagnostics.Failure.PROCESS_EXIT); MapDiagnostics.renderer(MapDiagnostics.Renderer.FAILED); rendererFailed(); rendererId++; loaded = false; web = null; }
                    removeView(v); v.destroy(); return true;
                }
                @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                    if (v == web && r.isForMainFrame()) { MapDiagnostics.failure(MapDiagnostics.Failure.MAIN_DOCUMENT); MapDiagnostics.renderer(MapDiagnostics.Renderer.FAILED); rendererFailed(); }
                }
            });
            addView(web, 1, new LayoutParams(-1, -1));
            web.loadUrl("https://" + HOST + "/index.html");
        } catch (Exception e) { MapDiagnostics.failure(MapDiagnostics.Failure.CREATE); MapDiagnostics.renderer(MapDiagnostics.Renderer.FAILED); rendererFailed(); }
    }
    // EN: The synthetic HTTPS host serves bundled files and SQLite tiles; it is never contacted over the network.
    // IT: L'host HTTPS fittizio fornisce file inclusi e tile SQLite; non viene mai contattato tramite rete.
    private WebResourceResponse resource(Uri u) {
        try {
            if (!"https".equals(u.getScheme()) || !HOST.equals(u.getHost())) return response(null, "text/plain");
            String path = u.getPath();
            if ("/favicon.ico".equals(path)) return response(new byte[0], "image/x-icon");
            if (path == null || path.contains("..")) return response(null, "text/plain");
            if (path.startsWith("/tiles/")) {
                String[] p = path.split("/");
                if (p.length != 6) return response(null, "text/plain");
                return response(maps.tile(p[2], Integer.parseInt(p[3]), Integer.parseInt(p[4]), Integer.parseInt(p[5])), "application/x-protobuf");
            }
            String mime = path.endsWith(".js") ? "application/javascript" : path.endsWith(".css") ? "text/css"
                    : path.endsWith(".pbf") ? "application/x-protobuf" : "text/html";
            try (java.io.InputStream in = getContext().getAssets().open("offline-map" + path)) {
                return response(OfflineMaps.read(in, 2_000_000), mime);
            }
        } catch (Exception e) {
            String path = u.getPath();
            MapDiagnostics.failure(path != null && path.startsWith("/fonts/") ? MapDiagnostics.Failure.GLYPHS
                    : path != null && path.contains("worker") ? MapDiagnostics.Failure.WORKER
                    : "/maplibre-gl.js".equals(path) || "/compat.js".equals(path) ? MapDiagnostics.Failure.LIBRARY : MapDiagnostics.Failure.RESOURCE);
            return response(null, "text/plain");
        }
    }
    private WebResourceResponse response(byte[] bytes, String mime) {
        boolean ok = bytes != null;
        return new WebResourceResponse(mime, "UTF-8", ok ? 200 : 404, ok ? "OK" : "Not Found",
                Collections.singletonMap("Cache-Control", "no-store"), new ByteArrayInputStream(ok ? bytes : new byte[0]));
    }
    private void show(boolean value) {
        boolean changed = shown != value; shown = value;
        if (value) mapReady = true;
        else MapDiagnostics.position(MapDiagnostics.Position.NONE);
        decor.setVisibility(value ? GONE : VISIBLE);
        if (value) { failureCount = 0; retryAt = 0; MapDiagnostics.retry(0); MapDiagnostics.renderer(MapDiagnostics.Renderer.DISPLAYING); }
        else if (changed && !failed) MapDiagnostics.renderer(MapDiagnostics.Renderer.HIDDEN);
        if (web != null) {
            // Allow requestAnimationFrame/WebGL to initialize behind the fallback.
            web.setAlpha(value ? 1f : 0f);
            web.setVisibility(value || eligible() ? VISIBLE : INVISIBLE);
        }
        badge.setVisibility(value ? VISIBLE : GONE); attribution.setVisibility(value ? VISIBLE : GONE); recenter.setVisibility(value ? VISIBLE : GONE);
        if (changed && displayChanged != null) displayChanged.run();
    }
    private boolean eligible() { return !detached && isAttachedToWindow() && getWindowVisibility() == VISIBLE && foreground && isShown() && prefs.offlineMaps(); }
    private long fixAgeMs(Location location) {
        if (location == null) return -1;
        long delta = SystemClock.elapsedRealtimeNanos() - location.getElapsedRealtimeNanos();
        return delta < 0 ? -1 : delta / 1_000_000L;
    }
    private boolean fresh(Location l) {
        return l != null && Double.isFinite(l.getLatitude()) && Double.isFinite(l.getLongitude())
                && Math.abs(l.getLatitude()) <= 85.051129 && Math.abs(l.getLongitude()) <= 180
                && MapLocationPolicy.fresh(fixAgeMs(l), l.hasAccuracy(), l.getAccuracy());
    }
    private void positionState(boolean live) {
        badge.setText(L.s(R.string.maps_offline_badge) + " · " + mapName
                + (live ? "" : " · " + L.s(R.string.maps_position_last_known)));
        MapDiagnostics.position(live ? MapDiagnostics.Position.LIVE : MapDiagnostics.Position.LAST_KNOWN);
        if (web != null && loaded) web.evaluateJavascript("window.setPositionFresh(" + live + ")", null);
    }
    private void showPosition(boolean live) { positionState(live); show(true); }
    // EN: Coordinate storage, visibility and GPS; delay renderer disposal so brief interruptions recover quickly.
    // IT: Coordina archivio, visibilita e GPS; ritarda la chiusura del renderer per recuperare da brevi interruzioni.
    public void refresh() {
        if (maps == null) return; // parent can dispatch visibility during construction
        maps.refreshStorage();
        long revision = maps.revision();
        if (revision != lastRevision) {
            lastRevision = revision; failed = false; failureCount = 0; retryAt = 0; loaded = false; source = ""; mapReady = false; mapName = "";
            if (web != null) { rendererId++; WebView old = web; web = null; removeView(old); old.destroy(); }
            show(false);
        }
        boolean run = eligible();
        boolean any = false; for (OfflineMaps.Region r : maps.regions()) any |= maps.installed(r);
        run &= any;
        if (!prefs.offlineMaps()) fallback(Fallback.DISABLED);
        else if (!maps.storageAvailable()) fallback(Fallback.STORAGE);
        else if (!any) fallback(Fallback.NO_MAPS);
        else if (!eligible()) fallback(Fallback.PAUSED);
        if (run && !tracking && locations != null) {
            try {
                for (String provider : locations.getProviders(true)) {
                    if (LocationManager.GPS_PROVIDER.equals(provider) || LocationManager.NETWORK_PROVIDER.equals(provider)) {
                        locations.requestLocationUpdates(provider, 1000, 0, locationListener, Looper.getMainLooper());
                        Location last = locations.getLastKnownLocation(provider);
                        if (fresh(last) && (fix == null || last.getElapsedRealtimeNanos() > fix.getElapsedRealtimeNanos())) fix = last;
                    }
                }
                tracking = true;
            } catch (SecurityException e) { if (locations != null) locations.removeUpdates(locationListener); tracking = false; }
        } else if (!run && tracking) { locations.removeUpdates(locationListener); tracking = false; }
        main.removeCallbacks(watchdog);
        if (run) {
            main.removeCallbacks(releaseRenderer); releaseScheduled = false;
            main.postDelayed(watchdog, 5000); if (web != null) web.onResume();
        } else {
            request++; show(false); if (web != null) web.onPause();
            if (web != null && !releaseScheduled) { releaseScheduled = true; main.postDelayed(releaseRenderer, prefs.offlineMaps() ? MapLocationPolicy.INACTIVE_RENDERER_MS : 10_000); }
        }
        if (!run && eligible() && !"internal".equals(maps.storageId())) main.postDelayed(watchdog, 5000);
        publishDiagnostics();
        if (run) update();
    }
    private void publishDiagnostics() {
        boolean fine = getContext().checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        boolean coarse = getContext().checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        boolean gps = false, gpsEnabled = false, network = false, networkEnabled = false;
        if (locations != null) try {
            java.util.List<String> providers = locations.getAllProviders();
            gps = providers.contains(LocationManager.GPS_PROVIDER);
            network = providers.contains(LocationManager.NETWORK_PROVIDER);
            gpsEnabled = gps && locations.isProviderEnabled(LocationManager.GPS_PROVIDER);
            networkEnabled = network && locations.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Exception ignored) { /* Never log provider exceptions or raw Location objects. */ }
        MapDiagnostics.gps(locations != null, fine, coarse, gps, gpsEnabled, network, networkEnabled, tracking);
        MapDiagnostics.mode(prefs.offlineMaps(), eligible());
        MapDiagnostics.fix(fix != null, fresh(fix));
        if (SystemClock.elapsedRealtime() >= nextMemorySample) {
            nextMemorySample = SystemClock.elapsedRealtime() + 30_000;
            com.mg4.launcher.svc.MapResourceDiagnostics.sample(getContext());
        }
    }
    // EN: Back off repeated renderer failures to avoid a tight recreate loop on older head units.
    // IT: Distanzia i tentativi dopo errori ripetuti del renderer per evitare riavvii continui sulle unita meno recenti.
    private void rendererFailed() {
        if (failed) return;
        failed = true;
        fallback(Fallback.RENDERER);
        long delay = Math.min(60_000L, 5000L << Math.min(failureCount++, 4));
        retryAt = SystemClock.elapsedRealtime() + delay; MapDiagnostics.retry(delay);
        show(false);
    }
    private final Runnable releaseRenderer = this::releaseRenderer;
    private void releaseRenderer() {
        releaseScheduled = false; rendererId++; loaded = false; source = ""; mapName = ""; mapReady = false; show(false);
        if (web != null) { WebView old = web; web = null; removeView(old); old.destroy(); }
        maps.releaseDatabases();
        MapDiagnostics.renderer(MapDiagnostics.Renderer.RELEASED);
    }
    private final Runnable watchdog = this::refresh;
    private final LocationListener locationListener = new LocationListener() {
        @Override public void onLocationChanged(Location l) { MapDiagnostics.fix(l != null, fresh(l)); if (fresh(l)) fix = new Location(l); update(); }
        @Override public void onProviderEnabled(String p) { refresh(); }
        @Override public void onProviderDisabled(String p) { refresh(); }
        @Override public void onStatusChanged(String p, int s, Bundle b) {}
    };
    private void update() {
        final int token = ++request;
        if (!eligible()) { show(false); return; }
        Fallback locationReason = locationFallback();
        if (locationReason != null) {
            fallback(locationReason);
            if (locationReason != Fallback.PERMISSION && !failed && web != null
                    && MapLocationPolicy.retainRenderedMap(mapReady, fixAgeMs(fix))) {
                // EN: Keep the rendered area during brief GPS loss, but dim the marker to show it is not live.
                // IT: Conserva l'area disegnata durante brevi perdite GPS, attenuando il simbolo non aggiornato.
                MapDiagnostics.coverage(true); showPosition(false);
            } else {
                MapDiagnostics.coverage(false); show(false);
                // A brief loss of position must not destroy a warm renderer.
                if (web != null && fixAgeMs(fix) > MapLocationPolicy.LAST_KNOWN_MAP_MS) releaseRenderer();
            }
            return;
        }
        if (failed) {
            if (SystemClock.elapsedRealtime() < retryAt) { fallback(Fallback.RENDERER); show(false); return; }
            releaseRenderer(); failed = false;
        }
        // EN: Query coverage off the UI thread; the request token discards results from outdated requests.
        // IT: Cerca la copertura fuori dal thread grafico; il token scarta risultati di richieste superate.
        final Location position = new Location(fix);
        io.execute(() -> {
            OfflineMaps.Coverage c = maps.coverage(position.getLatitude(), position.getLongitude());
            final long revision = maps.revision();
            main.post(() -> {
                if (token != request || !eligible()) return;
                MapDiagnostics.coverage(c != null);
                if (c == null) { fallback(Fallback.NO_COVERAGE); source = ""; show(false); if (web != null) releaseRenderer.run(); return; }
                createWeb(); if (!loaded || web == null) return;
                try {
                    JSONObject j = new JSONObject();
                    j.put("id", c.region.id); j.put("revision", revision); j.put("minZoom", c.minZoom); j.put("maxZoom", c.maxZoom);
                    j.put("lat", position.getLatitude()); j.put("lon", position.getLongitude());
                    j.put("bearing", position.hasBearing() ? position.getBearing() : 0); j.put("day", theme.day);
                    String key = c.region.id + ":" + revision;
                    if (!key.equals(source)) { mapReady = false; show(false); source = key; }
                    mapName = c.region.name;
                    positionState(true);
                    web.evaluateJavascript("window.updatePosition(" + j.toString() + ")", null);
                } catch (Exception ignored) { rendererFailed(); }
            });
        });
    }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); maps.addListener(mapListener); refresh(); }
    private final Runnable visibilityRefresh = this::refresh;
    private void settleVisibility() {
        if (maps == null || detached) return;
        main.removeCallbacks(visibilityRefresh); main.post(visibilityRefresh);
    }
    @Override protected void onVisibilityChanged(View v, int visibility) { super.onVisibilityChanged(v, visibility); settleVisibility(); }
    @Override protected void onWindowVisibilityChanged(int v) { super.onWindowVisibilityChanged(v); settleVisibility(); }
    @Override protected void onDetachedFromWindow() {
        rendererId++; detached = true; foreground = false; refresh(); maps.removeListener(mapListener); main.removeCallbacksAndMessages(null);
        io.shutdownNow(); if (web != null) { removeView(web); web.destroy(); web = null; }
        fix = null; super.onDetachedFromWindow();
    }
}
