# Actually Connected Textures

OptiFine-style connected textures (CTM) for Minecraft Bedrock 1.26.60 and 1.26.62 on Android (arm64,
LeviLauncher), plus the tools to make packs for it:

| Folder | What it is |
| --- | --- |
| `mod/` | The LeviLauncher native mod (`ConnectedTextures.levipack`) |
| `converter/` | `ctm_convert.py`: Java CTM / Fusion pack -> Bedrock pack (the reference converter) |
| `ctm-forge/` | **CTM Forge**, the Android app: browse Modrinth / CurseForge / Planet Minecraft, convert on the phone, import into LeviLauncher |
| `tools/` | Checks for converted packs, previews, `merge_packs.py`, and the scripts that build `converter/data` |

Converted third-party CTM packs aren't in this repo. The built-in terrain resources
are adapted from the supplied Connected Grass pack by Vanilla Tweaks / Bedrock Tweaks;
their credits are retained in each bundle.

## GitHub Actions

Every push to `main` and every pull request runs `.github/workflows/build.yml`:

1. **Connected Textures mod**: host tests for the tile logic, then builds the `.levipack`
   with NDK r28c against pinned preloader-android headers.
2. **CTM Forge app**: bundles that `.levipack`, runs the converter and Robolectric tests,
   and builds the APK.

Both files are under the run's **Artifacts**. Pushing a tag like `v1.0` also publishes a
GitHub release with them. CTM Forge checks the latest release for updates to itself and to
the mod (see `ctm-forge/README.md`, "Updates"): bump the app's `versionCode` before tagging,
or installed copies won't see the new APK as newer.

To sign the app with your real key (so CI builds update your installed CTM Forge), add
these under Settings > Secrets and variables > Actions > Secrets:

| Secret | Value |
| --- | --- |
| `CTM_FORGE_KEYSTORE_BASE64` | the keystore file, base64-encoded (`base64 -w0 ctmforge-release.jks`) |
| `CTM_FORGE_KEYSTORE_PASSWORD` | keystore password |
| `CTM_FORGE_KEY_ALIAS` | key alias (`ctmforge`) |
| `CTM_FORGE_KEY_PASSWORD` | key password |

Without them, CI signs the APK with a throwaway debug key: it installs on a phone that
doesn't have CTM Forge yet, but can't update one that does. Release tags refuse to build
without the real key.

## The mod

### Install

1. Import `ConnectedTextures.levipack` in LeviLauncher and enable it.
2. Optional **Full Grass Sides** and **Whiter Snow** are included in mod **1.5.0**,
   both **off by default**. Enable either in the Connected Textures Mod Menu, then
   restart Minecraft. On **LeviLauncher 1.5.27 or newer**, enabled features load
   automatically; no separate import or manual Global Resources activation is needed.
