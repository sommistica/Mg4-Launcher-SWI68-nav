package com.mg4.launcher.svc;

import java.util.Locale;

/** Map diagnostics accept fixed states/counters only, never positions, paths, URLs or exception messages. */
public final class MapDiagnostics {
    public enum Renderer { NOT_CREATED, LOADING, READY, DISPLAYING, HIDDEN, RELEASED, FAILED }
    public enum Failure { CREATE, MAIN_DOCUMENT, PROCESS_EXIT, WEBGL, TILE_SOURCE, SCRIPT, TIMEOUT, RESOURCE, LIBRARY, WORKER, GLYPHS, REFERENCE, TYPE, DOWNLOAD, CHECKSUM, PACKAGE, STORAGE_SPACE, STORAGE_ACCESS }
    public enum Download { IDLE, DOWNLOADING, VERIFYING, PAUSED, FAILED, INSTALLED }
    private MapDiagnostics() {}

    public static void gps(boolean service, boolean fine, boolean coarse, boolean gps, boolean gpsEnabled,
                           boolean network, boolean networkEnabled, boolean tracking) {
        Diag.put("maps.gps.service", service);
        Diag.put("maps.gps.permissionFine", fine);
        Diag.put("maps.gps.permissionCoarse", coarse);
        Diag.put("maps.gps.providerPresent", gps);
        Diag.put("maps.gps.providerEnabled", gpsEnabled);
        Diag.put("maps.gps.networkProviderPresent", network);
        Diag.put("maps.gps.networkProviderEnabled", networkEnabled);
        Diag.put("maps.gps.tracking", tracking);
    }
    public static void fix(boolean present, boolean usable) {
        Diag.put("maps.gps.fix", !present ? "none" : usable ? "usable" : "stale_or_inaccurate");
    }
    public static void mode(boolean enabled, boolean visible) {
        Diag.put("maps.enabled", enabled); Diag.put("maps.panelActive", visible);
    }
    public static void coverage(boolean available) { Diag.put("maps.coverageAvailable", available); }
    public static void renderer(Renderer state) { Diag.put("maps.renderer", state.name().toLowerCase(Locale.ROOT)); }
    public static void failure(Failure reason) { Diag.put("maps.lastFailure", reason.name().toLowerCase(Locale.ROOT)); }
    public static void scriptFailure(int code) {
        failure(code == 1 ? Failure.WEBGL : code == 2 ? Failure.TILE_SOURCE : code == 4 ? Failure.TIMEOUT : code == 5 ? Failure.REFERENCE : code == 6 ? Failure.TYPE : code == 7 ? Failure.LIBRARY : Failure.SCRIPT);
        renderer(Renderer.FAILED);
    }
    public static void version(String version) {
        Diag.put("maps.webViewVersion", version != null && version.matches("[0-9]+(?:\\.[0-9]+){0,4}") ? version : "unknown");
    }
    public static void storage(boolean internal, boolean available, int removableCount, long freeBytes, int installedCount) {
        Diag.put("maps.storage.kind", internal ? "internal" : "removable");
        Diag.put("maps.storage.available", available);
        Diag.put("maps.storage.removableCount", Math.max(0, Math.min(32, removableCount)));
        Diag.putQuiet("maps.storage.freeMiB", mib(freeBytes));
        Diag.put("maps.packages.installedCount", Math.max(0, Math.min(1000, installedCount)));
    }
    public static void download(Download state) { Diag.put("maps.download.state", state.name().toLowerCase(Locale.ROOT)); }
    public static void retry(long delayMs) { Diag.putQuiet("maps.rendererRetrySeconds", delayMs / 1000); }
    public static void http(int code) { Diag.put("maps.download.httpStatus", code >= 100 && code <= 599 ? code : 0); }
    public static void memory(long heapUsed, long heapMax, long systemAvailable, boolean low) {
        Diag.putQuiet("maps.memory.javaUsedMiB", mib(heapUsed));
        Diag.putQuiet("maps.memory.javaMaxMiB", mib(heapMax));
        Diag.putQuiet("maps.memory.systemAvailableMiB", mib(systemAvailable));
        Diag.put("maps.memory.systemLow", low);
    }
    public static void systemMemory(long total, long available, long processRss) {
        Diag.putQuiet("maps.memory.systemTotalMiB", mib(total));
        Diag.putQuiet("maps.memory.systemUsedEstimateMiB", mib(Math.max(0, total - available)));
        Diag.putQuiet("maps.memory.launcherProcessRssMiB", processRss < 0 ? -1 : mib(processRss));
        Diag.put("maps.memory.processMetricAvailable", processRss >= 0);
        Diag.put("maps.memory.processMetric", "rss_proc_self_status");
    }
    public static void internalDisk(long total, long free) {
        Diag.putQuiet("maps.disk.internalTotalMiB", mib(total));
        Diag.putQuiet("maps.disk.internalFreeMiB", mib(free));
        Diag.putQuiet("maps.disk.internalUsedEstimateMiB", mib(Math.max(0, total - free)));
    }
    public static void mapDisk(long total, long free, long mapFiles, long partialFiles) {
        Diag.putQuiet("maps.disk.selectedTotalMiB", mib(total));
        Diag.putQuiet("maps.disk.selectedFreeMiB", mib(free));
        Diag.putQuiet("maps.disk.selectedUsedEstimateMiB", mib(Math.max(0, total - free)));
        Diag.putQuiet("maps.disk.mapFilesMiB", mib(mapFiles));
        Diag.putQuiet("maps.disk.partialDownloadsMiB", mib(partialFiles));
    }
    private static long mib(long bytes) { return Math.max(0, bytes / (1024 * 1024)); }
}
