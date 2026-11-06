"""Conservative Fusion import: static full-cube faces, same-block connections.

Layout indices describe Fusion's public format; see tests/fixtures/fusion/README.md
for the upstream examples and independent 256-neighbour-state oracle.
"""
from __future__ import annotations
import io
import json
import re
from dataclasses import dataclass
from PIL import Image

FACES = ('down', 'up', 'north', 'south', 'west', 'east')
GRIDS = {'full': (8, 6), 'pieced': (5, 1), 'compact': (5, 1),
         'simple': (4, 4), 'horizontal': (4, 1), 'vertical': (1, 4)}
FULL = [0,1,2,3,4,5,6,7,44,36,43,42,8,9,10,11,12,13,14,15,45,37,35,34,16,17,18,19,22,23,20,21,38,39,32,40,24,25,26,27,30,31,28,29,46,47,33]
SIMPLE = [0,3,2,11,6,4,10,9,7,15,5,13,14,12,8,1]
# Texture-only inference is deliberately limited to known vanilla full cubes.
CUBES = set('glass tinted_glass iron_block lapis_block gold_block diamond_block emerald_block redstone_block coal_block netherite_block stone cobblestone mossy_cobblestone diorite andesite granite polished_diorite polished_andesite polished_granite obsidian crying_obsidian bricks end_stone end_stone_bricks netherrack nether_bricks red_nether_bricks stone_bricks mossy_stone_bricks cracked_stone_bricks chiseled_stone_bricks tuff polished_tuff tuff_bricks calcite amethyst_block'.split())
COLORS = 'white orange magenta light_blue yellow lime pink gray light_gray cyan purple blue brown green red black'.split()
CUBES.update(c + '_' + kind for c in COLORS for kind in ('stained_glass', 'wool', 'concrete', 'terracotta'))
META = re.compile(r'assets/[^/]+/textures/.+\.png\.mcmeta$')

@dataclass
class Rule:
    path: str
    texture: str
    block: str
    faces: set[str]
    layout: str


def object_json(data):
    value = json.loads(data.decode('utf-8-sig'))
    if not isinstance(value, dict):
        raise ValueError('expected a JSON object')
    return value


def has_fusion(data):
    try:
        return 'fusion' in object_json(data)
    except (ValueError, UnicodeError):
        return b'"fusion"' in data


def texture_paths(pack):
    return {p[:-7] for p, b in pack.files.items() if META.fullmatch(p) and has_fusion(b)}


def resource(value, kind):
    if not isinstance(value, str) or not re.fullmatch(r'(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+', value):
        raise ValueError('invalid resource location')
    ns, name = value.split(':', 1) if ':' in value else ('minecraft', value)
    if any(x in ('', '.', '..') for x in name.split('/')):
        raise ValueError('invalid resource path')
    return f'assets/{ns}/{kind}/{name}' + ('.png' if kind == 'textures' else '.json')


