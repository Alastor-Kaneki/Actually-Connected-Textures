#!/usr/bin/env python3
"""Package build/libConnectedTextures.so as a LeviLauncher .levipack."""
import json, sys, zipfile
from pathlib import Path
here = Path(__file__).resolve().parent
sys.path.insert(0, str(here.parent / "tools"))
from build_builtin_continuity import build_bundle
lib = here / "build" / "libConnectedTextures.so"
icon = here / "assets" / "icon.png"
builtin = here / "assets" / "builtin"
out = Path(sys.argv[1]) if len(sys.argv) > 1 else here / "build" / "ConnectedTextures.levipack"
manifest = {
    "type": "preload-native",
    "name": "Connected Textures",
    "author": "Alastor",
    "description": "OptiFine-style connected textures (CTM) for glass and other blocks, with overlays, biome filters and custom block connections. "
                   "Includes Continuity default textures (on), Full Grass Sides and Whiter Snow (off). "
                   "Mod Menu switches; restart Minecraft after changes. Minecraft 1.26.60 / 1.26.62; LeviLauncher 1.5.27+.",
    "version": "1.6.2",
    "entry": "libConnectedTextures.so",
    "icon": "icon.png",
    "overwrite_files": ["icon.png"],
    "overwrite_folders": ["resources/minecraft_resource_packs/" + folder for folder in ("ctm_defaults", "ctm_grass", "ctm_whiter_snow")],
}
resources = {}
for source in sorted(builtin.rglob("*")):
    if source.is_file():
        relative = source.relative_to(builtin)
        # PLMod_Load exposes these manifests according to saved settings, before
        # LeviLauncher installs/activates the bundles. Never ship one forced on.
        if relative.name == "manifest.json":
            relative = relative.with_name("manifest.template.json")
        resources["resources/minecraft_resource_packs/" + relative.as_posix()] = source.read_bytes()
defaults, summary = build_bundle()
for name, data in defaults.items():
    if name == "manifest.json":
        name = "manifest.template.json"
    resources["resources/minecraft_resource_packs/ctm_defaults/" + name] = data
assert any(n.endswith("ctm_defaults/manifest.template.json") for n in resources)
assert any(n.endswith("ctm_grass/manifest.template.json") for n in resources)
assert any(n.endswith("ctm_whiter_snow/manifest.template.json") for n in resources)
out.parent.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
    z.writestr("manifest.json", json.dumps(manifest, indent=2) + "\n")
    z.write(lib, "libConnectedTextures.so")
    z.write(icon, "icon.png")
    for name, data in resources.items():
        z.writestr(name, data)
with zipfile.ZipFile(out) as z:
    names = sorted(z.namelist())
    assert names == sorted(["icon.png", "libConnectedTextures.so", "manifest.json", *resources]), names
    assert json.loads(z.read("manifest.json")) == manifest
    assert z.getinfo("libConnectedTextures.so").file_size == lib.stat().st_size
    for name, data in resources.items():
        assert z.read(name) == data
print(out, out.stat().st_size)
