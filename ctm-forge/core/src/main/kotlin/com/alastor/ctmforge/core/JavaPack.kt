package com.alastor.ctmforge.core

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * A Java Edition resource pack loaded into memory: path -> bytes, relative to the folder
 * that holds pack.mcmeta (so packs inside mod jars or sub-folders work too).
 */
class JavaPack private constructor(val files: Map<String, ByteArray>, val root: String) {

    fun get(path: String): ByteArray? = files[path]

    val ctmRuleCount: Int
        get() = files.keys.count { isCtmRule(it) }

    companion object {
        /** Upper bound for the unpacked size of a pack, to keep zip bombs out. */
        const val MAX_TOTAL_BYTES = 400L * 1024 * 1024

        fun isCtmRule(path: String) =
            path.endsWith(".properties") && Regex("assets/[^/]+/(optifine|mcpatcher)/ctm/.*").matches(path)

        fun fromEntries(all: Map<String, ByteArray>): JavaPack {
            val roots = all.keys.filter { it == "pack.mcmeta" || it.endsWith("/pack.mcmeta") }
                .map { it.removeSuffix("pack.mcmeta") }
                .sortedWith(compareBy<String> { it.length }.thenBy { it })
            val root = roots.firstOrNull() ?: ""
            val files = LinkedHashMap<String, ByteArray>()
            for ((k, v) in all) if (k.startsWith(root)) files[k.substring(root.length)] = v
            return JavaPack(files, root)
        }

        /** Files a conversion can use; everything else (sounds, models, classes...) is skipped. */
        fun useful(path: String): Boolean {
            val p = path.lowercase()
            return p.endsWith(".png") || p.endsWith(".properties") || p.endsWith(".mcmeta") || p.endsWith(".zip") ||
                p.endsWith("manifest.json")
        }

        /** Reads the files of a zip (or jar) into memory, with sanitised paths. */
        fun readZip(input: InputStream, keep: (String) -> Boolean = { true }): Map<String, ByteArray> {
            val out = LinkedHashMap<String, ByteArray>()
            var total = 0L
            ZipInputStream(input).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.isDirectory) continue
                    val parts = e.name.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." && it != ".." }
                    if (parts.isEmpty()) continue
                    val path = parts.joinToString("/")
                    if (!keep(path)) continue
                    val bytes = zip.readBytes()
                    total += bytes.size
                    if (total > MAX_TOTAL_BYTES) throw IllegalArgumentException("Pack is too large to open")
                    out[path] = bytes
                }
            }
            return out
        }

        /**
         * All packs found in a download: the zip itself, plus packs nested one level deep
         * (some sites wrap the real pack zip in another zip). Ordered by CTM rule count.
         */
        fun candidates(zipBytes: ByteArray, name: String): List<Candidate> {
            val entries = readZip(ByteArrayInputStream(zipBytes), ::useful)
            val found = ArrayList<Candidate>()
            val roots = entries.keys.filter { it == "pack.mcmeta" || it.endsWith("/pack.mcmeta") }
                .map { it.removeSuffix("pack.mcmeta") }.sortedBy { it.length }
            for (r in roots.ifEmpty { listOf("") }) {
                val sub = entries.filterKeys { it.startsWith(r) }
                // nested pack roots belong to their own candidate
                val pack = fromEntries(sub)
                if (pack.root == r || roots.isEmpty()) {
                    val label = if (r.isEmpty()) name else r.trimEnd('/').substringAfterLast('/')
                    found += Candidate(label, pack)
                }
            }
            for ((path, bytes) in entries) {
                if (!path.lowercase().endsWith(".zip")) continue
                runCatching {
                    val inner = fromEntries(readZip(ByteArrayInputStream(bytes), ::useful))
                    found += Candidate(path.substringAfterLast('/').removeSuffix(".zip"), inner)
                }
            }
            return found.distinctBy { it.label + "|" + it.pack.root + "|" + it.pack.files.size }
                .sortedByDescending { it.pack.ctmRuleCount }
        }
    }

    class Candidate(val label: String, val pack: JavaPack)

    /** What a file looks like before it is opened as a Java pack. */
    enum class Kind { ZIP, BEDROCK_PACK, RAR, SEVEN_ZIP, HTML, UNKNOWN }
}

fun sniff(bytes: ByteArray): JavaPack.Kind {
    fun at(vararg b: Int) = bytes.size >= b.size && b.indices.all { (bytes[it].toInt() and 0xFF) == b[it] }
    return when {
        at(0x50, 0x4B, 0x03, 0x04) || at(0x50, 0x4B, 0x05, 0x06) -> JavaPack.Kind.ZIP
        at(0x52, 0x61, 0x72, 0x21) -> JavaPack.Kind.RAR
        at(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) -> JavaPack.Kind.SEVEN_ZIP
        String(bytes, 0, minOf(bytes.size, 512), Charsets.ISO_8859_1).trimStart().lowercase()
            .let { it.startsWith("<!doctype html") || it.startsWith("<html") } -> JavaPack.Kind.HTML
        else -> JavaPack.Kind.UNKNOWN
    }
}

/** True when the zip is already a Bedrock pack (manifest.json, no pack.mcmeta). */
fun isBedrockPack(entries: Collection<String>): Boolean =
    entries.none { it == "pack.mcmeta" || it.endsWith("/pack.mcmeta") } &&
        entries.any { it == "manifest.json" || it.count { c -> c == '/' } == 1 && it.endsWith("/manifest.json") }

object Uuid5 {
    val NAMESPACE_URL: UUID = UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8")

    fun of(namespace: UUID, name: String): UUID {
        val md = MessageDigest.getInstance("SHA-1")
        val ns = ByteArray(16)
        for (i in 0 until 8) ns[i] = (namespace.mostSignificantBits ushr (56 - 8 * i)).toByte()
        for (i in 0 until 8) ns[8 + i] = (namespace.leastSignificantBits ushr (56 - 8 * i)).toByte()
        md.update(ns)
        md.update(name.toByteArray(Charsets.UTF_8))
        val h = md.digest()
        h[6] = ((h[6].toInt() and 0x0F) or 0x50).toByte()
        h[8] = ((h[8].toInt() and 0x3F) or 0x80).toByte()
        var msb = 0L; var lsb = 0L
        for (i in 0 until 8) msb = (msb shl 8) or (h[i].toLong() and 0xFF)
        for (i in 8 until 16) lsb = (lsb shl 8) or (h[i].toLong() and 0xFF)
        return UUID(msb, lsb)
    }
}
