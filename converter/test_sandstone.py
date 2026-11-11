"""Sandstone family definitions must survive conversion and pack merging."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile

from PIL import Image
import ctm_convert as ctm


class SandstoneTest(unittest.TestCase):
    def convert(self, root, blocks, direct=False):
        source = root / "source"
        for block in blocks:
            directory = source / f"assets/minecraft/optifine/ctm/{block}"
            directory.mkdir(parents=True)
            Image.new("RGBA", (16, 16), (125, 60, 20, 255)).save(directory / "0.png")
            (directory / "rule.properties").write_text(
                f"matchBlocks={block}\nmethod=fixed\ntiles=0\nfaces=sides\n")
        output = root / "pack.mcpack"
        summary = ctm.convert(source, output, "Sandstone", root.name, direct)
        with zipfile.ZipFile(output) as pack:
            definitions = json.loads(pack.read("blocks.json"))
        self.assertEqual(set(summary["blocks"]), set(blocks))
        return output, definitions

    def assert_family(self, definitions, base, converted):
        order = list(definitions)
        for block in (base, "chiseled_" + base, "cut_" + base, "smooth_" + base):
            self.assertIn(block, definitions)
            entry = definitions[block]
            vanilla = ctm.VANILLA_BLOCKS[block]
            # Each inventory icon has its own normal texture, including the
            # chiseled, cut and smooth sides that differ from the base block.
            self.assertEqual(entry["carried_textures"], vanilla["textures"])
            faces = ctm.face_aliases(entry)
            normal = ctm.face_aliases(vanilla)
            if block in converted:
                self.assertTrue(faces["north"].endswith("_" + block))
                self.assertTrue(faces["north"].startswith("ctm_"))
                for face in ("up", "down"):
                    self.assertEqual(faces[face], normal[face])
            else:
                self.assertEqual(faces, normal)
            if block != base:
                self.assertLess(order.index(base), order.index(block))

    def test_base_only_keeps_all_untargeted_variants(self):
        for direct in (False, True):
            with self.subTest(direct=direct), tempfile.TemporaryDirectory() as tmp:
                _, blocks = self.convert(Path(tmp), ["sandstone", "red_sandstone"], direct)
                for base in ("sandstone", "red_sandstone"):
                    self.assert_family(blocks, base, {base})

    def test_explicit_variant_rules_survive_base_rule_and_merge(self):
        targets = ["sandstone", "chiseled_sandstone", "cut_sandstone", "smooth_sandstone",
                   "red_sandstone", "chiseled_red_sandstone", "cut_red_sandstone"]
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            pack, definitions = self.convert(root, targets)
            for base in ("sandstone", "red_sandstone"):
                self.assert_family(definitions, base, targets)
            # Simulate an older converted pack: variants precede their parent,
            # and untargeted smooth red sandstone is missing entirely.
            legacy = {"format_version": definitions.pop("format_version")}
            legacy.update((k, definitions[k]) for k in sorted(targets))
            old_pack = root / "legacy.mcpack"
            with zipfile.ZipFile(pack) as source, zipfile.ZipFile(old_pack, "w") as output:
                for name in source.namelist():
                    output.writestr(name, json.dumps(legacy).encode() if name == "blocks.json" else source.read(name))
            merged = root / "merged.mcpack"
            subprocess.run([sys.executable, str(Path(ctm.HERE).parent / "tools/merge_packs.py"),
                            str(merged), "Merged", "merged", "Sandstone regression", str(old_pack)],
                           check=True, capture_output=True)
            with zipfile.ZipFile(merged) as output:
                definitions = json.loads(output.read("blocks.json"))
            for base in ("sandstone", "red_sandstone"):
                self.assert_family(definitions, base, targets)

    def test_unrelated_and_variant_only_packs_do_not_override_siblings(self):
        for target in ("glass", "cut_sandstone", "chiseled_red_sandstone"):
            with self.subTest(target=target), tempfile.TemporaryDirectory() as tmp:
                _, blocks = self.convert(Path(tmp), [target])
                self.assertEqual(set(blocks), {"format_version", target})


if __name__ == "__main__":
    unittest.main()
