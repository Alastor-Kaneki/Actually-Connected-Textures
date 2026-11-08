// Connected Textures for LeviLauncher - platform independent CTM logic.
//
// Tile selection follows the OptiFine/MCPatcher CTM format (the same behaviour
// Continuity implements for Java Edition): 47-tile "ctm", horizontal, vertical,
// horizontal+vertical, vertical+horizontal, top, random, repeat and fixed.
// This header has no game dependencies so it can be unit tested on a PC.
#pragma once

#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>

namespace ctm {

enum class Method : uint8_t { None, Ctm, Horizontal, Vertical, HV, VH, Top, Random, Repeat, Fixed, Overlay };

struct SheetSpec {
    Method method = Method::None;
    uint8_t cols = 0;
    uint8_t rows = 0;
    uint16_t count = 0;
    bool innerSeams = false;
    bool overlay = false;  // drawn as an extra quad on top of the base texture (OptiFine overlay methods)
};

struct Int3 {
    int x, y, z;
};
inline Int3 operator+(Int3 a, Int3 b) { return {a.x + b.x, a.y + b.y, a.z + b.z}; }
inline bool operator==(Int3 a, Int3 b) { return a.x == b.x && a.y == b.y && a.z == b.z; }

// World directions of a face's texture edges as the face is seen from outside:
// left = u0 side, right = u1 side, up = v0 side, down = v1 side.
struct FaceFrame {
    Int3 left, down, right, up, normal;
};

// Bedrock facing order: down, up, north, south, west, east.
inline constexpr FaceFrame kFaceFrames[6] = {
    {{-1, 0, 0}, {0, 0, -1}, {1, 0, 0}, {0, 0, 1}, {0, -1, 0}},   // down
    {{-1, 0, 0}, {0, 0, 1}, {1, 0, 0}, {0, 0, -1}, {0, 1, 0}},    // up
    {{1, 0, 0}, {0, -1, 0}, {-1, 0, 0}, {0, 1, 0}, {0, 0, -1}},   // north
    {{-1, 0, 0}, {0, -1, 0}, {1, 0, 0}, {0, 1, 0}, {0, 0, 1}},    // south
    {{0, 0, -1}, {0, -1, 0}, {0, 0, 1}, {0, 1, 0}, {-1, 0, 0}},   // west
    {{0, 0, 1}, {0, -1, 0}, {0, 0, -1}, {0, 1, 0}, {1, 0, 0}},    // east
};

// 47-tile lookup, index bits: L=1 DL=2 D=4 DR=8 R=16 UR=32 U=64 UL=128.
inline constexpr uint8_t kCtm47[256] = {
    0, 3, 0, 3, 12, 5, 12, 15, 0, 3, 0, 3, 12, 5, 12, 15, 1, 2, 1, 2, 4, 7, 4, 29, 1, 2, 1, 2, 13, 31, 13, 14,
    0, 3, 0, 3, 12, 5, 12, 15, 0, 3, 0, 3, 12, 5, 12, 15, 1, 2, 1, 2, 4, 7, 4, 29, 1, 2, 1, 2, 13, 31, 13, 14,
    36, 17, 36, 17, 24, 19, 24, 43, 36, 17, 36, 17, 24, 19, 24, 43, 16, 18, 16, 18, 6, 46, 6, 21, 16, 18, 16, 18, 28, 9, 28, 22,
    36, 17, 36, 17, 24, 19, 24, 43, 36, 17, 36, 17, 24, 19, 24, 43, 37, 40, 37, 40, 30, 8, 30, 34, 37, 40, 37, 40, 25, 23, 25, 45,
    0, 3, 0, 3, 12, 5, 12, 15, 0, 3, 0, 3, 12, 5, 12, 15, 1, 2, 1, 2, 4, 7, 4, 29, 1, 2, 1, 2, 13, 31, 13, 14,
    0, 3, 0, 3, 12, 5, 12, 15, 0, 3, 0, 3, 12, 5, 12, 15, 1, 2, 1, 2, 4, 7, 4, 29, 1, 2, 1, 2, 13, 31, 13, 14,
    36, 39, 36, 39, 24, 41, 24, 27, 36, 39, 36, 39, 24, 41, 24, 27, 16, 42, 16, 42, 6, 20, 6, 10, 16, 42, 16, 42, 28, 35, 28, 44,
    36, 39, 36, 39, 24, 41, 24, 27, 36, 39, 36, 39, 24, 41, 24, 27, 37, 38, 37, 38, 30, 11, 30, 32, 37, 38, 37, 38, 25, 33, 25, 26,
};

// horizontal: bit1 = left, bit2 = right; vertical: bit1 = down, bit2 = up.
inline constexpr uint8_t kLine4[4] = {3, 2, 0, 1};

// horizontal+vertical fallback (no horizontal neighbours). Bits:
// 32 16 8 / . * . / 1 2 4  (top row: up-left, up, up-right; bottom row: down-left, down, down-right)
inline constexpr uint8_t kHvSecondary[64] = {
    3, 3, 6, 3, 3, 3, 3, 3, 3, 3, 6, 3, 3, 3, 3, 3, 4, 4, 5, 4, 4, 4, 4, 4, 3, 3, 6, 3, 3, 3, 3, 3,
    3, 3, 6, 3, 3, 3, 3, 3, 3, 3, 6, 3, 3, 3, 3, 3, 3, 3, 6, 3, 3, 3, 3, 3, 3, 3, 6, 3, 3, 3, 3, 3,
};
// vertical+horizontal fallback (no vertical neighbours). Bits: left=1, left-up=2,
// right-up=4, right=8, right-down=16, left-down=32 (both diagonals of a side act the same).
inline constexpr uint8_t kVhSecondary[64] = {
    3, 6, 3, 3, 3, 6, 3, 3, 4, 5, 4, 4, 3, 6, 3, 3, 3, 6, 3, 3, 3, 6, 3, 3, 3, 6, 3, 3, 3, 6, 3, 3,
    3, 3, 3, 3, 3, 3, 3, 3, 4, 4, 4, 4, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3,
};

// Parses ".../ctm/<method>-<cols>x<rows>-<count>[-s]/<name>" anywhere in a texture path.
inline bool parseSheetPath(const char* path, size_t len, SheetSpec& out) {
    static constexpr char kMarker[] = "/ctm/";
    const size_t mlen = sizeof(kMarker) - 1;
    if (!path || len < mlen + 8) return false;
    const char* p = nullptr;
    for (size_t i = 0; i + mlen <= len; ++i) {
        if (path[i] == '/' && std::memcmp(path + i, kMarker, mlen) == 0) { p = path + i + mlen; }
    }
    if (!p) return false;
    const char* end = path + len;
    const char* slash = p;
    while (slash < end && *slash != '/') ++slash;
    if (slash >= end) return false;  // the spec must be a directory, followed by a file name
    const char* dash = p;
    while (dash < slash && *dash != '-') ++dash;
    if (dash >= slash) return false;
    const size_t mlen2 = static_cast<size_t>(dash - p);
    struct Name { const char* s; Method m; };
    static constexpr Name kNames[] = {
        {"ctm", Method::Ctm}, {"horizontal", Method::Horizontal}, {"vertical", Method::Vertical},
        {"hv", Method::HV}, {"vh", Method::VH}, {"top", Method::Top}, {"random", Method::Random},
        {"repeat", Method::Repeat}, {"fixed", Method::Fixed}, {"overlay", Method::Overlay},
    };
    Method method = Method::None;
    for (const auto& n : kNames) {
        if (std::strlen(n.s) == mlen2 && std::memcmp(n.s, p, mlen2) == 0) method = n.m;
    }
    if (method == Method::None) return false;
    auto readInt = [&](const char*& c, int& v) {
        if (c >= slash || *c < '0' || *c > '9') return false;
        v = 0;
        while (c < slash && *c >= '0' && *c <= '9') { v = v * 10 + (*c - '0'); if (v > 4096) return false; ++c; }
        return true;
    };
    const char* c = dash + 1;
    int cols = 0, rows = 0, count = 0;
    if (!readInt(c, cols) || c >= slash || *c != 'x') return false;
    ++c;
    if (!readInt(c, rows) || c >= slash || *c != '-') return false;
    ++c;
    if (!readInt(c, count)) return false;
    bool seams = false, overlay = false;
    while (c < slash) {
        if (*c == '-' && c + 1 < slash && c[1] == 's') { seams = true; c += 2; }
        else if (*c == '-' && c + 1 < slash && c[1] == 'o') { overlay = true; c += 2; }
        else { ++c; }
    }
    if (cols < 1 || rows < 1 || cols > 64 || rows > 64 || count < 1 || count > cols * rows) return false;
    const int need = method == Method::Ctm ? 47 : method == Method::HV || method == Method::VH ? 7
                   : method == Method::Horizontal || method == Method::Vertical ? 4 : method == Method::Top ? 2
                   : method == Method::Overlay ? 17 : 1;
    if (count < need) return false;
    out = {method, static_cast<uint8_t>(cols), static_cast<uint8_t>(rows), static_cast<uint16_t>(count), seams, overlay};
    return true;
}

// Tile used when neighbours cannot be read: the "standalone block" tile.
inline int defaultTile(const SheetSpec& s) {
    switch (s.method) {
    case Method::Ctm: return 0;
    case Method::Horizontal: case Method::Vertical: case Method::HV: case Method::VH: return 3;
    default: return 0;
    }
}

inline uint32_t hash3(int x, int y, int z, int salt) {
    uint32_t h = static_cast<uint32_t>(x) * 0x8DA6B343u ^ static_cast<uint32_t>(y) * 0xD8163841u ^
                 static_cast<uint32_t>(z) * 0xCB1AB31Fu ^ static_cast<uint32_t>(salt) * 0x9E3779B9u;
    h ^= h >> 15; h *= 0x2C1B3C6Du; h ^= h >> 12; h *= 0x297A2D39u; h ^= h >> 15;
    return h;
}

inline int floorMod(int a, int m) { int r = a % m; return r < 0 ? r + m : r; }

// Coordinate along a unit axis, matching OptiFine's repeat formulas
// (negative x/z use -c - 1, negative y uses -y).
inline int axisCoord(Int3 p, Int3 d) {
    if (d.x) return d.x > 0 ? p.x : -p.x - 1;
    if (d.y) return d.y > 0 ? p.y : -p.y;
    return d.z > 0 ? p.z : -p.z - 1;
}

// Match: bool(Int3 pos) -> true when the block at pos is the same kind of block.
template <class Match>
int selectTile(const SheetSpec& s, const FaceFrame& f, Int3 pos, Match&& match) {
    auto connected = [&](Int3 q) {
        if (!match(q)) return false;
        return s.innerSeams ? !match(q + f.normal) : true;
    };
    switch (s.method) {
    case Method::Ctm: {
        const Int3 dirs[4] = {f.left, f.down, f.right, f.up};
        int bits = 0;
        for (int i = 0; i < 4; ++i)
            if (connected(pos + dirs[i])) bits |= 1 << (i * 2);
        for (int i = 0; i < 4; ++i) {
            const int j = (i + 1) % 4;
            if ((bits >> (i * 2) & 1) && (bits >> (j * 2) & 1) && connected(pos + dirs[i] + dirs[j]))
                bits |= 1 << (i * 2 + 1);
        }
        return kCtm47[bits];
    }
    case Method::Horizontal:
        return kLine4[(connected(pos + f.left) ? 1 : 0) | (connected(pos + f.right) ? 2 : 0)];
    case Method::Vertical:
        return kLine4[(connected(pos + f.down) ? 1 : 0) | (connected(pos + f.up) ? 2 : 0)];
    case Method::HV: {
        const int h = (connected(pos + f.left) ? 1 : 0) | (connected(pos + f.right) ? 2 : 0);
        if (h) return kLine4[h];
        int b = 0;
        if (connected(pos + f.down)) {
            b |= 2;
            if (connected(pos + f.down + f.left)) b |= 1;
            if (connected(pos + f.down + f.right)) b |= 4;
        }
        if (connected(pos + f.up)) {
            b |= 16;
            if (connected(pos + f.up + f.right)) b |= 8;
            if (connected(pos + f.up + f.left)) b |= 32;
        }
        return kHvSecondary[b];
    }
    case Method::VH: {
        const int v = (connected(pos + f.down) ? 1 : 0) | (connected(pos + f.up) ? 2 : 0);
        if (v) return kLine4[v];
        int b = 0;
        if (connected(pos + f.left)) {
            b |= 1;
            if (connected(pos + f.left + f.down)) b |= 32;
            if (connected(pos + f.left + f.up)) b |= 2;
        }
        if (connected(pos + f.right)) {
            b |= 8;
            if (connected(pos + f.right + f.up)) b |= 4;
            if (connected(pos + f.right + f.down)) b |= 16;
        }
        return kVhSecondary[b];
    }
    case Method::Top:
        if (f.normal.y != 0) return 0;
        return match(pos + Int3{0, 1, 0}) ? 1 : 0;
    case Method::Random: {
        const int salt = f.normal.x * 3 + f.normal.y * 5 + f.normal.z * 7;
        return static_cast<int>(hash3(pos.x, pos.y, pos.z, salt) % s.count);
    }
    case Method::Repeat: {
        const int x = floorMod(axisCoord(pos, f.right), s.cols);
        const int y = floorMod(axisCoord(pos, f.down), s.rows);
        return y * s.cols + x;
    }
    case Method::Fixed:
    default:
        return 0;
    }
}

// ------------------------------------------------------------------ overlays
// OptiFine's overlay methods draw the overlay on top of the base texture.
// different(q) = true when the block at q is NOT the base block (a transition).
// Returns how many overlay quads to emit (0..3) and writes their tile indices.
//
// method=overlay uses 17 tiles: 13 side pieces + 4 corner pieces. A side piece
// is drawn where that side's neighbour is different; corners fill diagonals.
// Tile layout (OptiFine / MCPatcher, also what Continuity implements):
//   0 corner D+R   1 D        2 corner L+D   3 D R     4 L D
//   5 L D R        6 L D U    7 R            8 L D R U  9 L
//  10 R U         11 L U     12 D R U       13 L R U   14 corner R+U
//  15 U           16 corner L+U
// overlay_ctm reuses the 47-tile table with "different" as the connection test.
template <class Different>
int overlayTiles(const SheetSpec& s, const FaceFrame& f, Int3 pos, Different&& different, int tiles[3]) {
    switch (s.method) {
    case Method::Ctm: {  // overlay_ctm
        const Int3 dirs[4] = {f.left, f.down, f.right, f.up};
        int bits = 0;
        for (int i = 0; i < 4; ++i)
            if (different(pos + dirs[i])) bits |= 1 << (i * 2);
        for (int i = 0; i < 4; ++i) {
            const int j = (i + 1) % 4;
            if ((bits >> (i * 2) & 1) && (bits >> (j * 2) & 1) && different(pos + dirs[i] + dirs[j]))
                bits |= 1 << (i * 2 + 1);
        }
        tiles[0] = kCtm47[bits];
        return 1;
    }
    case Method::Overlay: {
        const int mask = (different(pos + f.left) ? 1 : 0) | (different(pos + f.down) ? 2 : 0) |
                         (different(pos + f.right) ? 4 : 0) | (different(pos + f.up) ? 8 : 0);
        if (mask == 0) return 0;  // all neighbours match: no transition, no overlay
        int n = 0;
        switch (mask) {
        case 15: tiles[n++] = 8; break;   // L D R U
        case 7: tiles[n++] = 5; break;    // L D R
        case 11: tiles[n++] = 6; break;   // L D U
        case 13: tiles[n++] = 13; break;  // L R U
        case 14: tiles[n++] = 12; break;  // D R U
        case 5: tiles[n++] = 9; tiles[n++] = 7; break;   // L + R
        case 10: tiles[n++] = 1; tiles[n++] = 15; break; // D + U
        case 3: tiles[n++] = 4; break;    // L D
        case 6: tiles[n++] = 3; break;    // D R
        case 12: tiles[n++] = 10; break;  // R U
        case 9: tiles[n++] = 11; break;   // L U
        case 1: tiles[n++] = 9; break;    // L
        case 2: tiles[n++] = 1; break;    // D
        case 4: tiles[n++] = 7; break;    // R
        case 8: tiles[n++] = 15; break;   // U
        }
        // Corner pieces, only for the one-side and two-adjacent-side cases
        // (matching OptiFine: three or more sides use a single side tile).
        const bool ld = different(pos + f.left + f.down);
        const bool dr = different(pos + f.down + f.right);
        const bool ru = different(pos + f.right + f.up);
        const bool lu = different(pos + f.left + f.up);
        switch (mask) {
        case 3: if (ld) tiles[n++] = 2; break;
        case 6: if (dr) tiles[n++] = 0; break;
        case 12: if (ru) tiles[n++] = 14; break;
        case 9: if (lu) tiles[n++] = 16; break;
        case 1:
            if (ld) tiles[n++] = 2;
            if (lu) tiles[n++] = 16;
            break;
        case 2:
            if (ld) tiles[n++] = 2;
            if (dr) tiles[n++] = 0;
            break;
        case 4:
            if (dr) tiles[n++] = 0;
            if (ru) tiles[n++] = 14;
            break;
        case 8:
            if (ru) tiles[n++] = 14;
            if (lu) tiles[n++] = 16;
            break;
        }
        return n;
    }
    default:  // overlay_random / overlay_repeat / overlay_fixed: one full-face overlay quad
        tiles[0] = selectTile(s, f, pos, [](Int3) { return false; });
        return 1;
    }
}

struct UvRect {
    float u0, v0, u1, v1;
};

inline UvRect tileRect(const SheetSpec& s, int tile, UvRect sheet) {
    if (tile < 0 || tile >= s.count) tile = 0;
    const int col = tile % s.cols, row = tile / s.cols;
    const float du = (sheet.u1 - sheet.u0) / s.cols, dv = (sheet.v1 - sheet.v0) / s.rows;
    return {sheet.u0 + du * col, sheet.v0 + dv * row, sheet.u0 + du * (col + 1), sheet.v0 + dv * (row + 1)};
}

// ---------------------------------------------------------------- glass panes
// Panes are tessellated as free-form quads. For a quad whose UVs lie in the
// sheet, work out which way its texture faces from the vertex data alone.
struct QuadVert {
    float x, y, z, u, v;
};

inline bool inRect(float u, float v, UvRect r) {
    const float eu = (r.u1 - r.u0) * 1e-3f + 1e-6f, ev = (r.v1 - r.v0) * 1e-3f + 1e-6f;
    const float lo_u = r.u0 < r.u1 ? r.u0 : r.u1, hi_u = r.u0 < r.u1 ? r.u1 : r.u0;
    const float lo_v = r.v0 < r.v1 ? r.v0 : r.v1, hi_v = r.v0 < r.v1 ? r.v1 : r.v0;
    return u >= lo_u - eu && u <= hi_u + eu && v >= lo_v - ev && v <= hi_v + ev;
}

inline Int3 cross(Int3 a, Int3 b) {
    return {a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x};
}

// Returns false for quads it cannot classify (e.g. horizontal or degenerate ones).
inline bool quadFrame(const QuadVert q[4], FaceFrame& out) {
    float mn[3] = {q[0].x, q[0].y, q[0].z}, mx[3] = {q[0].x, q[0].y, q[0].z};
    for (int i = 1; i < 4; ++i) {
        const float c[3] = {q[i].x, q[i].y, q[i].z};
        for (int k = 0; k < 3; ++k) { if (c[k] < mn[k]) mn[k] = c[k]; if (c[k] > mx[k]) mx[k] = c[k]; }
    }
    const float eps = 1e-4f;
    int hAxis;  // horizontal in-plane axis: 0 = x, 2 = z
    if (mx[2] - mn[2] < eps && mx[0] - mn[0] > eps) hAxis = 0;
    else if (mx[0] - mn[0] < eps && mx[2] - mn[2] > eps) hAxis = 2;
    else return false;
    if (mx[1] - mn[1] < eps) return false;
    auto coord = [&](const QuadVert& v, int k) { return k == 0 ? v.x : k == 1 ? v.y : v.z; };
    // u follows the horizontal axis, v follows y: pick the vertex pairs with the largest UV change.
    float bestU = 0, bestV = 0, dirU = 0, dirV = 0;
    for (int a = 0; a < 4; ++a)
        for (int b = a + 1; b < 4; ++b) {
            const float du = q[b].u - q[a].u, dv = q[b].v - q[a].v;
            const float dh = coord(q[b], hAxis) - coord(q[a], hAxis), dy = q[b].y - q[a].y;
            if (du * du > bestU && dh * dh > eps * eps) { bestU = du * du; dirU = du * dh; }
            if (dv * dv > bestV && dy * dy > eps * eps) { bestV = dv * dv; dirV = dv * dy; }
        }
    if (dirU == 0 || dirV == 0) return false;
    const Int3 h = hAxis == 0 ? Int3{1, 0, 0} : Int3{0, 0, 1};
    const Int3 nh = {-h.x, -h.y, -h.z};
    out.right = dirU > 0 ? h : nh;
    out.left = dirU > 0 ? nh : h;
    out.up = dirV < 0 ? Int3{0, 1, 0} : Int3{0, -1, 0};
    out.down = dirV < 0 ? Int3{0, -1, 0} : Int3{0, 1, 0};
    out.normal = cross(out.right, out.up);
    return true;
}

// ---------------------------------------------------------------- stacked panes
// Bedrock (1.26.60) draws every pane's top and bottom caps with the edge texture, even
// when the same pane continues above or below, and stops the side faces 0.001 short of
// the block's top and bottom (y+0.001 .. y+0.999). Java skips the caps between stacked
// panes of the same kind, which is what makes connected glass panes seamless there.
// Returns true when [q] is such a cap and should be dropped; otherwise snaps the quad's
// top/bottom edges to the block boundary on the sides where the pane continues.
inline bool fixStackedPaneQuad(QuadVert q[4], int blockY, bool sameAbove, bool sameBelow) {
    if (!sameAbove && !sameBelow) return false;
    float lo = q[0].y, hi = q[0].y;
    for (int i = 1; i < 4; ++i) {
        if (q[i].y < lo) lo = q[i].y;
        if (q[i].y > hi) hi = q[i].y;
    }
    const float top = static_cast<float>(blockY) + 1.f, bottom = static_cast<float>(blockY);
    constexpr float kNear = 0.01f;  // the game draws at 0.001 from the edge
    if (hi - lo < 1e-4f) {          // horizontal quad: a cap
        return (sameAbove && std::fabs(lo - top) < kNear) || (sameBelow && std::fabs(lo - bottom) < kNear);
    }
    for (int i = 0; i < 4; ++i) {
        if (sameAbove && std::fabs(q[i].y - top) < kNear) q[i].y = top;
        else if (sameBelow && std::fabs(q[i].y - bottom) < kNear) q[i].y = bottom;
    }
    return false;
}

}  // namespace ctm
