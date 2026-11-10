// Shared runtime texture selection; host tests use the same vector traversal as Android.
#pragma once
#include "CtmLogic.hpp"
#include "Game.hpp"
namespace ctm {
using namespace game;
struct WorldContext { void* tessellator = nullptr; const int* position = nullptr; };
struct WorldScope {
    WorldContext& current;
    WorldContext previous;
    WorldScope(WorldContext& current, void* bt, const int* pos) : current(current), previous(current) {
        current = {bt, pos};
    }
    ~WorldScope() { current = previous; }
    WorldScope(const WorldScope&) = delete;
    WorldScope& operator=(const WorldScope&) = delete;
};
inline bool parseUvSheet(const void* uv, SheetSpec& spec) {
    const auto& path = *uvPath(uv);
    return path.size() >= 20 && path.size() <= 1024 && parseSheetPath(path.data(), path.size(), spec);
}
// All sheets of one face/variant: the first non-overlay sheet (the face's CTM
// tile source) plus every overlay sheet, in pack order.
struct SheetSet {
    const void* normal = nullptr;
    const void* base = nullptr;
    bool conditionalNormal = false;
    bool hasSheet = false;
    int overlayCount = 0;
    const void* overlays[4] = {};
    ctm::SheetSpec specs[4] = {};
};

template<class Context>
SheetSet findSheets(const void* graphics, size_t face, uint32_t variant, Context& context) {
    SheetSet out;
    const char* fb = at<const char*>(graphics, off::BG_Faces);
    const char* fe = at<const char*>(graphics, off::BG_Faces + 8);
    if (!fb || fe <= fb) return out;
    const size_t nf = static_cast<size_t>(fe - fb) / off::BG_FaceItemSize;
    if (!nf) return out;
    const char* item = fb + (face < nf ? face : nf - 1) * off::BG_FaceItemSize;
    const char* vb = at<const char*>(item, off::FaceItem_Variants);
    const char* ve = at<const char*>(item, off::FaceItem_Variants + 8);
    if (!vb || ve <= vb) return out;
    const size_t nv = static_cast<size_t>(ve - vb) / 24;
    if (!nv) return out;
    const char* vec = vb + (variant < nv ? variant : 0) * 24;
    const char* ub = at<const char*>(vec, 0);
    const char* ue = at<const char*>(vec, 8);
    if (!ub || ue <= ub) return out;
    const size_t n = static_cast<size_t>(ue - ub) / off::UV_Size;
    for (size_t i = 0; i < n && i < 256; ++i) {
        const char* uv = ub + i * off::UV_Size;
        ctm::SheetSpec spec;
        if (!parseUvSheet(uv, spec)) { if (!out.base) out.base = uv; continue; }
        out.hasSheet = true;
        if (!context.matches(spec)) continue;
        if (spec.overlay) {
            if (out.overlayCount < 4) {
                out.overlays[out.overlayCount] = uv;
                out.specs[out.overlayCount] = spec;
                ++out.overlayCount;
            }
        } else if (!out.normal || (!out.conditionalNormal && spec.conditions.conditional())) {
            out.normal = uv;
            out.conditionalNormal = spec.conditions.conditional();
        }
    }
    return out;
}

} // namespace ctm
