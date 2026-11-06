"""Offline official-example regressions plus pixel oracles and malformed-input cases."""
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'converter'))
import ctm_convert as ctm
import fusion
FIX = ROOT / 'tests/fixtures/fusion'
SUPPORTED = {'glass': 'glass', 'compact': 'iron_block', 'full': 'lapis_block',
             'glass-legacy': 'glass', 'compact-legacy': 'iron_block', 'full-legacy': 'lapis_block'}


def base_pack(layout='pieced', size=4):
    cols, rows = fusion.GRIDS.get(layout, (5, 1))
    atlas = Image.new('RGBA', (cols*size, rows*size))
    # Unique source pixels: detect orientation, row/column order and quarter cropping.
    for y in range(atlas.height):
        for x in range(atlas.width):
            atlas.putpixel((x,y), (x, y, 123, 255))
    png = io.BytesIO(); atlas.save(png, 'PNG')
    pack = ctm.JavaPack(FIX/'glass')
    pack.files['assets/minecraft/textures/block/glass.png'] = png.getvalue()
    pack.files['assets/minecraft/textures/block/glass.png.mcmeta'] = json.dumps({'fusion': {
        'type':'connecting', 'layout':layout, 'connections':{'type':'is_same_block'}}}).encode()
    return pack, atlas


def outputs(pack, direct=False):
    with tempfile.TemporaryDirectory() as tmp:
        src=Path(tmp)/'input.zip'; dst=Path(tmp)/'output.mcpack'
        with zipfile.ZipFile(src,'w') as z:
            for p,b in pack.files.items(): z.writestr(p,b)
        result=ctm.convert(src,dst,'Fixture','fixture',direct)
        with zipfile.ZipFile(dst) as z: return result,{p:z.read(p) for p in z.namelist()}


