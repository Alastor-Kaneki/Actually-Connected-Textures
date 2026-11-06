#!/usr/bin/env python3
"""Build converter/data/java_26.3.json: how Java Edition block textures and block ids map
onto Bedrock ones.

Java and Bedrock name most block textures differently ("acacia_planks" vs "planks_acacia",
"nether_portal" vs "portal"), and some block ids differ too ("sea_lantern" vs "seaLantern").
The converter needs both directions to find the Bedrock blocks and faces a Java texture
ends up on. This script pairs every Java block's model faces with the same block's
blocks.json faces and lets the pairs vote, then adds a few hand-checked fixes.

Inputs (not shipped): a Java client asset tree (assets/minecraft/{blockstates,models/block})
and the converter's vanilla_26.60.json. Only names are written out, no game assets.

Usage: build_java_map.py <java assets root> <vanilla_26.60.json> <out.json> [texture list]
"""
from __future__ import annotations

import collections
import difflib
import json
import re
import sys
from pathlib import Path

FACES = ("down", "up", "north", "south", "west", "east")
SIDES = ("north", "south", "west", "east")

# Java block id -> Bedrock block id where the names differ and the model faces can't tell.
BLOCK_FIXES = {
    "grass_block": "grass",
    "nether_portal": "portal",
    "sea_lantern": "seaLantern",
    "fire": "fire",
    "soul_fire": "soul_fire",
    "water": "water",
    "lava": "lava",
    "jack_o_lantern": "lit_pumpkin",
    "note_block": "noteblock",
    "nether_quartz_ore": "quartz_ore",
    "redstone_lamp": "redstone_lamp",
    "snow_block": "snow",
    "snow": "snow_layer",
    "terracotta": "hardened_clay",
    "dirt_path": "grass_path",
    "magma_block": "magma",
    "nether_wart_block": "nether_wart_block",
    "red_nether_bricks": "red_nether_brick",
    "nether_bricks": "nether_brick",
    "bricks": "brick_block",
    "slime_block": "slime",
    "cobweb": "web",
    "spawner": "mob_spawner",
    "melon": "melon_block",
    "end_stone_bricks": "end_bricks",
    "prismarine_bricks": "prismarine_bricks",
    "rooted_dirt": "dirt_with_roots",
    "sugar_cane": "reeds",
    "lily_pad": "waterlily",
    "powered_rail": "golden_rail",
    "cauldron": "cauldron",
    "repeater": "unpowered_repeater",
    "comparator": "unpowered_comparator",
    "tripwire": "trip_wire",
    "tripwire_hook": "tripwire_hook",
    "moving_piston": "moving_block",
    "piston_head": "piston_arm_collision",
    "light": "light_block",
    "dead_bush": "deadbush",
    "trial_spawner": "trial_spawner",
    "chiseled_quartz_block": "chiseled_quartz_block",
    "quartz_block": "quartz_block",
    "smooth_quartz": "smooth_quartz",
}

