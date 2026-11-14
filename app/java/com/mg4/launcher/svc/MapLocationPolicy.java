package com.mg4.launcher.svc;

/** Keep position freshness separate from the lifetime of already rendered map data. */
public final class MapLocationPolicy {
    public static final long LIVE_FIX_MS = 30_000;
    public static final long LAST_KNOWN_MAP_MS = 120_000;
    public static final long INACTIVE_RENDERER_MS = 30_000;
    private MapLocationPolicy() {}
    public static boolean fresh(long ageMs, boolean hasAccuracy, float accuracy) {
        return ageMs >= 0 && ageMs <= LIVE_FIX_MS && hasAccuracy
                && Float.isFinite(accuracy) && accuracy >= 0 && accuracy <= 200;
    }
    public static boolean retainRenderedMap(boolean rendered, long ageMs) {
        return rendered && ageMs >= 0 && ageMs <= LAST_KNOWN_MAP_MS;
    }
}
