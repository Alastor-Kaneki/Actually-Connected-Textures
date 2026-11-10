#!/usr/bin/env python3
"""Package build/libConnectedTextures.so as a LeviLauncher .levipack."""
import json, sys, zipfile
from pathlib import Path
here = Path(__file__).resolve().parent
lib = here / "build" / "libConnectedTextures.so"
icon = here / "assets" / "icon.png"
out = Path(sys.argv[1]) if len(sys.argv) > 1 else here / "build" / "ConnectedTextures.levipack"
manifest = {
    "type": "preload-native",
    "name": "Connected Textures",
    "author": "Alastor",
    "description": "OptiFine-style connected textures (CTM) for glass and other blocks, with overlays, biome filters and custom block connections. "
                   "Needs a converted CTM resource pack. Built for Minecraft 1.26.60 and 1.26.62.",
    "version": "1.3.1",
    "entry": "libConnectedTextures.so",
    "icon": "icon.png",
    "overwrite_files": ["icon.png"],
    "overwrite_folders": [],
}
out.parent.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
    z.writestr("manifest.json", json.dumps(manifest, indent=2) + "\n")
    z.write(lib, "libConnectedTextures.so")
    z.write(icon, "icon.png")
with zipfile.ZipFile(out) as z:
    names = sorted(z.namelist())
    assert names == ["icon.png", "libConnectedTextures.so", "manifest.json"], names
    assert json.loads(z.read("manifest.json")) == manifest
    assert z.getinfo("libConnectedTextures.so").file_size == lib.stat().st_size
print(out, out.stat().st_size)
