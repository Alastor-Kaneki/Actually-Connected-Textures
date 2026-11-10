package com.alastor.ctmforge.core

import java.util.zip.ZipInputStream

/** Offline fallbacks built from pinned bedrock-samples by tools/build_vanilla_fallbacks.py. */
internal object VanillaImages {
    private val images: Map<String, ByteArray> by lazy {
        val input = VanillaImages::class.java.getResourceAsStream("/vanilla_fallbacks.zip")
            ?: error("Vanilla fallbacks are missing from this build; run tools/build_vanilla_fallbacks.py")
        ZipInputStream(input).use { zip ->
            buildMap {
                while (true) {
                    val entry = zip.nextEntry ?: break
                    put(entry.name, zip.readBytes())
                }
            }
        }
    }

    fun png(path: String): ByteArray = images["$path.png"]
        ?: throw ConversionException("No bundled normal texture for $path; supply it in the source pack", emptyList())
}
