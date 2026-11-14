"""Exercise the actual diagnostic serializer without an Android runtime."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


@unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "Requires JDK (available in the build container)")
class MapDiagnosticsTest(unittest.TestCase):
    def test_only_fixed_states_and_sanitized_metadata_are_emitted(self):
        root = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory() as tmp:
            temp = Path(tmp)
            stub = temp / "Diag.java"
            stub.write_text('''package com.mg4.launcher.svc;
public final class Diag {
 public static void put(String key, Object value) { System.out.println(key + "=" + value); }
 public static void putQuiet(String key, Object value) { put(key, value); }
}''', encoding="utf-8")
            probe = temp / "Probe.java"
            probe.write_text('''import com.mg4.launcher.svc.MapDiagnostics;
public class Probe {
 public static void main(String[] args) {
  MapDiagnostics.gps(true, true, false, true, true, false, false, false);
  MapDiagnostics.fix(true, false);
  MapDiagnostics.renderer(MapDiagnostics.Renderer.READY);
  MapDiagnostics.scriptFailure(999999);
  MapDiagnostics.storage(false, true, Integer.MAX_VALUE, -10, -1);
  MapDiagnostics.http(987654321);
  MapDiagnostics.systemMemory(2048L*1048576, 512L*1048576, 80L*1048576);
  MapDiagnostics.internalDisk(8000L*1048576, 2000L*1048576);
  MapDiagnostics.mapDisk(32000L*1048576, 10000L*1048576, 500L*1048576, 50L*1048576);
  MapDiagnostics.version("123.0.6312.80");
  MapDiagnostics.version("Utrecht /storage/PRIVATE-USB/ 52.0907,5.1214");
 }
}''', encoding="utf-8")
            subprocess.run(["javac", "-encoding", "UTF-8", "-d", str(temp), str(stub),
                            str(root / "app/java/com/mg4/launcher/svc/MapDiagnostics.java"), str(probe)], check=True, capture_output=True)
            text = subprocess.run(["java", "-cp", str(temp), "Probe"], check=True, capture_output=True, text=True).stdout
            self.assertIn("maps.gps.fix=stale_or_inaccurate", text)
            self.assertIn("maps.webViewVersion=123.0.6312.80", text)
            self.assertIn("maps.webViewVersion=unknown", text)
            self.assertIn("maps.lastFailure=script", text)
            self.assertIn("maps.storage.removableCount=32", text)
            self.assertIn("maps.download.httpStatus=0", text)
            self.assertIn("maps.memory.systemUsedEstimateMiB=1536", text)
            self.assertIn("maps.memory.launcherProcessPssMiB=80", text)
            self.assertIn("maps.disk.internalUsedEstimateMiB=6000", text)
            self.assertIn("maps.disk.mapFilesMiB=500", text)
            self.assertIn("maps.disk.partialDownloadsMiB=50", text)
            for forbidden in ["Utrecht", "PRIVATE-USB", "52.0907", "5.1214", "/storage/", "999999", "987654321"]:
                self.assertNotIn(forbidden, text)


if __name__ == "__main__":
    unittest.main()
