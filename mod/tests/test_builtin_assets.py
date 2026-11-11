"""Guard the split between full sides and optional snow, and launcher identity."""
import json
from pathlib import Path
import unittest
from uuid import UUID

ROOT = Path(__file__).resolve().parents[1] / "assets" / "builtin"


class BuiltinAssetsTest(unittest.TestCase):
    def test_full_sides_keep_biome_colors_and_use_active_snow(self):
        grass = ROOT / "ctm_grass"
        blocks = json.loads((grass / "blocks.json").read_text())
        self.assertEqual(set(blocks) - {"format_version"}, {
            "grass", "grass_path", "mycelium", "podzol", "crimson_nylium", "warped_nylium",
        })
        data = json.loads((grass / "textures/terrain_texture.json").read_text())["texture_data"]
        self.assertEqual(set(data), {"grass_side"})
        textures = data["grass_side"]["textures"]
        self.assertEqual(len(textures), 20)
        self.assertTrue(all(t["path"] == "textures/blocks/grass_top" and
                            t["overlay_color"].startswith("#") for t in textures[:-1]))
        self.assertEqual(textures[-1], "textures/blocks/snow")
        # The full-side bundle must work without enabling whiter snow.
        self.assertEqual(list((grass / "textures").rglob("*.png")), [])

    def test_snow_contains_only_optional_effects(self):
        snow = ROOT / "ctm_whiter_snow"
        self.assertEqual({p.relative_to(snow).as_posix() for p in (snow / "textures").rglob("*") if p.is_file()}, {
            "textures/blocks/snow.png", "textures/blocks/powder_snow.png",
            "textures/entity/snow_golem.png", "textures/items/snowball.png",
            "textures/items/bucket_powder_snow.png", "textures/ui/frozen_effect.png",
        })
        self.assertFalse((snow / "blocks.json").exists())
        self.assertFalse((snow / "textures/terrain_texture.json").exists())

    def test_manifest_ids_are_distinct_and_credits_are_retained(self):
        ids = set()
        for pack in ROOT.iterdir():
            doc = json.loads((pack / "manifest.json").read_text())
            self.assertEqual(doc["modules"][0]["type"], "resources")
            defaults = pack.name == "ctm_defaults"
            self.assertEqual(doc["metadata"]["authors"],
                             ["PepperCode1", "Alastor"] if defaults else ["VanillaTweaks", "BedrockTweaks"])
            for entry in [doc["header"], *doc["modules"]]:
                identity = str(UUID(entry["uuid"]))
                self.assertNotIn(identity, ids)
                self.assertNotEqual(identity, "670ab91e-1df0-49cd-a6b2-d9c62843c315")
                ids.add(identity)
            self.assertIn("https://github.com/PepperCode1/Continuity" if defaults else "https://bedrocktweaks.net/",
                          (pack / "credits.txt").read_text())


if __name__ == "__main__":
    unittest.main()