# Java texture -> Bedrock texture path, checked by hand (animated blocks first).
TEXTURE_FIXES = {
    "nether_portal": "textures/blocks/portal",
    "fire_0": "textures/blocks/fire_0",
    "fire_1": "textures/blocks/fire_1",
    "soul_fire_0": "textures/blocks/soul_fire_0",
    "soul_fire_1": "textures/blocks/soul_fire_1",
    "water_still": "textures/blocks/water_still_grey",
    "water_flow": "textures/blocks/water_flow_grey",
    "lava_still": "textures/blocks/lava_still",
    "lava_flow": "textures/blocks/lava_flow",
    "campfire_fire": "textures/blocks/campfire",
    "soul_campfire_fire": "textures/blocks/soul_campfire",
    "campfire_log_lit": "textures/blocks/campfire_log_lit",
    "soul_campfire_log_lit": "textures/blocks/soul_campfire_log_lit",
    "prismarine": "textures/blocks/prismarine_rough",
    "sea_lantern": "textures/blocks/sea_lantern",
    "magma": "textures/blocks/magma",
    "stonecutter_saw": "textures/blocks/stonecutter2_saw",
    "crimson_stem": "textures/blocks/huge_fungus/crimson_log_side",
    "warped_stem": "textures/blocks/huge_fungus/warped_stem_side",
    "kelp": "textures/blocks/kelp_top",
    "kelp_plant": "textures/blocks/kelp_a",
    "seagrass": "textures/blocks/seagrass",
    "tall_seagrass_bottom": "textures/blocks/seagrass_doubletall_bottom_a",
    "tall_seagrass_top": "textures/blocks/seagrass_doubletall_top_a",
    "respawn_anchor_top": "textures/blocks/respawn_anchor_top",
    "command_block_front": "textures/blocks/command_block_front_mipmap",
    "command_block_back": "textures/blocks/command_block_back_mipmap",
    "command_block_side": "textures/blocks/command_block_side_mipmap",
    "command_block_conditional": "textures/blocks/command_block_conditional_mipmap",
    "chain_command_block_front": "textures/blocks/chain_command_block_front_mipmap",
    "chain_command_block_back": "textures/blocks/chain_command_block_back_mipmap",
    "chain_command_block_side": "textures/blocks/chain_command_block_side_mipmap",
    "chain_command_block_conditional": "textures/blocks/chain_command_block_conditional_mipmap",
    "repeating_command_block_front": "textures/blocks/repeating_command_block_front_mipmap",
    "repeating_command_block_back": "textures/blocks/repeating_command_block_back_mipmap",
    "repeating_command_block_side": "textures/blocks/repeating_command_block_side_mipmap",
    "repeating_command_block_conditional": "textures/blocks/repeating_command_block_conditional_mipmap",
    "fletching_table_front": "textures/blocks/fletcher_table_side1",
    "fletching_table_side": "textures/blocks/fletcher_table_side2",
    "blast_furnace_front_on": "textures/blocks/blast_furnace_front_on",
    "smoker_front_on": "textures/blocks/smoker_front_on",
    "lantern": "textures/blocks/lantern",
    "soul_lantern": "textures/blocks/soul_lantern",
    "copper_lantern": "textures/blocks/copper_lantern",
    "exposed_copper_lantern": "textures/blocks/exposed_copper_lantern",
    "weathered_copper_lantern": "textures/blocks/weathered_copper_lantern",
    "oxidized_copper_lantern": "textures/blocks/oxidized_copper_lantern",
    "sculk": "textures/blocks/sculk",
    "sculk_vein": "textures/blocks/sculk_vein",
    "sculk_catalyst_side_bloom": "textures/blocks/sculk_catalyst_side_bloom",
    "sculk_catalyst_top_bloom": "textures/blocks/sculk_catalyst_top_bloom",
    "sculk_sensor_tendril_active": "textures/blocks/sculk_sensor_tendril_active",
    "sculk_sensor_tendril_inactive": "textures/blocks/sculk_sensor_tendril_inactive",
    "sculk_shrieker_inner_top": "textures/blocks/sculk_shrieker_inner_top",
    "sculk_shrieker_can_summon_inner_top": "textures/blocks/sculk_shrieker_can_summon_inner_top",
    "firefly_bush_emissive": "textures/blocks/firefly_bush_firefly",
    "glass_pane_top": "textures/blocks/glass_pane_top",
    "creaking_heart": "textures/blocks/creaking_heart_side_inactive",
    "creaking_heart_dormant": "textures/blocks/creaking_heart_side_dormant",
    "creaking_heart_awake": "textures/blocks/creaking_heart_side_active",
    "creaking_heart_top": "textures/blocks/creaking_heart_top_inactive",
    "creaking_heart_top_dormant": "textures/blocks/creaking_heart_top_dormant",
    "creaking_heart_top_awake": "textures/blocks/creaking_heart_top_active",
}
_COLORS = ["white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
           "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"]
for _c in _COLORS:
    _b = "silver" if _c == "light_gray" else _c
    TEXTURE_FIXES[f"{_c}_stained_glass"] = f"textures/blocks/glass_{_b}"
    TEXTURE_FIXES[f"{_c}_stained_glass_pane_top"] = f"textures/blocks/glass_pane_top_{_b}"


# Vanilla parent models the converter knows the faces of (keep in sync with ctm_convert.py).
TEMPLATES = {
    "block/cube", "block/cube_all", "block/cube_column", "block/cube_column_horizontal",
    "block/cube_column_mirrored", "block/cube_column_uv_locked_x", "block/cube_column_uv_locked_y",
    "block/cube_column_uv_locked_z", "block/cube_bottom_top", "block/cube_top", "block/cube_mirrored",
    "block/cube_mirrored_all", "block/cube_north_west_mirrored_all", "block/cube_directional",
    "block/orientable", "block/orientable_with_bottom", "block/orientable_vertical", "block/leaves",
    "block/slab", "block/slab_top", "block/stairs", "block/inner_stairs", "block/outer_stairs",
    "block/template_glass_pane_post", "block/template_glass_pane_side", "block/template_glass_pane_side_alt",
    "block/template_glass_pane_noside", "block/template_glass_pane_noside_alt",
}


def model_id(ref: str) -> str:
    ns, path = ref.split(":", 1) if ":" in ref else ("minecraft", ref)
    if "/" not in path:
        path = "block/" + path
    return path if ns == "minecraft" else f"{ns}:{path}"


def strip_ns(ref: str) -> str:
    ref = ref.split(":", 1)[1] if ":" in ref else ref
    return ref[len("block/"):] if ref.startswith("block/") else ref