class FusionTest(unittest.TestCase):
    def test_official_examples_and_base_texture(self):
        for case, block in SUPPORTED.items():
            for direct in (False, True):
                with self.subTest(case=case,direct=direct):
                    result,files=outputs(ctm.JavaPack(FIX/case),direct)
                    self.assertEqual(result['blocks'],{block:'ctm-8x6-47'})
                    sheet=Image.open(io.BytesIO(files[f'textures/blocks/ctm/ctm-8x6-47/fixture_{block}.png']))
                    size=sheet.width//8
                    self.assertEqual(sheet.height,6*size)
                    target=ctm.java_to_bedrock_texture(block) or ctm.converted_block_texture(block,result['blocks'])
                    self.assertEqual(Image.open(io.BytesIO(files[target+'.png'])).size,(size,size))
                    if not direct:
                        base=Image.open(io.BytesIO(files[f'textures/blocks/ctm/base/fixture_{block}.png']))
                        self.assertEqual(base.tobytes(),sheet.crop((0,0,size,size)).tobytes())
                    self.assertIn('textures/terrain_texture.json',files)
                    self.assertEqual(json.loads(files['manifest.json'])['modules'][0]['type'],'resources')

    def test_all_256_neighbour_states_against_upstream_oracle(self):
        oracle=json.loads((FIX/'layout-oracle.json').read_text())
        for layout in fusion.GRIDS:
            pack,atlas=base_pack(layout)
            rule=fusion.read_rules(pack,[])[0]
            tiles=fusion.tiles(pack,rule,ctm.CTM47)
            cols,_=fusion.GRIDS[layout]
            for mask in range(256):
                actual=tiles[ctm.CTM47[mask]]
                if layout=='pieced':
                    expected=Image.new('RGBA',(4,4))
                    # Fusion's public corner truth table, independent from compact_to_47.
                    table=[0,3,2,4,0,3,2,1]
                    for x,y,h,v,c in ((0,0,0,6,7),(0,2,0,2,1),(2,2,4,2,3),(2,0,4,6,5)):
                        k=table[((mask>>h)&1) + 2*((mask>>v)&1) + 4*((mask>>c)&1)]
                        expected.paste(atlas.crop((k*4+x,y,k*4+x+2,y+2)),(x,y))
                else:
                    k=oracle[layout][mask];x=k%cols*4;y=k//cols*4
                    expected=atlas.crop((x,y,x+4,y+4))
                self.assertEqual(actual.tobytes(),expected.tobytes(),f'{layout} mask {mask}')

    def test_unsupported_official_examples(self):
        for case, reason in [('animated','animated'),('grass','supported'),('random','type'),('overlay','overlay')]:
            log=[]; rules=ctm.read_rules(ctm.JavaPack(FIX/case),log)
            self.assertFalse(rules,case)
            self.assertTrue(any(reason in l for l in log),(case,log))
            with self.assertRaises(SystemExit) as e:
                outputs(ctm.JavaPack(FIX/case))
            self.assertIn('skip ',str(e.exception))

    def test_bad_dimensions_and_missing_image(self):
        pack,_=base_pack('vertical')
        rule=fusion.read_rules(pack,[])[0]
        self.assertEqual(len(fusion.tiles(pack,rule,ctm.CTM47)),47) # Tall static sheet must not be cropped!
        pack.files[rule.texture]=ctm.JavaPack(FIX/'glass').files[rule.texture]
        with self.assertRaises(ValueError): fusion.tiles(pack,rule,ctm.CTM47)
        del pack.files[rule.texture]
        with self.assertRaisesRegex(ValueError,'missing'): fusion.tiles(pack,rule,ctm.CTM47)
        pack,_=base_pack(size=3)
        with self.assertRaisesRegex(ValueError,'even'): fusion.tiles(pack,fusion.read_rules(pack,[])[0],ctm.CTM47)

    def test_models_aliases_inheritance_and_blockstates(self):
        pack,_=base_pack()
        path='assets/minecraft/models/block/glass.json'
        pack.files[path]=json.dumps({'loader':'fusion:model','type':'connecting','parent':'test:block/base','textures':{'all':'#alias','alias':'minecraft:block/glass'},'connections':{'type':'is_same_block'}}).encode()
        pack.files['assets/test/models/block/base.json']=b'{"parent":"block/cube_all"}'
        self.assertEqual(len(fusion.read_rules(pack,[])),1)
        model=json.loads(pack.files[path]); model['textures']['alias']='#all';pack.files[path]=json.dumps(model).encode()
        log=[];self.assertEqual(fusion.read_rules(pack,log),[]);self.assertTrue(any('cyclic texture' in x for x in log))
        model['textures']['alias']='block/glass';model['parent']='block/glass';pack.files[path]=json.dumps(model).encode()
        log=[];self.assertEqual(fusion.read_rules(pack,log),[]);self.assertTrue(any('cyclic' in x for x in log))
        pack,_=base_pack()
        pack.files['assets/minecraft/blockstates/glass.json']=b'{"variants":{"facing=north":{"model":"block/glass"}}}'
        log=[];self.assertEqual(fusion.read_rules(pack,log),[]);self.assertTrue(any('conditional' in x for x in log))

    def test_unsupported_predicates_and_metadata(self):
        for f in [{'type':'connecting','layout':'overlay'}, {'type':'connecting','emissive':True},
                  {'type':'connecting','connections':{'type':'is_same_state'}}, {'type':'connecting','connections':{'type':'or','predicates':[]}},
                  {'type':'connecting','layout':['pieced']}, None]:
            pack,_=base_pack(); pack.files['assets/minecraft/textures/block/glass.png.mcmeta']=json.dumps({'fusion':f}).encode()
            log=[];self.assertEqual(fusion.read_rules(pack,log),[]);self.assertTrue(log)

    def test_mixed_pack_keeps_optifine_priority_and_reports_unsupported(self):
        pack,_=base_pack('overlay')
        pack.files['assets/minecraft/optifine/ctm/glass/a.properties']=b'matchBlocks=glass\nmethod=fixed\ntiles=0'
        b=io.BytesIO();Image.new('RGBA',(4,4),(1,2,3,255)).save(b,'PNG')
        pack.files['assets/minecraft/optifine/ctm/glass/0.png']=b.getvalue()
        result,files=outputs(pack)
        self.assertEqual(result['blocks'],{'glass':'fixed-1x1-1'})
        self.assertTrue(any('overlay' in l for l in result['log']))
        self.assertNotIn('textures/blocks/glass.png',files)
        base=Image.open(io.BytesIO(files['textures/blocks/ctm/base/fixture_glass.png']))
        self.assertEqual(base.getpixel((0,0)),(1,2,3,255))

    def test_model_only_diagnostics_and_optifine_default_guard(self):
        pack,_=base_pack()
        pack.files={'pack.mcmeta':b'{}', 'assets/example/models/item/test.json':b'{"loader":"fusion:model","type":"unknown"}'}
        log=[]
        self.assertEqual(fusion.read_rules(pack,log),[])
        self.assertTrue(any('item/entity/custom' in x for x in log))
        pack,_=base_pack()
        pack.files['assets/minecraft/optifine/ctm/glass/a.properties']=b'matchBlocks=glass\nmethod=fixed\ntiles=<default>'
        result,_=outputs(pack)
        self.assertEqual(result['blocks'],{'glass':'ctm-8x6-47'})
        self.assertTrue(any('missing tile' in x for x in result['log']))

    def test_nested_zip_and_legacy_full_square(self):
        pack,_=base_pack('full')
        atlas=Image.new('RGBA',(32,32),(0,0,0,0));a=Image.open(io.BytesIO(pack.files['assets/minecraft/textures/block/glass.png']));atlas.paste(a)
        b=io.BytesIO();atlas.save(b,'PNG');pack.files['assets/minecraft/textures/block/glass.png']=b.getvalue()
        pack.files={'nested/'+p:v for p,v in pack.files.items()}
        result,_=outputs(pack)
        self.assertIn('glass',result['blocks'])

if __name__=='__main__': unittest.main()
