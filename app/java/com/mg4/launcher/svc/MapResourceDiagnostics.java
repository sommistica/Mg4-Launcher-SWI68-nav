package com.mg4.launcher.svc;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Debug;
import java.io.File;

/** Fresh resource snapshots for uploaded logs, including while the map is disabled. */
public final class MapResourceDiagnostics {
    private MapResourceDiagnostics() {}
    public static void sample(Context context) {
        try {
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager != null) {
                ActivityManager.MemoryInfo system = new ActivityManager.MemoryInfo(); manager.getMemoryInfo(system);
                Runtime runtime = Runtime.getRuntime();
                MapDiagnostics.memory(runtime.totalMemory() - runtime.freeMemory(), runtime.maxMemory(), system.availMem, system.lowMemory);
                Debug.MemoryInfo process = new Debug.MemoryInfo(); Debug.getMemoryInfo(process);
                MapDiagnostics.systemMemory(system.totalMem, system.availMem, process.getTotalPss() * 1024L);
            }
            File internal = context.getFilesDir();
            MapDiagnostics.internalDisk(internal.getTotalSpace(), internal.getUsableSpace());
            OfflineMaps maps = OfflineMaps.get(context); maps.refreshStorage(); maps.snapshotDisk();
        } catch (Exception ignored) { /* Never include paths or exception messages in diagnostics. */ }
    }
}
