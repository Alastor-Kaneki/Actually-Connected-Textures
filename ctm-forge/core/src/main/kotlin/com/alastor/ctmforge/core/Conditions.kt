package com.alastor.ctmforge.core

/** Conditions travel with the atlas path and animation strip. Below-block filters require mod 1.4+. */
data class RuleConditions(
    val biomes: List<String> = emptyList(),
    val excludeBiomes: Boolean = false,
    val connectBlocks: List<String> = emptyList(),
    val neighborBlocks: List<String> = emptyList(),
    val belowBlocks: List<String> = emptyList(),
) {
    val conditional get() = biomes.isNotEmpty() || neighborBlocks.isNotEmpty() || belowBlocks.isNotEmpty()
    val predicateKey get() = "${if (excludeBiomes) "!" else ""}${biomes.joinToString(".")}/${neighborBlocks.joinToString(".")}/${belowBlocks.joinToString(".")}"
    val path: String get() {
        val parts = buildList {
            if (biomes.isNotEmpty()) add("${if (excludeBiomes) "x" else "b"}-${biomes.joinToString(".")}")
            if (connectBlocks.isNotEmpty()) add("c-${connectBlocks.joinToString(".")}")
            if (neighborBlocks.isNotEmpty()) add("n-${neighborBlocks.joinToString(".")}")
            if (belowBlocks.isNotEmpty()) add("d-${belowBlocks.joinToString(".")}")
        }
        return if (parts.isEmpty()) "" else "when/${parts.joinToString("/")}/end/"
    }

    companion object {
        // Java names with a different vanilla Bedrock spelling. Retain the original too:
        // this also accepts packs made for versions that renamed a biome.
        private val biomeAliases = mapOf(
            "snowy_plains" to "ice_plains", "snowy_taiga" to "cold_taiga",
            "old_growth_pine_taiga" to "mega_taiga", "old_growth_spruce_taiga" to "redwood_taiga_mutated",
            "old_growth_birch_forest" to "birch_forest_mutated", "windswept_hills" to "extreme_hills",
            "windswept_forest" to "extreme_hills_plus_trees", "windswept_gravelly_hills" to "extreme_hills_mutated",
            "snowy_beach" to "cold_beach", "stony_shore" to "stone_beach", "sparse_jungle" to "jungle_edge",
            "badlands" to "mesa", "wooded_badlands" to "mesa_plateau_stone", "eroded_badlands" to "mesa_bryce",
            "windswept_savanna" to "savanna_mutated", "swamp" to "swampland",
            "mushroom_fields" to "mushroom_island", "dark_forest" to "roofed_forest",
            "nether_wastes" to "hell", "the_end" to "sky",
        )
        private fun names(value: String): List<String> = value.trim().takeIf { it.isNotEmpty() }
            ?.split(Regex("[\\s,]+"))?.map { token ->
                val id = token.lowercase().removePrefix("minecraft:")
                require(Regex("[a-z][a-z0-9_]{0,79}").matches(id)) { "Use vanilla names separated by spaces; block states and numeric IDs are not supported." }
                id
            }?.distinct()?.sorted() ?: emptyList()

        fun parse(biomes: String = "", connectBlocks: String = "", neighborBlocks: String = "", belowBlocks: String = ""): RuleConditions {
            val value = biomes.trim()
            val exclude = value.startsWith('!')
            val biomeNames = names(if (exclude) value.drop(1) else value)
            require(!exclude || biomeNames.isNotEmpty()) { "Add biome names after !." }
            fun blocks(s: String) = names(s).flatMap { name ->
                val id = Bedrock.block(name) ?: Vanilla.blocks.keys.firstOrNull { it.equals(name, ignoreCase = true) }
                require(id != null && id in Vanilla.blocks) { "Unknown connection block '$name'." }
                // Resource-pack aliases and runtime registry names can differ. Avoid ambiguous
                // Java names such as snow (snow_layer), which is itself another Bedrock block.
                listOf(id.lowercase()) + JavaNames.blocks.filter { (java, bedrock) -> bedrock == id && java !in Vanilla.blocks }.keys
            }.distinct().sorted()
            val result = RuleConditions(biomeNames.flatMap { listOfNotNull(it, biomeAliases[it]) }.distinct().sorted(),
                exclude, blocks(connectBlocks), blocks(neighborBlocks), blocks(belowBlocks))
            require(listOf(result.biomes, result.connectBlocks, result.neighborBlocks, result.belowBlocks).all { it.joinToString(".").length <= 200 }
                && result.path.length <= 500) { "Condition lists are too long. Use fewer names per rule." }
            return result
        }
    }
}