3. For other CTM effects, import one of the converted `.mcpack` files and turn it on under
   Settings > Global Resources (or in a world's resource packs).
4. Load a world. Glass, glass panes and the pack's other blocks connect.

Turn off **Connected Glass** in BedrockTools while using this. Both mods change
the same glass faces.

### Checking that it works

The mod writes `status.txt` into its folder in LeviLauncher's mods directory.
After you load a world with glass in view it should contain a line starting with
`Connected textures active`. If the hooks loaded but that line never appears, the
game didn't keep the extra textures from the normal packs. Use the matching
`*_direct.mcpack` from the fallback set instead. Those always hand the tiles to the
mod, but glass shows a grid of tiles when the mod is off.

`config/connected_textures.json` in the mod folder has six switches:
`enabled`, `blocks` (full glass blocks), `panes`, `overlays` (OptiFine overlay
textures drawn on top of the base block), `connectedGrass` (default **false**) and
`whiterSnow` (default **false**). The same toggles also appear as a
**Connected Textures module in LeviLauncher's Mod Menu**, where you can change
them in game; edits are written back to that JSON file.

**Built-in terrain:** Full Grass Sides uses the supplied Connected Grass pack's full sides for grass, snowy grass,
mycelium, dirt paths, podzol and both nylium types, preserving the supplied biome
colors. These are the uploaded pack's full-side effects, not neighbor-based
grass connections. **Whiter Snow** independently enables the supplied snow and
powder-snow textures, snow golem, snowball, powder-snow bucket and freezing overlay.
With Full Grass Sides on and Whiter Snow off, snowy grass keeps full sides using
the active normal snow texture. With both off, the mod leaves terrain unchanged.

After changing either terrain switch, **fully restart Minecraft through LeviLauncher**.
The main module switch also disables the built-in terrain after a restart.
The launcher automatically manages the bundled resources and removes their active
copies when the mod is disabled. Existing user/world resource packs can override
them. Disable the separately imported Connected Grass pack if it is active, since
its own Whiter Snow textures would otherwise remain enabled. CTM Forge build **14**
includes the same mod. Fire and soul-fire rules are unaffected.

### What's supported

* Methods: `ctm` (47 tiles), `ctm_compact` (expanded to 47 at conversion),
  `horizontal`, `vertical`, `horizontal+vertical`, `vertical+horizontal`, `top`,
  `random` (weights, but not OptiFine's exact random pattern), `repeat`, `fixed`.
* Overlay methods: `overlay` (17 tiles), `overlay_ctm`, `overlay_random`,
  `overlay_repeat`, `overlay_fixed`. Overlays are drawn as extra quads on top of
  the face's own texture, including `<skip>` (transparent) overlay tiles.
  Several overlay rules on one block face are merged in pack order, and an
  overlay can share a face with a normal CTM rule. Overlays only apply to
  full-cube blocks, not to panes. Overlay animations use the first frame, and
  `connectTiles`, `tintIndex`, `tintBlock` and `layer` are ignored.
  `connectBlocks` restricts transitions to the named neighboring blocks.
* Rule keys: `matchBlocks`, `matchTiles` (by block name), `tiles`, `faces`,
  `innerSeams`, `weights`, `width`/`height`. `connect` is always `block`.
  `biomes` includes/excludes biome names. `connectBlocks` chooses which blocks
  count as connected, and `ctmforge.neighborBlocks` activates a rule when any of
  the six immediately adjacent blocks matches. `ctmforge.belowBlocks` (the creator's
  **Only when on** field) checks only the block underneath and requires mod **1.4.0** /
  app build **13**. Height and block-state conditions are ignored.
* Not supported: Fusion's `overlay` layout, and overlay rules in `direct` packs.
  Conditional rules require standard (variations) mode. The first matching
  conditional rule wins; otherwise the first unconditional rule or vanilla texture is used.
  See [conditional textures](docs/conditional-textures.md) for examples and limits.
* Fusion (SuperMartijn642) connecting textures, from `pack.mcmeta` overrides folders, block
  models (`fusion:model`) and texture `.mcmeta` files: the `full`, `simple`, `compact`,
  `pieced`, `horizontal` and `vertical` layouts, plus Fusion's random, continuous and
  scrolling textures. The `overlay` layout isn't supported. Connections are same-block only.
* Animated textures (`.png.mcmeta` animations): animated CTM tiles, and plain animated block
  textures such as fire, soul fire, nether portals, water, lava, sea lanterns, magma, command
  blocks and lanterns. These become Bedrock flipbooks, so packs that only animate textures
  work without the mod.
* Full cube blocks go through the classic block renderer while the mod is on.

Bedrock draws clear glass with alpha test (pixels are either solid or invisible),
so packs that rely on see-through pixels on clear glass (More Clear Glass, the
Translucent Glass pack) won't look the same as on Java.

### How it works

`converter/ctm_convert.py` turns a Java CTM pack into a Bedrock pack. Every
connected block gets a texture alias whose terrain texture is a variations list,
`[base texture (weight 1000000), tile sheet (weight 0.05)]`. Without the mod the
base texture wins. The sheet path encodes how to read it:
`textures/blocks/ctm/<method>-<cols>x<rows>-<count>[-s]/<name>.png`.

Both images must be included in the converted pack. Bedrock drops variations
whose images come from lower-priority packs, so build 12 copies each needed
normal fallback to a private path. Conditional and overlay-only rules prefer a
normal texture supplied by the Java pack, otherwise the bundled vanilla image.

Animated tiles make a square, animated sheet: a flipbook (`textures/flipbook_textures.json`)
plays whole-sheet frames from `textures/blocks/ctm/<spec>/anim/<name>.png` into the sheet's
atlas slot, so whichever tile the mod picks animates. Plain animated textures are written at
their Bedrock paths with a flipbook that follows the Java timing; tiles vanilla already
animates keep vanilla's image path, `atlas_index`, `atlas_tile_variant` and `replicate`.

The mod (`mod/src`) hooks the 1.26.60 / 1.26.62 block renderer:

* `RenderChunkBuilder::build` marks chunk builds.
* `BlockGraphics::getTexture` returns the sheet instead of the base texture
  during chunk builds.
* The six `BlockTessellator::tessellateFace*` functions swap in the tile chosen
  from the 8 neighbours.
* For glass panes, the pane tessellator's vertices are held back one quad at a time.
  The mod works out which way the quad faces, then sends the vertices on with the
  tile's UVs.
* `BlockTessellatorPipeline::useNewTessellation` is turned off for blocks that
  have a tile sheet, so they use the classic renderer.

Signatures for the shared functions are copied verbatim from BedrockTools 1.5.9
(MIT License, Copyright (c) 2026 RadiantByte; see THIRD_PARTY_NOTICES.md). Identical pattern strings let Preloader's signature cache return
the right address even when BedrockTools has already hooked a function.

### Build

Prepare the offline fallback images before running the converters, tests or app
build. CI does this automatically. Use Mojang/bedrock-samples **v1.26.60.5**
(commit `15701fa9c13923f12662984c32c124327e40e3d3`):

```
python3 -m pip install "Pillow>=10,<13"
python3 tools/build_vanilla_fallbacks.py /path/to/bedrock-samples/resource_pack
```

This creates a roughly 1 MB archive for Python and the Android app. The app
converts offline; generated archives and source images are not checked into git.

```
cd mod
NDK=/path/to/android-ndk-r28c PL=/path/to/preloader-android/include sh build.sh
python3 package.py    # -> mod/build/ConnectedTextures.levipack
```

`PL` is the `include` folder of [preloader-android](https://github.com/LiteLDev/preloader-android).
`convert_all.sh` converts the glass packs (unpacked under `PACKS`, default `./packs`), and
`tools/merge_packs.py` merges converted packs into one, the highest-priority pack first.
`tools/build_java_map.py` (Java 26.3 names -> Bedrock) and `tools/build_vanilla_flipbooks.py`
(vanilla flipbooks) rebuild the name tables in `converter/data`; copy them to
`ctm-forge/core/src/main/resources` too.

Tests: `clang++ -std=c++20 -Imod/src mod/tests/host_test.cpp -o host_test && ./host_test unit`.
`tools/check_random.py`, `tools/check_sheet.py` and `tools/validate_packs.py`
check tile choices against the tile art, the converted sheets, and the pack
structure.
