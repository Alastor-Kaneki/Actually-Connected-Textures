// Connected Textures for LeviLauncher (Minecraft Bedrock 1.26.60 / 1.26.62, arm64).
//
// Works together with Bedrock packs made by ctm_convert.py. Each converted
// block's texture is a variations list [base texture, tile sheet]. While a
// chunk is being built this mod hands the tile sheet to the tessellator and
// replaces the UVs of every face with the tile the CTM rules pick.
#include <android/log.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <jni.h>
#include <sys/stat.h>
#include <unistd.h>

#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <span>
#include <string>
#include <string_view>
#include <unordered_map>
#include <vector>

#include "CtmLogic.hpp"
#include "Game.hpp"
#include "pl/memory/Hook.hpp"
#include "pl/memory/Signature.hpp"

#define CTM_EXPORT extern "C" __attribute__((visibility("default")))
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "ConnectedTextures", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "ConnectedTextures", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "ConnectedTextures", __VA_ARGS__)

extern "C" {
// Preloader's C lifecycle ABI (src/pl/legacy/LegacyMod.h).
typedef struct PLModInfo {
    uint32_t size;
    const char* mod_id;
    const char* display_name;
    const char* author;
    const char* version;
    const char* entry_path;
    const char* entry_file_name;
    const char* library_path;
    const char* icon_path;
    const char* manifest_path;
    const char* mod_root_path;
} PLModInfo;
}

