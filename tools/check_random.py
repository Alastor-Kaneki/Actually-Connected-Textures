# Independent check of the mod's 47-tile selection: expected tiles come from the
# tile artwork (which edges/corners show a border), not from the lookup table.
import sys
from PIL import Image
art, data = sys.argv[1], sys.argv[2]
def feats(i):
    im = Image.open(f'{art}/{i}.png').convert('RGBA'); w, h = im.size
    a = lambda x, y: im.getpixel((x, y))[3] > 40
    mid = range(h // 2 - 2, h // 2 + 2)
    return (all(a(0, y) for y in mid), all(a(w - 1, y) for y in mid), all(a(x, 0) for x in mid), all(a(x, h - 1) for x in mid),
            a(0, 0), a(w - 1, 0), a(0, h - 1), a(w - 1, h - 1))
inv = {feats(i): i for i in range(47)}
# face frames (left, down, right, up) in Bedrock facing order: down up north south west east
F = [((-1,0,0),(0,0,-1),(1,0,0),(0,0,1)), ((-1,0,0),(0,0,1),(1,0,0),(0,0,-1)), ((1,0,0),(0,-1,0),(-1,0,0),(0,1,0)),
     ((-1,0,0),(0,-1,0),(1,0,0),(0,1,0)), ((0,0,-1),(0,-1,0),(0,0,1),(0,1,0)), ((0,0,1),(0,-1,0),(0,0,-1),(0,1,0))]
add = lambda a, b: (a[0]+b[0], a[1]+b[1], a[2]+b[2])
cells = set(); checked = bad = 0
for line in open(data):
    t = line.split()
    if t[0] == 'W': cells = set()
    elif t[0] == 'B': cells.add(tuple(map(int, t[1:4])))
    elif t[0] == 'F':
        x, y, z, f, tile = map(int, t[1:])
        p = (x, y, z); L, D, R, U = F[f]
        cL, cR, cU, cD = (add(p, L) in cells), (add(p, R) in cells), (add(p, U) in cells), (add(p, D) in cells)
        nTL = cU and cL and add(add(p, U), L) not in cells; nTR = cU and cR and add(add(p, U), R) not in cells
        nBL = cD and cL and add(add(p, D), L) not in cells; nBR = cD and cR and add(add(p, D), R) not in cells
        want = (not cL, not cR, not cU, not cD, (not cU) or (not cL) or nTL, (not cU) or (not cR) or nTR,
                (not cD) or (not cL) or nBL, (not cD) or (not cR) or nBR)
        exp = inv[tuple(bool(v) for v in want)]
        checked += 1
        if exp != tile:
            bad += 1
            if bad < 5: print('mismatch', p, f, tile, exp)
print(f'checked {checked} faces, mismatches {bad}')
sys.exit(1 if bad else 0)
