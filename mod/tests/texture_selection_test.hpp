// Exercise the real vector traversal and scope used by the Android hooks.
#include <new>
#include "TextureSelection.hpp"

static void textureSelectionTests() {
    using namespace game;
    constexpr size_t count = 5;
    alignas(16) char graphics[0x80]{};
    alignas(16) char face[off::BG_FaceItemSize]{};
    alignas(16) char variant[24]{};
    alignas(16) char uv[count * off::UV_Size]{};
    at<void*>(graphics, off::BG_Faces) = face;
    at<void*>(graphics, off::BG_Faces + 8) = face + sizeof face;
    at<void*>(face, off::FaceItem_Variants) = variant;
    at<void*>(face, off::FaceItem_Variants + 8) = variant + sizeof variant;
    at<void*>(variant, 0) = uv;
    at<void*>(variant, 8) = uv + sizeof uv;
    const char* paths[] = {
        "textures/blocks/fire_0",
        "textures/blocks/ctm/fixed-1x1-1/fallback",
        "textures/blocks/ctm/fixed-1x1-1/when/b-sky.the_end/end/fire",
        "textures/blocks/ctm/fixed-1x1-1/when/b-desert/n-sand/end/sand",
        "textures/blocks/ctm/fixed-1x1-1-o/when/b-the_end/end/overlay",
    };
    for (size_t i = 0; i < count; ++i) new (uv + i * off::UV_Size + off::UV_Path) std::string(paths[i]);
    struct Context {
        std::string_view biome, neighbor;
        bool matches(const SheetSpec& s) {
            return conditionsMatch(s.conditions, biome, [&](std::string_view n) { return nameInList(n, neighbor); });
        }
    } context{"minecraft:the_end", "end_stone"};
    auto set = findSheets(graphics, 0, 0, context);
    CHECK(set.base == uv && set.normal == uv + 2 * off::UV_Size);
    CHECK(set.hasSheet && set.overlayCount == 1);
    context = {"desert", "minecraft:sand"};
    set = findSheets(graphics, 0, 0, context);
    CHECK(set.normal == uv + 3 * off::UV_Size && set.overlayCount == 0);
    context.neighbor = "stone";
    CHECK(findSheets(graphics, 0, 0, context).normal == uv + off::UV_Size);
    context = {"plains", "sand"};
    CHECK(findSheets(graphics, 0, 0, context).normal == uv + off::UV_Size);
    context = {"", "sand"};
    CHECK(findSheets(graphics, 0, 0, context).normal == uv + off::UV_Size);
    // Conditional-only lists keep the base when the condition fails.
    *reinterpret_cast<std::string*>(uv + off::UV_Size + off::UV_Path) =
        "textures/blocks/ctm/fixed-1x1-1/when/b-the_end/end/other";
    set = findSheets(graphics, 0, 0, context);
    CHECK(set.normal == nullptr && set.base == uv);
    // No CTM means ordinary random variation selection is not replaced with variation zero.
    for (size_t i = 1; i < count; ++i)
        *reinterpret_cast<std::string*>(uv + i * off::UV_Size + off::UV_Path) = "textures/blocks/stone";
    CHECK(!findSheets(graphics, 0, 0, context).hasSheet);
    for (size_t i = 0; i < count; ++i)
        reinterpret_cast<std::string*>(uv + i * off::UV_Size + off::UV_Path)->~basic_string();

    WorldContext world;
    int a[] = {30, 70, -40}, b[] = {-1, 64, 50};
    {
        WorldScope outer(world, graphics, a);
        CHECK(world.tessellator == graphics && world.position == a);
        {
            WorldScope inner(world, face, b);
            CHECK(world.tessellator == face && world.position == b);
        }
        CHECK(world.tessellator == graphics && world.position == a);
    }
    CHECK(world.tessellator == nullptr && world.position == nullptr);
}
