#!/usr/bin/env python3
"""Convert Java Edition connected-texture resource packs into Bedrock resource packs that
work with the "Connected Textures" LeviLauncher mod.

Reads OptiFine/MCPatcher CTM rules (also used by Continuity), Fusion connecting textures,
and animated block textures (.png.mcmeta animations and Fusion scrolling textures).

How the output works
--------------------
Every block a CTM rule targets gets its own texture alias ``ctm_<slug>_<block>`` whose terrain
texture is a weighted *variations* list:

    [ base texture (weight 1000000), tile sheet (weight 0.05) ]

Without the mod the game practically always picks the base texture, so the pack
degrades to the plain texture. With the mod, the game's texture lookup is
redirected to the tile sheet while chunks are built, and the mod picks the
right tile per face. All tiles of a rule live in one sheet image whose path
tells the mod how to read it:

    textures/blocks/ctm/<method>-<cols>x<rows>-<count>[-s]/<slug>_<block>.png

``-s`` means innerSeams=true. Supported methods: ctm (47 tiles; ctm_compact and the
Fusion layouts are expanded to 47 here), horizontal, vertical, hv, vh, top, random, repeat,
fixed. Overlay methods are skipped.

Animated tiles become animated sheets: the sheet is laid out square (n x n tiles) and a
flipbook (textures/flipbook_textures.json) plays every frame of the whole sheet into the
atlas, so the tiles the mod picks animate. The frames live next to the sheet in
``textures/blocks/ctm/<spec>/anim/<name>.png``. Plain animated block textures (fire, nether
portals, water, lava, sea lanterns...) are written at their Bedrock paths with a flipbook entry
that follows the Java .mcmeta timing. Tiles vanilla already animates keep vanilla's flipbook
image path, atlas_index, atlas_tile_variant and replicate (flowing water and lava).

``--direct`` writes a fallback layout where the block texture is the sheet itself (no base
texture). Use it only if the normal layout doesn't connect.

Usage:
    ctm_convert.py <java pack .zip or folder> <out.mcpack> [--name NAME] [--slug SLUG] [--direct]
"""
from __future__ import annotations

import argparse
import io
import json
import math
import re
import sys
import uuid
import zipfile
from dataclasses import dataclass, field
from pathlib import Path

from PIL import Image

HERE = Path(__file__).resolve().parent
VANILLA = json.loads((HERE / "data" / "vanilla_26.60.json").read_text(encoding="utf-8"))
VANILLA_BLOCKS: dict = VANILLA["blocks"]
TERRAIN: dict = VANILLA["terrain_textures"]
JAVA = json.loads((HERE / "data" / "java_26.3.json").read_text(encoding="utf-8"))
JAVA_TEXTURES: dict = JAVA["textures"]        # Java block texture name -> Bedrock texture path
JAVA_BLOCKS: dict = JAVA["blocks"]            # Java block id -> Bedrock block id (where they differ)
MODEL_PARENTS: dict = JAVA["model_parents"]   # vanilla block model -> template it is built on

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
# lowest neighbour state of every CTM47 class (diagonals only where both sides connect)
CTM47_REP = [CTM47.index(i) for i in range(47)]

FACE_NAMES = ("down", "up", "north", "south", "west", "east")
SIDES = ("north", "south", "west", "east")
OPTIFINE_FACES = {
    "bottom": {"down"}, "top": {"up"}, "north": {"north"}, "south": {"south"},
    "east": {"east"}, "west": {"west"}, "sides": {"north", "south", "east", "west"},
    "all": set(FACE_NAMES),
}
COLORS = ["white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
          "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"]
BEDROCK_COLOR = {c: ("silver" if c == "light_gray" else c) for c in COLORS}

MAX_TICKS = 12000        # longest combined animation loop kept (10 minutes)
MAX_FRAMES = 64          # most frames one animated sheet keeps
MAX_STRIP_HEIGHT = 16384  # tallest flipbook image written


def java_to_bedrock_texture(name: str) -> str | None:
    """Java block texture name -> Bedrock texture path (without extension), glass family."""
    if name in ("glass", "glass_pane_top", "tinted_glass"):
        return f"textures/blocks/{name}"
    m = re.fullmatch(r"(\w+?)_stained_glass(_pane_top)?", name)
    if m and m.group(1) in BEDROCK_COLOR:
        c = BEDROCK_COLOR[m.group(1)]
        return f"textures/blocks/glass_pane_top_{c}" if m.group(2) else f"textures/blocks/glass_{c}"
    return None


def bedrock_block(java_id: str) -> str | None:
    b = JAVA_BLOCKS.get(java_id, java_id)
    return b if b in VANILLA_BLOCKS else None


def main_java_texture(block: str) -> str:
    """Java texture a block's main faces use (panes use their glass texture)."""
    return block[: -len("_pane")] if block.endswith("_pane") else block


def is_pane(block: str) -> bool:
    return block.endswith("glass_pane")


def as_int(v) -> int | None:
    if isinstance(v, bool):
        return None
    if isinstance(v, int):
        return v
    if isinstance(v, float) and v.is_integer():
        return int(v)
    return None


# --------------------------------------------------------------------------- vanilla indexes

def alias_entries(alias) -> list | None:
    """terrain alias -> raw per-atlas_index entries (strings or objects)."""
    t = TERRAIN.get(alias) if isinstance(alias, str) else None
    if isinstance(t, (str, dict)):
        return [t]
    if isinstance(t, list):
        return t
    return None


def entry_paths(e) -> list[str]:
    if isinstance(e, str):
        return [e]
    if isinstance(e, dict):
        if isinstance(e.get("variations"), list):
            return [v.get("path", "") for v in e["variations"] if isinstance(v, dict)]
        if isinstance(e.get("path"), str):
            return [e["path"]]
    return []


def face_aliases(entry: dict) -> dict:
    """blocks.json entry -> face -> terrain alias."""
    t = entry.get("textures")
    if isinstance(t, str):
        return {f: t for f in FACE_NAMES}
    out = {}
    if isinstance(t, dict):
        for f in FACE_NAMES:
            a = t.get(f) if f in t else (t.get("side") if f in SIDES else None)
            if isinstance(a, str):
                out[f] = a
    return out


def _build_uses():
    uses: dict[str, list] = {}       # Bedrock path -> [(block, face, alias, atlas_index)]
    for block, entry in VANILLA_BLOCKS.items():
        for f, alias in face_aliases(entry).items():
            for i, e in enumerate(alias_entries(alias) or []):
                for p in entry_paths(e):
                    uses.setdefault(p, []).append((block, f, alias, i))
    tiles: dict[str, list] = {}      # Bedrock path -> [(alias, atlas_index)]
    for alias in TERRAIN:
        for i, e in enumerate(alias_entries(alias) or []):
            for p in entry_paths(e):
                if (alias, i) not in tiles.setdefault(p, []):
                    tiles[p].append((alias, i))
    return uses, tiles


PATH_USES, PATH_TILES = _build_uses()

# vanilla flipbooks: (atlas tile, atlas_index) -> vanilla's entry. A tile a pack re-animates
# keeps that entry's image path, atlas_index, atlas_tile_variant and replicate.
VANILLA_FLIPBOOKS = {(e["atlas_tile"], e.get("atlas_index", 0)): e for e in VANILLA.get("flipbooks", [])}


# 1.12 texture names (textures/blocks/) that are neither Bedrock's nor 1.13+ Java's
LEGACY_TEXTURES = {"fire_layer_0": "fire_0", "fire_layer_1": "fire_1"}


def java_texture_path(name: str) -> str | None:
    """Java block texture name (no namespace/folder) -> Bedrock texture path."""
    return java_to_bedrock_texture(name) or JAVA_TEXTURES.get(name)


# --------------------------------------------------------------------------- pack I/O

def junk(path: str) -> bool:
    parts = path.split("/")
    return parts[0] == "__MACOSX" or parts[-1].startswith("._") or parts[-1] == ".DS_Store"


