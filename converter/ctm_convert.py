#!/usr/bin/env python3
"""Convert Java Edition OptiFine/MCPatcher CTM resource packs into Bedrock
resource packs that work with the "Connected Textures" LeviLauncher mod.

How the output works
--------------------
Every block a CTM rule targets gets its own texture alias ``ctm_<slug>_<block>``
whose terrain texture is a weighted *variations* list:

    [ base texture (weight 1000000), tile sheet (weight 0.05) ]

Without the mod the game practically always picks the base texture, so the pack
degrades to ordinary glass. With the mod, the game's texture lookup is
redirected to the tile sheet while chunks are built, and the mod picks the
right tile per face. All tiles of a rule live in one sheet image whose path
tells the mod how to read it:

    textures/blocks/ctm/<method>-<cols>x<rows>-<count>[-s]/<slug>_<block>.png

``-s`` means innerSeams=true. Supported methods: ctm (47 tiles; ctm_compact
is expanded to 47 here), horizontal, vertical, hv, vh, top, random, repeat,
fixed. Overlay methods are skipped.

``--direct`` writes a fallback layout where the block texture is the sheet
itself (no base texture). Use it only if the normal layout doesn't connect.

Usage:
    ctm_convert.py <java pack .zip or folder> <out.mcpack> [--name NAME] [--slug SLUG] [--direct]
"""
from __future__ import annotations

import argparse
import io
import json
import math
import os
import re
import sys
import tempfile
import uuid
import zipfile
from dataclasses import dataclass, field
from pathlib import Path

from PIL import Image

try:
    from . import fusion
except ImportError:
    import fusion

HERE = Path(__file__).resolve().parent
VANILLA = json.loads((HERE / "data" / "vanilla_26.60.json").read_text(encoding="utf-8"))
VANILLA_BLOCKS: dict = VANILLA["blocks"]

# OptiFine 47-tile lookup. Index bits: L=1 DL=2 D=4 DR=8 R=16 UR=32 U=64 UL=128,
# where L/R/U/D are the texture's left/right/top/bottom as seen on the face.
# Derived from tile artwork (see tools/derive_ctm47.py); matches the standard template.
CTM47 = [
    0, 3, 0, 3, 12, 5, 12, 15, 0, 3, 0, 3, 12, 5, 12, 15, 1, 2, 1, 2, 4, 7, 4, 29, 1, 2, 1, 2, 13, 31, 13, 14,
    0, 3, 0, 3, 12, 5, 12, 15, 0, 3, 0, 3, 12, 5, 12, 15, 1, 2, 1, 2, 4, 7, 4, 29, 1, 2, 1, 2, 13, 31, 13, 14,
    36, 17, 36, 17, 24, 19, 24, 43, 36, 17, 36, 17, 24, 19, 24, 43, 16, 18, 16, 18, 6, 46, 6, 21, 16, 18, 16, 18, 28, 9, 28, 22,
    36, 17, 36, 17, 24, 19, 24, 43, 36, 17, 36, 17, 24, 19, 24, 43, 37, 40, 37, 40, 30, 8, 30, 34, 37, 40, 37, 40, 25, 23, 25, 45,
    0, 3, 0, 3, 12, 5, 12, 15, 0, 3, 0, 3, 12, 5, 12, 15, 1, 2, 1, 2, 4, 7, 4, 29, 1, 2, 1, 2, 13, 31, 13, 14,
    0, 3, 0, 3, 12, 5, 12, 15, 0, 3, 0, 3, 12, 5, 12, 15, 1, 2, 1, 2, 4, 7, 4, 29, 1, 2, 1, 2, 13, 31, 13, 14,
    36, 39, 36, 39, 24, 41, 24, 27, 36, 39, 36, 39, 24, 41, 24, 27, 16, 42, 16, 42, 6, 20, 6, 10, 16, 42, 16, 42, 28, 35, 28, 44,
    36, 39, 36, 39, 24, 41, 24, 27, 36, 39, 36, 39, 24, 41, 24, 27, 37, 38, 37, 38, 30, 11, 30, 32, 37, 38, 37, 38, 25, 33, 25, 26,
]

