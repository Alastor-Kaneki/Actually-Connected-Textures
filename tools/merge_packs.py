#!/usr/bin/env python3
"""Merge converted CTM Bedrock packs (made by ctm_convert.py / CTM Forge) into one pack.

Packs are given highest priority first. For every block, each face takes the
connected-texture alias of the highest-priority pack that converts that face; faces
no pack converts keep the vanilla texture. Only the tile sheets the merged
blocks.json actually uses are copied, so losing packs don't fill the texture atlas.

Usage: merge_packs.py OUT.mcpack NAME SLUG DESCRIPTION PACK1.mcpack [PACK2.mcpack ...]
"""
import copy
import io
import json
import sys
import uuid
import zipfile

from PIL import Image

FACES = ("down", "up", "north", "south", "west", "east")
SIDES = {"north", "south", "west", "east"}


def faces_of(tex):
    """blocks.json textures (string or dict, maybe with "side") -> {face: alias}."""
    if isinstance(tex, str):
        return {f: tex for f in FACES}
    out = {}
    for f in FACES:
        v = tex.get(f, tex.get("side") if f in SIDES else None)
        if v is not None:
            out[f] = v
    return out


def is_ctm(alias):
    return isinstance(alias, str) and alias.startswith("ctm_")


def paths_of(entry):
    """Texture file paths (no extension) a terrain_texture entry refers to."""
    t = entry.get("textures")
    if isinstance(t, str):
        return [t]
    if isinstance(t, dict) and "variations" in t:
        return [v["path"] for v in t["variations"]]
    if isinstance(t, list):
        return [x if isinstance(x, str) else x["path"] for x in t]
    if isinstance(t, dict) and "path" in t:
        return [t["path"]]
    return []


def main():
    out_path, name, slug, description, *inputs = sys.argv[1:]
    packs = []
    for p in inputs:
        z = zipfile.ZipFile(p)
        packs.append({
            "path": p,
            "zip": z,
            "blocks": json.loads(z.read("blocks.json")),
            "terrain": json.loads(z.read("textures/terrain_texture.json"))["texture_data"],
            "manifest": json.loads(z.read("manifest.json")),
        })

    merged_blocks = {"format_version": "1.21.120"}
    used_aliases = {}  # alias -> pack index
    winners = {}       # block -> {face: pack index}
    all_blocks = []
    for pk in packs:
        for b in pk["blocks"]:
            if b != "format_version" and b not in all_blocks:
                all_blocks.append(b)

    for b in all_blocks:
        holders = [i for i, pk in enumerate(packs) if b in pk["blocks"]]
        top = holders[0]
        entry = copy.deepcopy(packs[top]["blocks"][b])
        base_faces = faces_of(entry["textures"])
        faces = dict(base_faces)
        who = {}
        for f in FACES:
            for i in holders:
                a = faces_of(packs[i]["blocks"][b]["textures"]).get(f)
                if is_ctm(a):
                    faces[f] = a
                    who[f] = i
                    break
        for f, i in who.items():
            used_aliases[faces[f]] = i
        values = [faces[f] for f in FACES if f in faces]
        entry["textures"] = values[0] if len(set(values)) == 1 and len(values) == 6 else {f: faces[f] for f in FACES if f in faces}
        if who:
            entry.pop("isotropic", None)
        merged_blocks[b] = entry
        winners[b] = who

    files = {}
    terrain = {}
    for alias, i in used_aliases.items():
        pk = packs[i]
        if alias not in pk["terrain"]:
            raise SystemExit(f"{pk['path']}: alias {alias} has no terrain texture")
        terrain[alias] = pk["terrain"][alias]
        for p in paths_of(pk["terrain"][alias]):
            files[p + ".png"] = pk["zip"].read(p + ".png")

    # plain texture overrides (glass, obsidian, ...): highest priority wins
    for pk in reversed(packs):
        for n in pk["zip"].namelist():
            if n.startswith("textures/blocks/") and not n.startswith("textures/blocks/ctm/") and n.endswith(".png"):
                files[n] = pk["zip"].read(n)

    # icon: the first four packs' icons in a 2x2 grid
    icons = []
    for pk in packs:
        if "pack_icon.png" in pk["zip"].namelist() and len(icons) < 4:
            icons.append(Image.open(io.BytesIO(pk["zip"].read("pack_icon.png"))).convert("RGBA"))
    if icons:
        canvas = Image.new("RGBA", (256, 256), (0, 0, 0, 0))
        for k, im in enumerate(icons):
            resample = Image.NEAREST if im.width <= 64 else Image.LANCZOS
            canvas.paste(im.resize((128, 128), resample), ((k % 2) * 128, (k // 2) * 128))
        b = io.BytesIO()
        canvas.save(b, "PNG", optimize=True)
        files["pack_icon.png"] = b.getvalue()

    ns = uuid.uuid5(uuid.NAMESPACE_URL, f"https://example.invalid/ctm-merge/{slug}")
    files["manifest.json"] = json.dumps({
        "format_version": 2,
        "header": {
            "name": name,
            "description": description,
            "uuid": str(uuid.uuid5(ns, "header")),
            "version": [1, 0, 0],
            "min_engine_version": [1, 21, 120],
        },
        "modules": [{"type": "resources", "uuid": str(uuid.uuid5(ns, "resources")), "version": [1, 0, 0]}],
    }, indent=1).encode()
    files["blocks.json"] = json.dumps(merged_blocks, indent=1).encode()
    files["textures/terrain_texture.json"] = json.dumps({
        "resource_pack_name": slug, "texture_name": "atlas.terrain", "padding": 8, "num_mip_levels": 4,
        "texture_data": terrain,
    }, indent=1).encode()

    with zipfile.ZipFile(out_path, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for k in sorted(files):
            z.writestr(k, files[k])

    report = {"blocks": len(all_blocks), "aliases": len(terrain), "files": len(files), "per_pack": {}}
    for i, pk in enumerate(packs):
        won = sorted(b for b, who in winners.items() if i in who.values())
        report["per_pack"][pk["manifest"]["header"]["name"]] = {"blocks_won": len(won), "blocks": won}
    print(json.dumps(report, indent=1))


if __name__ == "__main__":
    main()
