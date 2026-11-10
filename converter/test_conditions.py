"""Conditional-pack integration tests; run with python -m unittest discover -s converter."""
import io
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

from PIL import Image
import ctm_convert as ctm


class ConditionalPacks(unittest.TestCase):
    def convert(self, rules, block="glass", animated=False, direct=False):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            src = root / "input"
            for i, condition in enumerate(rules):
                d = src / f"assets/minecraft/optifine/ctm/r{i}"
                d.mkdir(parents=True)
                Image.new("RGBA", (16, 32 if animated else 16), (120, 0, 210, 255)).save(d / "0.png")
                (d / "a.properties").write_text(f"matchBlocks={block}\nmethod=fixed\ntiles=0\n{condition}")
                if animated:
                    (d / "0.png.mcmeta").write_text('{"animation":{"frametime":3}}')
            summary = ctm.convert(src, root / "pack.mcpack", "Conditions", "conditions", direct)
            with zipfile.ZipFile(root / "pack.mcpack") as z:
                return summary, {p: z.read(p) for p in z.namelist()}

    def variants(self, files):
        return [v["textures"]["variations"] for v in json.loads(files["textures/terrain_texture.json"])["texture_data"].values()]

    def test_variants_and_fallback_survive(self):
        summary, files = self.convert(["", "biomes=desert", "ctmforge.neighborBlocks=sand"])
        self.assertEqual(3, summary["methods"]["fixed"])
        v = self.variants(files)[0]
        self.assertEqual(4, len(v))
        self.assertIn("/base/", v[0]["path"])
        self.assertIn("/when/b-desert/end/", v[2]["path"])
        self.assertIn("/when/n-sand/end/", v[3]["path"])

    def test_conditional_only_preserves_face_bases(self):
        _, files = self.convert(["biomes=desert"], block="grass_block")
        bases = {v[0]["path"] for v in self.variants(files)}
        self.assertGreater(len(bases), 1)
        self.assertTrue(all("/ctm/" not in p for p in bases))

    def test_animation_preserves_conditions(self):
        _, files = self.convert(["biomes=!desert\nctmforge.neighborBlocks=sand"], animated=True)
        flips = json.loads(files["textures/flipbook_textures.json"])
        self.assertTrue(flips)
        for f in flips:
            self.assertIn("/when/x-desert/n-sand/end/anim/", f["flipbook_texture"])
            self.assertIn(f["flipbook_texture"] + ".png", files)
            self.assertEqual(3, f["ticks_per_frame"])

    def test_end_fire_keeps_both_layers_frames_and_animated_fallback(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            d = root / "src/assets/minecraft/optifine/ctm/end"
            d.mkdir(parents=True)
            image = Image.new("RGBA", (16, 512))
            for frame in range(32):
                image.paste((frame, 0, 200, 255), (0, frame * 16, 16, (frame + 1) * 16))
            for layer in range(2):
                name = f"fire_{layer}"
                image.save(d / f"{name}.png")
                (d / f"{name}.properties").write_text(
                    f"matchTiles={name}\nmethod=fixed\ntiles={name}\n"
                    "biomes=the_end end_highlands end_midlands end_barrens small_end_islands")
                animation = {"frames": list(range(16, 32)) + list(range(16))} if layer == 0 else {}
                (d / f"{name}.png.mcmeta").write_text(json.dumps({"animation": animation}))
            ctm.convert(root / "src", root / "out.mcpack", "End Fire", "end_fire")
            with zipfile.ZipFile(root / "out.mcpack") as z:
                files = {p: z.read(p) for p in z.namelist()}
            faces = json.loads(files["blocks.json"])["fire"]["textures"]
            self.assertEqual(set(ctm.FACE_NAMES), set(faces))
            self.assertNotEqual(faces["up"], faces["down"])
            self.assertEqual(faces["up"], faces["north"])
            terrain = json.loads(files["textures/terrain_texture.json"])["texture_data"]
            flips = json.loads(files["textures/flipbook_textures.json"])
            self.assertEqual(4, len(flips))
            for face, layer, first in [("up", 0, 16), ("down", 1, 0)]:
                alias = faces[face]
                variations = terrain[alias]["textures"]["variations"]
                self.assertEqual(f"textures/blocks/fire_{layer}", variations[0]["path"])
                self.assertIn("the_end/end/", variations[1]["path"])
                base = next(f for f in flips if f["atlas_tile"] == alias and f["atlas_tile_variant"] == 0)
                self.assertEqual(f"textures/blocks/fire_{layer}", base["flipbook_texture"])
                sheet = next(f for f in flips if f["atlas_tile"] == alias and f["atlas_tile_variant"] == 1)
                strip = Image.open(io.BytesIO(files[sheet["flipbook_texture"] + ".png"]))
                self.assertEqual((16, 512), strip.size)
                self.assertEqual(first, strip.getpixel((0, 0))[0])
                self.assertEqual(list(range(32)), sheet["frames"])

    def test_names_and_rejection(self):
        self.assertEqual(["sea_lantern", "sealantern"], ctm.rule_conditions(connect_blocks="sea_lantern").connect_blocks)
        self.assertEqual(["snow_layer"], ctm.rule_conditions(connect_blocks="snow").connect_blocks)
        self.assertEqual(["ice_plains", "snowy_plains"], ctm.rule_conditions("snowy_plains").biomes)
        for v in ("!", "!desert !plains", "mod:desert", "../desert"):
            with self.assertRaises(ValueError):
                ctm.rule_conditions(v)
        with self.assertRaises(SystemExit):
            self.convert(["connectBlocks=unknown_block"])
        with self.assertRaises(SystemExit):
            self.convert(["biomes=desert"], direct=True)


if __name__ == "__main__":
    unittest.main()
