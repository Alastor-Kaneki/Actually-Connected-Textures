# Fusion compatibility in CTM Forge 4

The Android Kotlin converter and Python reference converter recognize Fusion's
`fusion` section in `assets/<namespace>/textures/**/*.png.mcmeta`, and block models
using `loader: "fusion:model"`, `type: "connecting"`. Both produce the existing
Levi mod's `ctm-8x6-47` sheets. No mod update or new runtime format is required.

## Supported subset

| Input | Conversion |
| --- | --- |
| `pieced` (5 × 1) | Compose tile quarters into all 47 CTM states |
| `compact` (5 × 1) | Preserve Fusion's **whole-tile** selection; this is different from OptiFine `ctm_compact` |
| `full` (8 × 6, or legacy 8 × 8) | Remap Fusion tile positions to the mod's CTM ordering |
| `simple` (4 × 4) | Expand side-only connections to 47 states |
| `horizontal` (4 × 1), `vertical` (1 × 4) | Expand the appropriate side connections to 47 states |
| Explicit `is_same_block` / `fusion:is_same_block` | Use the mod's same-block connections |
| Cube parents | `minecraft:block/cube_all`, `cube`, `cube_bottom_top`, `cube_column` |
| Model references | Pack-local parent inheritance, texture `#aliases`, namespaced texture locations |
| Explicit geometry | One unrotated full cube with all six faces and ordinary UVs |
| Blockstate mapping | A single unconditional variant, without rotation, weighting or UV locking |

Tiles must be square. Pieced tile dimensions must be even so the quarter boundary
can be represented exactly. Static vertical sheets are not mistaken for animation.

The first release intentionally limits target blocks to a known full-cube list:
clear/tinted/stained glass, iron/lapis/gold/diamond/emerald/redstone/coal/netherite
blocks, common stone and brick cubes, obsidian, tuff, calcite, amethyst, and colored
wool/concrete/terracotta. The complete list is `CUBES` in `converter/fusion.py` and
`cubes` in `Fusion.kt`. Modded block IDs and glass panes are not inferred from names.
A texture with no supported block/model binding is reported instead of guessed.

Fusion 1.3 texture-only packs can use the vanilla same-name cube mapping. Legacy
packs are resolved through their model, including its connection predicate. A pack
that overrides a model or blockstate with an unsupported form does not fall back
to guessing from the texture name.

## Reported limitations

The conversion notes list the source path and reason for skipped features:

- Animation (including per-tile animation), scrolling, random/continuous texture
  types, overlays/borders, sub-textures, emissiveness and tinting.
- Default `is_same_state`, state/tag/direction/neighbor predicates, compound
  predicates, and per-material or per-face connection predicates. A singleton
  array containing `is_same_block` is accepted.
- Custom/non-cube geometry, rotated elements/UVs, multiple elements, weighted or
  conditional/multipart blockstates, item/entity model features and modifiers.
- Fusion override folders and unknown model/texture fields.
- Missing/cyclic model or texture references, missing images, malformed metadata,
  and incompatible sheet dimensions.

`render_type` is detected and explicitly reported as not transferred. Bedrock
retains the original block material: clear glass uses alpha testing, so partial
transparency may look different. Models are read to map textures and faces; Java
geometry is not exported.

OptiFine rules retain priority in mixed packs. If a feature is unsupported, a
valid unrelated rule can still convert. If nothing converts, the Android error
screen and Python CLI include the diagnostics. The Android success screen opens
notes automatically when anything was skipped.

The ordinary block/item fallback uses a single disconnected tile, never the full
Fusion atlas. Unsupported Fusion sheets are excluded from ordinary texture copying.
The `--direct` fallback also uses this single tile for carried textures.

## Verification

Run from the repository root:

```sh
python3 -m pip install Pillow==11.3.0
python3 -m unittest discover -s tests -v
python3 tests/fusion_goldens.py
cd ctm-forge
./gradlew :core:test :app:testDebugUnitTest :app:assembleRelease
```

CI runs these regressions before building `CTM-Forge-4.apk`. Tests use pinned,
MIT-licensed FusionExamples fixtures, test all 256 neighbor masks for each layout,
and compare 12 Kotlin outputs to Python at the decoded-pixel, JSON and report
levels. They also check nested ZIP recognition, model aliases/cycles, mixed packs,
unsupported features and malformed dimensions. Provenance is in
[`tests/fixtures/fusion/README.md`](../tests/fixtures/fusion/README.md).

These checks validate conversion and the existing mod's texture format. On-device
rendering on Bedrock/LeviLauncher still needs visual confirmation.