class Models:
    def __init__(self, root: Path):
        self.dir = root / "assets/minecraft/models"
        self.cache: dict[str, dict] = {}

    def raw(self, ref: str) -> dict | None:
        name = ref.split(":", 1)[1] if ":" in ref else ref
        p = self.dir / f"{name}.json"
        if not p.is_file():
            return None
        return json.loads(p.read_text(encoding="utf-8"))

    def resolve(self, ref: str) -> tuple[dict, list]:
        """-> (textures, elements) with parents applied."""
        if ref in self.cache:
            return self.cache[ref]
        m = self.raw(ref) or {}
        tex: dict = {}
        elements: list = []
        parent = m.get("parent")
        if parent and not parent.startswith("builtin/"):
            ptex, pel = self.resolve(parent)
            tex.update(ptex)
            elements = pel
        tex.update(m.get("textures", {}))
        if "elements" in m:
            elements = m["elements"]
        self.cache[ref] = (tex, elements)
        return tex, elements


def resolve_tex(tex: dict, ref, depth: int = 0) -> str | None:
    if isinstance(ref, dict):  # 26.x: {"sprite": "...", "force_translucent": true}
        ref = ref.get("sprite", "")
    while isinstance(ref, str) and ref.startswith("#") and depth < 16:
        ref = tex.get(ref[1:], "")
        if isinstance(ref, dict):
            ref = ref.get("sprite", "")
        depth += 1
    return strip_ns(ref) if isinstance(ref, str) and ref and not ref.startswith("#") else None


def model_faces(models: Models, ref: str) -> dict[str, str]:
    """face -> Java texture name, taking the biggest element face per direction."""
    tex, elements = models.resolve(ref)
    best: dict[str, tuple[float, str]] = {}
    for el in elements:
        f0, t0 = el.get("from", [0, 0, 0]), el.get("to", [16, 16, 16])
        size = [abs(t0[i] - f0[i]) for i in range(3)]
        area = {"down": size[0] * size[2], "up": size[0] * size[2], "north": size[0] * size[1],
                "south": size[0] * size[1], "west": size[2] * size[1], "east": size[2] * size[1]}
        for face, fd in el.get("faces", {}).items():
            name = resolve_tex(tex, fd.get("texture", ""))
            if name and face in area and area[face] > best.get(face, (-1, ""))[0]:
                best[face] = (area[face], name)
    return {f: v[1] for f, v in best.items()}


def default_models(state: dict) -> list[str]:
    out = []
    if "variants" in state:
        for _, v in list(state["variants"].items())[:1]:
            for m in v if isinstance(v, list) else [v]:
                out.append(m["model"])
    for part in state.get("multipart", [])[:1]:
        a = part.get("apply")
        for m in a if isinstance(a, list) else [a]:
            if m:
                out.append(m["model"])
    return out


def all_models(state: dict) -> list[str]:
    out = []
    for v in state.get("variants", {}).values():
        for m in v if isinstance(v, list) else [v]:
            out.append(m["model"])
    for part in state.get("multipart", []):
        a = part.get("apply")
        for m in a if isinstance(a, list) else [a]:
            if m:
                out.append(m["model"])
    return out


