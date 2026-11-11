# Continuity defaults: source and adaptation

This bundle uses the modern default resource pack from PepperCode1's
Continuity **3.0.1+26.3**, provided in `continuity-3.0.1+26.3.jar`.
Upstream: https://github.com/PepperCode1/Continuity (LGPL-3.0-only).

JAR SHA-256: `429fb0946118e86cce8ace8b57d07e1971088fb3f8115439bb0f2f12df14f511`.
Selected-source ZIP SHA-256: `44193eddf5f4e3d6cd7cb6bc016fdde1e3628cbcf0f0864c556eb63988105b50`.

`source/continuity-default.zip` contains the original, unmodified files from
`resourcepacks/default/assets/continuity/optifine/ctm/default/`, plus that pack's
`pack.mcmeta` and `pack.png`. `source/adapted/` contains the modified property
files used for conversion. The PNGs are unchanged before atlas assembly.
No Java code, Programmer Art variants or Java pane-culling models are included.

## Coverage and limits

42 Bedrock block IDs: clear glass and panes, all 16 stained colors and panes,
tinted glass, bookshelves, normal/red sandstone, cut normal/red sandstone and
chiseled normal/red sandstone. Glass connects to the same block/color. Bookshelf
and cut/chiseled sandstone use horizontal connections. Normal sandstone connects
its side texture vertically when a normal same-color sandstone block, slab,
double slab, stair or wall is directly above. The top-rule target is the full
sandstone block; shape textures and ambiguous legacy stone-slab states are not
changed. Smooth sandstone is not part of Continuity's default rules.

Bedrock cannot use Continuity's `resourceCondition` mechanism. Disable **Default
Connected Textures** in the mod menu when using a plain texture pack that replaces
these blocks. A higher-priority converted CTM pack can replace these block aliases.
Grass and snow options are independent. Restart the game after changing switches.

## Rebuild or modify

Source repository: https://github.com/Alastor-Kaneki/Actually-Connected-Textures

1. Edit `mod/third_party/continuity-default-3.0.1+26.3.zip` if changing the original
   art, then update its checksum in `tools/build_builtin_continuity.py`.
2. Prepare vanilla fallbacks from Mojang/bedrock-samples commit
   `15701fa9c13923f12662984c32c124327e40e3d3` with
   `python3 tools/build_vanilla_fallbacks.py /path/to/resource_pack`.
3. Run `python3 tools/build_builtin_continuity.py /tmp/continuity-defaults.mcpack`
   to inspect the standalone conversion. Normal mod packaging runs this same
   builder automatically. Python 3 and Pillow are required.
4. `mod/package.py` bundles the generated resources with a manifest template;
   the native mod exposes it before LeviLauncher's resource installer runs.

The builder only writes private CTM texture paths; normal images are copied into
the same bundle as fallback variants so Bedrock retains them in the atlas.
All modified Continuity resources remain LGPL-3.0-only. See both license files.
