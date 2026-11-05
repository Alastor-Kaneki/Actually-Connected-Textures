package com.alastor.ctmforge.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.alastor.ctmforge.core.BlockSheet
import com.alastor.ctmforge.core.ConversionResult
import com.alastor.ctmforge.core.net.Http
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.util.zip.ZipFile

/** A converted pack kept by the app. */
class LibraryEntry(
    val id: String,
    val dir: File,
    val name: String,
    val slug: String,
    val direct: Boolean,
    val fileName: String,
    val blocks: Map<String, String>,
    val sheets: List<BlockSheet>,
    val log: List<String>,
    val createdAt: Long,
    val source: String?,
    val sourceUrl: String?,
    val version: List<Int>,
    val headerUuid: String,
) {
    val mcpack: File get() = File(dir, fileName)
    val icon: File get() = File(dir, "icon.png")
    val layoutLabel: String get() = if (direct) "Direct layout" else "Normal layout"
    val versionLabel: String get() = version.joinToString(".")

    /** Reads one file of the converted pack (for previews). */
    fun readPackFile(path: String): ByteArray? = runCatching {
        ZipFile(mcpack).use { z -> z.getEntry(path)?.let { e -> z.getInputStream(e).use { it.readBytes() } } }
    }.getOrNull()
}

class Library(context: Context) {
    private val root = File(context.filesDir, "library")

    var entries by mutableStateOf<List<LibraryEntry>>(emptyList())
        private set
    var loaded by mutableStateOf(false)
        private set

    fun idFor(slug: String, direct: Boolean) = slug + if (direct) "-direct" else ""

    @Synchronized
    fun reload() {
        val list = root.listFiles()?.filter { it.isDirectory }?.mapNotNull { read(it) } ?: emptyList()
        entries = list.sortedByDescending { it.createdAt }
        loaded = true
    }

    fun find(id: String): LibraryEntry? = entries.firstOrNull { it.id == id }

    /** Stores a conversion, replacing an earlier conversion of the same pack and layout. */
    @Synchronized
    fun add(result: ConversionResult, source: String?, sourceUrl: String?): LibraryEntry {
        val id = idFor(result.slug, result.direct)
        val dir = File(root, id)
        val tmp = File(root, ".$id.tmp")
        tmp.deleteRecursively()
        tmp.mkdirs()
        val fileName = Http.sanitizeFileName("${result.name} (CTM${if (result.direct) " direct" else ""}).mcpack")
        File(tmp, fileName).outputStream().buffered().use { result.writeMcpack(it) }
        result.icon?.let { File(tmp, "icon.png").writeBytes(it) }
        val meta = JsonObject(linkedMapOf(
            "name" to JsonPrimitive(result.name),
            "slug" to JsonPrimitive(result.slug),
            "direct" to JsonPrimitive(result.direct),
            "file" to JsonPrimitive(fileName),
            "blocks" to JsonObject(result.blocks.mapValues { JsonPrimitive(it.value) }),
            "sheets" to JsonArray(result.sheets.map {
                JsonObject(linkedMapOf(
                    "block" to JsonPrimitive(it.block), "spec" to JsonPrimitive(it.spec), "path" to JsonPrimitive(it.sheetPath),
                    "faces" to JsonArray(it.faces.map { f -> JsonPrimitive(f) }),
                ))
            }),
            "log" to JsonArray(result.log.map { JsonPrimitive(it) }),
            "created" to JsonPrimitive(System.currentTimeMillis()),
            "source" to (source?.let { JsonPrimitive(it) } ?: JsonPrimitive("")),
            "source_url" to (sourceUrl?.let { JsonPrimitive(it) } ?: JsonPrimitive("")),
            "version" to JsonArray(result.version.map { JsonPrimitive(it) }),
            "uuid" to JsonPrimitive(result.headerUuid),
        ))
        File(tmp, "meta.json").writeText(meta.toString())
        dir.deleteRecursively()
        if (!tmp.renameTo(dir)) {
            tmp.copyRecursively(dir, overwrite = true)
            tmp.deleteRecursively()
        }
        reload()
        return find(id) ?: error("Could not save the converted pack")
    }

    @Synchronized
    fun delete(entry: LibraryEntry) {
        entry.dir.deleteRecursively()
        reload()
    }

    private fun read(dir: File): LibraryEntry? = runCatching {
        val o = Json.parseToJsonElement(File(dir, "meta.json").readText()).jsonObject
        fun s(k: String) = (o[k] as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() }
        val fileName = s("file") ?: return null
        if (!File(dir, fileName).isFile) return null
        LibraryEntry(
            id = dir.name,
            dir = dir,
            name = s("name") ?: dir.name,
            slug = s("slug") ?: dir.name,
            direct = (o["direct"] as? JsonPrimitive)?.booleanOrNull == true,
            fileName = fileName,
            blocks = (o["blocks"] as? JsonObject)?.mapValues { (it.value as JsonPrimitive).content } ?: emptyMap(),
            sheets = (o["sheets"] as? JsonArray)?.mapNotNull { it as? JsonObject }?.map { sh ->
                BlockSheet(
                    (sh["block"] as JsonPrimitive).content, (sh["spec"] as JsonPrimitive).content, (sh["path"] as JsonPrimitive).content,
                    (sh["faces"] as? JsonArray)?.map { (it as JsonPrimitive).content } ?: emptyList(),
                )
            } ?: emptyList(),
            log = (o["log"] as? JsonArray)?.map { (it as JsonPrimitive).content } ?: emptyList(),
            createdAt = (o["created"] as? JsonPrimitive)?.longOrNull ?: dir.lastModified(),
            source = s("source"),
            sourceUrl = s("source_url"),
            version = (o["version"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull } ?: listOf(1, 0, 0),
            headerUuid = s("uuid") ?: "",
        )
    }.getOrNull()
}
