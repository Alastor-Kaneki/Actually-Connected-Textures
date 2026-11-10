# Conditional textures

Block-below rules require CTM Forge build **13** and Connected Textures **1.4.0**.
Biome/touching rules still work with 1.3.1. Reconvert packs made before build 12:
newer builds embed the normal fallback so Bedrock retains both texture variations
when loading the pack. Install the updated mod as well as the new pack when using
the block-below condition.

## Create a pack

Open **Create → your pack → a block rule → Biome & neighbor rules**.

| Field | Example | Behavior |
| --- | --- | --- |
| Biomes | `desert plains` | Use this rule in either biome. |
| Biomes | `!desert plains` | Use this rule outside both biomes. |
| Connect to blocks | `glass tinted_glass` | Count either block as a connected neighbor when choosing CTM tiles. |
| Only when touching | `sand gravel` | Enable this rule if any directly adjacent block is sand or gravel. |
| Only when on | `end_stone purpur_block` | Enable this rule if the block immediately underneath is listed. |

Use space- or comma-separated vanilla names, optionally prefixed with `minecraft:`.
Biome names use modern underscore spelling; common Java-to-Bedrock biome aliases
are expanded during conversion. Unknown biome names never match. Unsupported block
names and block-state expressions are rejected with a build error.

An empty **Connect to blocks** field uses the original same-block behavior. A
nonempty list replaces it: include the target block if it should connect to itself.
Connections affect texture selection; they do not change pane geometry, collision,
block behavior, or biome generation.

**Only when touching** checks the six immediate neighbors (up, down, north, south,
east and west), including air if explicitly listed. It does not search diagonally
or across a radius. **Only when on** checks only world down, at `(x, y-1, z)`;
blocks beside or above the target do not satisfy it. All filled condition fields
combine with AND. Each list uses OR. Use separate rules for alternatives such as
"in the End OR on purpur".

You may add several rules for the same block faces. The first matching conditional
rule wins, in creator/file order, regardless of where the unfiltered fallback sits.
If none matches, the first unfiltered rule wins. Without one, each face retains its
own bundled vanilla texture, or the source Java pack's replacement when supplied.
These fallback images belong to the converted pack; lower-priority resource packs
cannot change them. Different conditions can use different animations.
All existing animation frame/timing/size limits still apply.

## Imported Java properties

```properties
matchBlocks=minecraft:glass
method=ctm_compact
tiles=0-4
biomes=desert plains
connectBlocks=glass tinted_glass
ctmforge.neighborBlocks=sand gravel
ctmforge.belowBlocks=end_stone purpur_block
```

`biomes` and `connectBlocks` are read from OptiFine/MCPatcher rules.
`ctmforge.neighborBlocks` and `ctmforge.belowBlocks` are this project's extensions. Overlay rules can also use
these filters; their `connectBlocks` list selects transition neighbors. Overlay
animations still use the first frame. Fusion connection predicates, `connect=tile`,
`connectTiles`, state filters, height filters and other directional conditions are
not added by this change. **Direct mode rejects conditional/custom connection rules**
because it cannot carry a fallback and alternative sheets.

## Runtime format

Conditions are directories inside the sheet path, after the existing sheet spec:

```text
textures/blocks/ctm/ctm-8x6-47/when/b-desert.plains/c-glass.tinted_glass/n-sand/end/pack_glass.png
```

`b-` includes biomes; `x-` excludes them; `c-` lists connection blocks; `n-` lists
activation neighbors; `d-` lists blocks allowed directly underneath. Lists are
dot-separated; `end/` terminates the condition
section. Animated strips carry exactly the same condition directories before
`anim/`. The converter bounds list/path sizes. Unknown, repeated or malformed
condition segments fail closed. Missing biome lookup skips biome-filtered rules,
including exclusions, while other CTM rules continue to work.

## Validation and testing in game

