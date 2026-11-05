# Derive the OptiFine 47-tile lookup from tile artwork, then compare with Continuity's table.
import sys
from PIL import Image
d = sys.argv[1]
def feats(i):
    im = Image.open(f'{d}/{i}.png').convert('RGBA'); w, h = im.size
    a = lambda x, y: im.getpixel((x, y))[3] > 40
    mid = range(h // 2 - 2, h // 2 + 2)
    L = all(a(0, y) for y in mid); R = all(a(w - 1, y) for y in mid)
    T = all(a(x, 0) for x in mid); B = all(a(x, h - 1) for x in mid)
    return (L, R, T, B, a(0, 0), a(w - 1, 0), a(0, h - 1), a(w - 1, h - 1))
tiles = {i: feats(i) for i in range(47)}
assert len(set(tiles.values())) == 47, "tiles not distinguishable"
inv = {v: k for k, v in tiles.items()}
# bits: L=1 DL=2 D=4 DR=8 R=16 UR=32 U=64 UL=128
table = []
for s in range(256):
    L, DL, D, DR, R, UR, U, UL = [(s >> k) & 1 for k in range(8)]
    # corner bits only count when both edges connected (as in OptiFine/Continuity)
    cTL = U and L and not UL; cTR = U and R and not UR; cBL = D and L and not DL; cBR = D and R and not DR
    want = (not L, not R, not U, not D,
            (not U) or (not L) or cTL, (not U) or (not R) or cTR,
            (not D) or (not L) or cBL, (not D) or (not R) or cBR)
    table.append(inv[tuple(bool(x) for x in want)])
print(table)
if len(sys.argv) > 2:
    import re
    src = open(sys.argv[2]).read()
    m = re.search(r'SPRITE_INDEX_MAP = new int\[\] \{(.*?)\};', src, re.S)
    cont = [int(x) for x in re.findall(r'\d+', m.group(1))]
    print('matches Continuity:', cont == table, sum(a != b for a, b in zip(cont, table)), 'differences')
