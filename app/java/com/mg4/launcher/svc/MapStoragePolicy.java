package com.mg4.launcher.svc;

/** Download headroom; the map must not consume the last space needed by the car. */
public final class MapStoragePolicy {
    private MapStoragePolicy() {}
    public static long reserve(boolean internal) {
        return (internal ? 512L : 256L) * 1024 * 1024;
    }
    public static boolean fits(long free, long total, long partial, long reserve) {
        if (free < 0 || total <= 0 || partial < 0 || reserve < 0 || free < reserve) return false;
        if (partial > total) partial = 0;
        return total - partial <= free - reserve;
    }
}