namespace {

using namespace game;

// ------------------------------------------------------------------ state
struct Config {
    bool enabled = true;
    bool blocks = true;
    bool panes = true;
};
Config g_cfg;
std::atomic<bool> g_enabled{false};
bool g_hooked = false;
uintptr_t g_addr[Sig_Count] = {};

FaceFn g_faceOrig[6] = {};
GetTextureFn g_getTextureOrig = nullptr;
PaneFn g_paneOrig = nullptr;
UseNewFn g_useNewOrig = nullptr;
BuildFn g_buildOrig = nullptr;
VertexUVFn g_vertexUVOrig = nullptr;
GetBlockFn g_getBlock = nullptr;
UvCopyFn g_uvCopy = nullptr;
UvDtorFn g_uvDtor = nullptr;
GraphicsForBlockFn g_graphicsForBlock = nullptr;

std::atomic<int> g_paneCaptures{0};  // threads currently inside a pane with a tile sheet
std::string g_statusPath;               // <mod folder>/status.txt, readable without adb
std::atomic<bool> g_reportedSheet{false};

void status(const char* line, bool truncate = false) {
    if (g_statusPath.empty()) return;
    if (FILE* f = std::fopen(g_statusPath.c_str(), truncate ? "wb" : "ab")) {
        std::fputs(line, f);
        std::fputc('\n', f);
        std::fclose(f);
    }
}

struct PaneState {
    bool active = false;
    bool haveSheet = false;
    void* tess = nullptr;
    const void* legacy = nullptr;
    void* region = nullptr;
    ctm::Int3 pos{};
    bool sameAbove = false;  // the same pane block continues above / below this one
    bool sameBelow = false;
    ctm::SheetSpec spec{};
    ctm::UvRect rect{};
    int pending = 0;
    ctm::QuadVert verts[4]{};
};

struct ThreadState {
    int terrainDepth = 0;  // > 0 while RenderChunkBuilder::build runs on this thread
    PaneState pane;
};
thread_local ThreadState t_state;

// ------------------------------------------------------------------ helpers
bool sheetSpecOf(const void* uv, ctm::SheetSpec& spec) {
    const std::string* p = uvPath(uv);
    const size_t n = p->size();
    if (n < 20 || n > 1024) return false;
    return ctm::parseSheetPath(p->data(), n, spec);
}

ctm::UvRect rectOf(const void* uv) {
    return {at<float>(uv, off::UV_U0), at<float>(uv, off::UV_V0), at<float>(uv, off::UV_U1), at<float>(uv, off::UV_V1)};
}

const void* blockAt(void* region, ctm::Int3 q) {
    const int p[3] = {q.x, q.y, q.z};
    return g_getBlock(region, p, 0);
}

// Variations of one face/variant of a BlockGraphics, mirroring BlockGraphics::getTexture.
// minCount = 2 for the converter's [base, sheet] lists; 1 also accepts "direct" packs.
const void* findSheetVariation(const void* graphics, size_t face, uint32_t variant, size_t minCount = 2) {
    const char* fb = at<const char*>(graphics, off::BG_Faces);
    const char* fe = at<const char*>(graphics, off::BG_Faces + 8);
    if (!fb || fe <= fb) return nullptr;
    const size_t nf = static_cast<size_t>(fe - fb) / off::BG_FaceItemSize;
    if (!nf) return nullptr;
    const char* item = fb + (face < nf ? face : nf - 1) * off::BG_FaceItemSize;
    const char* vb = at<const char*>(item, off::FaceItem_Variants);
    const char* ve = at<const char*>(item, off::FaceItem_Variants + 8);
    if (!vb || ve <= vb) return nullptr;
    const size_t nv = static_cast<size_t>(ve - vb) / 24;
    if (!nv) return nullptr;
    const char* vec = vb + (variant < nv ? variant : 0) * 24;
    const char* ub = at<const char*>(vec, 0);
    const char* ue = at<const char*>(vec, 8);
    if (!ub || ue <= ub) return nullptr;
    const size_t n = static_cast<size_t>(ue - ub) / off::UV_Size;
    if (n < minCount) return nullptr;
    ctm::SheetSpec spec;
    for (size_t i = 0; i < n && i < 256; ++i) {
        const char* uv = ub + i * off::UV_Size;
        if (sheetSpecOf(uv, spec)) return uv;
    }
    return nullptr;
}

bool graphicsHasSheet(const void* graphics) {
    const char* fb = at<const char*>(graphics, off::BG_Faces);
    const char* fe = at<const char*>(graphics, off::BG_Faces + 8);
    if (!fb || fe <= fb) return false;
    const size_t nf = static_cast<size_t>(fe - fb) / off::BG_FaceItemSize;
    for (size_t f = 0; f < nf && f < 6; ++f) {
        const char* item = fb + f * off::BG_FaceItemSize;
        const char* vb = at<const char*>(item, off::FaceItem_Variants);
        const char* ve = at<const char*>(item, off::FaceItem_Variants + 8);
        if (!vb || ve <= vb) continue;
        const size_t nv = static_cast<size_t>(ve - vb) / 24;
        for (size_t v = 0; v < nv && v < 64; ++v)
            if (findSheetVariation(graphics, f, static_cast<uint32_t>(v), 1)) return true;
    }
    return false;
}

// Lock-free cache: Block* -> uses CTM sheets (1) or not (2).
constexpr size_t kCacheSize = 1u << 15;
struct CacheSlot {
    std::atomic<uintptr_t> key{0};
    std::atomic<uint8_t> value{0};
};
CacheSlot g_blockCache[kCacheSize];

bool isCtmBlock(const void* block) {
    const uintptr_t key = reinterpret_cast<uintptr_t>(block);
    size_t i = (key >> 4) * 0x9E3779B97F4A7C15ull >> (64 - 15);
    for (size_t probe = 0; probe < 32; ++probe, i = (i + 1) & (kCacheSize - 1)) {
        const uintptr_t k = g_blockCache[i].key.load(std::memory_order_acquire);
        if (k == key) {
            const uint8_t v = g_blockCache[i].value.load(std::memory_order_acquire);
            if (v) return v == 1;
            break;  // being filled by another thread: compute below
        }
        if (k == 0) {
            const void* graphics = g_graphicsForBlock(block);
            const bool yes = graphics && graphicsHasSheet(graphics);
            uintptr_t expected = 0;
            if (g_blockCache[i].key.compare_exchange_strong(expected, key, std::memory_order_acq_rel) || expected == key)
                g_blockCache[i].value.store(yes ? 1 : 2, std::memory_order_release);
            return yes;
        }
    }
    const void* graphics = g_graphicsForBlock(block);
    return graphics && graphicsHasSheet(graphics);
}

int cubeTile(void* bt, const void* block, const float* p, int face, const ctm::SheetSpec& spec) {
    if (t_state.terrainDepth <= 0 || !block || !p) return ctm::defaultTile(spec);
    void* region = at<void*>(bt, off::BT_Region);
    if (!region) return ctm::defaultTile(spec);
    const ctm::Int3 pos{static_cast<int>(std::floor(p[0])), static_cast<int>(std::floor(p[1])),
                        static_cast<int>(std::floor(p[2]))};
    const void* legacy = legacyOf(block);
    if (!legacy || legacyOf(blockAt(region, pos)) != legacy) return ctm::defaultTile(spec);
    auto match = [&](ctm::Int3 q) { return legacyOf(blockAt(region, q)) == legacy; };
    return ctm::selectTile(spec, ctm::kFaceFrames[face], pos, match);
}

struct FlipGuard {
    uint8_t* flip;
    uint8_t* xflip;
    uint8_t oldFlip, oldX;
    FlipGuard(void* bt, int face)
        : flip(&at<uint8_t>(bt, off::BT_FlipFace + face)), xflip(&at<uint8_t>(bt, off::BT_XFlipTexture)),
          oldFlip(*flip), oldX(*xflip) {
        *flip = off::FlipDontRotate;
        *xflip = 0;
    }
    ~FlipGuard() {
        *flip = oldFlip;
        *xflip = oldX;
    }
};

struct UvCopy {
    alignas(16) unsigned char storage[off::UV_Size];
    explicit UvCopy(const void* src) { g_uvCopy(storage, src); }
    ~UvCopy() { g_uvDtor(storage); }
};

// ------------------------------------------------------------------ hooks
template <int F>
void faceDetour(void* bt, void* tess, const void* block, const float* p, const void* tex) {
    const FaceFn orig = g_faceOrig[F];
    ctm::SheetSpec spec;
    if (tex && bt && sheetSpecOf(tex, spec) &&
        !(at<uint8_t>(bt, off::BT_UseInternalTexture) && tex == static_cast<char*>(bt) + off::BT_InternalTexture)) {
        const int tile = g_enabled.load(std::memory_order_relaxed) && g_cfg.blocks ? cubeTile(bt, block, p, F, spec)
                                                                                   : ctm::defaultTile(spec);
        const ctm::UvRect r = ctm::tileRect(spec, tile, rectOf(tex));
        UvCopy uv(tex);
        at<float>(uv.storage, off::UV_U0) = r.u0;
        at<float>(uv.storage, off::UV_V0) = r.v0;
        at<float>(uv.storage, off::UV_U1) = r.u1;
        at<float>(uv.storage, off::UV_V1) = r.v1;
        const uint16_t w = at<uint16_t>(tex, off::UV_W), h = at<uint16_t>(tex, off::UV_H);
        at<uint16_t>(uv.storage, off::UV_W) = static_cast<uint16_t>(w / spec.cols ? w / spec.cols : 1);
        at<uint16_t>(uv.storage, off::UV_H) = static_cast<uint16_t>(h / spec.rows ? h / spec.rows : 1);
        at<uint8_t>(uv.storage, off::UV_Isotropic) = 0;
        FlipGuard guard(bt, F);
        orig(bt, tess, block, p, uv.storage);
        return;
    }
    orig(bt, tess, block, p, tex);
}

const void* getTextureDetour(void* graphics, const int* pos, size_t face, uintptr_t variant) {
    const void* res = g_getTextureOrig(graphics, pos, face, static_cast<uint32_t>(variant));
    if (!graphics || !g_enabled.load(std::memory_order_relaxed)) return res;
    ThreadState& ts = t_state;
    PaneState& ps = ts.pane;
    ctm::SheetSpec spec;
    const void* sheet = nullptr;
    if (res && sheetSpecOf(res, spec)) {
        sheet = res;  // "direct" pack: the block's texture is the sheet itself
    } else if (ts.terrainDepth > 0 && (ps.active ? g_cfg.panes : g_cfg.blocks)) {
        sheet = findSheetVariation(graphics, face, static_cast<uint32_t>(variant));
    }
    if (!sheet) return res;
    if (!g_reportedSheet.exchange(true, std::memory_order_relaxed)) {
        const std::string line = "Connected textures active - first tile sheet in use: " + *uvPath(sheet);
        LOGI("%s", line.c_str());
        status(line.c_str());
    }
    if (ps.active && face == 0) {
        ps.haveSheet = sheetSpecOf(sheet, ps.spec);
        ps.rect = rectOf(sheet);
    }
    return sheet;
}

void emitQuad(PaneState& ps) {
    // no caps between stacked panes of the same kind, and no gap either (see CtmLogic.hpp)
    if (g_cfg.panes && ctm::fixStackedPaneQuad(ps.verts, ps.pos.y, ps.sameAbove, ps.sameBelow)) return;
    bool inside = true;
    for (int i = 0; i < 4; ++i) inside = inside && ctm::inRect(ps.verts[i].u, ps.verts[i].v, ps.rect);
    ctm::UvRect r{};
    if (inside) {
        int tile = ctm::defaultTile(ps.spec);
        ctm::FaceFrame frame{};
        if (g_cfg.panes && ps.region && ps.legacy && ctm::quadFrame(ps.verts, frame)) {
            auto match = [&](ctm::Int3 q) { return legacyOf(blockAt(ps.region, q)) == ps.legacy; };
            tile = ctm::selectTile(ps.spec, frame, ps.pos, match);
        }
        r = ctm::tileRect(ps.spec, tile, ps.rect);
    }
    const float du = ps.rect.u1 - ps.rect.u0, dv = ps.rect.v1 - ps.rect.v0;
    for (int i = 0; i < 4; ++i) {
        float u = ps.verts[i].u, v = ps.verts[i].v;
        if (inside && du != 0.f && dv != 0.f) {
            u = r.u0 + (u - ps.rect.u0) / du * (r.u1 - r.u0);
            v = r.v0 + (v - ps.rect.v0) / dv * (r.v1 - r.v0);
        }
        const float p[3] = {ps.verts[i].x, ps.verts[i].y, ps.verts[i].z};
        g_vertexUVOrig(ps.tess, p, u, v);
    }
}

void flushPane(PaneState& ps) {
    for (int i = 0; i < ps.pending; ++i) {
        const float p[3] = {ps.verts[i].x, ps.verts[i].y, ps.verts[i].z};
        g_vertexUVOrig(ps.tess, p, ps.verts[i].u, ps.verts[i].v);
    }
    ps.pending = 0;
}

void vertexUVDetour(void* tess, const float* p, float u, float v) {
    if (g_paneCaptures.load(std::memory_order_relaxed) == 0) {
        g_vertexUVOrig(tess, p, u, v);
        return;
    }
    PaneState& ps = t_state.pane;
    if (!ps.active || !ps.haveSheet || tess != ps.tess || !p) {
        g_vertexUVOrig(tess, p, u, v);
        return;
    }
    ps.verts[ps.pending++] = {p[0], p[1], p[2], u, v};
    if (ps.pending == 4) {
        emitQuad(ps);
        ps.pending = 0;
    }
}

bool paneDetour(void* bt, void* tess, const void* block, const int* pos, uintptr_t arg) {
    ThreadState& ts = t_state;
    PaneState& ps = ts.pane;
    if (!g_enabled.load(std::memory_order_relaxed) || ps.active || !bt || !pos) {
        return g_paneOrig(bt, tess, block, pos, static_cast<int32_t>(arg));
    }
    ps = PaneState{};
    ps.active = true;
    ps.tess = tess;
    ps.legacy = legacyOf(block);
    ps.region = at<void*>(bt, off::BT_Region);
    ps.pos = {pos[0], pos[1], pos[2]};
    if (ps.region && ps.legacy && g_cfg.panes && ts.terrainDepth > 0) {
        ps.sameAbove = legacyOf(blockAt(ps.region, {ps.pos.x, ps.pos.y + 1, ps.pos.z})) == ps.legacy;
        ps.sameBelow = legacyOf(blockAt(ps.region, {ps.pos.x, ps.pos.y - 1, ps.pos.z})) == ps.legacy;
    }
    g_paneCaptures.fetch_add(1, std::memory_order_relaxed);
    struct Done {
        PaneState& ps;
        ~Done() {
            flushPane(ps);
            ps.active = false;
            ps.haveSheet = false;
            g_paneCaptures.fetch_sub(1, std::memory_order_relaxed);
        }
    } done{ps};
    return g_paneOrig(bt, tess, block, pos, static_cast<int32_t>(arg));
}

bool useNewDetour(const void* block, uintptr_t onlyNew) {
    if (!(onlyNew & 0xFF) && block && g_enabled.load(std::memory_order_relaxed) && t_state.terrainDepth > 0 &&
        isCtmBlock(block)) {
        return false;  // force the classic tessellator, which this mod hooks
    }
    return g_useNewOrig(block, (onlyNew & 0xFF) != 0);
}

void buildDetour(uintptr_t builder, uintptr_t geometry, uintptr_t transparentLeaves, uintptr_t lighting,
                 uintptr_t forExport, uintptr_t caps) {
    using Raw = void (*)(uintptr_t, uintptr_t, uintptr_t, uintptr_t, uintptr_t, uintptr_t);
    const Raw orig = reinterpret_cast<Raw>(g_buildOrig);
    if (forExport & 0xFF) {
        orig(builder, geometry, transparentLeaves, lighting, forExport, caps);
        return;
    }
    ThreadState& ts = t_state;
    ++ts.terrainDepth;
    struct Done {
        ThreadState& ts;
        ~Done() { --ts.terrainDepth; }
    } done{ts};
    orig(builder, geometry, transparentLeaves, lighting, forExport, caps);
}

// ------------------------------------------------------------------ setup
void loadConfig(const char* root) {
    if (!root || !*root) return;
    std::string dir = std::string(root) + "/config";
    std::string path = dir + "/connected_textures.json";
    std::string text;
    if (FILE* f = std::fopen(path.c_str(), "rb")) {
        char buf[1024];
        size_t n;
        while ((n = std::fread(buf, 1, sizeof buf, f)) > 0) text.append(buf, n);
        std::fclose(f);
    } else {
        ::mkdir(dir.c_str(), 0775);
        if (FILE* w = std::fopen(path.c_str(), "wb")) {
            std::fputs("{\n  \"enabled\": true,\n  \"blocks\": true,\n  \"panes\": true\n}\n", w);
            std::fclose(w);
        }
        return;
    }
    auto flag = [&](const char* key, bool& out) {
        const std::string k = std::string("\"") + key + "\"";
        size_t i = text.find(k);
        if (i == std::string::npos) return;
        i = text.find(':', i + k.size());
        if (i == std::string::npos) return;
        size_t j = text.find_first_not_of(" \t\r\n", i + 1);
        if (j == std::string::npos) return;
        if (text.compare(j, 4, "true") == 0) out = true;
        else if (text.compare(j, 5, "false") == 0) out = false;
    };
    flag("enabled", g_cfg.enabled);
    flag("blocks", g_cfg.blocks);
    flag("panes", g_cfg.panes);
}

template <class Fn>
bool install(Sig sig, void* detour, Fn* original) {
    void* target = reinterpret_cast<void*>(g_addr[sig]);
    if (!target) return false;
    const int rc = pl::memory::hook(target, detour, reinterpret_cast<void**>(original), pl::memory::HookPriority::Normal);
    if (rc != 0) {
        LOGE("hook failed for %s (rc=%d)", kSigs[sig].name, rc);
        status((std::string("Hook failed: ") + kSigs[sig].name).c_str());
    }
    return rc == 0;
}

bool setup() {
    std::vector<std::string> patterns;
    patterns.reserve(Sig_Count);
    for (const auto& s : kSigs) patterns.emplace_back(s.pattern);
    const auto found =
        pl::memory::resolveSignatures(std::span<const std::string>(patterns.data(), patterns.size()), "libminecraftpe.so");
    bool ok = true;
    for (int i = 0; i < Sig_Count; ++i) {
        const auto it = found.find(patterns[i]);
        g_addr[i] = it == found.end() ? 0 : it->second;
        if (!g_addr[i]) {
            LOGE("signature not found: %s", kSigs[i].name);
            status((std::string("Not found in this game version: ") + kSigs[i].name).c_str());
            ok = false;
        }
    }
    if (!ok) {
        LOGE("this Minecraft version is not supported (built for 1.26.60 and 1.26.62); connected textures stay off");
        return false;
    }
    g_getBlock = reinterpret_cast<GetBlockFn>(g_addr[Sig_GetBlock]);
    g_uvCopy = reinterpret_cast<UvCopyFn>(g_addr[Sig_UvCopy]);
    g_uvDtor = reinterpret_cast<UvDtorFn>(g_addr[Sig_UvDtor]);
    g_graphicsForBlock = reinterpret_cast<GraphicsForBlockFn>(g_addr[Sig_GraphicsForBlock]);

    bool all = true;
    all &= install(Sig_RenderChunkBuild, reinterpret_cast<void*>(&buildDetour), &g_buildOrig);
    all &= install(Sig_UseNewTessellation, reinterpret_cast<void*>(&useNewDetour), &g_useNewOrig);
    all &= install(Sig_GetTexture, reinterpret_cast<void*>(&getTextureDetour), &g_getTextureOrig);
    all &= install(Sig_FaceDown, reinterpret_cast<void*>(&faceDetour<0>), &g_faceOrig[0]);
    all &= install(Sig_FaceUp, reinterpret_cast<void*>(&faceDetour<1>), &g_faceOrig[1]);
    all &= install(Sig_FaceNorth, reinterpret_cast<void*>(&faceDetour<2>), &g_faceOrig[2]);
    all &= install(Sig_FaceSouth, reinterpret_cast<void*>(&faceDetour<3>), &g_faceOrig[3]);
    all &= install(Sig_FaceWest, reinterpret_cast<void*>(&faceDetour<4>), &g_faceOrig[4]);
    all &= install(Sig_FaceEast, reinterpret_cast<void*>(&faceDetour<5>), &g_faceOrig[5]);
    all &= install(Sig_VertexUV, reinterpret_cast<void*>(&vertexUVDetour), &g_vertexUVOrig);
    all &= install(Sig_PaneTessellate, reinterpret_cast<void*>(&paneDetour), &g_paneOrig);
    if (!all) {
        LOGE("some hooks failed; connected textures stay off");
        return false;
    }
    return true;
}

// The game library is usually loaded after mods ("preload-native"), so setup
// waits for dlopen("libminecraftpe.so") the same way BedrockTools does.
std::atomic<bool> g_wantEnabled{false};
std::mutex g_setupMutex;
bool g_setupDone = false;
thread_local bool t_inSetup = false;
using DlopenFn = void* (*)(const char*, int);
DlopenFn g_dlopenOrig = nullptr;

bool gameProcess() {
    char cmd[256] = {};
    const int fd = ::open("/proc/self/cmdline", O_RDONLY);
    if (fd < 0) return true;
    const ssize_t n = ::read(fd, cmd, sizeof cmd - 1);
    ::close(fd);
    if (n <= 0) return true;
    return !std::strcmp(cmd, "org.levimc.launcher") || !std::strcmp(cmd, "org.levimc.launcher:minecraft") ||
           !std::strcmp(cmd, "com.mojang.minecraftpe");
}

void trySetup() {
    std::lock_guard<std::mutex> lock(g_setupMutex);
    if (g_setupDone) return;
    const bool outer = !t_inSetup;
    t_inSetup = true;
    void* mc = dlopen("libminecraftpe.so", RTLD_NOW | RTLD_NOLOAD);
    if (mc) {
        dlclose(mc);
        g_setupDone = true;
        g_hooked = setup();
        LOGI("%s", g_hooked ? "hooks installed" : "hooks not installed");
        status(g_hooked ? "Hooks installed. Load a world with a converted CTM pack active."
                        : "Hooks NOT installed - connected textures are off.");
        g_enabled.store(g_hooked && g_cfg.enabled && g_wantEnabled.load(), std::memory_order_release);
    }
    if (outer) t_inSetup = false;
}

void* dlopenDetour(const char* filename, int flags) {
    void* handle = g_dlopenOrig ? g_dlopenOrig(filename, flags) : nullptr;
    if (handle && filename && !t_inSetup && std::strstr(filename, "libminecraftpe.so")) trySetup();
    return handle;
}

}  // namespace