class JavaPack:
    def __init__(self, src: Path):
        self.files: dict[str, bytes] = {}
        if src.is_dir():
            for p in sorted(src.rglob("*")):
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
        self.files = {k: v for k, v in self.files.items() if not junk(k)}
        roots = sorted({k[: -len("pack.mcmeta")] for k in self.files
                        if k == "pack.mcmeta" or k.endswith("/pack.mcmeta")}, key=lambda r: (len(r), r))
        self.root = roots[0] if roots else ""
        self.files = {k[len(self.root):]: v for k, v in self.files.items() if k.startswith(self.root)}
        self.overlays: list[str] = []
        self._apply_overlays()

    def _apply_overlays(self):
        """Fusion's overrides folder and pack.mcmeta overlays that current Java versions load."""
        meta = self.mcmeta()
        dirs = []
        ov = meta.get("overlays") if isinstance(meta, dict) else None
        for e in (ov.get("entries") if isinstance(ov, dict) else None) or []:
            if isinstance(e, dict) and isinstance(e.get("directory"), str) and overlay_applies(e):
                dirs.append(e["directory"])
        fu = meta.get("fusion") if isinstance(meta, dict) else None
        if isinstance(fu, dict) and isinstance(fu.get("overrides_folder"), str):
            dirs.append(fu["overrides_folder"])
        for d in dirs:
            d = d.strip("/")
            if not d or not re.fullmatch(r"[A-Za-z0-9_\-./]+", d):
                continue
            prefix = d + "/"
            moved = {k[len(prefix):]: v for k, v in self.files.items() if k.startswith(prefix + "assets/")}
            if moved:
                self.overlays.append(d)
                self.files.update(moved)

    def get(self, path: str) -> bytes | None:
        return self.files.get(path)

    def mcmeta(self) -> dict:
        try:
            d = json.loads(self.files.get("pack.mcmeta", b"{}").decode("utf-8-sig"))
            return d if isinstance(d, dict) else {}
        except Exception:
            return {}

    def json(self, path: str):
        data = self.files.get(path)
        if data is None:
            return None
        try:
            return json.loads(data.decode("utf-8-sig"))
        except Exception:
            return None


CURRENT_FORMAT = 99  # a pack format of the Java versions these packs are made for


def overlay_applies(e: dict) -> bool:
    def first(v):
        if isinstance(v, list) and v:
            return as_int(v[0])
        return as_int(v)
    lo, hi = None, None
    f = e.get("formats")
    if isinstance(f, list) and len(f) == 2:
        lo, hi = as_int(f[0]), as_int(f[1])
    elif isinstance(f, dict):
        lo, hi = as_int(f.get("min_inclusive")), as_int(f.get("max_inclusive"))
    elif as_int(f) is not None:
        lo = hi = as_int(f)
    if "min_format" in e or "max_format" in e:
        lo, hi = first(e.get("min_format")), first(e.get("max_format"))
    if lo is None and hi is None:
        return False
    return (lo is None or lo <= CURRENT_FORMAT) and (hi is None or hi >= CURRENT_FORMAT)


def plain_description(desc) -> str:
    if isinstance(desc, list):
        desc = "".join(plain_description(d) for d in desc)
    elif isinstance(desc, dict):
        desc = (desc.get("text") or "") + "".join(plain_description(d) for d in desc.get("extra", []))
    return re.sub(r"§.", "", str(desc or "")).strip()


def first_square(im: Image.Image) -> Image.Image:
    if im.height > im.width and im.height % im.width == 0:  # animated strip: first frame
        im = im.crop((0, 0, im.width, im.width))
    return im


def load_image(data: bytes) -> Image.Image:
    return first_square(Image.open(io.BytesIO(data)).convert("RGBA"))


# --------------------------------------------------------------------------- animated textures

@dataclass
class Tex:
    """A texture: its frames and Java timing ([(frame, ticks)]; one entry when static)."""
    frames: list
    timing: list = field(default_factory=lambda: [(0, 1)])
    interpolate: bool = False

    @property
    def animated(self) -> bool:
        return len({i for i, _ in self.timing}) > 1

    @property
    def first(self) -> Image.Image:
        return self.frames[self.timing[0][0]]


def anim_timing(anim: dict, count: int) -> list:
    default = as_int(anim.get("frametime"))
    default = default if default and default > 0 else 1
    timing = []
    fl = anim.get("frames")
    if isinstance(fl, list):
        for e in fl:
            if as_int(e) is not None:
                idx, t = as_int(e), default
            elif isinstance(e, dict):
                idx, t = as_int(e.get("index")), as_int(e.get("time", default))
            else:
                continue
            if idx is not None and 0 <= idx < count and t is not None and t > 0:
                timing.append((idx, t))
    return timing or [(i, default) for i in range(count)]


def frame_size(anim: dict, w: int, h: int) -> tuple[int, int]:
    fw, fh = as_int(anim.get("width")), as_int(anim.get("height"))
    if fw is not None and fw > 0:
        return fw, (fh if fh is not None and fh > 0 else h)
    if fh is not None and fh > 0:
        return w, fh
    s = min(w, h)
    return s, s


