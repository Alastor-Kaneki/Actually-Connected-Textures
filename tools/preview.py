# Render what each converted pack looks like with the mod: a glass wall (block faces),
# the same shape in stained glass, and as panes, using the mod's own tile choices.
import sys, io, zipfile, json, glob, os
from PIL import Image, ImageDraw, ImageFont
scene = [l.split() for l in open(sys.argv[1])]
out = sys.argv[2]
packs = sorted(glob.glob(sys.argv[3] + '/*.mcpack'))
T = 16; S = 3  # tile px, scale
def sheet_for(z, block, slug):
    names = [n for n in z.namelist() if n == f'textures/blocks/ctm/ctm-8x6-47/{slug}_{block}.png']
    if not names: return None
    im = Image.open(io.BytesIO(z.read(names[0]))).convert('RGBA')
    return im
def backdrop(w, h):
    bg = Image.new('RGBA', (w, h))
    d = ImageDraw.Draw(bg)
    for y in range(h):
        c = (int(120 + 80 * y / h), int(170 + 50 * y / h), 235, 255)
        d.line([(0, y), (w, y)], fill=c)
    for x in range(0, w, 40): d.rectangle([x, h * 0.62, x + 18, h], fill=(96, 140, 70, 255))
    return bg
cols = max(int(l[1]) for l in scene) + 1; rows = max(int(l[2]) for l in scene) + 1
panel_w, panel_h = cols * T * S, rows * T * S
font = ImageFont.load_default()
tiles_needed = [('block', 'glass'), ('block', 'light_blue_stained_glass'), ('pane', 'glass_pane'), ('block', 'tinted_glass')]
label_h = 18
sheet_img = Image.new('RGBA', (len(tiles_needed) * (panel_w + 12) + 12, len(packs) * (panel_h + label_h + 14) + 10), (30, 30, 36, 255))
dr = ImageDraw.Draw(sheet_img)
for pi, p in enumerate(packs):
    z = zipfile.ZipFile(p)
    name = json.loads(z.read('manifest.json'))['header']['name']
    y0 = 10 + pi * (panel_h + label_h + 14)
    dr.text((12, y0), name, fill=(240, 240, 240, 255), font=font)
    for ti, (kind, block) in enumerate(tiles_needed):
        x0 = 12 + ti * (panel_w + 12)
        panel = backdrop(panel_w, panel_h)
        sh = sheet_for(z, block, os.path.basename(p)[:-len('.mcpack')])
        if sh is None:
            ImageDraw.Draw(panel).text((6, 6), f'(no {block})', fill=(0, 0, 0, 255), font=font)
        else:
            s = sh.width // 8
            for kind2, x, y, tile in scene:
                if kind2 != kind: continue
                x, y, tile = int(x), int(y), int(tile)
                t = sh.crop(((tile % 8) * s, (tile // 8) * s, (tile % 8 + 1) * s, (tile // 8 + 1) * s)).resize((T * S, T * S), Image.NEAREST)
                panel.alpha_composite(t, (x * T * S, (rows - 1 - y) * T * S))
        sheet_img.alpha_composite(panel, (x0, y0 + label_h))
        dr.text((x0 + panel_w - 120, y0), f'{block}' + (' (pane)' if kind == 'pane' else ''), fill=(180, 180, 190, 255), font=font)
sheet_img.convert('RGB').save(out, optimize=True)
print(out, sheet_img.size)
