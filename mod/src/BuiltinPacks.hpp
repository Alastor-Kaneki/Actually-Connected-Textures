#pragma once

#include <cerrno>
#include <cstdio>
#include <string>
#include <unistd.h>

namespace ctm {

// LeviLauncher discovers resources/minecraft_resource_packs/*/manifest.json
// after PLMod_Load. Keep the template intact, exposing a manifest only for
// enabled features. The launcher owns installation, activation and cleanup.
inline bool readPackFile(const std::string& path, std::string& text) {
    FILE* file = std::fopen(path.c_str(), "rb");
    if (!file) return false;
    text.clear();
    char buffer[4096];
    size_t n;
    while ((n = std::fread(buffer, 1, sizeof buffer, file))) text.append(buffer, n);
    const bool ok = !std::ferror(file);
    std::fclose(file);
    return ok;
}

inline bool setBuiltinPack(const std::string& root, const char* folder, bool enabled) {
    if (root.empty()) return false;
    const std::string dir = root + "/resources/minecraft_resource_packs/" + folder;
    const std::string manifest = dir + "/manifest.json";
    if (!enabled) return ::unlink(manifest.c_str()) == 0 || errno == ENOENT;

    std::string wanted, existing;
    if (!readPackFile(dir + "/manifest.template.json", wanted) || wanted.empty()) return false;
    // Preserve timestamps when unchanged so the launcher can reuse its copy.
    if (readPackFile(manifest, existing) && existing == wanted) return true;
    const std::string temp = manifest + ".tmp";
    FILE* file = std::fopen(temp.c_str(), "wb");
    if (!file) return false;
    bool ok = std::fwrite(wanted.data(), 1, wanted.size(), file) == wanted.size();
    if (std::fclose(file) != 0) ok = false;
    if (ok) ok = std::rename(temp.c_str(), manifest.c_str()) == 0;
    if (!ok) ::unlink(temp.c_str());
    return ok;
}

inline bool syncBuiltinPacks(const std::string& root, bool enabled, bool defaults, bool grass, bool whiterSnow) {
    // Always process all bundles: a failure must not leave another enabled.
    const bool defaultsOk = setBuiltinPack(root, "ctm_defaults", enabled && defaults);
    const bool grassOk = setBuiltinPack(root, "ctm_grass", enabled && grass);
    const bool snowOk = setBuiltinPack(root, "ctm_whiter_snow", enabled && whiterSnow);
    return defaultsOk && grassOk && snowOk;
}

}  // namespace ctm