def split_frames(im: Image.Image, fw: int, fh: int) -> list | None:
    if fw <= 0 or fh <= 0 or im.width % fw or im.height % fh:
        return None
    cols = im.width // fw
    count = cols * (im.height // fh)
    frames = [im.crop(((i % cols) * fw, (i // cols) * fh, (i % cols + 1) * fw, (i // cols + 1) * fh))
              for i in range(count)]
    if fw != fh:  # Bedrock flipbooks are square frames
        frames = [f.resize((fw, fw), Image.NEAREST) for f in frames]
    return frames


def animated_tex(im: Image.Image, anim: dict) -> Tex | None:
    fw, fh = frame_size(anim, im.width, im.height)
    frames = split_frames(im, fw, fh)
    if not frames:
        return None
    return Tex(frames, anim_timing(anim, len(frames)), anim.get("interpolate") is True)


def texture_meta(pack: JavaPack, png_path: str) -> dict:
    m = pack.json(png_path + ".mcmeta")
    return m if isinstance(m, dict) else {}


def load_tex(pack: JavaPack, path: str) -> Tex | None:
    """A pack texture with its .mcmeta animation (first frame only when not animated)."""
    data = pack.get(path)
    if data is None:
        return None
    im = Image.open(io.BytesIO(data)).convert("RGBA")
    meta = texture_meta(pack, path)
    anim = meta.get("animation")
    f = meta.get("fusion")
    layout_type = isinstance(f, dict) and fusion_type(f) in ("connecting", "random", "continuous", "scrolling")
    if isinstance(anim, dict) and not layout_type:
        t = animated_tex(im, anim)
        if t is not None:
            return t
    sc = scrolling_tex(im, meta)
    if sc is not None:
        return sc
    return Tex([first_square(im)])


def round_half_up(num: int, den: int) -> int:
    return (2 * num + den) // (2 * den)


def scrolling_tex(im: Image.Image, meta: dict) -> Tex | None:
    """Fusion scrolling texture -> frames (the frame window moves across the image)."""
    f = meta.get("fusion")
    if not isinstance(f, dict) or fusion_type(f) != "scrolling":
        return None
    fw = as_int(f.get("frame_width")) or 16
    fh = as_int(f.get("frame_height")) or 16
    ft = as_int(f.get("frame_time")) or 10
    pause = as_int(f.get("loop_pause")) or 0
    loop = str(f.get("loop_type", "wrap")).lower()
    start, end = str(f.get("from", "top_left")).lower(), str(f.get("to", "bottom_left")).lower()
    w, h = im.width, im.height
    if fw <= 0 or fh <= 0 or ft <= 0 or w < fw or h < fh:
        return None
    wrap, rev = loop == "wrap", loop == "reverse"
    left = lambda p: p in ("top_left", "bottom_left")
    top = lambda p: p in ("top_left", "top_right")
    sx = 0 if left(start) else w - fw
    sy = 0 if top(start) else h - fh
    ex = (1 - fw if wrap else 0) if left(end) else (w - 1 if wrap else w - fw)
    ey = (1 - fh if wrap else 0) if top(end) else (h - 1 if wrap else h - fh)
    steps = max(abs(ex - sx), abs(ey - sy)) + 1
    if steps > 1024:
        return None
    count = max((steps - 1) * 2, 1) if rev else steps
    frames, timing = [], []
    for i in range(count):
        k = i if i < steps else 2 * (steps - 1) - i
        if steps > 1:
            x = round_half_up(sx * (steps - 1) + (ex - sx) * k, steps - 1)
            y = round_half_up(sy * (steps - 1) + (ey - sy) * k, steps - 1)
        else:
            x, y = round_half_up(2 * sx + (ex - sx), 2), round_half_up(2 * sy + (ey - sy), 2)
        fr = wrapped_crop(im, x, y, fw, fh) if wrap else im.crop((x, y, x + fw, y + fh))
        if fw != fh:
            fr = fr.resize((fw, fw), Image.NEAREST)
        frames.append(fr)
        t = ft + pause if (i == 0 and rev) or i == steps - 1 else ft
        timing.append((i, t))
    return Tex(frames, timing, False)


def wrapped_crop(im: Image.Image, x: int, y: int, w: int, h: int) -> Image.Image:
    out = Image.new("RGBA", (w, h))
    src = im.load()
    dst = out.load()
    for yy in range(h):
        for xx in range(w):
            dst[xx, yy] = src[(x + xx) % im.width, (y + yy) % im.height]
    return out


def merge_timeline(texs: list) -> tuple[list, list, int, bool]:
    """Common loop of several textures -> (states, Bedrock frames, ticks_per_frame, blend).
    A state is the frame index of every texture at one moment."""
    # very long loops (huge frame times) are counted in coarser steps
    longest = max(sum(n for _, n in t.timing) for t in texs)
    scale = -(-longest // MAX_TICKS) if longest > MAX_TICKS else 1
    seqs = []
    for t in texs:
        s = []
        for idx, n in t.timing:
            s.extend([idx] * max(1, round_half_up(n, scale)))
        seqs.append(s or [0])
    period = 1
    for s in seqs:
        if len(s) > 1:
            period = period * len(s) // math.gcd(period, len(s))
            if period > MAX_TICKS:
                period = max(len(x) for x in seqs)
                break
    ticks = [tuple(s[k % len(s)] for s in seqs) for k in range(period)]
    step = 1
    while True:
        runs = []
        for st in ticks[::step]:
            if runs and runs[-1][0] == st:
                runs[-1][1] += 1
            else:
                runs.append([st, 1])
        states = list(dict.fromkeys(st for st, _ in runs))
        if len(states) <= MAX_FRAMES:
            break
        step += 1
    g = 0
    for _, n in runs:
        g = math.gcd(g, n)
    index = {st: i for i, st in enumerate(states)}
    frames = []
    for st, n in runs:
        frames.extend([index[st]] * (n // g))
    return states, frames, g * step * scale, any(t.interpolate for t in texs if t.animated)


def plain_timeline(t: Tex) -> tuple[list, list, int, bool] | None:
    """One animated block texture -> (states, Bedrock frames, ticks_per_frame, blend) like
    merge_timeline, but the strip keeps every frame in the pack's order (the layout a Java strip
    and vanilla's entry for the tile use). Too tall strips keep only the frames that play, and
    None means even that is too tall for one flipbook."""
    w = t.frames[0].width
    if len(t.frames) * w > MAX_STRIP_HEIGHT:
        merged = merge_timeline([t])
        return merged if len(merged[0]) * w <= MAX_STRIP_HEIGHT else None
    runs = []
    for idx, n in t.timing:
        if runs and runs[-1][0] == idx:
            runs[-1][1] += n
        else:
            runs.append([idx, n])
    g = 0
    for _, n in runs:
        g = math.gcd(g, n)
    if sum(n // g for _, n in runs) > MAX_TICKS:  # very uneven frame times: coarser steps
        g = -(-sum(n for _, n in runs) // MAX_TICKS)
    frames = []
    for idx, n in runs:
        frames.extend([idx] * max(1, round_half_up(n, g)))
    return [(i,) for i in range(len(t.frames))], frames, g, t.interpolate


def flipbook_entry(strip: str, alias: str, frames: list, tpf: int, blend: bool,
                   index: int | None = None, variant: int | None = None) -> dict:
    e = {"flipbook_texture": strip, "atlas_tile": alias}
    if index is not None:
        e["atlas_index"] = index
    if variant is not None:
        e["atlas_tile_variant"] = variant
    e["ticks_per_frame"] = tpf
    e["frames"] = frames
    e["blend_frames"] = blend
    return e


def stack(frames: list) -> Image.Image:
    w, h = frames[0].width, frames[0].height
    out = Image.new("RGBA", (w, h * len(frames)))
    for i, f in enumerate(frames):
        out.paste(f, (0, i * h))
    return out


# --------------------------------------------------------------------------- rules

@dataclass
class Rule:
    path: str
    props: dict
    method: str
    blocks: list[str]
    faces: set[str]
    tiles: list = field(default_factory=list)       # Tex per tile
    inner_seams: bool = False
    weights: list[int] = field(default_factory=list)
    width: int = 1
    height: int = 1
    pack: "JavaPack | None" = None
    textures: list[str] = field(default_factory=list)   # matchTiles: Java texture names
    source: str = "optifine"
    base: "Tex | None" = None                            # explicit base texture (Fusion)
    layout: "str | None" = None                          # Fusion layout of the tiles
    overlay: bool = False                                # OptiFine overlay* methods


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
    for tok in re.split(r"[\s,]+", spec):  # OptiFine separates list items with spaces or commas
        if not tok:
            continue
        m = re.fullmatch(r"(\d+)-(\d+)", tok)
        if m:
            a, b = int(m.group(1)), int(m.group(2))
            out += [str(i) for i in (range(a, b + 1) if a <= b else range(a, b - 1, -1))]
        else:
            out.append(tok)
    return out


def resolve_tile(pack: JavaPack, rule_dir: str, namespace: str, tok: str, base_path) -> str | None:
    if tok in ("<default>", "<skip>"):
        return base_path
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
        if pack.get(c) is not None:
            return c
    return None


def block_names(spec: str) -> list[str]:
    out = []
    for tok in re.split(r"[\s,]+", spec):
        if not tok:
            continue
        parts = tok.split(":")
        if len(parts) >= 2 and parts[0] == "minecraft":
            parts = parts[1:]
        elif len(parts) >= 2 and "=" not in parts[1]:
            continue  # other namespace (mod block)
        out.append(parts[0])
    return out


def tile_names(spec: str) -> list[str]:
    """matchTiles entries -> Java block texture names (vanilla textures only)."""
    out = []
    for tok in re.split(r"[\s,]+", spec):
        if not tok:
            continue
        if ":" in tok:
            ns, tok = tok.split(":", 1)
            if ns != "minecraft":
                continue
        tok = tok[:-4] if tok.endswith(".png") else tok
        if tok.startswith("textures/"):
            tok = tok[len("textures/"):]
        if "/" in tok and not tok.startswith(("block/", "blocks/")):
            continue  # a texture of the pack itself
        out.append(tok.rsplit("/", 1)[-1])
    return out


def main_texture_path(pack: JavaPack, block: str) -> str | None:
    p = f"assets/minecraft/textures/block/{main_java_texture(block)}.png"
    return p if pack.get(p) is not None else None


def skipped_tex() -> Tex:
    """OptiFine's <skip> overlay tile: fully transparent, so nothing is drawn."""
    return Tex([Image.new("RGBA", (16, 16), (0, 0, 0, 0))])


def read_rules(pack: JavaPack, log: list[str]) -> list[Rule]:
    rules = []
    paths = sorted(p for p in pack.files if p.endswith(".properties")
                   and re.match(r"assets/[^/]+/(optifine|mcpatcher)/ctm/", p))
    for path in paths:
        namespace = path.split("/")[1]
        rule_dir = path.rsplit("/", 1)[0]
        props = parse_properties(pack.files[path].decode("utf-8", "replace"))
        method = props.get("method", "ctm").lower().replace("horizontal+vertical", "hv").replace("vertical+horizontal", "vh")
        overlay = method.startswith("overlay")
        if overlay and method not in ("overlay", "overlay_ctm", "overlay_random", "overlay_repeat", "overlay_fixed"):
            log.append(f"skip {path}: unknown method '{method}'")
            continue
        blocks = block_names(props.get("matchBlocks", ""))
        textures = []
        if not blocks and props.get("matchTiles"):
            textures = tile_names(props["matchTiles"])
        if not blocks and not textures:
            stem = path.rsplit("/", 1)[-1][:-len(".properties")]
            if stem.startswith("block_"):
                blocks = [stem[len("block_"):]]
        faces = set()
        for f in re.split(r"[\s,]+", props.get("faces", "all")):  # OptiFine allows spaces or commas
            faces |= OPTIFINE_FACES.get(f.lower(), set())
        for unsupported in ("biomes", "heights", "minHeight", "maxHeight", "metadata", "name"):
            if unsupported in props:
                log.append(f"note {path}: '{unsupported}' is ignored")
        r = Rule(path, props, method, blocks, faces or set(FACE_NAMES), pack=pack, textures=textures)
        r.overlay = overlay
        connect = props.get("connect", "block").lower()
        if connect not in ("block", ""):
            log.append(f"note {path}: connect={connect} is treated as connect=block")
        if overlay:
            for k in ("connectTiles", "connectBlocks", "tintIndex", "tintBlock", "layer"):
                if k in props:
                    log.append(f"note {path}: '{k}' is ignored")
        r.inner_seams = props.get("innerSeams", "false").lower() == "true"
        base_path = main_texture_path(pack, blocks[0]) if blocks else (
            f"assets/minecraft/textures/block/{textures[0]}.png" if textures and pack.get(
                f"assets/minecraft/textures/block/{textures[0]}.png") is not None else None)
        toks = expand_tile_list(props.get("tiles", ""))
        tile_paths = [("<skip>" if overlay and t == "<skip>"
                       else resolve_tile(pack, rule_dir, namespace, t, base_path)) for t in toks]
        if any(t is None for t in tile_paths) or not tile_paths:
            log.append(f"skip {path}: missing tile images")
            continue
        try:
            r.tiles = [skipped_tex() if t == "<skip>" else load_tex(pack, t) for t in tile_paths]
        except Exception as e:  # noqa: BLE001
            log.append(f"skip {path}: unreadable tile image ({e})")
            continue
        if base_path is not None:
            try:
                r.base = load_tex(pack, base_path)
            except Exception:  # noqa: BLE001
                r.base = None
        if method in ("random", "overlay_random"):
            w = [int(x) for x in re.split(r"[\s,]+", props.get("weights", "")) if x.isdigit()]
            r.weights = w if len(w) == len(r.tiles) else [1] * len(r.tiles)
        if method in ("repeat", "overlay_repeat"):
            r.width, r.height = int(props.get("width", 1)), int(props.get("height", 1))
        rules.append(r)
    return rules


# --------------------------------------------------------------------------- Fusion

FUSION_GRID = {"full": (8, 6), "simple": (4, 4), "compact": (5, 1), "pieced": (5, 1),
               "horizontal": (4, 1), "vertical": (1, 4), "overlay": (6, 3)}


def fusion_type(f: dict) -> str:
    t = str(f.get("type", "")).lower()
    return t[len("fusion:"):] if t.startswith("fusion:") else t


def _flags(s: int):
    """CTM47 neighbour bits -> Fusion's (top, topRight, right, bottomRight, bottom, bottomLeft, left, topLeft)."""
    return (bool(s & 64), bool(s & 32), bool(s & 16), bool(s & 8), bool(s & 4), bool(s & 2), bool(s & 1), bool(s & 128))


def _fusion_full(s: int) -> tuple[int, int]:
    t, tr, r, br, b, bl, l, tl = _flags(s)
    if not l and not t and not r and not b:
        return 0, 0
    if l and not t and not r and not b:
        return 3, 0
    if not l and t and not r and not b:
        return 0, 3
    if not l and not t and r and not b:
        return 1, 0
    if not l and not t and not r and b:
        return 0, 1
    if l and not t and r and not b:
        return 2, 0
    if not l and t and not r and b:
        return 0, 2
    if l and t and not r and not b:
        return (3, 3) if tl else (5, 1)
    if not l and t and r and not b:
        return (1, 3) if tr else (4, 1)
    if not l and not t and r and b:
        return (1, 1) if br else (4, 0)
    if l and not t and not r and b:
        return (3, 1) if bl else (5, 0)
    if not l:
        return (1, 2) if tr and br else (4, 2) if tr else (6, 2) if br else (6, 0)
    if not t:
        return (2, 1) if bl and br else (7, 2) if bl else (5, 2) if br else (7, 0)
    if not r:
        return (3, 2) if tl and bl else (7, 3) if tl else (5, 3) if bl else (7, 1)
    if not b:
        return (2, 3) if tl and tr else (4, 3) if tl else (6, 3) if tr else (6, 1)
    if tl and tr and bl and br:
        return 2, 2
    if not tl and tr and bl and br:
        return 7, 5
    if tl and not tr and bl and br:
        return 6, 5
    if tl and tr and not bl and br:
        return 7, 4
    if tl and tr and bl and not br:
        return 6, 4
    if not tl and tr and not br and bl:
        return 0, 4
    if tl and not tr and br and not bl:
        return 0, 5
    if not tl and not tr and br and bl:
        return 3, 4
    if tl and not tr and not br and bl:
        return 3, 5
    if tl and tr and not br and not bl:
        return 2, 5
    if not tl and tr and br and not bl:
        return 2, 4
    if tl:
        return 5, 5
    if tr:
        return 4, 5
    if br:
        return 4, 4
    if bl:
        return 5, 4
    return 1, 4


def _fusion_simple(s: int) -> tuple[int, int]:
    t, _, r, _, b, _, l, _ = _flags(s)
    if not (l or t or r or b):
        return 0, 0
    one = {(True, False, False, False): (3, 0), (False, True, False, False): (3, 1),
           (False, False, True, False): (2, 1), (False, False, False, True): (2, 0),
           (True, False, True, False): (0, 1), (False, True, False, True): (1, 1),
           (True, True, False, False): (3, 3), (False, True, True, False): (2, 3),
           (False, False, True, True): (2, 2), (True, False, False, True): (3, 2)}
    k = (l, t, r, b)
    if k in one:
        return one[k]
    if not l:
        return 0, 2
    if not t:
        return 1, 2
    if not r:
        return 1, 3
    if not b:
        return 0, 3
    return 1, 0


def _fusion_compact(s: int) -> tuple[int, int]:
    t, tr, r, br, b, bl, l, tl = _flags(s)
    sides = l + t + r + b
    if sides <= 1:
        return 0, 0
    if sides == 2:
        return (3, 0) if l and r else (2, 0) if t and b else (0, 0)
    if sides == 3:
        if l and r:
            return (3, 0) if (tl and t and tr) or (bl and b and br) else (0, 0)
        return (2, 0) if (tl and l and bl) or (tr and r and br) else (0, 0)
    if tl and tr and bl and br:
        return 1, 0
    if not tl and not tr and not bl and not br:
        return 4, 0
    return 0, 0


def _layout47(fn, grid_w: int) -> list[int]:
    out = []
    for c in range(47):
        x, y = fn(CTM47_REP[c])
        out.append(x + y * grid_w)
    return out


FUSION_TO_47 = {"full": _layout47(_fusion_full, 8), "simple": _layout47(_fusion_simple, 4),
                "compact": _layout47(_fusion_compact, 5)}
# OptiFine tile order of the 4-tile methods: [one neighbour after, both, one neighbour before, none]
FUSION_LINE = {"horizontal": [1, 2, 3, 0], "vertical": [3, 2, 1, 0]}


def image_empty(im: Image.Image) -> bool:
    return im.getchannel("A").getextrema()[1] == 0


def fusion_grid(im: Image.Image, gw: int, gh: int, meta: dict, legacy_full: bool) -> list | None:
    """Fusion texture -> Tex per grid tile (row-major), honouring animation."""
    anim = meta.get("animation") if isinstance(meta.get("animation"), dict) else None
    f = meta.get("fusion") or {}
    if legacy_full and anim is None and im.width == im.height:
        im = im.crop((0, 0, im.width, im.height * 6 // 8))
    if anim is None:
        if im.width % gw or im.height % gh:
            return None
        tw, th = im.width // gw, im.height // gh
        if tw <= 0 or th <= 0:
            return None
        out = []
        for y in range(gh):
            for x in range(gw):
                t = im.crop((x * tw, y * th, (x + 1) * tw, (y + 1) * th))
                out.append(Tex([t if tw == th else t.resize((tw, tw), Image.NEAREST)]))
        return out
    if f.get("per_tile_animation") is True:
        if im.width % gw or im.height % gh:
            return None
        cw, ch = im.width // gw, im.height // gh
        out = []
        for y in range(gh):
            for x in range(gw):
                cell = im.crop((x * cw, y * ch, (x + 1) * cw, (y + 1) * ch))
                t = animated_tex(cell, anim)
                if t is None:
                    return None
                out.append(t)
        return out
    ts = min(im.width // gw, im.height // gh)
    fw, fh = as_int(anim.get("width")), as_int(anim.get("height"))
    fw = fw if fw and fw > 0 else gw * ts
    fh = fh if fh and fh > 0 else gh * ts
    if ts <= 0 or fw % gw or fh % gh or im.width % fw or im.height % fh:
        return None
    tw, th = fw // gw, fh // gh
    cols = im.width // fw
    count = cols * (im.height // fh)
    timing = anim_timing(anim, count)
    interp = anim.get("interpolate") is True
    out = []
    for y in range(gh):
        for x in range(gw):
            frames = []
            for i in range(count):
                ox, oy = (i % cols) * fw, (i // cols) * fh
                t = im.crop((ox + x * tw, oy + y * th, ox + (x + 1) * tw, oy + (y + 1) * th))
                frames.append(t if tw == th else t.resize((tw, tw), Image.NEAREST))
            out.append(Tex(frames, list(timing), interp))
    return out


def tex_ref(ref, textures: dict) -> str | None:
    """Model texture reference -> 'namespace:path' (no #, no extension)."""
    for _ in range(16):
        if isinstance(ref, dict):
            ref = ref.get("sprite")
        if not isinstance(ref, str) or not ref:
            return None
        if not ref.startswith("#"):
            break
        ref = textures.get(ref[1:])
    else:
        return None
    if not isinstance(ref, str) or ref.startswith("#"):
        return None
    ns, path = ref.split(":", 1) if ":" in ref else ("minecraft", ref)
    return f"{ns}:{path}"


def tex_file(ref: str) -> str:
    ns, path = ref.split(":", 1)
    return f"assets/{ns}/textures/{path}.png"


def norm_model(ref: str) -> str:
    ns, path = ref.split(":", 1) if ":" in ref else ("minecraft", ref)
    if "/" not in path:
        path = "block/" + path
    return f"{ns}:{path}"


_ALL = {f: "#all" for f in FACE_NAMES}
_COLUMN = {"down": "#end", "up": "#end", "north": "#side", "south": "#side", "west": "#side", "east": "#side"}
_BOTTOM_TOP = {"down": "#bottom", "up": "#top", "north": "#side", "south": "#side", "west": "#side", "east": "#side"}
_CUBE = {f: "#" + f for f in FACE_NAMES}
_PANE = {"pane": "#pane"}
TEMPLATE_FACES = {
    "cube": _CUBE, "cube_mirrored": _CUBE, "cube_directional": _CUBE,
    "cube_all": _ALL, "cube_mirrored_all": _ALL, "cube_north_west_mirrored_all": _ALL, "leaves": _ALL,
    "cube_column": _COLUMN, "cube_column_mirrored": _COLUMN, "cube_column_uv_locked_x": _COLUMN,
    "cube_column_uv_locked_y": _COLUMN, "cube_column_uv_locked_z": _COLUMN,
    "cube_column_horizontal": {"down": "#side", "up": "#side", "north": "#end", "south": "#end",
                               "west": "#side", "east": "#side"},
    "cube_bottom_top": _BOTTOM_TOP, "slab": _BOTTOM_TOP, "slab_top": _BOTTOM_TOP, "stairs": _BOTTOM_TOP,
    "inner_stairs": _BOTTOM_TOP, "outer_stairs": _BOTTOM_TOP,
    "cube_top": {"down": "#side", "up": "#top", "north": "#side", "south": "#side", "west": "#side", "east": "#side"},
    "orientable": {"down": "#top", "up": "#top", "north": "#front", "south": "#side", "west": "#side", "east": "#side"},
    "orientable_with_bottom": {"down": "#bottom", "up": "#top", "north": "#front", "south": "#side",
                               "west": "#side", "east": "#side"},
    "orientable_vertical": {"down": "#side", "up": "#front", "north": "#side", "south": "#side",
                            "west": "#side", "east": "#side"},
    "template_glass_pane_post": _PANE, "template_glass_pane_side": _PANE, "template_glass_pane_side_alt": _PANE,
    "template_glass_pane_noside": _PANE, "template_glass_pane_noside_alt": _PANE,
}


def element_faces(elements) -> dict:
    """Model elements -> face -> texture ref of the biggest element face in that direction."""
    best = {}
    for el in elements if isinstance(elements, list) else []:
        if not isinstance(el, dict):
            continue
        a, b = el.get("from", [0, 0, 0]), el.get("to", [16, 16, 16])
        try:
            size = [abs(float(b[i]) - float(a[i])) for i in range(3)]
        except Exception:  # noqa: BLE001
            continue
        area = {"down": size[0] * size[2], "up": size[0] * size[2], "north": size[0] * size[1],
                "south": size[0] * size[1], "west": size[2] * size[1], "east": size[2] * size[1]}
        faces = el.get("faces")
        for face in FACE_NAMES:
            fd = faces.get(face) if isinstance(faces, dict) else None
            if isinstance(fd, dict) and "texture" in fd and area[face] > best.get(face, (-1.0, None))[0]:
                best[face] = (area[face], fd["texture"])
    return {f: v[1] for f, v in best.items()}


def resolve_model(pack: JavaPack, ref: str) -> tuple[dict, dict] | None:
    """-> (face -> texture ref, textures) with parents applied; None when unknown."""
    chain = []
    cur = norm_model(ref)
    faces = None
    for _ in range(32):
        ns, path = cur.split(":", 1)
        m = pack.json(f"assets/{ns}/models/{path}.json")
        if isinstance(m, dict):
            chain.append(m)
            if isinstance(m.get("elements"), list):
                faces = element_faces(m["elements"])
                break
            parent = m.get("parent")
            if not isinstance(parent, str) or parent.startswith("builtin/"):
                break
            cur = norm_model(parent)
            continue
        if ns == "minecraft" and path.startswith("block/"):
            name = path[len("block/"):]
            tmpl = TEMPLATE_FACES.get(name) or TEMPLATE_FACES.get(MODEL_PARENTS.get(name, ""))
            if tmpl:
                faces = dict(tmpl)
        break
    if faces is None:
        return None
    textures: dict = {}
    for m in reversed(chain):
        t = m.get("textures")
        if isinstance(t, dict):
            textures.update(t)
    return faces, textures


def blockstate_models(state) -> list[str]:
    """Default models of a blockstate file (the 'axis=y' / first variant, or every multipart part)."""
    out = []
    if not isinstance(state, dict):
        return out
    var = state.get("variants")
    if isinstance(var, dict) and var:
        keys = list(var)
        key = next((k for k in keys if k == "" or "axis=y" in k.split(",")), keys[0])
        v = var[key]
        for m in v if isinstance(v, list) else [v]:
            if isinstance(m, dict) and isinstance(m.get("model"), str):
                out.append(m["model"])
                break
    for part in state.get("multipart", []) if isinstance(state.get("multipart"), list) else []:
        a = part.get("apply") if isinstance(part, dict) else None
        for m in a if isinstance(a, list) else [a]:
            if isinstance(m, dict) and isinstance(m.get("model"), str):
                out.append(m["model"])
    return out


def fusion_rules(pack: JavaPack, log: list[str], claimed: set) -> list[Rule]:
    """Fusion connecting/random/continuous textures -> rules (one per block and texture)."""
    metas = {}
    for p in sorted(pack.files):
        if p.endswith(".png.mcmeta") and p.startswith("assets/"):
            m = pack.json(p)
            f = m.get("fusion") if isinstance(m, dict) else None
            if isinstance(f, dict) and fusion_type(f) in ("connecting", "random", "continuous"):
                png = p[: -len(".mcmeta")]
                if pack.get(png) is not None:
                    metas[png] = m
    if not metas:
        return []
    # model-driven: block -> faces using each Fusion texture
    uses: dict[tuple[str, str], set] = {}
    states = sorted(p for p in pack.files if re.fullmatch(r"assets/minecraft/blockstates/[a-z0-9_]+\.json", p))
    blocks = {p.rsplit("/", 1)[1][:-5]: [norm_model(m) for m in blockstate_models(pack.json(p))] for p in states}
    for p in sorted(pack.files):
        m = re.fullmatch(r"assets/minecraft/models/block/([a-z0-9_]+)\.json", p)
        if m and m.group(1) not in blocks and bedrock_block(m.group(1)):
            blocks[m.group(1)] = [f"minecraft:block/{m.group(1)}"]
    for jblock, models in blocks.items():
        for model in models:
            res = resolve_model(pack, model)
            if res is None:
                continue
            faces, textures = res
            for face, ref in faces.items():
                r = tex_ref(ref, textures)
                if r is None or tex_file(r) not in metas:
                    continue
                if is_pane(jblock):  # Bedrock panes show the glass texture on every face but the edge
                    fs = {"down", "up", "north", "south", "west"}
                elif face == "pane":
                    continue
                else:
                    fs = {face}
                uses.setdefault((jblock, tex_file(r)), set()).update(fs)
    rules = []
    for (jblock, png), faces in sorted(uses.items()):
        r = fusion_rule(pack, png, metas[png], [jblock], [], faces, log)
        if r:
            rules.append(r)
            claimed.add(png)
    # texture-driven: Fusion textures that replace a vanilla texture no pack model points at
    for png, meta in sorted(metas.items()):
        m = re.fullmatch(r"assets/minecraft/textures/block/([a-z0-9_]+)\.png", png)
        if png in claimed or not m:
            continue
        r = fusion_rule(pack, png, meta, [], [m.group(1)], set(FACE_NAMES), log)
        if r:
            rules.append(r)
            claimed.add(png)
    return rules


def fusion_rule(pack: JavaPack, png: str, meta: dict, blocks: list, textures: list, faces: set,
                log: list[str]) -> Rule | None:
    f = meta["fusion"]
    kind = fusion_type(f)
    try:
        im = Image.open(io.BytesIO(pack.get(png))).convert("RGBA")
    except Exception as e:  # noqa: BLE001
        log.append(f"skip {png}: unreadable image ({e})")
        return None
    r = Rule(png, {}, "", list(blocks), set(faces), pack=pack, textures=list(textures), source="fusion")
    if kind == "connecting":
        layout = str(f.get("layout", "full")).lower()
        if layout not in FUSION_GRID or layout == "overlay":
            log.append(f"skip {png}: Fusion '{layout}' layout is not supported")
            return None
        gw, gh = FUSION_GRID[layout]
        tiles = fusion_grid(im, gw, gh, meta, layout == "full")
        if tiles is None:
            log.append(f"skip {png}: image size doesn't fit the '{layout}' layout")
            return None
        r.method, r.layout, r.tiles = "fusion", layout, tiles
        conn = f.get("connections")
        if conn is not None and not same_block_predicate(conn):
            log.append(f"note {png}: Fusion connection rules are treated as 'is_same_block'")
    elif kind in ("random", "continuous"):
        rows = as_int(f.get("rows")) or 1
        cols = as_int(f.get("columns")) or 1
        if rows <= 0 or cols <= 0:
            return None
        tiles = fusion_grid(im, cols, rows, meta, False)
        if tiles is None:
            log.append(f"skip {png}: image size doesn't fit {cols}x{rows} tiles")
            return None
        if kind == "random":
            tiles = [t for i, t in enumerate(tiles) if i == 0 or not image_empty(t.first)]
            r.method, r.tiles, r.weights = "random", tiles, [1] * len(tiles)
        else:
            r.method, r.tiles, r.width, r.height = "repeat", tiles, cols, rows
    else:
        return None
    if isinstance(f.get("sub_texture"), dict):
        log.append(f"note {png}: Fusion sub_texture is ignored")
    return r


def same_block_predicate(p) -> bool:
    if isinstance(p, list):
        return all(same_block_predicate(x) for x in p)
    if isinstance(p, dict):
        t = str(p.get("type", "")).lower()
        t = t[len("fusion:"):] if t.startswith("fusion:") else t
        if t in ("or", "and"):
            return same_block_predicate(p.get("predicates", []))
        return t in ("is_same_block", "is_same_state")
    return False


# --------------------------------------------------------------------------- sheets

def compact_to_47(c: list[Image.Image]) -> list[Image.Image]:
    """Build the 47 standard tiles from the 5 ctm_compact tiles (OptiFine/Continuity rules)."""
    size = max(t.width for t in c)
    c = [t.resize((size, size), Image.NEAREST) for t in c]
    h = size // 2
    # quadrant q: 0=top-left, 1=bottom-left, 2=bottom-right, 3=top-right; dirs [L, D, R, U]
    boxes = [(0, 0, h, h), (0, h, h, size), (h, h, size, size), (h, 0, size, h)]
    out = []
    for idx in range(47):
        s = CTM47_REP[idx]
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


def build_sheet(tiles: list[Image.Image], cols: int, rows: int | None = None) -> tuple[Image.Image, int, int]:
    size = max(max(t.width, t.height) for t in tiles)
    rows = rows or math.ceil(len(tiles) / cols)
    sheet = Image.new("RGBA", (cols * size, rows * size), (0, 0, 0, 0))
    for i, t in enumerate(tiles):
        if t.size != (size, size):
            t = t.resize((size, size), Image.NEAREST)
        sheet.paste(t, ((i % cols) * size, (i // cols) * size))
    return sheet, cols, rows


@dataclass
class Layout:
    method: str
    inputs: list          # Tex
    build: object         # list[Image] (one per input) -> list[Image] (runtime tiles)
    cols: int
    count: int
    overlay: bool = False


def rule_layout(rule: Rule, log: list[str]) -> Layout | None:
    m = rule.method
    tiles = rule.tiles
    ident = lambda ims: ims  # noqa: E731
    if m == "fusion":
        lay = rule.layout
        if lay in FUSION_TO_47:
            idx = FUSION_TO_47[lay]
            return Layout("ctm", tiles, lambda ims: [ims[i] for i in idx], 8, 47)
        if lay == "pieced":
            return Layout("ctm", tiles[:5], compact_to_47, 8, 47)
        order = FUSION_LINE[lay]
        return Layout(lay, [tiles[i] for i in order], ident, 4, 4)
    if m == "ctm":
        if len(tiles) < 47:
            log.append(f"skip {rule.path}: ctm needs 47 tiles, found {len(tiles)}")
            return None
        return Layout("ctm", tiles[:47], ident, 8, 47)
    if m == "ctm_compact":
        if len(tiles) < 5:
            log.append(f"skip {rule.path}: ctm_compact needs 5 tiles")
            return None
        if any(k.startswith("ctm.") for k in rule.props):
            log.append(f"note {rule.path}: ctm.N replacement tiles are ignored")
        return Layout("ctm", tiles[:5], compact_to_47, 8, 47)
    if m in ("horizontal", "vertical"):
        if len(tiles) < 4:
            log.append(f"skip {rule.path}: {m} needs 4 tiles, found {len(tiles)}")
            return None
        return Layout(m, tiles[:4], ident, 4, 4)
    if m in ("hv", "vh"):
        if len(tiles) < 7:
            log.append(f"skip {rule.path}: {rule.props.get('method')} needs 7 tiles, found {len(tiles)}")
            return None
        return Layout(m, tiles[:7], ident, 7, 7)
    if m == "top":
        if rule.base is None:
            log.append(f"skip {rule.path}: 'top' needs the block's own texture in the pack")
            return None
        return Layout("top", [rule.base, tiles[0]], ident, 2, 2)
    if m == "fixed":
        return Layout("fixed", tiles[:1], ident, 1, 1)
    if m == "repeat":
        n = rule.width * rule.height
        if rule.width <= 0 or rule.height <= 0 or len(tiles) < n:
            log.append(f"skip {rule.path}: repeat needs width x height ({rule.width} x {rule.height}) tiles, found {len(tiles)}")
            return None
        return Layout("repeat", tiles[:n], ident, rule.width, n)
    if m == "random":
        g = 0
        for w in rule.weights:
            g = math.gcd(g, w)
        counts = [max(1, w // max(g, 1)) for w in rule.weights]
        if sum(counts) > 64:
            log.append(f"note {rule.path}: random weights too fine, using equal weights")
            counts = [1] * len(tiles)
        n = sum(counts)

        def expand(ims, counts=counts):
            out = []
            for im, c in zip(ims, counts):
                out += [im] * c
            return out
        return Layout("random", tiles, expand, min(8, n), n)
    if m == "overlay":
        if len(tiles) < 17:
            log.append(f"skip {rule.path}: overlay needs 17 tiles, found {len(tiles)}")
            return None
        return Layout("overlay", tiles[:17], ident, 6, 17, True)
    if m == "overlay_ctm":
        if len(tiles) < 47:
            log.append(f"skip {rule.path}: overlay_ctm needs 47 tiles, found {len(tiles)}")
            return None
        return Layout("ctm", tiles[:47], ident, 8, 47, True)
    if m == "overlay_fixed":
        return Layout("fixed", tiles[:1], ident, 1, 1, True)
    if m == "overlay_repeat":
        n = rule.width * rule.height
        if rule.width <= 0 or rule.height <= 0 or len(tiles) < n:
            log.append(f"skip {rule.path}: overlay_repeat needs width x height ({rule.width} x {rule.height}) tiles, found {len(tiles)}")
            return None
        return Layout("repeat", tiles[:n], ident, rule.width, n, True)
    if m == "overlay_random":
        g = 0
        for w in rule.weights:
            g = math.gcd(g, w)
        counts = [max(1, w // max(g, 1)) for w in rule.weights]
        if sum(counts) > 64:
            log.append(f"note {rule.path}: random weights too fine, using equal weights")
            counts = [1] * len(tiles)
        n = sum(counts)

        def expand(ims, counts=counts):
            out = []
            for im, c in zip(ims, counts):
                out += [im] * c
            return out
        return Layout("random", tiles, expand, min(8, n), n, True)
    log.append(f"skip {rule.path}: unknown method '{m}'")
    return None


# --------------------------------------------------------------------------- targets

@dataclass
class Target:
    block: str                 # Bedrock block id
    faces: set
    index: "int | None" = None  # atlas_index when only one entry of an array alias is replaced
    texture: "str | None" = None  # Java texture the faces show (for the base image)


def rule_targets(rule: Rule, log: list[str]) -> list[Target]:
    out = []
    for jblock in rule.blocks:
        block = bedrock_block(jblock)
        if block is None:
            log.append(f"skip {rule.path}: '{jblock}' has no Bedrock blocks.json entry")
            continue
        if rule.source == "fusion":
            faces = set(rule.faces)
        else:
            faces = ({"down", "up", "north", "south", "west"} if is_pane(block) and rule.faces & OPTIFINE_FACES["sides"]
                     else set(rule.faces) - ({"east"} if is_pane(block) else set()))
        out.append(Target(block, faces, None, main_java_texture(jblock)))
    for name in rule.textures:
        path = java_texture_path(name)
        uses = PATH_USES.get(path or "", [])
        if not uses:
            log.append(f"skip {rule.path}: texture '{name}' is not used by any Bedrock block")
            continue
        by_block: dict = {}
        for block, face, alias, i in uses:
            if face not in rule.faces:
                continue
            n = len(alias_entries(alias) or [])
            key = (block, i if n > 1 else None)
            by_block.setdefault(key, set()).add(face)
        for (block, i), faces in by_block.items():
            out.append(Target(block, faces, i, name))
    return out


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
    claimed: set = set()
    rules += fusion_rules(pack, log, claimed)

    files: dict[str, bytes] = {}
    terrain: dict[str, dict] = {}
    blocks_json: dict[str, dict] = {"format_version": "1.21.120"}
    flipbooks: list[dict] = []
    array_aliases: dict[tuple, str] = {}
    rule_count: dict[str, int] = {}
    summary = {"blocks": {}, "methods": {}, "animated": []}

    def png(im: Image.Image) -> bytes:
        b = io.BytesIO()
        im.save(b, "PNG", optimize=True)
        return b.getvalue()

    # Pass 1: build every rule's sheet and collect the sheets that apply to each
    # (block, face). The first non-overlay rule wins a face; overlays all stack.
    block_faces: dict[tuple, dict] = {}
    for rule in rules:
        layout = rule_layout(rule, log)
        if not layout:
            continue
        targets = rule_targets(rule, log)
        if not targets:
            continue
        animated = any(t.animated for t in layout.inputs)
        if animated and layout.overlay:
            log.append(f"note {rule.path}: overlay animations are not supported, using the first frame")
            animated = False
        if animated:
            states, frames, tpf, blend = merge_timeline(layout.inputs)
            n = math.isqrt(layout.count - 1) + 1
            sheets = [build_sheet(layout.build([layout.inputs[i].frames[s[i]] for i in range(len(s))]), n, n)[0]
                      for s in states]
            if sheets[0].height * len(sheets) > MAX_STRIP_HEIGHT or layout.method == "repeat":
                log.append(f"note {rule.path}: animation too large for one flipbook, using the first frame")
                animated = False
        if animated:
            sheet, cols, rows = sheets[0], n, n
            tile0 = layout.build([t.frames[states[0][i]] for i, t in enumerate(layout.inputs)])[0]
        else:
            built = layout.build([t.first for t in layout.inputs])
            sheet, cols, rows = build_sheet(built, layout.cols)
            tile0 = built[0]
        spec = f"{layout.method}-{cols}x{rows}-{layout.count}" + ("-s" if rule.inner_seams else "") + ("-o" if layout.overlay else "")
        strip_path = None
        contributed = set()
        for tg in targets:
            block = tg.block
            nth = rule_count.get(block, 0)
            rule_count[block] = nth + 1
            stem = f"{slug}_{block}" + (f"_{nth + 1}" if nth else "")
            sheet_path = f"textures/blocks/ctm/{spec}/{stem}"
            base_path = f"textures/blocks/ctm/base/{stem}"
            files[sheet_path + ".png"] = png(sheet)
            base_tex = None
            if tg.texture:
                bp = f"assets/minecraft/textures/block/{tg.texture}.png"
                if pack.get(bp) is not None and bp not in claimed:  # not a Fusion layout image
                    try:
                        base_tex = load_tex(pack, bp)
                    except Exception:  # noqa: BLE001
                        base_tex = None
            base_img = base_tex.first if base_tex is not None else tile0
            if animated and strip_path is None:
                # parseable like the sheet itself, in case the game reports the flipbook's path
                strip_path = f"textures/blocks/ctm/{spec}/anim/{stem}"
                files[strip_path + ".png"] = png(stack(sheets))
            if direct and layout.overlay:
                log.append(f"note {rule.path}: overlay rules are not supported in direct mode")
                continue
            entry = {
                "layout": layout, "spec": spec, "sheet_path": sheet_path, "base_path": base_path,
                "base_img": base_img, "tile0": tile0, "base_tex": base_tex, "animated": animated,
                "strip_path": strip_path,
                "frames": frames if animated else None, "tpf": tpf if animated else 1,
                "blend": blend if animated else False,
            }
            faces = block_faces.setdefault((block, tg.index), {})
            for f in tg.faces:
                lst = faces.setdefault(f, [])
                if not layout.overlay and any(not e["layout"].overlay for e in lst):
                    log.append(f"note {rule.path}: {block} {f} already covered by an earlier rule")
                    continue
                if direct and lst:
                    continue  # direct mode has no variations list to merge into
                lst.append(entry)
                contributed.add(block)
        key = f"fusion:{rule.layout}" if rule.source == "fusion" and rule.layout else rule.method
        summary["methods"][key] = summary["methods"].get(key, 0) + len(contributed)
        for block in sorted(contributed):
            if animated and block not in summary["animated"]:
                summary["animated"].append(block)
            summary["blocks"][block] = spec

    # Pass 2: one alias per block and face set, merging all the sheets that apply.
    for (block, index), faces in block_faces.items():
        groups: dict[tuple, list] = {}
        for f, entries in faces.items():
            # Faces with different vanilla textures need their own base, so an
            # overlay-only block (e.g. grass) still shows each face's own texture.
            base_key = next((e["base_path"] for e in entries if not e["layout"].overlay), None)
            if base_key is None:
                paths = entry_paths(face_aliases(VANILLA_BLOCKS[block]).get(f))
                base_key = paths[0] if paths else ""
            key = (tuple(e["sheet_path"] for e in entries), base_key)
            groups.setdefault(key, []).append(f)
        entry = blocks_json.get(block) or json.loads(json.dumps(VANILLA_BLOCKS[block]))
        tex = entry.get("textures")
        if isinstance(tex, str):
            tex = {f: tex for f in FACE_NAMES}
        if not isinstance(tex, dict):
            log.append(f"skip: '{block}' has no textures in blocks.json")
            continue
        if "carried_textures" not in entry:
            entry["carried_textures"] = json.loads(json.dumps(VANILLA_BLOCKS[block].get("textures")))
        for gi, (key, group_faces) in enumerate(groups.items()):
            entries = faces[group_faces[0]]
            alias = f"ctm_{slug}_{block}" + (f"_{index}" if index is not None else "") + (f"_{gi}" if gi else "")
            normal = next((e for e in entries if not e["layout"].overlay), None)
            if direct:  # fallback layout: the block's texture is the sheet itself (needs the mod)
                value = entries[0]["sheet_path"]
            else:
                if normal is not None:
                    files[normal["base_path"] + ".png"] = png(normal["base_img"].resize(normal["tile0"].size, Image.NEAREST))
                    base_value = normal["base_path"]
                else:
                    # Overlay-only: each face's own vanilla texture, so the block
                    # looks vanilla without the mod. A custom base texture in the
                    # pack still reaches it through the plain texture copies below.
                    base_value = key[1]
                    if not base_value:
                        log.append(f"skip: '{block}' has no vanilla base texture")
                        continue
                value = {"variations": [{"path": base_value, "weight": 1000000}] +
                                       [{"path": p, "weight": 0.05} for p in key[0]]}
            vanilla_alias = face_aliases(VANILLA_BLOCKS[block]).get(min(group_faces))
            arr_entries = alias_entries(vanilla_alias) if index is not None else None
            array = arr_entries is not None and len(arr_entries) > 1
            if array and (block, vanilla_alias) in array_aliases:
                # another entry of the same array alias was converted already: share its alias
                alias = array_aliases[(block, vanilla_alias)]
                terrain[alias]["textures"][index] = value
            elif array:
                arr = list(arr_entries)
                arr[index] = value
                terrain[alias] = {"textures": arr}
                array_aliases[(block, vanilla_alias)] = alias
            else:
                terrain[alias] = {"textures": value}
            for pos, e in enumerate(entries):
                if not e["animated"]:
                    continue
                idx = index if array else None
                if not direct and e["base_tex"] is not None and e["base_tex"].animated:
                    bst, bfr, btpf, bbl = merge_timeline([e["base_tex"]])
                    bstrip = f"textures/blocks/ctm/base/anim/{slug}_{block}"
                    files[bstrip + ".png"] = png(stack([e["base_tex"].frames[st[0]].resize(e["tile0"].size, Image.NEAREST)
                                                        for st in bst]))
                    flipbooks.append(flipbook_entry(bstrip, alias, bfr, btpf, bbl, idx, 0))
                # the sheet goes last: Bedrock may only play the last entry of one atlas tile
                flipbooks.append(flipbook_entry(e["strip_path"], alias, e["frames"], e["tpf"], e["blend"], idx,
                                                None if direct else 1 + pos))
            for f in group_faces:
                tex[f] = alias
        entry["textures"] = tex if len(set(tex.values())) > 1 else next(iter(tex.values()))
        entry.pop("isotropic", None)  # random texture rotation would scramble connected tiles
        blocks_json[block] = entry

    # Bedrock texture paths of converted blocks (for the plain texture copies below)
    converted_paths = set()
    for block in summary["blocks"]:
        for alias in face_aliases(VANILLA_BLOCKS[block]).values():
            for e in alias_entries(alias) or []:
                converted_paths.update(entry_paths(e))

    # plain texture overrides: animated block textures get a flipbook; static ones are copied
    # for converted blocks (items, particles) and the glass family
    animated_paths = set()
    for p, data in sorted(pack.files.items()):
        m = re.fullmatch(r"assets/minecraft/textures/(block|blocks)/([a-z0-9_]+)\.png", p)
        if not m or p in claimed:
            continue
        name = m.group(2)
        if m.group(1) == "blocks":  # 1.12 and older packs already use (most) Bedrock names
            # (1.12 water_still/water_flow keep their blue, untinted Bedrock twins: the grey ones are tinted)
            same = f"textures/blocks/{name}"
            target = same if same in PATH_TILES else java_texture_path(LEGACY_TEXTURES.get(name, name))
        else:
            target = java_texture_path(name)
        if not target:
            continue
        try:
            t = load_tex(pack, p)
        except Exception as e:  # noqa: BLE001
            log.append(f"note {p}: unreadable image, not copied ({e})")
            continue
        if t is not None and t.animated:
            tiles = PATH_TILES.get(target, [])
            if not tiles:
                continue
            timeline = plain_timeline(t)
            if timeline is None:
                log.append(f"note {p}: animation too large for one flipbook, not converted")
                continue
            states, frames, tpf, blend = timeline
            images = []
            for alias, i in tiles:
                v = VANILLA_FLIPBOOKS.get((alias, i))
                if v is not None:  # a tile vanilla animates: same image and atlas fields as vanilla
                    image, index, variant = v["flipbook_texture"], v.get("atlas_index"), v.get("atlas_tile_variant")
                else:  # array tiles name their index twice, as vanilla's own array flipbooks do
                    many = len(alias_entries(alias) or []) > 1
                    image, index, variant = target, (i if many else None), (i if many else None)
                e = flipbook_entry(image, alias, frames, tpf, blend, index, variant)
                if v is not None and "replicate" in v:
                    e["replicate"] = v["replicate"]
                flipbooks.append(e)
                if image not in images:
                    images.append(image)
            strip = png(stack([t.frames[s[0]] for s in states]))
            for image in images:
                files[image + ".png"] = strip
            if target not in images:  # the atlas keeps a still copy, as vanilla's command blocks do
                files[target + ".png"] = png(t.first)
            animated_paths.add(target)
            summary["animated"].append(name)
        elif t is not None and (java_to_bedrock_texture(name) or target in converted_paths):
            files[target + ".png"] = png(t.first)

    if not summary["blocks"] and not animated_paths:
        raise SystemExit(f"{src.name}: no connected or animated textures found")

    files["textures/terrain_texture.json"] = json.dumps({
        "resource_pack_name": slug, "texture_name": "atlas.terrain", "padding": 8, "num_mip_levels": 4,
        "texture_data": terrain}, indent=1).encode()
    files["blocks.json"] = json.dumps(blocks_json, indent=1).encode()
    if flipbooks:
        files["textures/flipbook_textures.json"] = json.dumps(flipbooks, indent=1).encode()
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
