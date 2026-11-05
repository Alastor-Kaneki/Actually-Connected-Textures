# Cut a converted 47-tile sheet back into tiles and re-derive the lookup table from the art.
import sys, io, zipfile, json
from PIL import Image
sys.path.insert(0, 'converter')
pack, member, thr = sys.argv[1], sys.argv[2], int(sys.argv[3]) if len(sys.argv) > 3 else 40
z = zipfile.ZipFile(pack)
name = [n for n in z.namelist() if n.startswith('textures/blocks/ctm/ctm-8x6-47/') and n.endswith(member + '.png')][0]
sheet = Image.open(io.BytesIO(z.read(name))).convert('RGBA')
s = sheet.width // 8
tiles = [sheet.crop(((i % 8) * s, (i // 8) * s, (i % 8 + 1) * s, (i // 8 + 1) * s)) for i in range(47)]
def feats(im):
    w, h = im.size; a = lambda x, y: im.getpixel((x, y))[3] > thr
    mid = range(h // 2 - 2, h // 2 + 2)
    return (all(a(0, y) for y in mid), all(a(w-1, y) for y in mid), all(a(x, 0) for x in mid), all(a(x, h-1) for x in mid),
            a(0, 0), a(w-1, 0), a(0, h-1), a(w-1, h-1))
F = [feats(t) for t in tiles]
if len(set(F)) != 47:
    print(name, 'tiles not distinguishable at threshold', thr, '- unique:', len(set(F))); sys.exit(1)
inv = {f: i for i, f in enumerate(F)}
table = []
for st in range(256):
    L, DL, D, DR, R, UR, U, UL = [(st >> k) & 1 for k in range(8)]
    want = (not L, not R, not U, not D, (not U) or (not L) or (U and L and not UL), (not U) or (not R) or (U and R and not UR),
            (not D) or (not L) or (D and L and not DL), (not D) or (not R) or (D and R and not DR))
    table.append(inv[tuple(bool(x) for x in want)])
from ctm_convert import CTM47
print(name, 'sheet', sheet.size, 'matches standard layout:', table == CTM47)
sys.exit(0 if table == CTM47 else 1)
