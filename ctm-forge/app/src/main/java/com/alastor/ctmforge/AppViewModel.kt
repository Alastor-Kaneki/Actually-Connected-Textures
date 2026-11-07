package com.alastor.ctmforge

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.CookieManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.alastor.ctmforge.core.ConversionException
import com.alastor.ctmforge.core.ConversionResult
import com.alastor.ctmforge.core.ConvertOptions
import com.alastor.ctmforge.core.Converter
import com.alastor.ctmforge.core.JavaPack
import com.alastor.ctmforge.core.isBedrockPack
import com.alastor.ctmforge.core.net.Http
import com.alastor.ctmforge.core.net.Modrinth
import com.alastor.ctmforge.core.sniff
import com.alastor.ctmforge.data.Launchers
import com.alastor.ctmforge.ui.RemoteImages
import com.alastor.ctmforge.ui.WebHolder
import com.alastor.ctmforge.ui.WebSite
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val app get() = getApplication<App>()
    val settings get() = app.settings
    val library get() = app.library
    val updater get() = app.updater

    // ------------------------------------------------------------------ navigation

    enum class Tab { BROWSE, LIBRARY, SETTINGS }

    sealed interface Route {
        data class Project(val hit: Modrinth.Hit) : Route
        data class Pack(val id: String) : Route
        data object Convert : Route
    }

    var tab by mutableStateOf(Tab.BROWSE)
    val routes = mutableStateListOf<Route>()

    fun push(route: Route) {
        routes.remove(route)
        routes.add(route)
    }

    fun pop() {
        val top = routes.lastOrNull() ?: return
        if (top == Route.Convert) closeSession() else routes.removeAt(routes.lastIndex)
    }

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages
    fun toast(message: String) { _messages.tryEmit(message) }

    // ------------------------------------------------------------------ browse: Modrinth

    enum class Source(val label: String) { MODRINTH("Modrinth"), CURSEFORGE("CurseForge"), PLANET("Planet MC") }

    var source by mutableStateOf(Source.MODRINTH)
    var query by mutableStateOf("connected glass")
    var sort by mutableStateOf(Modrinth.Sort.RELEVANCE)
    val hits = mutableStateListOf<Modrinth.Hit>()
    var totalHits by mutableStateOf(-1)
        private set
    var searching by mutableStateOf(false)
        private set
    var searchError by mutableStateOf<String?>(null)
        private set
    private var searchJob: Job? = null
    private var searchGen = 0

    fun search() {
        searchJob?.cancel()
        hits.clear()
        totalHits = -1
        searchError = null
        val gen = ++searchGen
        searchJob = viewModelScope.launch { loadPage(0, gen) }
    }

    fun loadMore() {
        if (searching || searchError != null || totalHits < 0 || hits.size >= totalHits) return
        val gen = searchGen
        searchJob = viewModelScope.launch { loadPage(hits.size, gen) }
    }

    private suspend fun loadPage(offset: Int, gen: Int) {
        searching = true
        try {
            val q = query.trim().ifEmpty { "connected textures" }
            val page = withContext(Dispatchers.IO) { Modrinth.search(q, offset, 20, sort) }
            if (gen != searchGen) return
            val seen = hits.map { it.id }.toHashSet()
            hits.addAll(page.hits.filter { seen.add(it.id) })
            totalHits = if (page.hits.isEmpty()) hits.size else page.total
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (gen == searchGen) searchError = friendly(e)
        } finally {
            if (gen == searchGen) searching = false
        }
    }

    class ProjectState(val hit: Modrinth.Hit) {
        var project by mutableStateOf<Modrinth.Project?>(null)
        var versions by mutableStateOf<List<Modrinth.Version>?>(null)
        var error by mutableStateOf<String?>(null)
        var loading by mutableStateOf(false)
    }

    private val projects = HashMap<String, ProjectState>()

    fun projectState(hit: Modrinth.Hit): ProjectState =
        projects.getOrPut(hit.id) { ProjectState(hit).also { loadProject(it) } }

    fun loadProject(st: ProjectState) {
        if (st.loading) return
        st.loading = true
        st.error = null
        viewModelScope.launch {
            try {
                coroutineScope {
                    val p = async(Dispatchers.IO) { Modrinth.project(st.hit.id) }
                    val v = async(Dispatchers.IO) { Modrinth.versions(st.hit.id) }
                    st.project = p.await()
                    st.versions = v.await()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                st.error = friendly(e)
            } finally {
                st.loading = false
            }
        }
    }

    fun downloadModrinth(hit: Modrinth.Hit, version: Modrinth.Version, file: Modrinth.FileRef) {
        startDownload(file.url, file.fileName, "Modrinth", hit.pageUrl, hit.title, emptyMap())
    }

    // ------------------------------------------------------------------ browse: web sites

    val webHolders = HashMap<WebSite, WebHolder>()

    fun onWebDownload(site: WebSite, url: String, userAgent: String?, pageUrl: String?) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            toast("This download can't be caught (${url.substringBefore(':')}: link). Try a different download button.")
            return
        }
        val headers = LinkedHashMap<String, String>()
        userAgent?.takeIf { it.isNotBlank() }?.let { headers["User-Agent"] = it }
        runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()?.takeIf { it.isNotBlank() }?.let { headers["Cookie"] = it }
        pageUrl?.let { headers["Referer"] = it }
        startDownload(url, null, site.title, pageUrl ?: url, null, headers)
    }

    // ------------------------------------------------------------------ conversion sessions

    class CandidateUi(
        val label: String,
        val pack: JavaPack,
        val rules: Int,
        val icon: ImageBitmap?,
        val description: String,
        /** "3 CTM rules · 12 Fusion textures · 2 animated textures" */
        val contents: String = "",
    )

    private fun contentsLabel(p: JavaPack): String = listOfNotNull(
        p.ctmRuleCount.takeIf { it > 0 }?.let { "$it CTM rule${if (it == 1) "" else "s"}" },
        p.fusionTextureCount.takeIf { it > 0 }?.let { "$it Fusion texture${if (it == 1) "" else "s"}" },
        p.animatedTextureCount.takeIf { it > 0 }?.let { "$it animated texture${if (it == 1) "" else "s"}" },
    ).joinToString(" · ")

    sealed interface Phase {
        data class Downloading(val done: Long, val total: Long) : Phase
        data object Reading : Phase
        data object Ready : Phase
        data class Converting(val done: Int, val total: Int) : Phase
        data class Done(val entryId: String, val result: ConversionResult) : Phase
        data class Failed(val title: String, val message: String, val log: List<String> = emptyList()) : Phase
    }

    class Session(fileName: String, val source: String?, val sourceUrl: String?, val suggestedName: String?) {
        var fileName by mutableStateOf(fileName)
        var phase by mutableStateOf<Phase>(Phase.Reading)
        var name by mutableStateOf(suggestedName ?: "")
        var nameEdited by mutableStateOf(false)
        var direct by mutableStateOf(false)
        var candidates by mutableStateOf<List<CandidateUi>>(emptyList())
        var selected by mutableStateOf(0)
        internal var job: Job? = null
        val busy get() = phase is Phase.Downloading || phase is Phase.Reading || phase is Phase.Converting
    }

    var session by mutableStateOf<Session?>(null)
        private set

    private fun newSession(fileName: String, source: String?, sourceUrl: String?, suggestedName: String?): Session {
        session?.job?.cancel()
        val s = Session(fileName, source, sourceUrl, suggestedName)
        s.direct = settings.directLayout
        session = s
        push(Route.Convert)
        return s
    }

    fun closeSession() {
        session?.job?.cancel()
        session = null
        routes.remove(Route.Convert)
    }

    private fun startDownload(
        url: String, fileName: String?, source: String, pageUrl: String?, suggestedName: String?, headers: Map<String, String>,
    ) {
        val s = newSession(fileName ?: Http.fileNameFrom(null, Uri.parse(url).path), source, pageUrl, suggestedName)
        s.phase = Phase.Downloading(0, -1)
        s.job = viewModelScope.launch {
            try {
                val job = coroutineContext.job
                val dl = withContext(Dispatchers.IO) {
                    val dir = File(app.cacheDir, "downloads")
                    dir.listFiles()?.forEach { it.delete() } // one download at a time; don't pile them up
                    Http.download(url, dir, headers, fileName) { done, total ->
                        if (!job.isActive) throw CancellationException("cancelled")
                        s.phase = Phase.Downloading(done, total)
                    }
                }
                s.fileName = dl.fileName
                val bytes = withContext(Dispatchers.IO) { dl.file.readBytes() }
                read(s, bytes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                s.phase = Phase.Failed("Download failed", friendly(e))
            }
        }
    }

    /** Opens a pack the user picked or shared from another app. */
    fun openUri(uri: Uri) {
        val resolver = app.contentResolver
        val name = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "pack.zip"
        val s = newSession(name, "Files", null, null)
        s.job = viewModelScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    resolver.openInputStream(uri)?.use { readCapped(it) } ?: throw IOException("Couldn't open the file")
                }
                read(s, bytes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                s.phase = Phase.Failed("Couldn't read the file", friendly(e))
            }
        }
    }

    private fun readCapped(input: InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > 512L * 1024 * 1024) throw IOException("The file is too big (over 512 MB)")
        }
        return out.toByteArray()
    }

    private suspend fun read(s: Session, bytes: ByteArray) {
        s.phase = Phase.Reading
        when (sniff(bytes)) {
            JavaPack.Kind.ZIP, JavaPack.Kind.BEDROCK_PACK -> Unit
            JavaPack.Kind.RAR, JavaPack.Kind.SEVEN_ZIP -> {
                s.phase = Phase.Failed(
                    "This is a ${if (sniff(bytes) == JavaPack.Kind.RAR) "RAR" else "7-Zip"} archive",
                    "CTM Forge reads .zip packs (and mod .jar files). Extract this archive with a file manager, " +
                        "then open the .zip inside it, or zip the pack folder.",
                )
                return
            }
            JavaPack.Kind.HTML -> {
                s.phase = Phase.Failed(
                    "The site sent a web page, not the pack",
                    "Go back and try the download button again. Some sites need the page to finish loading " +
                        "(or a countdown to end) before the real download starts.",
                )
                return
            }
            JavaPack.Kind.UNKNOWN -> {
                s.phase = Phase.Failed("Not a pack file", "CTM Forge reads Java Edition resource packs: .zip files, or mod .jar files that contain one.")
                return
            }
        }
        val candidates = withContext(Dispatchers.Default) {
            JavaPack.candidates(bytes, s.fileName).map { c ->
                CandidateUi(
                    label = c.label,
                    pack = c.pack,
                    rules = c.pack.convertibleCount,
                    contents = contentsLabel(c.pack),
                    icon = c.pack.get("pack.png")?.let { runCatching { RemoteImages.decode(it, 128) }.getOrNull() },
                    description = packDescription(c.pack),
                )
            }
        }
        val usable = candidates.filter { it.rules > 0 }
        if (usable.isEmpty()) {
            s.phase = if (candidates.any { isBedrockPack(it.pack.files.keys) }) {
                Phase.Failed("This is already a Bedrock pack", "It doesn't need converting: import it into Minecraft as it is.")
            } else {
                Phase.Failed(
                    "No connected or animated textures in this pack",
                    "CTM Forge converts packs made for OptiFine, Continuity or Fusion connected textures, and animated " +
                        "block textures. This pack has no \"optifine/ctm\" folder, no Fusion textures and no animated blocks.",
                )
            }
            return
        }
        s.candidates = usable
        s.selected = 0
        if (!s.nameEdited) s.name = s.suggestedName ?: defaultName(usable[0].label)
        s.phase = Phase.Ready
    }

    fun selectCandidate(index: Int) {
        val s = session ?: return
        s.selected = index
        if (!s.nameEdited && s.suggestedName == null) s.candidates.getOrNull(index)?.let { s.name = defaultName(it.label) }
    }

    fun convert() {
        val s = session ?: return
        val c = s.candidates.getOrNull(s.selected) ?: return
        val name = s.name.trim().ifEmpty { defaultName(c.label) }
        s.phase = Phase.Converting(0, c.rules)
        s.job = viewModelScope.launch {
            try {
                val job = coroutineContext.job
                val slug = Converter.slugify(name)
                val patch = settings.nextPatch(library.idFor(slug, s.direct))
                val options = ConvertOptions(name = name, direct = s.direct, sourceName = s.fileName, version = listOf(1, 0, patch), pngLevel = 6)
                val result = withContext(Dispatchers.Default) {
                    Converter.convert(c.pack, options) { done, total ->
                        if (!job.isActive) throw CancellationException("cancelled")
                        s.phase = Phase.Converting(done, total)
                    }
                }
                val entry = withContext(Dispatchers.IO) { library.add(result, s.source, s.sourceUrl) }
                s.phase = Phase.Done(entry.id, result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ConversionException) {
                s.phase = Phase.Failed(
                    "Nothing could be converted",
                    "None of this pack's connected or animated textures work on Bedrock yet. The notes below say why for each one.",
                    e.log,
                )
            } catch (e: OutOfMemoryError) {
                s.phase = Phase.Failed("This pack is too big", "The phone ran out of memory while converting it. Packs with very high resolution textures may not fit.")
            } catch (e: Throwable) {
                s.phase = Phase.Failed("Conversion failed", friendly(e))
            }
        }
    }

    fun deleteEntry(id: String) {
        val e = library.find(id) ?: return
        routes.remove(Route.Pack(id))
        viewModelScope.launch(Dispatchers.IO) { library.delete(e) }
        toast("Deleted ${e.name}")
    }

    fun clearCaches(done: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            File(app.cacheDir, "downloads").deleteRecursively()
            RemoteImages.clear()
            withContext(Dispatchers.Main) { done() }
        }
    }

    fun cacheBytes(): Long =
        (File(app.cacheDir, "downloads").listFiles()?.sumOf { it.length() } ?: 0L) + RemoteImages.diskBytes()

    /**
     * Hands the newest mod the app has (a downloaded update or the bundled one) to LeviLauncher.
     * With [shareIfMissing], opens the share sheet instead when LeviLauncher isn't installed.
     */
    fun installMod(context: Context, shareIfMissing: Boolean = false) {
        viewModelScope.launch {
            try {
                val f = withContext(Dispatchers.IO) { Launchers.modFile(app) }
                if (shareIfMissing && !Launchers.isInstalled(context, Launchers.LEVILAUNCHER)) {
                    Launchers.share(context, f)
                    return@launch
                }
                val problem = Launchers.open(context, f, Launchers.LEVILAUNCHER)
                if (problem != null) toast(problem) else updater.markModSent()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(friendly(e))
            }
        }
    }

    // Last in the class body: everything above must be initialised before the first search.
    init {
        viewModelScope.launch(Dispatchers.IO) { library.reload() }
        updater.busy = { session?.busy == true }
        search()
    }

    override fun onCleared() {
        webHolders.values.forEach { it.destroy() }
        webHolders.clear()
        super.onCleared()
    }

    companion object {
        /** A readable pack name from a file name ("Clean_Connected_Glass [1.20-26.x].zip" -> "Clean Connected Glass"). */
        fun defaultName(label: String): String {
            val base = Converter.displayNameFor(label)
            val cleaned = base.replace(Regex("\\s*[\\[(][^\\])]*\\d[^\\])]*[\\])]"), "")
                .replace(Regex("\\s+(v?\\d+(\\.\\d+)+[a-z]?)$", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s{2,}"), " ").trim()
            return cleaned.ifEmpty { base }.ifEmpty { "Converted pack" }
        }

        fun packDescription(pack: JavaPack): String = runCatching {
            val o = Json.parseToJsonElement(String(pack.get("pack.mcmeta") ?: return "", Charsets.UTF_8).removePrefix("﻿")) as JsonObject
            flatText((o["pack"] as? JsonObject)?.get("description")).replace(Regex("§."), "").trim()
        }.getOrDefault("")

        private fun flatText(e: JsonElement?): String = when (e) {
            is JsonPrimitive -> if (e.isString) e.content else ""
            is JsonArray -> e.joinToString("") { flatText(it) }
            is JsonObject -> flatText(e["text"]) + flatText(e["extra"])
            else -> ""
        }

        fun friendly(e: Throwable): String = when (e) {
            is UnknownHostException -> "No internet connection (couldn't reach ${e.message ?: "the server"})."
            is SocketTimeoutException -> "The server took too long to answer. Try again."
            is Http.HttpException -> when (e.code) {
                403 -> "The site refused the download (HTTP 403). Try it from the site's own download page."
                404 -> "The file isn't there anymore (HTTP 404)."
                429 -> "Too many requests; wait a moment and try again."
                in 500..599 -> "The server had a problem (HTTP ${e.code}). Try again later."
                else -> e.message ?: "HTTP ${e.code}"
            }
            is OutOfMemoryError -> "Ran out of memory."
            else -> e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        }
    }
}
