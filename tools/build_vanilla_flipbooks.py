#!/usr/bin/env python3
"""Add vanilla Bedrock's flipbook table to converter/data/vanilla_26.60.json.

Reads textures/flipbook_textures.json from Mojang/bedrock-samples' resource_pack and keeps, for
every animated atlas tile, the fields the converter copies when a Java pack re-animates that
tile: the flipbook image path and atlas_index / atlas_tile_variant / replicate. Timings are left
out (they come from the Java pack's .mcmeta). Only names and numbers are written, no game assets.

Usage: build_vanilla_flipbooks.py <bedrock-samples resource_pack folder> <vanilla_26.60.json>
"""
import json
import re
import sys
from pathlib import Path

KEEP = ("atlas_tile", "atlas_index", "atlas_tile_variant", "flipbook_texture", "replicate")


def main() -> None:
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    rp, out = Path(sys.argv[1]), Path(sys.argv[2])
    text = (rp / "textures" / "flipbook_textures.json").read_text(encoding="utf-8-sig")
    entries = json.loads(re.sub(r"^\s*//.*$", "", text, flags=re.M))  # the file starts with a comment
    vanilla = json.loads(out.read_text(encoding="utf-8"))
    vanilla["flipbooks"] = [{k: e[k] for k in KEEP if k in e} for e in entries]
    src = vanilla["source"].split(" (")[0]
    vanilla["source"] = f"{src} (blocks.json + terrain_texture.json aliases + flipbook_textures.json)"
    out.write_text(json.dumps(vanilla, separators=(",", ":")), encoding="utf-8")
    print(out, len(vanilla["flipbooks"]), "flipbooks")


if __name__ == "__main__":
    main()
