"""Conditional-pack integration tests; run with python -m unittest discover -s converter."""
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