Host tests cover parsing, exclusions, combined filters and custom connection masks.
Converter tests cover overlapping variants, face-specific fallbacks, animated
conditions, invalid input and direct-mode rejection. Android tests edit, persist,
reopen and build a conditional draft. The game binding was inspected against both
supported arm64 binaries; see [binding notes](../mod/BINDINGS.md).

The renderer still needs device validation. Install both updates, then try one
block across a plains/desert boundary, add/remove the required neighbor, and check
connections between two listed block types. Also check panes, chunk boundaries and
animated rules. Reload the world/resource pack after editing a pack or changing a
biome externally so Minecraft rebuilds its chunk meshes.

## Purple fire on End building blocks

The updated Purple End Fire pack uses separate rules for End biomes, end-stone
support blocks, and purpur support blocks. Both fire layers retain their original
32-frame animations. Ordinary fire outside those conditions uses the embedded
orange fallback; the pack does not target soul fire.

| Family | Supported Bedrock block IDs |
| --- | --- |
| End stone | `end_stone`, `end_bricks`, `end_brick_stairs`, `end_stone_brick_slab`, `end_stone_brick_double_slab`, `end_stone_brick_wall` |
| Purpur | `purpur_block`, `purpur_pillar`, `purpur_stairs`, `purpur_slab`, `purpur_double_slab`, `deprecated_purpur_block_1`, `deprecated_purpur_block_2` |

Pillar orientation and slab/stair/wall state do not affect name matching. The
converter also expands Java aliases such as `end_stone_bricks` and
`end_stone_brick_stairs`. The families use separate rules to stay within the path
segment limit. This is a texture rule; placement and fire lifetime follow the game.

Host tests exercise the production world-direction lookup for all 13 IDs and
verify that matching blocks on the other five sides do not activate a below rule.
Converter tests retain the independent End rule, normal fallbacks, and animation
indices. Android tests edit, persist, reopen and export the new field.


## If the texture stays vanilla

Update **both** CTM Forge and the installed Connected Textures Levi mod. Updating
only the app does not replace the installed mod. Reconvert/import the pack, enable
it in the world's resources, and fully close/reopen the world so its chunks rebuild.
The mod's `status.txt` starts with its version and reports whether biome lookup is
available. It records the first matching and nonmatching biome/neighbor condition,
including the actual names read from the game. A missing context or malformed path
is reported separately. These lines are bounded to avoid logging every block.

The inspected `purple-end-fire.zip` contains two animated `fixed` rules for
`fire_0` and `fire_1`, limited to the End biomes; it contains no neighbor rules.
Both 32-frame timelines are retained, including fire_0's frame-16 start. Build 11
also registers vanilla fire animations on each fallback alias, and expands the
old `side` key before replacing faces. Test the converted pack with fire in the
End and the Overworld: only the End should use the purple animation.

## Build 12: purple fire outside the End

The 26.60 atlas loader filters each variations list to the highest-priority pack
containing an image. A list containing a vanilla fallback and a local purple sheet
therefore loses the fallback. The flipbook loader clamps an out-of-range variant
index to zero, so the purple animation then paints the only remaining slot.
This happens before the mod evaluates the biome rule.

Both converters now place each required fallback in a private `ctm/fallback/`
path inside the output pack. Static source-pack overrides take precedence over
bundled vanilla images. Animated fallbacks receive their own strip, original
frame order and timing, and variation-zero flipbook. Neither `fire_0`/`fire_1`
nor soul-fire image paths are globally replaced by a conditional-only pack.

The loading behavior was inspected in the supplied 26.60 arm64 binary:
`TextureAtlas::_addElementCollection` at `0xb73e250` queries
`ResourcePackLoader::getPackStackIndexOfResource`, keeps the minimum stack index
at `0xb73e3ec`, and removes other variants at `0xb73e534`.
Flipbook registration at `0xb77ca70` bounds-checks `atlas_tile_variant` and
falls back to zero. Tests assert all variations resolve within the output pack,
the two fire layers keep separate slots, and the normal animation strips match
the pinned vanilla source. In-game End/Overworld validation is still required.