FACE_NAMES = ("down", "up", "north", "south", "west", "east")
OPTIFINE_FACES = {
    "bottom": {"down"}, "top": {"up"}, "north": {"north"}, "south": {"south"},
    "east": {"east"}, "west": {"west"}, "sides": {"north", "south", "east", "west"},
    "all": set(FACE_NAMES),
}
COLORS = ["white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
          "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"]
BEDROCK_COLOR = {c: ("silver" if c == "light_gray" else c) for c in COLORS}


def java_to_bedrock_texture(name: str) -> str | None:
    """Java block texture name -> Bedrock texture path (without extension)."""
    if name in ("glass", "glass_pane_top", "tinted_glass"):
        return f"textures/blocks/{name}"
    m = re.fullmatch(r"(\w+?)_stained_glass(_pane_top)?", name)
    if m and m.group(1) in BEDROCK_COLOR:
        c = BEDROCK_COLOR[m.group(1)]
        return f"textures/blocks/glass_pane_top_{c}" if m.group(2) else f"textures/blocks/glass_{c}"
    return None


def converted_block_texture(name: str, converted: dict) -> str | None:
    """Bedrock texture path for a converted block whose vanilla texture is one plain file."""
    if name not in converted:
        return None
    alias = VANILLA_BLOCKS.get(name, {}).get("textures")
    path = VANILLA["terrain_textures"].get(alias) if isinstance(alias, str) else None
    return path if isinstance(path, str) else None


def main_java_texture(block: str) -> str:
    """Java texture a block's main faces use (panes use their glass texture)."""
    return block[: -len("_pane")] if block.endswith("_pane") else block


def is_pane(block: str) -> bool:
    return block.endswith("glass_pane")


# --------------------------------------------------------------------------- pack I/O

class JavaPack:
    def __init__(self, src: Path):
        self.files: dict[str, bytes] = {}
        if src.is_dir():
            for p in src.rglob("*"):
                if p.is_file():
                    self.files[p.relative_to(src).as_posix()] = p.read_bytes()
        else:
            with zipfile.ZipFile(src) as z:
                for info in z.infolist():
                    if info.is_dir():
                        continue
                    name = info.filename.replace("\\", "/")
                    parts = [x for x in name.split("/") if x not in ("", ".", "..")]
                    if parts:
                        self.files["/".join(parts)] = z.read(info)
        roots = sorted({k[: -len("pack.mcmeta")] for k in self.files if k.endswith("pack.mcmeta")}, key=len)
        self.root = roots[0] if roots else ""
        self.files = {k[len(self.root):]: v for k, v in self.files.items() if k.startswith(self.root)}

    def get(self, path: str) -> bytes | None:
        return self.files.get(path)

    def mcmeta(self) -> dict:
        try:
            return json.loads(self.files.get("pack.mcmeta", b"{}").decode("utf-8-sig"))
        except Exception:
            return {}


def plain_description(desc) -> str:
    if isinstance(desc, list):
        desc = "".join(plain_description(d) for d in desc)
    elif isinstance(desc, dict):
        desc = (desc.get("text") or "") + "".join(plain_description(d) for d in desc.get("extra", []))
    return re.sub(r"§.", "", str(desc or "")).strip()


def load_image(data: bytes) -> Image.Image:
    im = Image.open(io.BytesIO(data)).convert("RGBA")
    if im.height > im.width and im.height % im.width == 0:  # animated strip: first frame
        im = im.crop((0, 0, im.width, im.width))
    return im


# --------------------------------------------------------------------------- rules

@dataclass
class Rule:
    path: str
    props: dict
    method: str
    blocks: list[str]
    faces: set[str]
    tiles: list[Image.Image] = field(default_factory=list)
    inner_seams: bool = False
    weights: list[int] = field(default_factory=list)
    width: int = 1
    height: int = 1
    pack: "JavaPack | None" = None
    fusion_rule: "fusion.Rule | None" = None


