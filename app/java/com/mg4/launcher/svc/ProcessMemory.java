package com.mg4.launcher.svc;

/**
 * EN: Convert Linux VmRSS from KiB to bytes; this is resident memory, not PSS. Return -1 if unavailable.
 * IT: Converti VmRSS di Linux da KiB a byte; indica memoria residente, non PSS. Restituisci -1 se assente.
 */
public final class ProcessMemory {
    private ProcessMemory() {}
    public static long rssBytes(String line) {
        if (line == null || !line.matches("VmRSS:\\s+[0-9]+\\s+kB\\s*")) return -1;
        try { return Math.multiplyExact(Long.parseLong(line.trim().split("\\s+")[1]), 1024L); }
        catch (Exception ignored) { return -1; }
    }
}
