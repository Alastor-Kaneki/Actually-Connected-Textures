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

    def test_uppercase_tile_extension_matches_the_reported_zip(self):
        d = 'assets/minecraft/optifine/ctm/copper_grate'
        entries = {}
        for stage, block in enumerate(STAGES, 1):
            entries[f'{d}/{stage}/copper_grate.properties'] = f'method=ctm\ntiles=0-46\nmatchBlocks=waxed_{block} {block}'
            for tile in range(47):
                extension = 'PNG' if stage == 1 and tile == 45 else 'png'
                entries[f'{d}/{stage}/{tile}.{extension}'] = self.image((tile, stage, 3, 255))
        result, files = self.run_pack(entries)
        self.assertEqual(set(STAGES + ['waxed_' + s for s in STAGES]), set(result['blocks']))
        self.assertEqual([], result['log'])
        sheets = [p for p in files if '/ctm-8x6-47/' in p and p.endswith('.png')]
        for path in sheets:
            im = Image.open(io.BytesIO(files[path]))
            self.assertEqual(45, im.getpixel((5 * 16, 5 * 16))[0])
            self.assertEqual(0, im.getpixel((5 * 16 + 7, 5 * 16 + 7))[3])

    def test_exact_spelling_wins_ambiguous_case_fails_and_missing_name_is_reported(self):
        d = 'assets/minecraft/optifine/ctm/copper_grate/1'
        props = 'matchBlocks=copper_grate\nmethod=fixed\ntiles=45'
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); folder = root / d; folder.mkdir(parents=True)
            (folder / 'copper_grate.properties').write_text(props)
            (folder / '45.PNG').write_bytes(self.image((1, 0, 0, 255)))
            (folder / '45.PnG').write_bytes(self.image((2, 0, 0, 255)))
            log = []; self.assertEqual([], ctm.read_rules(ctm.JavaPack(root), log))
            self.assertIn('45.png', log[-1]); self.assertIn('ambiguous', log[-1])
            (folder / '45.png').write_bytes(self.image((3, 0, 0, 255)))
            pack = ctm.JavaPack(root)
            self.assertEqual(f'{d}/45.png', pack.resolve_path(f'{d}/45.png'))
            rules = ctm.read_rules(pack, [])
            self.assertEqual((3, 0, 0, 255), rules[0].tiles[0].first.getpixel((0, 0)))
            (folder / 'copper_grate.properties').write_text(props.replace('tiles=45', 'tiles=45.PNG'))
            self.assertEqual((1, 0, 0, 255), ctm.read_rules(ctm.JavaPack(root), [])[0].tiles[0].first.getpixel((0, 0)))
            for name in ('45.png', '45.PNG', '45.PnG'): (folder / name).unlink()
            log = []; self.assertEqual([], ctm.read_rules(ctm.JavaPack(root), log))
            self.assertIn('45.PNG', log[-1])

    def test_uppercase_tile_keeps_animation_metadata(self):
        d = 'assets/minecraft/optifine/ctm/grates'
        strip = Image.new('RGBA', (16, 32), (12, 34, 56, 255)); b = io.BytesIO(); strip.save(b, format='PNG')
        _, files = self.run_pack({f'{d}/45.PNG': b.getvalue(), f'{d}/45.png.MCMETA': '{"animation":{"frametime":3}}',
                                 f'{d}/a.properties': 'matchBlocks=copper_grate\nmethod=fixed\ntiles=45'})
        flips = json.loads(files['textures/flipbook_textures.json'])
        self.assertTrue(any(f['ticks_per_frame'] == 3 and len(f['frames']) == 2 for f in flips))

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
