// Exercise the actual launcher-discovery boundary with packaged resources.
// Usage: builtin_packs_test mod/assets/builtin
#include "BuiltinPacks.hpp"
#include <cassert>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <vector>

namespace fs = std::filesystem;

int main(int argc, char** argv) {
    assert(argc == 2);
    char pattern[] = "/tmp/ctm-builtin-test-XXXXXX";
    const char* temp = ::mkdtemp(pattern);
    assert(temp);
    const fs::path root(temp);
    const auto packs = root / "resources/minecraft_resource_packs";
    fs::create_directories(packs);
    fs::copy(argv[1], packs, fs::copy_options::recursive);
    std::vector<fs::path> manifest;
    for (const char* folder : {"ctm_grass", "ctm_whiter_snow"}) {
        const auto path = packs / folder / "manifest.json";
        fs::rename(path, path.parent_path() / "manifest.template.json");
        manifest.push_back(path);
    }
    // An unrelated bundle in the mod must never be changed by these switches.
    fs::create_directories(packs / "other");
    std::ofstream(packs / "other/manifest.json") << "unrelated";
    for (bool enabled : {true, false}) {
        for (bool grass : {true, false}) {
            for (bool snow : {true, false}) {
                assert(ctm::syncBuiltinPacks(root.string(), enabled, grass, snow));
                assert(fs::exists(manifest[0]) == (enabled && grass));
                assert(fs::exists(manifest[1]) == (enabled && snow));
                assert(fs::exists(packs / "other/manifest.json"));
            }
        }
    }
    // Restore after off, preserve content and timestamps on repeated launches.
    assert(ctm::syncBuiltinPacks(root.string(), true, true, true));
    const auto old = fs::file_time_type::clock::now() - std::chrono::hours(24);
    fs::last_write_time(manifest[0], old);
    fs::last_write_time(manifest[1], old);
    assert(ctm::syncBuiltinPacks(root.string(), true, true, true));
    for (const auto& path : manifest) {
        assert(fs::last_write_time(path) == old);
        std::string active, source;
        assert(ctm::readPackFile(path.string(), active));
        assert(ctm::readPackFile((path.parent_path() / "manifest.template.json").string(), source));
        assert(active == source);
    }
    // An upgrade replaces the template; the next load exposes the new version.
    std::ofstream(manifest[0].parent_path() / "manifest.template.json") << "new version";
    assert(ctm::syncBuiltinPacks(root.string(), true, true, false));
    std::string text;
    assert(ctm::readPackFile(manifest[0].string(), text) && text == "new version");
    // A broken base bundle must not prevent disabling optional snow.
    assert(ctm::syncBuiltinPacks(root.string(), true, false, true));
    fs::remove(manifest[0].parent_path() / "manifest.template.json");
    assert(!ctm::syncBuiltinPacks(root.string(), true, true, false));
    assert(!fs::exists(manifest[0]) && !fs::exists(manifest[1]));
    assert(!ctm::syncBuiltinPacks("", true, true, true));
    fs::remove_all(root);
    std::cout << "Built-in terrain activation tests OK\n";
}
