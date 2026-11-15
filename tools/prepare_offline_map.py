#!/usr/bin/env python3
"""Create smaller Librescoot-compatible MBTiles, excluding buildings/addresses/geocoding.

Usage: python tools/prepare_offline_map.py source.mbtiles slim.mbtiles
Host the result separately, then update offline-map/catalog.json with the printed
byte size and SHA-256. Never commit map databases into the source repository.
"""
import argparse
from contextlib import closing
import gzip
import hashlib
import json
import sqlite3
from pathlib import Path

# EN: Offline preparation only, not part of app downloads. Keep these layers; drop buildings,
# addresses and separate place lookup tables when writing the new database.
# IT: Preparazione separata, non usata nei download dell'app. Conserva questi livelli; elimina
# edifici, indirizzi e tabelle di ricerca dei luoghi durante la creazione del nuovo database.
KEEP = {"streets", "street_labels", "water_polygons", "water_lines", "land"}


def varint(data, offset):
    value = shift = 0
    while offset < len(data) and shift < 70:
        byte = data[offset]
        offset += 1
        value |= (byte & 127) << shift
        if byte < 128:
            return value, offset
        shift += 7
    raise ValueError("Invalid protobuf varint")


def fields(data):
    offset = 0
    while offset < len(data):
        start = offset
        tag, offset = varint(data, offset)
        wire = tag & 7
        if wire == 2:
            length, offset = varint(data, offset)
            payload = data[offset:offset + length]
            offset += length
        elif wire == 0:
            _, offset = varint(data, offset)
            payload = b""
        elif wire in (1, 5):
            offset += 8 if wire == 1 else 4
            payload = b""
        else:
            raise ValueError("Unsupported protobuf wire type")
        if offset > len(data) or tag >> 3 == 0:
            raise ValueError("Truncated protobuf field")
        yield tag >> 3, wire, payload, data[start:offset]


def simplify_tile(tile):
    if tile.startswith(b"\x1f\x8b"):
        tile = gzip.decompress(tile)
    retained = []
    for number, wire, layer, raw in fields(tile):
        if number == 3 and wire == 2:
            name = next((p.decode("utf-8") for n, w, p, _ in fields(layer) if n == 1 and w == 2), "")
            if name in KEEP:
                retained.append(raw)
    return gzip.compress(b"".join(retained), mtime=0)


def prepare(source, output):
    source, output = Path(source), Path(output)
    if output.exists() or source.resolve() == output.resolve():
        raise ValueError("Output must be a new file")
    try:
        with closing(sqlite3.connect(source.resolve().as_uri() + "?mode=ro", uri=True)) as src, closing(sqlite3.connect(output)) as dst:
            metadata = dict(src.execute("SELECT name, value FROM metadata"))
            if metadata.get("format") != "pbf":
                raise ValueError("Expected vector MBTiles")
            schema = json.loads(metadata["json"])
            schema["vector_layers"] = [x for x in schema["vector_layers"] if x["id"] in KEEP]
            if not any(x["id"] == "streets" for x in schema["vector_layers"]):
                raise ValueError("Expected Librescoot streets schema")
            metadata["json"] = json.dumps(schema, separators=(",", ":"))
            metadata["description"] = "Simplified streets and water; no buildings, addresses or geocoding index"
            dst.execute("CREATE TABLE metadata (name TEXT PRIMARY KEY, value TEXT)")
            dst.execute("CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB, PRIMARY KEY (zoom_level,tile_column,tile_row))")
            dst.executemany("INSERT INTO metadata VALUES (?,?)", metadata.items())
            for z, x, y, tile in src.execute("SELECT zoom_level,tile_column,tile_row,tile_data FROM tiles"):
                dst.execute("INSERT INTO tiles VALUES (?,?,?,?)", (z, x, y, simplify_tile(tile)))
            dst.commit()
    except BaseException:
        output.unlink(missing_ok=True)
        raise
    h = hashlib.sha256()
    with output.open("rb") as f:
        for block in iter(lambda: f.read(1024 * 1024), b""):
            h.update(block)
    digest = h.hexdigest()
    return {"bytes": output.stat().st_size, "sha256": digest}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    print(json.dumps(prepare(args.source, args.output), indent=2))
