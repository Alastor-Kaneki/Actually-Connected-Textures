// Host-side tests for CtmLogic.hpp. Build: clang++ -std=c++20 -O2 -Isrc tests/host_test.cpp
// Modes:
//   host_test unit            -> runs assertions, prints "OK" on success
//   host_test random <seed>   -> random 3D worlds, prints "x y z face tile" for every exposed face (ctm)
//   host_test scene           -> preview scene: prints "kind x y tile" lines for the renderer
#include <array>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <random>
#include <set>
#include <string>
#include <tuple>

#include "CtmLogic.hpp"

using namespace ctm;

static int failures = 0;
#define CHECK(c)                                                         \
    do {                                                                 \
        if (!(c)) {                                                      \
            std::fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #c); \
            ++failures;                                                  \
        }                                                                \
    } while (0)

static bool parse(const char* s, SheetSpec& out) { return parseSheetPath(s, std::strlen(s), out); }

static void unitTests() {
    SheetSpec s;
    CHECK(parse("textures/blocks/ctm/ctm-8x6-47/clean_glass", s));
    CHECK(s.method == Method::Ctm && s.cols == 8 && s.rows == 6 && s.count == 47 && !s.innerSeams);
    CHECK(parse("textures/blocks/ctm/ctm-8x6-47-s/x", s) && s.innerSeams);
    CHECK(parse("textures/blocks/ctm/repeat-3x2-6/stone", s) && s.method == Method::Repeat && s.cols == 3);
    CHECK(parse("textures/blocks/ctm/top-2x1-2/sandstone", s) && s.method == Method::Top);
    CHECK(!parse("textures/blocks/glass", s));
    CHECK(!parse("textures/blocks/ctm/base/clean_glass", s));
    CHECK(!parse("textures/blocks/ctm/ctm-8x6-40/x", s));      // too few tiles for ctm
    CHECK(!parse("textures/blocks/ctm/ctm-8x6-47", s));        // no file name after the spec
    // animated sheets are square, and their flipbook frames sit in an anim/ folder next to them
    CHECK(parse("textures/blocks/ctm/ctm-7x7-47/x_seaLantern", s) && s.cols == 7 && s.rows == 7 && s.count == 47);
    CHECK(parse("textures/blocks/ctm/ctm-7x7-47/anim/x_seaLantern", s) && s.method == Method::Ctm && s.cols == 7);
    CHECK(!parse("textures/blocks/ctm/ctm-8x5-47/x", s));      // count > cols*rows
    CHECK(!parse("textures/blocks/ctm/bogus-8x6-47/x", s));

    // tile rects
    SheetSpec c{Method::Ctm, 8, 6, 47, false};
    UvRect r = tileRect(c, 9, {0.f, 0.f, 0.8f, 0.6f});
    CHECK(std::abs(r.u0 - 0.1f) < 1e-6f && std::abs(r.v0 - 0.1f) < 1e-6f && std::abs(r.u1 - 0.2f) < 1e-6f &&
          std::abs(r.v1 - 0.2f) < 1e-6f);

    // single block: isolated tile on every face
    auto none = [](Int3) { return false; };
    for (int f = 0; f < 6; ++f) CHECK(selectTile(c, kFaceFrames[f], {0, 0, 0}, none) == 0);
    // a horizontal row seen from the south: left end, middle, right end
    auto row = [](Int3 p) { return p.y == 0 && p.z == 0 && p.x >= 0 && p.x <= 2; };
    CHECK(selectTile(c, kFaceFrames[3], {0, 0, 0}, row) == 1);  // connected right only
    CHECK(selectTile(c, kFaceFrames[3], {1, 0, 0}, row) == 2);
    CHECK(selectTile(c, kFaceFrames[3], {2, 0, 0}, row) == 3);
    // seen from the north the texture's right side is west, so the ends swap
    CHECK(selectTile(c, kFaceFrames[2], {0, 0, 0}, row) == 3);
    CHECK(selectTile(c, kFaceFrames[2], {2, 0, 0}, row) == 1);
    // a column seen from the east
    auto col = [](Int3 p) { return p.x == 0 && p.z == 0 && p.y >= 0 && p.y <= 2; };
    CHECK(selectTile(c, kFaceFrames[5], {0, 2, 0}, col) == 12);  // connected down only
    CHECK(selectTile(c, kFaceFrames[5], {0, 1, 0}, col) == 24);
    CHECK(selectTile(c, kFaceFrames[5], {0, 0, 0}, col) == 36);
    // 3x3 wall: centre fully connected, corners
    auto wall = [](Int3 p) { return p.z == 0 && p.x >= 0 && p.x <= 2 && p.y >= 0 && p.y <= 2; };
    CHECK(selectTile(c, kFaceFrames[3], {1, 1, 0}, wall) == 26);
    CHECK(selectTile(c, kFaceFrames[3], {0, 2, 0}, wall) == 13);  // top-left corner: right+down
    // L shape without the diagonal: inner corner notch tile
    auto ell = [](Int3 p) { return p.z == 0 && ((p.x == 0 && p.y >= 0 && p.y <= 1) || (p.y == 0 && p.x >= 0 && p.x <= 1)); };
    CHECK(selectTile(c, kFaceFrames[3], {0, 0, 0}, ell) == kCtm47[16 | 64]);  // right+up, no up-right

    // horizontal / vertical / repeat / random
    SheetSpec h{Method::Horizontal, 4, 1, 4, false};
    CHECK(selectTile(h, kFaceFrames[3], {0, 0, 0}, row) == 0);
    CHECK(selectTile(h, kFaceFrames[3], {1, 0, 0}, row) == 1);
    CHECK(selectTile(h, kFaceFrames[3], {2, 0, 0}, row) == 2);
    CHECK(selectTile(h, kFaceFrames[3], {5, 5, 5}, none) == 3);
    SheetSpec v{Method::Vertical, 4, 1, 4, false};
    CHECK(selectTile(v, kFaceFrames[5], {0, 0, 0}, col) == 0);  // connected up only -> bottom piece
    CHECK(selectTile(v, kFaceFrames[5], {0, 2, 0}, col) == 2);
    SheetSpec rp{Method::Repeat, 3, 2, 6, false};
    // south face: x across, -y down (OptiFine: spriteX = x, spriteY = -y)
    CHECK(selectTile(rp, kFaceFrames[3], {4, -3, 0}, none) == (3 % 2) * 3 + 4 % 3);
    // north face: spriteX = -x - 1
    CHECK(selectTile(rp, kFaceFrames[2], {4, 0, 0}, none) == floorMod(-4 - 1, 3));
    SheetSpec rnd{Method::Random, 4, 1, 4, false};
    for (int i = 0; i < 50; ++i) {
        const int t = selectTile(rnd, kFaceFrames[i % 6], {i, i * 3, -i}, none);
        CHECK(t >= 0 && t < 4);
    }
    SheetSpec top{Method::Top, 2, 1, 2, false};
    CHECK(selectTile(top, kFaceFrames[3], {0, 1, 0}, col) == 1);
    CHECK(selectTile(top, kFaceFrames[3], {0, 2, 0}, col) == 0);
    CHECK(selectTile(top, kFaceFrames[1], {0, 1, 0}, col) == 0);  // top face never changes

    // pane quads: frame detection from vertices
    auto quad = [](float x0, float z0, float x1, float z1, bool uAlongPlus) {
        // quad from (x0,z0) to (x1,z1) horizontally, y 0..1; u runs along the horizontal direction
        QuadVert q[4];
        const float ua = uAlongPlus ? 0.25f : 0.75f, ub = uAlongPlus ? 0.75f : 0.25f;
        q[0] = {x0, 1, z0, ua, 0.f};
        q[1] = {x0, 0, z0, ua, 1.f};
        q[2] = {x1, 0, z1, ub, 1.f};
        q[3] = {x1, 1, z1, ub, 0.f};
        FaceFrame f{};
        const bool ok = quadFrame(q, f);
        return std::make_tuple(ok, f);
    };
    {
        auto [ok, f] = quad(0, 0.5f, 1, 0.5f, true);  // u grows to +x: seen from +z (south)
        CHECK(ok && f.right == (Int3{1, 0, 0}) && f.left == (Int3{-1, 0, 0}) && f.up == (Int3{0, 1, 0}) &&
              f.normal == (Int3{0, 0, 1}));
    }
    {
        auto [ok, f] = quad(0, 0.5f, 1, 0.5f, false);  // u grows to -x: seen from -z (north)
        CHECK(ok && f.right == (Int3{-1, 0, 0}) && f.normal == (Int3{0, 0, -1}));
    }
    {
        auto [ok, f] = quad(0.5f, 0, 0.5f, 1, true);  // u grows to +z: seen from -x (west)
        CHECK(ok && f.right == (Int3{0, 0, 1}) && f.normal == (Int3{-1, 0, 0}));
    }
    {
        // flipped v: v grows upward -> up is -y
        QuadVert q[4] = {{0, 1, 0, 0, 1}, {0, 0, 0, 0, 0}, {1, 0, 0, 1, 0}, {1, 1, 0, 1, 1}};
        FaceFrame f{};
        CHECK(quadFrame(q, f) && f.up == (Int3{0, -1, 0}));
    }
    {
        QuadVert flat[4] = {{0, 1, 0, 0, 0}, {1, 1, 0, 1, 0}, {1, 1, 1, 1, 1}, {0, 1, 1, 0, 1}};
        FaceFrame f{};
        CHECK(!quadFrame(flat, f));  // horizontal quads are not classified
    }
    // stacked panes, using the game's own geometry: sides y+0.001..y+0.999, caps at those heights
    {
        const int by = 64;
        auto side = [&]() {
            QuadVert q[4] = {{0, by + 0.999f, .5f, 0, 0}, {0, by + 0.001f, .5f, 0, 1},
                             {1, by + 0.001f, .5f, 1, 1}, {1, by + 0.999f, .5f, 1, 0}};
            return std::to_array(q);
        };
        auto cap = [&](float y) {
            QuadVert q[4] = {{.4375f, y, 0, 0, 0}, {.5625f, y, 0, 1, 0}, {.5625f, y, 1, 1, 1}, {.4375f, y, 1, 0, 1}};
            return std::to_array(q);
        };
        auto topCap = cap(by + 0.999f), bottomCap = cap(by + 0.001f);
        CHECK(!fixStackedPaneQuad(topCap.data(), by, false, false));     // lone pane: caps stay
        CHECK(fixStackedPaneQuad(topCap.data(), by, true, false));       // pane above: top cap goes
        CHECK(!fixStackedPaneQuad(bottomCap.data(), by, true, false));   // ...bottom cap stays
        CHECK(fixStackedPaneQuad(bottomCap.data(), by, false, true));    // pane below: bottom cap goes
        CHECK(!fixStackedPaneQuad(topCap.data(), by, false, true));
        auto s1 = side();
        CHECK(!fixStackedPaneQuad(s1.data(), by, true, false));          // sides are kept...
        CHECK(s1[0].y == by + 1.f && s1[3].y == by + 1.f);               // ...top edge closed
        CHECK(s1[1].y == by + 0.001f && s1[2].y == by + 0.001f);         // bottom edge untouched
        auto s2 = side();
        CHECK(!fixStackedPaneQuad(s2.data(), by, true, true));
        CHECK(s2[0].y == by + 1.f && s2[1].y == float(by) && s2[2].y == float(by) && s2[3].y == by + 1.f);
        CHECK(s2[0].u == 0 && s2[1].v == 1);                             // UVs untouched
        auto s3 = side();
        CHECK(!fixStackedPaneQuad(s3.data(), by, false, false));
        CHECK(s3[0].y == by + 0.999f);                                   // lone pane: untouched
        auto neg = cap(-64 + 0.999f);                                    // below y=0 too
        CHECK(fixStackedPaneQuad(neg.data(), -64, true, false));
    }
    CHECK(inRect(0.5f, 0.5f, {0.4f, 0.4f, 0.6f, 0.6f}));
    CHECK(!inRect(0.7f, 0.5f, {0.4f, 0.4f, 0.6f, 0.6f}));

    // ---------------------------------------------------------------- overlays
    CHECK(parse("textures/blocks/ctm/overlay-6x3-17-o/grass_top_overlay", s) && s.method == Method::Overlay &&
          s.count == 17 && s.overlay);
    CHECK(parse("textures/blocks/ctm/ctm-8x6-47-o/grass", s) && s.method == Method::Ctm && s.overlay);
    CHECK(parse("textures/blocks/ctm/ctm-8x6-47-s-o/x", s) && s.innerSeams && s.overlay);
    CHECK(parse("textures/blocks/ctm/random-4x1-4-o/x", s) && s.method == Method::Random && s.overlay);
    CHECK(!parse("textures/blocks/ctm/overlay-6x3-16-o/x", s));  // overlay needs all 17 tiles

    SheetSpec ov{Method::Overlay, 6, 3, 17, false, true};
    const FaceFrame& sf = kFaceFrames[3];  // south: left=-x down=-y right=+x up=+y
    int tiles[3] = {};
    // 3x3 wall, centre: all four neighbours match -> no overlay
    auto full = [](Int3 p) { return p.z == 0 && p.x >= -1 && p.x <= 1 && p.y >= -1 && p.y <= 1; };
    auto diffFull = [&](Int3 q) { return !full(q); };
    CHECK(overlayTiles(ov, sf, {0, 0, 0}, diffFull, tiles) == 0);
    // isolated block: every neighbour is different -> the full overlay tile
    auto single = [](Int3 p) { return p.x == 0 && p.y == 0 && p.z == 0; };
    auto diffSingle = [&](Int3 q) { return !single(q); };
    CHECK(overlayTiles(ov, sf, {0, 0, 0}, diffSingle, tiles) == 1 && tiles[0] == 8);
    // horizontal row: D and U differ -> tiles D and U (two quads)
    auto row2 = [](Int3 p) { return p.z == 0 && p.y == 0 && p.x >= -2 && p.x <= 2; };
    auto diffRow = [&](Int3 q) { return !row2(q); };
    CHECK(overlayTiles(ov, sf, {0, 0, 0}, diffRow, tiles) == 2 && tiles[0] == 1 && tiles[1] == 15);
    // vertical column: L and R differ -> tiles L and R
    auto col2 = [](Int3 p) { return p.z == 0 && p.x == 0 && p.y >= -2 && p.y <= 2; };
    auto diffCol = [&](Int3 q) { return !col2(q); };
    CHECK(overlayTiles(ov, sf, {0, 0, 0}, diffCol, tiles) == 2 && tiles[0] == 9 && tiles[1] == 7);
    // L+D differ and the L+D diagonal differs too: side pair + corner
    auto square = [](Int3 p) { return p.z == 0 && p.x >= 0 && p.x <= 1 && p.y >= 0 && p.y <= 1; };
    auto diffSquare = [&](Int3 q) { return !square(q); };
    CHECK(overlayTiles(ov, sf, {0, 0, 0}, diffSquare, tiles) == 2 && tiles[0] == 4 && tiles[1] == 2);
    // only D differs: D tile plus both lower corners (three quads)
    auto tee = [](Int3 p) {
        return p.z == 0 && ((p.x == 0 && p.y == 0) || (p.x == 1 && p.y == 0) || (p.x == -1 && p.y == 0) ||
                            (p.x == 0 && p.y == 1));
    };
    auto diffTee = [&](Int3 q) { return !tee(q); };
    CHECK(overlayTiles(ov, sf, {0, 0, 0}, diffTee, tiles) == 3 && tiles[0] == 1 && tiles[1] == 2 && tiles[2] == 0);
    // three differing sides: one side tile, no corners
    auto upOnly = [](Int3 p) { return p.z == 0 && p.x == 0 && p.y >= 0 && p.y <= 1; };
    auto diffUpOnly = [&](Int3 q) { return !upOnly(q); };
    CHECK(overlayTiles(ov, sf, {0, 0, 0}, diffUpOnly, tiles) == 1 && tiles[0] == 5);
    // L+D differ but their diagonal matches: side pair only, no corner
    auto withDiag = [](Int3 p) {
        return p.z == 0 && ((p.x >= 0 && p.x <= 1 && p.y >= 0 && p.y <= 1) || (p.x == -1 && p.y == -1));
    };
    auto diffWithDiag = [&](Int3 q) { return !withDiag(q); };
    CHECK(overlayTiles(ov, sf, {0, 0, 0}, diffWithDiag, tiles) == 1 && tiles[0] == 4);

    // overlay_ctm: same 47-tile table with the connection test inverted
    SheetSpec oc{Method::Ctm, 8, 6, 47, false, true};
    CHECK(overlayTiles(oc, sf, {0, 0, 0}, diffFull, tiles) == 1 && tiles[0] == 0);
    CHECK(overlayTiles(oc, sf, {0, 0, 0}, diffSingle, tiles) == 1 && tiles[0] == 26);
    // overlay_random/repeat/fixed: exactly one overlay quad
    SheetSpec ornd{Method::Random, 4, 1, 4, false, true};
    CHECK(overlayTiles(ornd, sf, {3, 7, -2}, diffSingle, tiles) == 1 && tiles[0] >= 0 && tiles[0] < 4);
    SheetSpec ofix{Method::Fixed, 1, 1, 1, false, true};
    CHECK(overlayTiles(ofix, sf, {0, 0, 0}, diffFull, tiles) == 1 && tiles[0] == 0);
}

