package com.mg4.launcher.svc;

import android.app.ActivityManager;
import android.content.Context;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

/** Independent, best-effort snapshots; no Debug.getMemoryInfo calls on vendor firmware. */
public final class MapResourceDiagnostics {
    private MapResourceDiagnostics() {}
    public static void sample(Context context) {
        try {
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager != null) {
                ActivityManager.MemoryInfo system = new ActivityManager.MemoryInfo(); manager.getMemoryInfo(system);
                Runtime runtime = Runtime.getRuntime();
                MapDiagnostics.memory(runtime.totalMemory() - runtime.freeMemory(), runtime.maxMemory(), system.availMem, system.lowMemory);
                MapDiagnostics.systemMemory(system.totalMem, system.availMem, residentBytes());
            }
        } catch (Exception ignored) { /* A RAM query must not suppress disk diagnostics. */ }
        try {
            File internal = context.getFilesDir();
            MapDiagnostics.internalDisk(internal.getTotalSpace(), internal.getUsableSpace());
        } catch (Exception ignored) {}
        try {
            OfflineMaps maps = OfflineMaps.get(context); maps.refreshStorage(); maps.snapshotDisk();
        } catch (Exception ignored) {}
    }
    private static long residentBytes() {
        try (BufferedReader in = new BufferedReader(new FileReader("/proc/self/status"))) {
            String line;
            while ((line = in.readLine()) != null) if (line.startsWith("VmRSS:")) return ProcessMemory.rssBytes(line);
        } catch (Exception ignored) {}
        return -1;
    }
}
