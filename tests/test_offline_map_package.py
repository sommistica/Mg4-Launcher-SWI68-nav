from contextlib import closing
import gzip
import importlib.util
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("prepare", Path(__file__).resolve().parents[1] / "tools/prepare_offline_map.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def layer(name):
    payload = b"\x0a" + bytes([len(name)]) + name.encode()
    return b"\x1a" + bytes([len(payload)]) + payload


class PackageTest(unittest.TestCase):
    def test_removes_sensitive_and_unused_layers_for_raw_and_gzip(self):
        data = layer("streets") + layer("buildings") + layer("addresses") + layer("water_lines")
        expected = layer("streets") + layer("water_lines")
        for encoded in (data, gzip.compress(data)):
            self.assertEqual(gzip.decompress(module.simplify_tile(encoded)), expected)

    def test_rejects_truncated_protobuf(self):
        for bad in (b"\x1a\x10x", b"\x80", b"\x00"):
            with self.assertRaises(ValueError):
                module.simplify_tile(bad)

    def test_output_contains_only_maps_and_retains_attribution(self):
        with tempfile.TemporaryDirectory() as tmp:
            src, dst = Path(tmp) / "src.mbtiles", Path(tmp) / "dst.mbtiles"
            with closing(sqlite3.connect(src)) as db:
                db.execute("CREATE TABLE metadata(name TEXT, value TEXT)")
                db.execute("CREATE TABLE tiles(zoom_level INTEGER,tile_column INTEGER,tile_row INTEGER,tile_data BLOB)")
                db.execute("CREATE TABLE places(name TEXT,latitude REAL,longitude REAL)")
                db.executemany("INSERT INTO metadata VALUES (?,?)", [
                    ("format", "pbf"), ("attribution", "© OpenStreetMap contributors"),
                    ("json", json.dumps({"vector_layers": [{"id": "streets"}, {"id": "addresses"}]}))])
                db.execute("INSERT INTO tiles VALUES (14,1,2,?)", (layer("streets") + layer("addresses"),))
                db.commit()
            result = module.prepare(src, dst)
            self.assertEqual(len(result["sha256"]), 64)
            self.assertEqual(result["bytes"], dst.stat().st_size)
            with closing(sqlite3.connect(dst)) as db:
                tables = {x[0] for x in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
                self.assertEqual(tables, {"metadata", "tiles"})
                self.assertEqual(dict(db.execute("SELECT * FROM metadata"))["attribution"], "© OpenStreetMap contributors")
            with self.assertRaises(ValueError): module.prepare(src, dst)


if __name__ == "__main__":
    unittest.main()