static void randomWorlds(unsigned seed) {
    std::mt19937 rng(seed);
    SheetSpec c{Method::Ctm, 8, 6, 47, false};
    for (int world = 0; world < 200; ++world) {
        std::set<std::tuple<int, int, int>> cells;
        const int density = 30 + static_cast<int>(rng() % 50);
        for (int x = 0; x < 6; ++x)
            for (int y = 0; y < 6; ++y)
                for (int z = 0; z < 6; ++z)
                    if (static_cast<int>(rng() % 100) < density) cells.insert({x, y, z});
        auto match = [&](Int3 p) { return cells.count({p.x, p.y, p.z}) != 0; };
        std::printf("W %d\n", world);
        for (auto [x, y, z] : cells) std::printf("B %d %d %d\n", x, y, z);
        for (auto [x, y, z] : cells)
            for (int f = 0; f < 6; ++f) {
                const Int3 p{x, y, z};
                if (match(p + kFaceFrames[f].normal)) continue;  // hidden face
                std::printf("F %d %d %d %d %d\n", x, y, z, f, selectTile(c, kFaceFrames[f], p, match));
            }
    }
}

// Preview scene seen from the south: a glass wall with openings, plus a pane run.
static const char* kWall[] = {
    "##########....###",
    "#....#..##.##.#.#",
    "#....#..#..##.###",
    "######..##.......",
    "##..##.....#####.",
    "##..######.#...#.",
    "...........#####.",
};