CTM_EXPORT bool PLMod_Load(JavaVM*, const PLModInfo* info) {
    const char* root = info ? info->mod_root_path : nullptr;
    if (root && *root) g_statusPath = std::string(root) + "/status.txt";
    if (!gameProcess()) return true;
    status("Connected Textures 1.0.2 for Minecraft 1.26.60 / 1.26.62", true);
    loadConfig(root);
    LOGI("loading (blocks=%d panes=%d enabled=%d)", g_cfg.blocks, g_cfg.panes, g_cfg.enabled);
    trySetup();
    if (!g_setupDone) {
        void* libdl = dlopen("libdl.so", RTLD_NOW);
        void* sym = libdl ? dlsym(libdl, "dlopen") : nullptr;
        if (sym && pl::memory::hook(sym, reinterpret_cast<void*>(&dlopenDetour), reinterpret_cast<void**>(&g_dlopenOrig),
                                    pl::memory::HookPriority::Normal) == 0) {
            status("Waiting for the game library to load...");
        } else {
            status("Could not watch for the game library; connected textures are off.");
        }
        if (libdl) dlclose(libdl);
    }
    return true;  // never block the game from starting
}

CTM_EXPORT bool PLMod_Enable() {
    g_wantEnabled.store(true, std::memory_order_release);
    g_enabled.store(g_hooked && g_cfg.enabled, std::memory_order_release);
    LOGI("enabled=%d", g_enabled.load() ? 1 : 0);
    return true;
}

CTM_EXPORT bool PLMod_Disable() {
    g_wantEnabled.store(false, std::memory_order_release);
    g_enabled.store(false, std::memory_order_release);
    return true;
}

CTM_EXPORT bool PLMod_Unload() {
    g_wantEnabled.store(false, std::memory_order_release);
    g_enabled.store(false, std::memory_order_release);
    return true;
}