def parse_properties(text: str) -> dict:
    props = {}
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith(("#", "!")) or "=" not in line:
            continue
        k, v = line.split("=", 1)
        props[k.strip()] = v.strip()
    return props


def expand_tile_list(spec: str) -> list[str]:
    out = []
    for tok in spec.split():
        m = re.fullmatch(r"(\d+)-(\d+)", tok)
        if m:
            a, b = int(m.group(1)), int(m.group(2))
            out += [str(i) for i in (range(a, b + 1) if a <= b else range(a, b - 1, -1))]
        else:
            out.append(tok)
    return out


def resolve_tile(pack: JavaPack, rule_dir: str, namespace: str, tok: str, base_img) -> Image.Image | None:
    if tok in ("<default>", "<skip>"):
        return base_img
    name = tok[:-4] if tok.endswith(".png") else tok
    cands = []
    if ":" in name:
        ns, rest = name.split(":", 1)
        cands.append(f"assets/{ns}/textures/{rest}.png")
    elif "/" in name:
        cands += [f"assets/{namespace}/{name}.png", f"assets/minecraft/{name}.png",
                  f"{rule_dir}/{name}.png", f"assets/{namespace}/textures/{name}.png"]
    else:
        cands += [f"{rule_dir}/{name}.png", f"assets/minecraft/textures/block/{name}.png"]
    for c in cands:
        data = pack.get(c)
        if data is not None:
            return load_image(data)
    return None


def block_names(spec: str) -> list[str]:
    out = []
    for tok in spec.split():
        parts = tok.split(":")
        if len(parts) >= 2 and parts[0] == "minecraft":
            parts = parts[1:]
        elif len(parts) >= 2 and "=" not in parts[1]:
            continue  # other namespace (mod block)
        out.append(parts[0])
    return out


def read_rules(pack: JavaPack, log: list[str]) -> list[Rule]:
    rules = []
    paths = sorted(p for p in pack.files if p.endswith(".properties")
                   and re.match(r"assets/[^/]+/(optifine|mcpatcher)/ctm/", p))
    for path in paths:
        namespace = path.split("/")[1]
        rule_dir = path.rsplit("/", 1)[0]
        props = parse_properties(pack.files[path].decode("utf-8", "replace"))
        method = props.get("method", "ctm").lower().replace("horizontal+vertical", "hv").replace("vertical+horizontal", "vh")
        if method.startswith("overlay"):
            log.append(f"skip {path}: overlay methods are not supported")
            continue
        blocks = block_names(props.get("matchBlocks", ""))
        if not blocks and props.get("matchTiles"):
            blocks = [t.rsplit("/", 1)[-1] for t in props["matchTiles"].split()]
        if not blocks:
            stem = path.rsplit("/", 1)[-1][:-len(".properties")]
            if stem.startswith("block_"):
                blocks = [stem[len("block_"):]]
        faces = set()
        for f in re.split(r"[\s,]+", props.get("faces", "all")):  # OptiFine allows spaces or commas
            faces |= OPTIFINE_FACES.get(f.lower(), set())
        for unsupported in ("biomes", "heights", "minHeight", "maxHeight", "metadata", "name"):
            if unsupported in props:
                log.append(f"note {path}: '{unsupported}' is ignored")
        r = Rule(path, props, method, blocks, faces or set(FACE_NAMES), pack=pack)
        connect = props.get("connect", "block").lower()
        if connect not in ("block", ""):
            log.append(f"note {path}: connect={connect} is treated as connect=block")
        r.inner_seams = props.get("innerSeams", "false").lower() == "true"
        base_img = None
        bt = java_main_texture_image(pack, blocks[0]) if blocks else None
        if bt is not None:
            base_img = bt
        toks = expand_tile_list(props.get("tiles", ""))
        tiles = [resolve_tile(pack, rule_dir, namespace, t, base_img) for t in toks]
        if any(t is None for t in tiles) or not tiles:
            log.append(f"skip {path}: missing tile images")
            continue
        r.tiles = tiles
        if method == "random":
            w = [int(x) for x in props.get("weights", "").split() if x.isdigit()]
            r.weights = w if len(w) == len(tiles) else [1] * len(tiles)
        if method == "repeat":
            r.width, r.height = int(props.get("width", 1)), int(props.get("height", 1))
        rules.append(r)
    for f in fusion.read_rules(pack, log):
        rules.append(Rule(f.path, {}, "fusion_" + f.layout, [f.block], f.faces, pack=pack, fusion_rule=f))
    return rules