def main() -> int:
    java = Path(sys.argv[1])
    vanilla = json.loads(Path(sys.argv[2]).read_text(encoding="utf-8"))
    out = Path(sys.argv[3])
    bblocks: dict = vanilla["blocks"]
    terrain: dict = vanilla["terrain_textures"]

    def alias_paths(alias) -> list[str]:
        t = terrain.get(alias)
        if isinstance(t, str):
            return [t]
        if isinstance(t, list):
            return [x if isinstance(x, str) else x.get("path", "") for x in t]
        if isinstance(t, dict):
            return [x.get("path", "") for x in t.get("variations", [])]
        return []

    def bedrock_faces(block: str) -> dict[str, list[str]]:
        e = bblocks.get(block) or {}
        t = e.get("textures")
        res: dict[str, list[str]] = {}
        if isinstance(t, str):
            for f in FACES:
                res[f] = alias_paths(t)
        elif isinstance(t, dict):
            for f in FACES:
                a = t.get(f) or (t.get("side") if f in SIDES else None)
                if a:
                    res[f] = alias_paths(a)
        return res

    all_paths = {p for a in terrain for p in alias_paths(a) if p}
    models = Models(java)
    states = {p.stem: json.loads(p.read_text(encoding="utf-8"))
              for p in sorted((java / "assets/minecraft/blockstates").glob("*.json"))}
    if len(sys.argv) > 4:  # list of texture file paths (e.g. `git ls-tree` output of a sparse checkout)
        java_textures = sorted({Path(l.strip()).name[:-4] for l in Path(sys.argv[4]).read_text().splitlines()
                                if l.strip().endswith(".png")})
    else:
        java_textures = sorted(p.stem for p in (java / "assets/minecraft/textures/block").glob("*.png")) or None

    jfaces = {b: model_faces(models, (default_models(s) or [""])[0]) for b, s in states.items()}
    jtex_by_block = {}
    for b, s in states.items():
        names = set()
        for m in all_models(s):
            tex, _ = models.resolve(m)
            for k in tex:
                n = resolve_tex(tex, "#" + k)
                if n:
                    names.add(n)
        jtex_by_block[b] = names

    # ---- block ids
    block_map: dict[str, str] = {}
    for b in states:
        if b in BLOCK_FIXES and BLOCK_FIXES[b] in bblocks:
            block_map[b] = BLOCK_FIXES[b]
        elif b in bblocks:
            block_map[b] = b

    # ---- texture votes from blocks that exist in both editions
    votes: dict[str, collections.Counter] = collections.defaultdict(collections.Counter)
    for jb, bb in block_map.items():
        jf, bf = jfaces.get(jb, {}), bedrock_faces(bb)
        if not jf or not bf:
            continue
        for f in ("up", "down"):
            if f in jf and bf.get(f):
                votes[jf[f]][bf[f][0]] += 2
        jside = [jf[f] for f in SIDES if f in jf]
        bside = [bf[f][0] for f in SIDES if bf.get(f)]
        if not jside or not bside:
            continue
        if len(set(jside)) == 1 and len(set(bside)) == 1:
            votes[jside[0]][bside[0]] += 2
        else:  # directional block: pair side textures by name similarity
            for jt in sorted(set(jside)):  # sorted: ties go the same way on every run
                best = max(sorted(set(bside)), key=lambda p: difflib.SequenceMatcher(None, jt, p.rsplit("/", 1)[-1]).ratio())
                votes[jt][best] += 1

    tex_map: dict[str, str] = {}
    names = set(java_textures or []) | set(votes) | set(TEXTURE_FIXES)
    for n in sorted(names):
        if n in TEXTURE_FIXES:
            tex_map[n] = TEXTURE_FIXES[n]
        elif f"textures/blocks/{n}" in all_paths:
            tex_map[n] = f"textures/blocks/{n}"
        elif n in votes:
            cand = votes[n].most_common()
            top = cand[0][1]
            best = max((p for p, v in cand if v == top),
                       key=lambda p: difflib.SequenceMatcher(None, n, p.rsplit("/", 1)[-1]).ratio())
            tex_map[n] = best
    for k in sorted(TEXTURE_FIXES):
        if TEXTURE_FIXES[k] not in all_paths:
            print(f"warning: fix {k} -> {TEXTURE_FIXES[k]} is not a vanilla atlas texture", file=sys.stderr)
    tex_map = {k: v for k, v in tex_map.items() if v in all_paths}

    # ---- Java ids that are missing on Bedrock: find the Bedrock block with the same textures
    for jb in states:
        if jb in block_map:
            continue
        mine = {tex_map[t] for t in jfaces.get(jb, {}).values() if t in tex_map}
        if not mine:
            continue
        hits = [bb for bb in bblocks if bb not in block_map.values()
                and {p for ps in bedrock_faces(bb).values() for p in ps[:1]} == mine]
        if len(hits) == 1:
            block_map[jb] = hits[0]

    # ---- vanilla block models -> the template they are built on
    parents: dict[str, str] = {}
    for p in sorted((java / "assets/minecraft/models/block").glob("*.json")):
        mid, seen = f"block/{p.stem}", set()
        cur = mid
        while cur and cur not in TEMPLATES and cur not in seen:
            seen.add(cur)
            raw = models.raw(cur) or {}
            cur = model_id(raw["parent"]) if raw.get("parent") and not raw["parent"].startswith("builtin/") else None
        if cur in TEMPLATES and mid not in TEMPLATES:
            parents[mid[len("block/"):]] = cur[len("block/"):]

    data = {
        "source": "Java 26.3 blockstates/models paired with Bedrock 1.26.60 blocks.json (tools/build_java_map.py)",
        "textures": dict(sorted(tex_map.items())),
        "blocks": {k: v for k, v in sorted(block_map.items()) if k != v},
        "model_parents": dict(sorted(parents.items())),
    }
    out.write_text(json.dumps(data, indent=1, sort_keys=False) + "\n", encoding="utf-8")
    print(f"{len(tex_map)} textures, {len(data['blocks'])} renamed blocks, {len(block_map)} blocks in both editions")
    missing = sorted(n for n in (java_textures or []) if n not in tex_map)
    print(f"{len(missing)} Java textures without a Bedrock match, e.g. {missing[:40]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