def read_rules(pack, log):
    paths = texture_paths(pack)
    if not paths and not any("/fusion/" in p or ("/models/" in p and b"fusion:model" in b) for p, b in pack.files.items()):
        return []
    metas = {}
    for p in sorted(paths):
        try:
            doc = object_json(pack.files[p + '.mcmeta'])
            f = doc['fusion']
            if not isinstance(f, dict):
                raise ValueError('fusion must be an object')
            typ = f.get('type')
            if typ not in ('connecting', 'fusion:connecting'):
                raise ValueError(f"unsupported Fusion texture type '{typ}'")
            layout = f.get('layout', 'full')
            if layout not in GRIDS:
                raise ValueError(f"unsupported Fusion layout '{layout}'")
            if 'animation' in doc:
                raise ValueError('animated Fusion textures are unsupported')
            extra = sorted(set(f) - {'type', 'layout', 'connections', 'render_type'})
            if extra:
                raise ValueError('unsupported Fusion texture fields: ' + ', '.join(extra))
            if f.get('render_type', 'opaque') not in ('opaque', 'cutout', 'translucent'):
                raise ValueError('unsupported Fusion render_type')
            metas[p] = (layout, f)
        except (ValueError, UnicodeError, TypeError) as e:
            log.append(f'skip {p}.mcmeta: {e}')

    def model(path, seen=()):
        if path in seen or len(seen) >= 32:
            raise ValueError('cyclic or excessive model inheritance')
        if path not in pack.files:
            templates = {
                'cube_all': dict.fromkeys(FACES, '#all'),
                'cube': {f: '#' + f for f in FACES},
                'cube_bottom_top': {f: '#' + ('bottom' if f == 'down' else 'top' if f == 'up' else 'side') for f in FACES},
                'cube_column': {f: '#' + ('end' if f in ('up','down') else 'side') for f in FACES},
            }
            for name, faces in templates.items():
                if path == f'assets/minecraft/models/block/{name}.json':
                    return {}, faces, None
            if path == 'assets/minecraft/models/block/block.json':
                return {}, {}, None
            raise ValueError('missing or unsupported model parent: ' + path)
        doc = object_json(pack.files[path])
        extra = sorted(set(doc) - {'loader','type','parent','textures','connections','elements','ambientocclusion','display','gui_light'})
        if extra:
            raise ValueError('unsupported Fusion model fields: ' + ', '.join(extra))
        if doc.get('loader', 'fusion:model') != 'fusion:model' or doc.get('type', 'connecting') not in ('connecting', 'fusion:connecting'):
            raise ValueError('unsupported model loader/type')
        tex, faces, pred = model(resource(doc['parent'], 'models'), (*seen, path)) if 'parent' in doc else ({}, {}, None)
        textures = doc.get('textures', {})
        if not isinstance(textures, dict) or not all(isinstance(v, str) for v in textures.values()):
            raise ValueError('model textures must be string references')
        tex.update(textures)
        pred = doc.get('connections', pred)
        if 'elements' in doc:
            elements = doc['elements']
            if not isinstance(elements, list) or len(elements) != 1:
                raise ValueError('only one unrotated full-cube element is supported')
            e = elements[0]
            if not isinstance(e, dict) or e.get('from') != [0,0,0] or e.get('to') != [16,16,16] or set(e) - {'from','to','faces','shade'}:
                raise ValueError('custom geometry or rotated elements are unsupported')
            fd = e.get('faces', {})
            if not isinstance(fd, dict) or set(fd) != set(FACES):
                raise ValueError('model must have all six full-cube faces')
            faces = {}
            for face, d in fd.items():
                if not isinstance(d, dict) or set(d) - {'texture','cullface','uv','rotation'} or d.get('rotation',0) != 0 or d.get('uv',[0,0,16,16]) != [0,0,16,16] or d.get('cullface',face) != face:
                    raise ValueError('custom UVs, tinting or face connections are unsupported')
                faces[face] = d.get('texture')
        if not faces:
            raise ValueError('model has no supported cube geometry')
        return tex, faces, pred

    def resolve(ref, tex):
        seen = set()
        while isinstance(ref, str) and ref.startswith('#'):
            if ref in seen:
                raise ValueError('cyclic texture references')
            seen.add(ref)
            ref = tex.get(ref[1:])
        return resource(ref, 'textures')

    def predicate(pred):
        if isinstance(pred, list) and len(pred) == 1:
            pred = pred[0]
        return isinstance(pred, dict) and set(pred) == {'type'} and pred['type'] in ('is_same_block','fusion:is_same_block')

    targets = set()
    for p in metas:
        m = re.fullmatch(r'assets/minecraft/textures/block/([a-z0-9_]+)\.png', p)
        if m:
            targets.add(m[1])
    for p, b in pack.files.items():
        m = re.fullmatch(r'assets/minecraft/(?:models/block|blockstates)/([a-z0-9_]+)\.json', p)
        if m:
            targets.add(m[1])
        if '/fusion/' in p and p.endswith('.json'):
            log.append(f'skip {p}: Fusion modifiers and overrides are unsupported')
    rules = []
    used = set()
    for block in sorted(targets):
        path = f'assets/minecraft/models/block/{block}.json'
        state_path = f'assets/minecraft/blockstates/{block}.json'
        try:
            if state_path in pack.files:
                state = object_json(pack.files[state_path])
                variants = state.get('variants')
                if set(state) != {'variants'} or not isinstance(variants, dict) or set(variants) != {''}:
                    raise ValueError('conditional or multipart blockstates are unsupported')
                v = variants['']
                if not isinstance(v, dict) or set(v) - {'model','x','y','uvlock'} or v.get('x',0) != 0 or v.get('y',0) != 0 or v.get('uvlock',False) is not False:
                    raise ValueError('weighted or rotated blockstate models are unsupported')
                path = resource(v.get('model'), 'models')
            if block not in CUBES:
                # Avoid advertising arbitrary texture names or non-cube geometry as blocks.
                if path in pack.files and b'fusion:model' in pack.files[path]:
                    raise ValueError('only known vanilla full-cube blocks are supported')
                continue
            if path in pack.files:
                tex, faces, pred = model(path)
                by_texture = {}
                for face, ref in faces.items():
                    t = resolve(ref, tex)
                    by_texture.setdefault(t, set()).add(face)
            elif state_path in pack.files:
                raise ValueError('blockstate references a missing model')
            else:
                by_texture = {f'assets/minecraft/textures/block/{block}.png': set(FACES)}
                pred = None
            for texture, faces in sorted(by_texture.items()):
                if texture not in metas:
                    continue
                layout, metadata = metas[texture]
                if not predicate(pred if pred is not None else metadata.get('connections')):
                    raise ValueError('only explicit is_same_block connections are supported (no state, direction, tag or compound predicates)')
                if 'render_type' in metadata:
                    log.append(f'note {texture}.mcmeta: Fusion render_type is not transferred; Bedrock keeps its native block material')
                rules.append(Rule(texture + '.mcmeta', texture, block, faces, layout))
                used.add(texture)
        except (ValueError, TypeError, UnicodeError) as e:
            log.append(f'skip {path}: {e}')
    for texture in sorted(set(metas) - used):
        log.append(f'skip {texture}.mcmeta: no supported full-cube model/texture binding')
    return rules