def java_main_texture_image(pack: JavaPack, block: str):
    path = f"assets/minecraft/textures/block/{main_java_texture(block)}.png"
    if fusion.has_fusion(pack.get(path + ".mcmeta") or b"{}"):
        return None  # A Fusion sheet is never an OptiFine default/base tile.
    data = pack.get(path)
    return load_image(data) if data else None


# --------------------------------------------------------------------------- sheets

def compact_to_47(c: list[Image.Image]) -> list[Image.Image]:
    """Build the 47 standard tiles from the 5 ctm_compact tiles (OptiFine/Continuity rules)."""
    size = max(t.width for t in c)
    c = [t.resize((size, size), Image.NEAREST) for t in c]
    h = size // 2
    # quadrant q: 0=top-left, 1=bottom-left, 2=bottom-right, 3=top-right; dirs [L, D, R, U]
    boxes = [(0, 0, h, h), (0, h, h, size), (h, h, size, size), (h, 0, size, h)]
    rep = {}
    for s in range(256):
        rep.setdefault(CTM47[s], s)
    out = []
    for idx in range(47):
        s = rep[idx]
        tile = Image.new("RGBA", (size, size))
        for q in range(4):
            i1, i2 = q, (q + 3) % 4
            c1, c2 = (s >> (i1 * 2)) & 1, (s >> (i2 * 2)) & 1
            if c1 and c2:
                k = 1 if (s >> (i2 * 2 + 1)) & 1 else 4
            elif c1:
                k = 3 - q % 2
            elif c2:
                k = 2 + q % 2
            else:
                k = 0
            tile.paste(c[k].crop(boxes[q]), boxes[q][:2])
        out.append(tile)
    return out