static void scene() {
    const int rows = sizeof(kWall) / sizeof(kWall[0]);
    auto solid = [&](int x, int y) {
        const int r = rows - 1 - y;
        return r >= 0 && r < rows && x >= 0 && x < static_cast<int>(std::strlen(kWall[r])) && kWall[r][x] == '#';
    };
    SheetSpec c{Method::Ctm, 8, 6, 47, false};
    auto blocks = [&](Int3 p) { return p.z == 0 && solid(p.x, p.y); };
    for (int y = 0; y < rows; ++y)
        for (int x = 0; x < static_cast<int>(std::strlen(kWall[0])); ++x)
            if (solid(x, y)) std::printf("block %d %d %d\n", x, y, selectTile(c, kFaceFrames[3], {x, y, 0}, blocks));
    // panes: the same layout, drawn as pane quads whose u grows to +x (south side)
    for (int y = 0; y < rows; ++y)
        for (int x = 0; x < static_cast<int>(std::strlen(kWall[0])); ++x) {
            if (!solid(x, y)) continue;
            QuadVert q[4] = {{float(x), float(y + 1), 0.5f, 0.f, 0.f},
                             {float(x), float(y), 0.5f, 0.f, 1.f},
                             {float(x + 1), float(y), 0.5f, 1.f, 1.f},
                             {float(x + 1), float(y + 1), 0.5f, 1.f, 0.f}};
            FaceFrame f{};
            int t = defaultTile(c);
            if (quadFrame(q, f)) t = selectTile(c, f, {x, y, 0}, blocks);
            std::printf("pane %d %d %d\n", x, y, t);
        }
}

int main(int argc, char** argv) {
    const char* mode = argc > 1 ? argv[1] : "unit";
    if (!std::strcmp(mode, "unit")) {
        unitTests();
        std::printf(failures ? "FAILED (%d)\n" : "OK\n", failures);
        return failures ? 1 : 0;
    }
    if (!std::strcmp(mode, "random")) {
        randomWorlds(argc > 2 ? static_cast<unsigned>(std::atoi(argv[2])) : 1u);
        return 0;
    }
    if (!std::strcmp(mode, "scene")) {
        scene();
        return 0;
    }
    return 2;
}
