package com.mg4.launcher.svc;

/**
 * EN: Accept live fixes for 30 seconds with accuracy within 200 m. Keep an existing map
 * for up to two minutes during GPS loss; keep an inactive renderer warm for 30 seconds.
 * IT: Accetta posizioni per 30 secondi con precisione entro 200 m. Conserva la mappa
 * fino a due minuti senza GPS; mantieni il renderer inattivo pronto per 30 secondi.
 */
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