def build_sheet(tiles: list[Image.Image], cols: int) -> tuple[Image.Image, int, int]:
    size = max(max(t.width, t.height) for t in tiles)
    rows = math.ceil(len(tiles) / cols)
    sheet = Image.new("RGBA", (cols * size, rows * size), (0, 0, 0, 0))
    for i, t in enumerate(tiles):
        if t.size != (size, size):
            t = t.resize((size, size), Image.NEAREST)
        sheet.paste(t, ((i % cols) * size, (i // cols) * size))
    return sheet, cols, rows


def rule_layout(rule: Rule, log: list[str]):
    """-> (runtime method name, tiles in runtime order, cols) or None."""
    if rule.fusion_rule is not None:
        try:
            return "ctm", fusion.tiles(rule.pack, rule.fusion_rule, CTM47), 8
        except (ValueError, OSError) as e:
            log.append(f"skip {rule.path}: {e}")
            return None
    m = rule.method
    tiles = rule.tiles
    if m == "ctm":
        if len(tiles) < 47:
            log.append(f"skip {rule.path}: ctm needs 47 tiles, found {len(tiles)}")
            return None
        return "ctm", tiles[:47], 8
    if m == "ctm_compact":
        if len(tiles) < 5:
            log.append(f"skip {rule.path}: ctm_compact needs 5 tiles")
            return None
        if any(k.startswith("ctm.") for k in rule.props):
            log.append(f"note {rule.path}: ctm.N replacement tiles are ignored")
        return "ctm", compact_to_47(tiles[:5]), 8
    if m in ("horizontal", "vertical"):
        return (m, tiles[:4], 4) if len(tiles) >= 4 else None
    if m in ("hv", "vh"):
        return (m, tiles[:7], 7) if len(tiles) >= 7 else None
    if m == "top":
        base = java_main_texture_image(rule.pack, rule.blocks[0]) if rule.blocks else None
        if base is None:
            log.append(f"skip {rule.path}: 'top' needs the block's own texture in the pack")
            return None
        return "top", [base, tiles[0]], 2
    if m == "fixed":
        return "fixed", tiles[:1], 1
    if m == "repeat":
        n = rule.width * rule.height
        return ("repeat", tiles[:n], rule.width) if len(tiles) >= n else None
    if m == "random":
        g = 0
        for w in rule.weights:
            g = math.gcd(g, w)
        expanded = []
        for t, w in zip(tiles, rule.weights):
            expanded += [t] * max(1, w // max(g, 1))
        if len(expanded) > 64:
            expanded = tiles
            log.append(f"note {rule.path}: random weights too fine, using equal weights")
        return "random", expanded, min(8, len(expanded))
    log.append(f"skip {rule.path}: unknown method '{m}'")
    return None


# --------------------------------------------------------------------------- output

def slugify(s: str) -> str:
    s = re.sub(r"[^a-z0-9]+", "_", s.lower()).strip("_")
    return s[:40] or "pack"


def convert(src: Path, out: Path, name: str | None, slug: str | None, direct: bool = False) -> dict:
    log: list[str] = []
    pack = JavaPack(src)
    meta = pack.mcmeta().get("pack", {})
    display = name or re.sub(r"[_\-]+", " ", src.stem).strip()
    slug = slug or slugify(display)
    rules = read_rules(pack, log)
    fusion_paths = fusion.texture_paths(pack)

    files: dict[str, bytes] = {}
    terrain: dict[str, dict] = {}
    blocks_json: dict[str, dict] = {"format_version": "1.21.120"}
    assigned: dict[str, set[str]] = {}
    rule_count: dict[str, int] = {}
    summary = {"blocks": {}, "methods": {}}

    def png(im: Image.Image) -> bytes:
        b = io.BytesIO()
        im.save(b, "PNG", optimize=True)
        return b.getvalue()

    for rule in rules:
        layout = rule_layout(rule, log)
        if not layout:
            continue
        method, tiles, cols = layout
        sheet, cols, rows = build_sheet(tiles, cols)
        spec = f"{method}-{cols}x{rows}-{len(tiles)}" + ("-s" if rule.inner_seams else "")
        for jblock in rule.blocks:
            block = jblock  # Java and Bedrock ids match for the glass family and most full blocks
            if block not in VANILLA_BLOCKS:
                log.append(f"skip {rule.path}: '{jblock}' has no Bedrock blocks.json entry")
                continue
            faces = ({"down", "up", "north", "south", "west"} if is_pane(block) and rule.faces & OPTIFINE_FACES["sides"]
                     else set(rule.faces) - ({"east"} if is_pane(block) else set()))
            faces -= assigned.get(block, set())
            if not faces:
                log.append(f"note {rule.path}: {block} faces already covered by an earlier rule")
                continue
            nth = rule_count.get(block, 0)
            rule_count[block] = nth + 1
            assigned.setdefault(block, set()).update(faces)
            stem = f"{slug}_{block}" + (f"_{nth + 1}" if nth else "")
            sheet_path = f"textures/blocks/ctm/{spec}/{stem}"
            base_path = f"textures/blocks/ctm/base/{stem}"
            base_img = (None if rule.fusion_rule is not None or f"assets/minecraft/textures/block/{main_java_texture(block)}.png" in fusion_paths
                        else java_main_texture_image(pack, block)) or tiles[0]
            files[sheet_path + ".png"] = png(sheet)
            alias = f"ctm_{stem}"
            if direct:  # fallback layout: the block's texture is the sheet itself (needs the mod)
                terrain[alias] = {"textures": sheet_path}
            else:
                files[base_path + ".png"] = png(base_img.resize(tiles[0].size, Image.NEAREST))
                terrain[alias] = {"textures": {"variations": [
                    {"path": base_path, "weight": 1000000},
                    {"path": sheet_path, "weight": 0.05},
                ]}}
            entry = blocks_json.get(block) or json.loads(json.dumps(VANILLA_BLOCKS[block]))
            tex = entry.get("textures")
            if isinstance(tex, str):
                tex = {f: tex for f in FACE_NAMES}
            if "carried_textures" not in entry:
                entry["carried_textures"] = json.loads(json.dumps(VANILLA_BLOCKS[block].get("textures")))
            for f in faces:
                tex[f] = alias
            entry["textures"] = tex if len(set(tex.values())) > 1 else next(iter(tex.values()))
            entry.pop("isotropic", None)  # random texture rotation would scramble connected tiles
            blocks_json[block] = entry
            summary["blocks"][block] = spec
            summary["methods"][rule.method] = summary["methods"].get(rule.method, 0) + 1
            if rule.fusion_rule is not None:
                target = java_to_bedrock_texture(block) or converted_block_texture(block, summary["blocks"])
                if target:
                    files[target + ".png"] = png(tiles[0])

    # base texture overrides (items, particles) when the Java pack ships them
    for p, data in pack.files.items():
        if p in fusion_paths:
            continue  # Never copy a Fusion atlas as an ordinary block/item texture.
        m = re.fullmatch(r"assets/minecraft/textures/block/([a-z0-9_]+)\.png", p)
        if m:
            target = java_to_bedrock_texture(m.group(1)) or converted_block_texture(m.group(1), summary["blocks"])
            if target:
                files[target + ".png"] = png(load_image(data))

    if not summary["blocks"]:
        raise SystemExit(f"{src.name}: no convertible CTM rules found\n" + "\n".join(log))

    files["textures/terrain_texture.json"] = json.dumps({
        "resource_pack_name": slug, "texture_name": "atlas.terrain", "padding": 8, "num_mip_levels": 4,
        "texture_data": terrain}, indent=1).encode()
    files["blocks.json"] = json.dumps(blocks_json, indent=1).encode()
    desc = plain_description(meta.get("description", ""))
    ns = uuid.uuid5(uuid.NAMESPACE_URL, f"https://example.invalid/ctm-converter/{slug}" + ("/direct" if direct else ""))
    files["manifest.json"] = json.dumps({
        "format_version": 2,
        "header": {
            "name": f"{display} (CTM{' direct' if direct else ''})",
            "description": (f"Converted from the Java pack \"{display}\" for the Connected Textures "
                            f"LeviLauncher mod." + (f" Original: {desc}" if desc else "")),
            "uuid": str(uuid.uuid5(ns, "header")), "version": [1, 0, 0], "min_engine_version": [1, 21, 120]},
        "modules": [{"type": "resources", "uuid": str(uuid.uuid5(ns, "resources")), "version": [1, 0, 0]}],
    }, indent=1).encode()
    icon = pack.get("pack.png")
    if icon:
        im = load_image(icon)
        if im.width > 256:
            im = im.resize((256, 256), Image.LANCZOS)
        files["pack_icon.png"] = png(im)

    out.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for k in sorted(files):
            z.writestr(k, files[k])
    summary.update(name=display, slug=slug, rules=len(rules), log=log, files=len(files))
    return summary


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("src", type=Path)
    ap.add_argument("out", type=Path)
    ap.add_argument("--name")
    ap.add_argument("--slug")
    ap.add_argument("--direct", action="store_true",
                    help="fallback layout without a base texture (glass looks wrong without the mod)")
    a = ap.parse_args()
    s = convert(a.src, a.out, a.name, a.slug, a.direct)
    print(json.dumps({k: v for k, v in s.items() if k != "log"}, indent=1))
    for line in s["log"]:
        print(" ", line)
    return 0


if __name__ == "__main__":
    sys.exit(main())
