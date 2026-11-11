#!/usr/bin/env python3
"""Package build/libConnectedTextures.so as a LeviLauncher .levipack."""
import json, sys, zipfile
from pathlib import Path
here = Path(__file__).resolve().parent
lib = here / "build" / "libConnectedTextures.so"
icon = here / "assets" / "icon.png"
builtin = here / "assets" / "builtin"
out = Path(sys.argv[1]) if len(sys.argv) > 1 else here / "build" / "ConnectedTextures.levipack"
manifest = {
    "type": "preload-native",
    "name": "Connected Textures",
    "author": "Alastor",
    "description": "OptiFine-style connected textures (CTM) for glass and other blocks, with overlays, biome filters and custom block connections. "
                   "Includes optional Full Grass Sides and Whiter Snow (both off by default; Mod Menu; restart Minecraft after changes). "
                   "Other CTM effects need converted resource packs. Minecraft 1.26.60 / 1.26.62; LeviLauncher 1.5.27+.",
    "version": "1.5.0",
    "entry": "libConnectedTextures.so",
    "icon": "icon.png",
    "overwrite_files": ["icon.png"],
    "overwrite_folders": ["resources/minecraft_resource_packs/ctm_grass", "resources/minecraft_resource_packs/ctm_whiter_snow"],
}
resources = {}
for source in sorted(builtin.rglob("*")):
    if source.is_file():
        relative = source.relative_to(builtin)
        # PLMod_Load exposes these manifests according to saved settings, before
        # LeviLauncher installs/activates the bundles. Never ship one forced on.
        if relative.name == "manifest.json":
            relative = relative.with_name("manifest.template.json")
        resources["resources/minecraft_resource_packs/" + relative.as_posix()] = source
assert any(n.endswith("ctm_grass/manifest.template.json") for n in resources)
assert any(n.endswith("ctm_whiter_snow/manifest.template.json") for n in resources)
out.parent.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
    z.writestr("manifest.json", json.dumps(manifest, indent=2) + "\n")
    z.write(lib, "libConnectedTextures.so")
    z.write(icon, "icon.png")
    for name, source in resources.items():
        z.write(source, name)
with zipfile.ZipFile(out) as z:
    names = sorted(z.namelist())
    assert names == sorted(["icon.png", "libConnectedTextures.so", "manifest.json", *resources]), names
    assert json.loads(z.read("manifest.json")) == manifest
    assert z.getinfo("libConnectedTextures.so").file_size == lib.stat().st_size
    for name, source in resources.items():
        assert z.read(name) == source.read_bytes()
print(out, out.stat().st_size)
