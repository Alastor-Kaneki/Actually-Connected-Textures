# Conditional texture binding verification

The optional `BlockSourceGetBiome` pattern is taken from the MIT-licensed
[BedrockTools signatures](https://github.com/QYCottage/BedrockTools/blob/c03d3ae0c9022557de0acdf0d94e373e0b9e0579/src/core/memory/Signatures.cpp).
It resolved exactly once in each user-supplied arm64 `libminecraftpe.so`:

| Minecraft | SHA-256 | getBiome VA |
| --- | --- | --- |
| 1.26.60 | `6c7b409d832e182537c0b1dbb8cc3c92f60b7795db7ea5e9f626fb6d6cb8c77f` | `0x1037ad4c` |
| 1.26.62 | `2a915cf07bd165c5f939b02b36ee756878a63c4489f4971fb14e6e7bdf39216b` | `0x1037b438` |

Both implementations accept a concrete BlockSource and a BlockPos, fetch the chunk
through vtable offset `0x138`, and return a Biome reference. `BlockTessellator::mRegion`
is a **BlockSource pointer**, not merely IConstBlockSource. This agrees with
[BedrockTools' wrapper](https://github.com/QYCottage/BedrockTools/blob/c03d3ae0c9022557de0acdf0d94e373e0b9e0579/include/bedrocktools/sdk/render/BlockTessellator.hpp)
and LeviLamina's generated BlockTessellator header.

The biome filter caller at `0xffede30` (26.60) / `0xffee51c` (26.62) passes the
returned Biome's `+0x190` member to HashedString's hash accessor. HashedString's
string accessor adds 8, confirming `Biome_Name=0x198`. The nearby accessors read the
libc++ string layout at +8/+16/+24. Biome ID loads at `+0x168` independently agree
with the generated Biome member layout, although this mod uses the name.

RenderChunkBuilder owns its BlockTessellator at `+0x10`. The existing build
signature's disassembly reads that member and assigns its region during the build
(26.62: reads at `0xa9c4b80` and `0xa9c4d34`, setter call at `0xa9c4d3c`). Therefore
thread state retains the tessellator, **not an early snapshot of its region**.
Texture lookup reads the current `mRegion` at `+0x8`, after the game sets it.
Nested build calls restore the previous thread-local tessellator.

Only the biome signature is optional. A missing binding skips biome rules rather
than disabling unrelated connected textures. These binary inspections do not
replace in-game testing or guarantee compatibility with later Minecraft builds.
