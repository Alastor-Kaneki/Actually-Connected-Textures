"""Verify the actual bundled Continuity conversion, including source pixels."""
import hashlib
import io
import json
from pathlib import Path
import re
import sys
import unittest
import zipfile

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
from build_builtin_continuity import build_bundle, SOURCE, SOURCE_SHA256, PREFIX
import ctm_convert as ctm


def pixels(data):
    return Image.open(io.BytesIO(data)).convert("RGBA")


class BuiltinContinuityTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.files, cls.summary = build_bundle()
        cls.blocks = json.loads(cls.files["blocks.json"])
        cls.terrain = json.loads(cls.files["textures/terrain_texture.json"])["texture_data"]

    def test_coverage_and_existing_options_are_separate(self):
        self.assertEqual(self.summary["rules"], 42)
        self.assertEqual(self.summary["methods"], {"ctm": 35, "horizontal": 5, "top": 2})
        self.assertEqual(self.summary["log"], [])
        self.assertEqual(len(self.summary["blocks"]), 42)
        self.assertEqual(len(self.blocks) - 1, 44)  # includes two preserved vanilla smooth variants
        for unrelated in ("grass", "snow", "fire", "soul_fire", "stone_block_slab", "sandstone_stairs"):
            self.assertNotIn(unrelated, self.blocks)
        self.assertFalse(any(n.endswith(".class") for n in self.files))
        self.assertTrue(all(n == "pack_icon.png" or n.startswith("textures/blocks/ctm/")
                            for n in self.files if n.endswith(".png")))

    def test_every_variant_has_local_normal_fallback_and_sheet(self):
        with zipfile.ZipFile(ROOT / "converter/data/vanilla_fallbacks.zip") as vanilla:
            for alias, entry in self.terrain.items():
                variants = entry["textures"]["variations"]
                self.assertEqual([v["weight"] for v in variants], [1000000, 0.05])
                for v in variants:
                    self.assertIn(v["path"] + ".png", self.files)
                    self.assertTrue(v["path"].startswith("textures/blocks/ctm/"))
                block = alias.removeprefix("ctm_continuity_defaults_")
                java = next((j for j, b in ctm.JAVA_BLOCKS.items() if b == block), block)
                path = ctm.java_texture_path(ctm.main_java_texture(java))
                self.assertIsNotNone(path, block)
                actual = pixels(self.files[variants[0]["path"] + ".png"])
                expected = pixels(vanilla.read(path + ".png"))
                self.assertEqual(actual.size, expected.size, block)
                self.assertEqual(actual.tobytes(), expected.tobytes(), block)
                spec = re.search(r"/(ctm|horizontal|top)-(\d+)x(\d+)-(\d+)/", variants[1]["path"])
                self.assertIsNotNone(spec)
                sheet = pixels(self.files[variants[1]["path"] + ".png"])
                self.assertEqual(sheet.size, (16 * int(spec[2]), 16 * int(spec[3])))

    def test_all_glass_sheet_tiles_preserve_upstream_pixels(self):
        with zipfile.ZipFile(SOURCE) as upstream:
            for name in upstream.namelist():
                if not name.endswith(".properties"):
                    continue
                props = ctm.parse_properties(upstream.read(name).decode())
                if props.get("method") != "ctm":
                    continue
                block = ctm.bedrock_block(props["matchBlocks"])
                variants = self.terrain["ctm_continuity_defaults_" + block]["textures"]["variations"]
                sheet = pixels(self.files[variants[1]["path"] + ".png"])
                # Pane rules reference their matching glass tiles in the same directory.
                for i in range(47):
                    tile = pixels(upstream.read(name.rsplit("/", 1)[0] + f"/{i}.png"))
                    x, y = i % 8 * 16, i // 8 * 16
                    self.assertEqual(sheet.crop((x, y, x + 16, y + 16)).tobytes(), tile.tobytes(), (name, i))

    def test_sandstone_uses_side_faces_and_explicit_neighbor_family(self):
        for block in ("sandstone", "red_sandstone"):
            faces = self.blocks[block]["textures"]
            alias = "ctm_continuity_defaults_" + block
            for side in ("north", "south", "east", "west"):
                self.assertEqual(faces[side], alias)
            self.assertFalse(faces["up"].startswith("ctm_"))
            self.assertFalse(faces["down"].startswith("ctm_"))
            path = self.terrain[alias]["textures"]["variations"][1]["path"]
            family = path.split("/when/c-", 1)[1].split("/end/", 1)[0].split(".")
            self.assertEqual(set(family), {block, *(block + suffix for suffix in
                                                ("_slab", "_double_slab", "_stairs", "_wall"))})
            sheet = pixels(self.files[path + ".png"])
            base = pixels(self.files[self.terrain[alias]["textures"]["variations"][0]["path"] + ".png"])
            self.assertEqual(sheet.crop((0, 0, 16, 16)).tobytes(), base.tobytes())

    def test_sandstone_variants_keep_distinct_world_and_inventory_definitions(self):
        order = list(self.blocks)
        for base in ("sandstone", "red_sandstone"):
            side_aliases = set()
            for block in (base, "chiseled_" + base, "cut_" + base, "smooth_" + base):
                entry = self.blocks[block]
                self.assertEqual(entry["carried_textures"], ctm.VANILLA_BLOCKS[block]["textures"])
                side_aliases.add(ctm.face_aliases(entry)["north"])
                if block != base:
                    self.assertLess(order.index(base), order.index(block))
            self.assertEqual(len(side_aliases), 4)
            smooth = "smooth_" + base
            self.assertEqual(self.blocks[smooth]["textures"], ctm.VANILLA_BLOCKS[smooth]["textures"])
            self.assertNotIn("ctm_continuity_defaults_" + smooth, self.terrain)

    def test_license_and_editable_sources_are_shipped(self):
        original = self.files["source/continuity-default.zip"]
        self.assertEqual(hashlib.sha256(original).hexdigest(), SOURCE_SHA256)
        self.assertEqual(SOURCE.read_bytes(), original)
        self.assertIn(b"GNU LESSER GENERAL PUBLIC LICENSE", self.files["LICENSE-LGPL-3.0.txt"])
        self.assertIn(b"GNU GENERAL PUBLIC LICENSE", self.files["LICENSE-GPL-3.0.txt"])
        self.assertIn(b"PepperCode1", self.files["credits.txt"])
        adapted = [n for n in self.files if n.startswith("source/adapted/")]
        self.assertEqual(len(adapted), 42)
        self.assertFalse(any(b"resourceCondition" in self.files[n] for n in adapted))
        cut = "source/adapted/" + PREFIX + "sandstone/cut_sandstone/cut_sandstone.properties"
        self.assertIn(b"matchBlocks=cut_sandstone", self.files[cut])


if __name__ == "__main__":
    unittest.main()
