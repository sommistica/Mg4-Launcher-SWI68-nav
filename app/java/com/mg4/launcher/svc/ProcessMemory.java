package com.mg4.launcher.svc;

/** VmRSS is resident process memory, not PSS; -1 means unavailable. */
public final class ProcessMemory {
    private ProcessMemory() {}
    public static long rssBytes(String line) {
        if (line == null || !line.matches("VmRSS:\\s+[0-9]+\\s+kB\\s*")) return -1;
        try { return Math.multiplyExact(Long.parseLong(line.trim().split("\\s+")[1]), 1024L); }
        catch (Exception ignored) { return -1; }
    }
}
