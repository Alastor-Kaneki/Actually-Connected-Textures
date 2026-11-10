// Path-encoded rule conditions. No allocation, game types or global state.
#pragma once
#include <string_view>

namespace ctm {
struct Conditions {
    std::string_view biomes, connections, neighbors, below;
    bool excludeBiomes = false;
    bool valid = true;
    bool conditional() const { return !biomes.empty() || !neighbors.empty() || !below.empty(); }
};

inline std::string_view bareName(std::string_view name) {
    constexpr std::string_view prefix = "minecraft:";
    return name.substr(0, prefix.size()) == prefix ? name.substr(prefix.size()) : name;
}

inline bool nameInList(std::string_view list, std::string_view name) {
    name = bareName(name);
    if (name.empty()) return false;
    while (!list.empty()) {
        const auto dot = list.find('.');
        const auto candidate = list.substr(0, dot);
        bool equal = candidate.size() == name.size();
        for (size_t i = 0; equal && i < name.size(); ++i) {
            const char lower = name[i] >= 'A' && name[i] <= 'Z' ? name[i] + ('a' - 'A') : name[i];
            equal = candidate[i] == lower;
        }
        if (equal) return true;
        if (dot == std::string_view::npos) break;
        list.remove_prefix(dot + 1);
    }
    return false;
}

inline bool validNameList(std::string_view list) {
    if (list.empty() || list.size() > 200 || list.front() == '.' || list.back() == '.') return false;
    bool start = true;
    for (char ch : list) {
        if (ch == '.') { if (start) return false; start = true; }
        else {
            if (start ? !(ch >= 'a' && ch <= 'z') :
                !((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '_')) return false;
            start = false;
        }
    }
    return true;
}

// Optional suffix after the sheet-spec directory:
// when/b-desert.plains/c-glass.tinted_glass/n-sand/d-end_stone/end/<image>
// x- excludes the listed biomes. Malformed/unknown conditions fail closed.
inline Conditions parseConditions(std::string_view rest) {
    Conditions out;
    if (rest.substr(0, 5) != "when/") return out;
    rest.remove_prefix(5);
    out.valid = false;
    for (int part = 0; part < 5; ++part) {
        const auto slash = rest.find('/');
        if (slash == std::string_view::npos) return out;
        const auto segment = rest.substr(0, slash);
        rest.remove_prefix(slash + 1);
        if (segment == "end") {
            out.valid = !rest.empty() && part > 0;
            return out;
        }
        if (segment.size() < 3 || segment[1] != '-' || !validNameList(segment.substr(2))) return out;
        const auto names = segment.substr(2);
        switch (segment[0]) {
        case 'b': case 'x':
            if (!out.biomes.empty()) return out;
            out.biomes = names; out.excludeBiomes = segment[0] == 'x'; break;
        case 'c': if (!out.connections.empty()) return out; out.connections = names; break;
        case 'n': if (!out.neighbors.empty()) return out; out.neighbors = names; break;
        case 'd': if (!out.below.empty()) return out; out.below = names; break;
        default: return out;
        }
    }
    return out;
}

template<class NeighborMatch>
bool conditionsMatch(const Conditions& c, std::string_view biome, NeighborMatch&& neighborMatch) {
    if (!c.valid) return false;
    if (!c.biomes.empty()) {
        // Unknown biome must also fail exclusions, never accidentally become a global rule.
        if (biome.empty() || nameInList(c.biomes, biome) == c.excludeBiomes) return false;
    }
    return (c.neighbors.empty() || neighborMatch(c.neighbors, false)) &&
           (c.below.empty() || neighborMatch(c.below, true));
}
} // namespace ctm
