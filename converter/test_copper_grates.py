"""Real conversions of every wax/oxidation state, including alpha and source fallbacks."""
import io
import json
from pathlib import Path
import tempfile
import unittest
import zipfile
from PIL import Image
import ctm_convert as ctm

STAGES = ['copper_grate', 'exposed_copper_grate', 'weathered_copper_grate', 'oxidized_copper_grate']


class CopperGrates(unittest.TestCase):
    def run_pack(self, entries):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for name, data in entries.items():
                path = root / 'src' / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(data.encode() if isinstance(data, str) else data)
            result = ctm.convert(root / 'src', root / 'grates.mcpack', 'Grates', 'grates')
            with zipfile.ZipFile(root / 'grates.mcpack') as z:
                return result, {p: z.read(p) for p in z.namelist()}

    def image(self, color=(182, 102, 73, 255), width=16):
        im = Image.new('RGBA', (width, 16), color)
        for x in range(width):
            if x % 16 in range(5, 11):
                for y in range(5, 11): im.putpixel((x, y), (0, 0, 0, 0))
        b = io.BytesIO(); im.save(b, format='PNG'); return b.getvalue()

    def test_implicit_names_convert_all_eight_states_and_keep_holes(self):
        d = 'assets/minecraft/optifine/ctm'
        entries = {f'{d}/0.png': self.image()}
        for name in STAGES: entries[f'{d}/{name}.properties'] = 'method=ctm_compact\ntiles=0 0 0 0 0\n'
        result, files = self.run_pack(entries)
        self.assertEqual(set(STAGES + ['waxed_' + s for s in STAGES]), set(result['blocks']))
        self.assertEqual(8, result['methods']['ctm_compact'])
        self.assertTrue(any('inferred matchTiles=copper_grate' in line for line in result['log']))
        sheets = [p for p in files if '/ctm-8x6-47/' in p and p.endswith('.png')]
        self.assertEqual(8, len(sheets))
        for path in sheets:
            im = Image.open(io.BytesIO(files[path]))
            self.assertEqual(0, im.getpixel((7, 7))[3])
            self.assertEqual(255, im.getpixel((0, 0))[3])

    def test_explicit_blocks_and_block_filename_keep_supplied_unwaxed_base(self):
        for target in STAGES + ['waxed_' + s for s in STAGES]:
            with self.subTest(target=target):
                d = 'assets/minecraft/optifine/ctm'
                base = target.removeprefix('waxed_')
                result, files = self.run_pack({
                    f'{d}/block_{target}.properties': 'method=fixed\ntiles=0\nbiomes=desert',
                    f'{d}/0.png': self.image(),
                    f'assets/minecraft/textures/block/{base}.png': self.image((1, 2, 3, 255)),
                })
                self.assertEqual({target}, set(result['blocks']))
                terrain = json.loads(files['textures/terrain_texture.json'])['texture_data']
                for entry in terrain.values():
                    path = entry['textures']['variations'][0]['path'] + '.png'
                    im = Image.open(io.BytesIO(files[path]))
                    self.assertEqual((1, 2, 3, 255), im.getpixel((0, 0)))
                    self.assertEqual(0, im.getpixel((7, 7))[3])
        d = 'assets/minecraft/optifine/ctm'
        result, _ = self.run_pack({f'{d}/copper_grate.properties': 'matchBlocks=glass\nmethod=fixed\ntiles=0', f'{d}/0.png': self.image()})
        self.assertEqual({'glass'}, set(result['blocks']), 'explicit target must override filename')

    def test_fusion_horizontal_grates_keep_all_states_and_alpha(self):
        d = 'assets/minecraft/textures/block'
        entries = {}
        for name in STAGES:
            entries[f'{d}/{name}.png'] = self.image(width=64)
            entries[f'{d}/{name}.png.mcmeta'] = '{"fusion":{"type":"connecting","layout":"horizontal"}}'
        result, files = self.run_pack(entries)
        self.assertEqual(set(STAGES + ['waxed_' + s for s in STAGES]), set(result['blocks']))
        sheets = [p for p in files if '/horizontal-4x1-4/' in p and p.endswith('.png')]
        self.assertEqual(8, len(sheets))
        for path in sheets: self.assertEqual(0, Image.open(io.BytesIO(files[path])).getpixel((7, 7))[3])


if __name__ == '__main__': unittest.main()
