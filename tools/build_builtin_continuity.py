#!/usr/bin/env python3
"""Build the bundled Bedrock defaults from the pinned Continuity resource source.

Only resource files are reused. Continuity's Java implementation is not bundled.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "converter"))
import ctm_convert as ctm

SOURCE = ROOT / "mod/third_party/continuity-default-3.0.1+26.3.zip"
SOURCE_SHA256 = "44193eddf5f4e3d6cd7cb6bc016fdde1e3628cbcf0f0864c556eb63988105b50"
METADATA = ROOT / "mod/assets/builtin/ctm_defaults"
PREFIX = "assets/continuity/optifine/ctm/default/"
JAVA_BLOCKS = {
    "glass", "glass_pane", "tinted_glass", "bookshelf", "sandstone", "red_sandstone",
    "cut_sandstone", "cut_red_sandstone", "chiseled_sandstone", "chiseled_red_sandstone",
    *(f"{color}_stained_glass{suffix}" for color in ctm.COLORS for suffix in ("", "_pane")),
}
EXPECTED_BLOCKS = {ctm.bedrock_block(b) for b in JAVA_BLOCKS}


def build_bundle() -> tuple[dict[str, bytes], dict]:
    """Return pack bytes and conversion report; keep vendored originals unchanged."""
    source = SOURCE.read_bytes()
    if hashlib.sha256(source).hexdigest() != SOURCE_SHA256:
        raise ValueError("Continuity source checksum mismatch")
    with tempfile.TemporaryDirectory(prefix="ctm-continuity-") as tmp:
        work = Path(tmp) / "source"
        work.mkdir()
        with zipfile.ZipFile(SOURCE) as upstream:
            # A fixed, checksummed source archive; refuse files outside its resource root.
            for name in upstream.namelist():
                if name not in ("pack.mcmeta", "pack.png") and not name.startswith(PREFIX):
                    raise ValueError(f"Unexpected upstream resource: {name}")
                if ".." in Path(name).parts:
                    raise ValueError(f"Unsafe source path: {name}")
                dest = work / name
                dest.parent.mkdir(parents=True, exist_ok=True)
                dest.write_bytes(upstream.read(name))

        rules = sorted(work.rglob("*.properties"))
        if len(rules) != 42:
            raise ValueError(f"Expected 42 Continuity rules, got {len(rules)}")
        textures = set()
        for rule in rules:
            props = ctm.parse_properties(rule.read_text())
            # Upstream has singular matchBlock in cut_sandstone.properties.
            if "matchBlock" in props:
                props["matchBlocks"] = props.pop("matchBlock")
            # Java texture-pack activation guards have no Bedrock equivalent.
            # This bundle targets vanilla; users can disable it for custom art.
            props.pop("resourceCondition", None)
            if props.get("method") == "top":
                block = props["matchTiles"]
                if block not in ("sandstone", "red_sandstone"):
                    raise ValueError(f"Unexpected top rule: {block}")
                # Match only the full block, never ambiguous legacy stone slabs.
                # Count adjacent normal-family shapes that share its side texture.
                props["matchBlocks"] = block
                props["faces"] = "sides"
                props["connect"] = "block"
                props["connectBlocks"] = " ".join(
                    [block, *(block + suffix for suffix in ("_slab", "_double_slab", "_stairs", "_wall"))])
            textures.update(ctm.main_java_texture(b) for b in props["matchBlocks"].split())
            rule.write_text("\n".join(f"{k}={v}" for k, v in props.items()) + "\n")

        # Isolated blocks must retain their normal texture when the hook is off.
        # Horizontal/top layouts also need the normal base while building sheets.
        with zipfile.ZipFile(ROOT / "converter/data/vanilla_fallbacks.zip") as vanilla:
            for name in sorted(textures):
                path = ctm.java_texture_path(name)
                if path is None:
                    raise ValueError(f"Missing Java-to-Bedrock texture mapping: {name}")
                dest = work / f"assets/minecraft/textures/block/{name}.png"
                dest.parent.mkdir(parents=True, exist_ok=True)
                dest.write_bytes(vanilla.read(path + ".png"))
        result = Path(tmp) / "converted.mcpack"
        summary = ctm.convert(work, result, "Continuity Defaults", "continuity_defaults")
        if set(summary["blocks"]) != EXPECTED_BLOCKS or summary["log"]:
            raise ValueError(f"Incomplete Continuity conversion: {summary}")
        files = {}
        with zipfile.ZipFile(result) as converted:
            for name in converted.namelist():
                # Private copies preserve vanilla fallback art without overriding
                # global vanilla paths or touching grass, snow, fire or soul fire.
                if name in ("blocks.json", "pack_icon.png", "textures/terrain_texture.json") or name.startswith("textures/blocks/ctm/"):
                    files[name] = converted.read(name)
        for path in sorted(METADATA.rglob("*")):
            if path.is_file():
                files[path.relative_to(METADATA).as_posix()] = path.read_bytes()
        files["source/continuity-default.zip"] = source
        # The modified property files are the preferred editable rule sources.
        for rule in rules:
            files["source/adapted/" + rule.relative_to(work).as_posix()] = rule.read_bytes()
        files["source/conversion.json"] = (json.dumps(summary, indent=2) + "\n").encode()
        return files, summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path, help="Output .mcpack for inspection")
    args = parser.parse_args()
    files, summary = build_bundle()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.output, "w", zipfile.ZIP_DEFLATED) as out:
        for name, data in sorted(files.items()):
            out.writestr(name, data)
    print(f"{args.output}: {len(summary['blocks'])} blocks; {summary['methods']}")


if __name__ == "__main__":
    main()