def tiles(pack, rule, ctm47):
    """Produce the runtime's 47 tiles without passing an atlas through strip cropping."""
    data = pack.get(rule.texture)
    if data is None:
        raise ValueError('missing Fusion texture image')
    atlas = Image.open(io.BytesIO(data)).convert('RGBA')
    cols, rows = GRIDS[rule.layout]
    # Old full layouts reserve two unused rows in an 8x8 square.
    legacy = rule.layout == 'full' and atlas.width == atlas.height
    height = atlas.height * 6 // 8 if legacy else atlas.height
    if atlas.width % cols or height % rows or atlas.width // cols != height // rows:
        raise ValueError(f'Fusion {rule.layout} requires {cols}x{rows} square tiles')
    size = atlas.width // cols
    if size < 1 or (rule.layout == 'pieced' and size % 2):
        raise ValueError('Fusion pieced tiles require an even positive size')
    source = [atlas.crop((x*size,y*size,(x+1)*size,(y+1)*size)) for y in range(rows) for x in range(cols)]
    out = []
    for idx in range(47):
        s = ctm47.index(idx)
        l,d,r,u = (bool(s & b) for b in (1,4,16,64))
        dl,dr,ur,ul = (bool(s & b) for b in (2,8,32,128))
        if rule.layout == 'pieced':
            tile = Image.new('RGBA',(size,size)); h=size//2
            for x,y,a,b,c in ((0,0,l,u,ul),(0,h,l,d,dl),(h,h,r,d,dr),(h,0,r,u,ur)):
                k = (1 if c else 4) if a and b else 3 if a else 2 if b else 0
                tile.paste(source[k].crop((x,y,x+h,y+h)),(x,y))
            out.append(tile)
            continue
        if rule.layout == 'full':
            k=FULL[idx]
        elif rule.layout == 'simple':
            k=SIMPLE[int(l)+2*int(d)+4*int(r)+8*int(u)]
        elif rule.layout == 'horizontal':
            k=2 if l and r else 3 if l else 1 if r else 0
        elif rule.layout == 'vertical':
            k=2 if u and d else 3 if u else 1 if d else 0
        else:  # compact selects WHOLE tiles, unlike pieced/OptiFine ctm_compact.
            count=sum((l,d,r,u)); k=0
            if count == 2:
                k=3 if l and r else 2 if u and d else 0
            elif count == 3:
                if l and r and ((u and ul and ur) or (d and dl and dr)): k=3
                elif u and d and ((l and ul and dl) or (r and ur and dr)): k=2
            elif count == 4:
                k=1 if all((dl,dr,ur,ul)) else 4 if not any((dl,dr,ur,ul)) else 0
        out.append(source[k])
    return out
