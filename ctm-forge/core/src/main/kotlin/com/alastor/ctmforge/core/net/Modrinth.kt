package com.alastor.ctmforge.core.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Small blocking HTTP helpers (call them off the main thread). */
object Http {
    @Volatile var userAgent = "alastor/ctm-forge (com.alastor.ctmforge)"

    class HttpException(val code: Int, message: String) : IOException(message)

    private fun open(url: String, headers: Map<String, String>): HttpURLConnection {
        var current = URL(url)
        repeat(8) {
            val c = current.openConnection() as HttpURLConnection
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", userAgent)
            for ((k, v) in headers) c.setRequestProperty(k, v)
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw HttpException(code, "Redirect without a location")
                c.disconnect()
                current = URL(current, loc) // handles relative and cross-protocol redirects
                return@repeat
            }
            if (code !in 200..299) {
                val body = runCatching { c.errorStream?.use { it.readBytes().toString(Charsets.UTF_8).take(300) } }.getOrNull()
                c.disconnect()
                throw HttpException(code, "HTTP $code${if (body.isNullOrBlank()) "" else ": $body"}")
            }
            return c
        }
        throw IOException("Too many redirects")
    }

    fun text(url: String, headers: Map<String, String> = emptyMap()): String {
        val c = open(url, headers)
        try {
            return c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            c.disconnect()
        }
    }

    fun json(url: String): JsonElement = Json.parseToJsonElement(text(url, mapOf("Accept" to "application/json")))

    class Download(val file: File, val fileName: String, val contentType: String?)

    /**
     * Downloads [url] into [dir]. [progress] gets (bytes so far, total or -1); throw from it
     * to cancel. [maxBytes] stops oversized downloads.
     */
    fun download(
        url: String,
        dir: File,
        headers: Map<String, String> = emptyMap(),
        suggestedName: String? = null,
        maxBytes: Long = 512L * 1024 * 1024,
        progress: (Long, Long) -> Unit = { _, _ -> },
    ): Download {
        val c = open(url, headers)
        try {
            val total = c.contentLengthLong
            if (total > maxBytes) throw IOException("The file is too big (${total / (1024 * 1024)} MB)")
            val name = sanitizeFileName(suggestedName ?: fileNameFrom(c.getHeaderField("Content-Disposition"), c.url.path))
            dir.mkdirs()
            val tmp = File.createTempFile("download", ".part", dir)
            try {
                c.inputStream.use { input -> tmp.outputStream().use { out -> copy(input, out, total, maxBytes, progress) } }
                val dest = File(dir, name)
                if (dest.exists()) dest.delete()
                if (!tmp.renameTo(dest)) {
                    tmp.copyTo(dest, overwrite = true)
                    tmp.delete()
                }
                return Download(dest, name, c.contentType)
            } catch (e: Throwable) {
                tmp.delete()
                throw e
            }
        } finally {
            c.disconnect()
        }
    }

    private fun copy(input: InputStream, out: java.io.OutputStream, total: Long, max: Long, progress: (Long, Long) -> Unit) {
        val buf = ByteArray(64 * 1024)
        var done = 0L
        var last = 0L
        progress(0, total)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            done += n
            if (done > max) throw IOException("The file is too big")
            if (done - last >= 128 * 1024) { progress(done, total); last = done }
        }
        progress(done, total)
    }

    /** File name from a Content-Disposition header, else from the URL path. */
    fun fileNameFrom(disposition: String?, path: String?): String {
        if (disposition != null) {
            Regex("filename\\*\\s*=\\s*(?:UTF-8|utf-8)''([^;]+)").find(disposition)?.let {
                return java.net.URLDecoder.decode(it.groupValues[1].trim().trim('"'), "UTF-8")
            }
            Regex("filename\\s*=\\s*\"([^\"]*)\"").find(disposition)?.let { return it.groupValues[1] }
            Regex("filename\\s*=\\s*([^;]+)").find(disposition)?.let { return it.groupValues[1].trim() }
        }
        val last = path?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "download.zip"
        return runCatching { java.net.URLDecoder.decode(last.replace("+", "%2B"), "UTF-8") }.getOrDefault(last)
    }

    fun sanitizeFileName(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().trim('.')
        return cleaned.take(120).ifEmpty { "download.zip" }
    }

    fun encode(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}

/** Modrinth's public API (no key needed). */
object Modrinth {
    const val API = "https://api.modrinth.com/v2"

    enum class Sort(val index: String, val label: String) {
        RELEVANCE("relevance", "Relevance"),
        DOWNLOADS("downloads", "Downloads"),
        FOLLOWS("follows", "Follows"),
        NEWEST("newest", "Newest"),
        UPDATED("updated", "Updated"),
    }

    class Hit(
        val id: String,
        val slug: String,
        val title: String,
        val description: String,
        val author: String,
        val downloads: Long,
        val follows: Long,
        val iconUrl: String?,
        val categories: List<String>,
        val gameVersions: List<String>,
        val dateModified: String,
        val featuredImage: String?,
        val color: Int?,
    ) {
        val pageUrl get() = "https://modrinth.com/resourcepack/$slug"
    }

    class SearchPage(val hits: List<Hit>, val offset: Int, val total: Int)

    class FileRef(val url: String, val fileName: String, val primary: Boolean, val size: Long)

