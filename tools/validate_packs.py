import os
# Structural checks for converted Bedrock packs.
import sys, json, zipfile, io, glob, re, uuid
from PIL import Image
ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
sys.path.insert(0, os.path.join(ROOT, 'converter'))
VAN = json.load(open(os.path.join(ROOT, 'converter', 'data', 'vanilla_26.60.json')))
sys.path.insert(0, os.path.join(ROOT, 'mod'))
bad = 0
def fail(p, msg):
    global bad; bad += 1; print('FAIL', p, msg)
seen_uuids = {}
for p in sorted(glob.glob(sys.argv[1] + '/*.mcpack')):
    z = zipfile.ZipFile(p); names = set(z.namelist())
    m = json.loads(z.read('manifest.json'))
    h = m['header']
    if m.get('format_version') != 2: fail(p, 'manifest format')
    for u in [h['uuid']] + [mod['uuid'] for mod in m['modules']]:
        uuid.UUID(u)
        if u in seen_uuids: fail(p, f'uuid reused from {seen_uuids[u]}')
        seen_uuids[u] = p
    if m['modules'][0]['type'] != 'resources': fail(p, 'module type')
    tt = json.loads(z.read('textures/terrain_texture.json'))
    if tt.get('texture_name') != 'atlas.terrain': fail(p, 'terrain atlas name')
    td = tt['texture_data']
    bl = json.loads(z.read('blocks.json'))
    aliases = set(td) | set(VAN['terrain_textures'])
    for block, entry in bl.items():
        if block == 'format_version': continue
        if block not in VAN['blocks']: fail(p, f'unknown block {block}')
        for key in ('textures', 'carried_textures'):
            v = entry.get(key)
            vals = [v] if isinstance(v, str) else list((v or {}).values())
            for a in vals:
                if a not in aliases: fail(p, f'{block}.{key} -> unknown alias {a}')
        tex = entry['textures']
        used = [tex] if isinstance(tex, str) else list(tex.values())
        if not any(a.startswith('ctm_') for a in used): fail(p, f'{block} has no ctm alias')
    for alias, d in td.items():
        t = d['textures']
        paths = [t] if isinstance(t, str) else [v['path'] for v in t['variations']]
        if isinstance(t, dict):
            ws = [v['weight'] for v in t['variations']]
            if not all(0.05 <= w <= 1000000 for w in ws): fail(p, f'{alias} weight out of range')
            if not re.search(r'/ctm/base/', paths[0]) or '/ctm/ctm-' not in paths[1] and '/ctm/' not in paths[1]: fail(p, f'{alias} variation order')
        for path in paths:
            if path + '.png' not in names: fail(p, f'{alias}: missing {path}.png')
        sheet = paths[-1]
        mm = re.search(r'/ctm/(\w+)-(\d+)x(\d+)-(\d+)', sheet)
        if not mm: fail(p, f'{alias}: sheet path not parseable {sheet}'); continue
        cols, rows = int(mm.group(2)), int(mm.group(3))
        im = Image.open(io.BytesIO(z.read(sheet + '.png')))
        if im.width % cols or im.height % rows or im.width // cols != im.height // rows: fail(p, f'{alias}: sheet {im.size} vs grid {cols}x{rows}')
    print(f'{p.split("/")[-1]:52s} blocks={len(bl)-1:2d} aliases={len(td):2d} files={len(names)}')
print('problems:', bad)
sys.exit(1 if bad else 0)