    class Version(
        val id: String,
        val name: String,
        val number: String,
        val type: String,
        val gameVersions: List<String>,
        val loaders: List<String>,
        val datePublished: String,
        val downloads: Long,
        val files: List<FileRef>,
    )

    class GalleryImage(val url: String, val rawUrl: String?, val title: String?, val featured: Boolean)

    class Project(
        val id: String,
        val slug: String,
        val title: String,
        val description: String,
        val body: String,
        val iconUrl: String?,
        val downloads: Long,
        val followers: Long,
        val license: String?,
        val categories: List<String>,
        val gallery: List<GalleryImage>,
        val updated: String,
    ) {
        val pageUrl get() = "https://modrinth.com/resourcepack/$slug"
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.long(k: String) = (this[k] as? JsonPrimitive)?.longOrNull ?: 0L
    private fun JsonObject.strings(k: String) =
        (this[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content } ?: emptyList()

    fun search(query: String, offset: Int = 0, limit: Int = 20, sort: Sort = Sort.RELEVANCE): SearchPage {
        val facets = Http.encode("[[\"project_type:resourcepack\"]]")
        val url = "$API/search?query=${Http.encode(query)}&facets=$facets&index=${sort.index}&offset=$offset&limit=$limit"
        val o = Http.json(url).jsonObject
        val hits = o["hits"]?.jsonArray?.map { it.jsonObject }?.map { h ->
            Hit(
                id = h.str("project_id") ?: "",
                slug = h.str("slug") ?: h.str("project_id") ?: "",
                title = h.str("title") ?: "Untitled",
                description = h.str("description") ?: "",
                author = h.str("author") ?: "",
                downloads = h.long("downloads"),
                follows = h.long("follows"),
                iconUrl = h.str("icon_url")?.takeIf { it.isNotBlank() },
                categories = h.strings("display_categories").ifEmpty { h.strings("categories") },
                gameVersions = h.strings("versions"),
                dateModified = h.str("date_modified") ?: "",
                featuredImage = h.str("featured_gallery")?.takeIf { it.isNotBlank() } ?: h.strings("gallery").firstOrNull(),
                color = (h["color"] as? JsonPrimitive)?.intOrNull,
            )
        } ?: emptyList()
        return SearchPage(hits, (o["offset"] as? JsonPrimitive)?.intOrNull ?: offset, (o["total_hits"] as? JsonPrimitive)?.intOrNull ?: hits.size)
    }

    fun project(idOrSlug: String): Project {
        val p = Http.json("$API/project/${Http.encode(idOrSlug)}").jsonObject
        return Project(
            id = p.str("id") ?: idOrSlug,
            slug = p.str("slug") ?: idOrSlug,
            title = p.str("title") ?: "Untitled",
            description = p.str("description") ?: "",
            body = p.str("body") ?: "",
            iconUrl = p.str("icon_url")?.takeIf { it.isNotBlank() },
            downloads = p.long("downloads"),
            followers = p.long("followers"),
            license = (p["license"] as? JsonObject)?.let { lic ->
                val id = lic.str("id").orEmpty()
                lic.str("name")?.takeIf { it.isNotBlank() } ?: when {
                    id.equals("LicenseRef-All-Rights-Reserved", ignoreCase = true) -> "All rights reserved"
                    id.startsWith("LicenseRef-") -> id.removePrefix("LicenseRef-").replace('-', ' ')
                    else -> id
                }.takeIf { it.isNotBlank() }
            },
            categories = p.strings("categories") + p.strings("additional_categories"),
            gallery = (p["gallery"] as? JsonArray)?.mapNotNull { it as? JsonObject }?.map {
                GalleryImage(it.str("url") ?: "", it.str("raw_url"), it.str("title"), (it["featured"] as? JsonPrimitive)?.booleanOrNull == true)
            }?.filter { it.url.isNotBlank() }?.sortedByDescending { it.featured } ?: emptyList(),
            updated = p.str("updated") ?: "",
        )
    }

    fun versions(idOrSlug: String): List<Version> =
        Http.json("$API/project/${Http.encode(idOrSlug)}/version").jsonArray.map { it.jsonObject }.map { v ->
            Version(
                id = v.str("id") ?: "",
                name = v.str("name") ?: v.str("version_number") ?: "",
                number = v.str("version_number") ?: "",
                type = v.str("version_type") ?: "release",
                gameVersions = v.strings("game_versions"),
                loaders = v.strings("loaders"),
                datePublished = v.str("date_published") ?: "",
                downloads = v.long("downloads"),
                files = (v["files"] as? JsonArray)?.mapNotNull { it as? JsonObject }?.map {
                    FileRef(it.str("url") ?: "", it.str("filename") ?: "pack.zip", (it["primary"] as? JsonPrimitive)?.booleanOrNull == true, it.long("size"))
                }?.filter { it.url.isNotBlank() } ?: emptyList(),
            )
        }

    /** "1.20 – 26.2" style summary of a version list (Modrinth lists every release). */
    fun versionRange(versions: List<String>): String {
        val releases = versions.filter { Regex("\\d+(\\.\\d+)+").matches(it) }
        if (releases.isEmpty()) return versions.lastOrNull() ?: ""
        return if (releases.size == 1) releases[0] else "${releases.first()} – ${releases.last()}"
    }
}
