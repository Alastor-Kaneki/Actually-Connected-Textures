package com.alastor.ctmforge

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.*
import com.alastor.ctmforge.core.*
import com.alastor.ctmforge.data.CreatorStore
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.util.UUID

class CreatorController(private val app: App, private val scope: CoroutineScope, private val onBuilt: (String) -> Unit) {
    val store = CreatorStore(app)
    var projects by mutableStateOf<List<CreatorProject>>(emptyList()); private set
    var project by mutableStateOf<CreatorProject?>(null); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)
    var pendingSaves by mutableIntStateOf(0); private set
    private val writes = Mutex()

    init { task { projects = withContext(Dispatchers.IO) { store.list() } } }

    private fun task(work: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try { work() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = AppViewModel.friendly(e) }
            catch (_: OutOfMemoryError) { error = "Not enough memory. Use smaller textures or fewer frames." }
            finally { busy = false }
        }
    }
    fun create() = task {
        val p = CreatorProject()
        writes.withLock { withContext(Dispatchers.IO) { store.save(p) } }
        projects = listOf(p) + projects
        project = p
    }
    fun open(p: CreatorProject) { if (!busy) { project = p; error = null } }
    fun close() { if (!busy) project = null }
    fun edit(p: CreatorProject) {
        if (busy || p.id != project?.id) return
        project = p
        projects = listOf(p) + projects.filterNot { it.id == p.id }
        pendingSaves++
        scope.launch {
            try { writes.withLock { withContext(Dispatchers.IO) { store.save(p) } } }
            catch (e: Exception) { error = "Couldn't save draft: ${e.message}" }
            finally { pendingSaves-- }
        }
    }
    fun editRule(id: String, change: (CreatorRule) -> CreatorRule) {
        project?.let { p -> edit(p.copy(rules = p.rules.map { if (it.id == id) change(it) else it })) }
    }
    fun delete(p: CreatorProject) = task {
        writes.withLock { withContext(Dispatchers.IO) { store.delete(p.id) } }
        projects = projects.filterNot { it.id == p.id }
        if (project?.id == p.id) project = null
    }

    /** PNG strips, a selection of individual animation frames, or a whole set of CTM tiles. */
    fun importImages(uris: List<Uri>, ruleId: String, tileIndex: Int, mode: String) {
        if (uris.isEmpty()) return
        val p = project ?: return
        val rule = p.rules.find { it.id == ruleId } ?: return
        task {
            val imported = withContext(Dispatchers.IO) {
                require(uris.size <= 64) { "Select at most 64 PNG files." }
                // Natural filename ordering gives 0, 1, 2, ... 10, 11 for CTM tile sets and frames.
                val sorted = uris.sortedWith(compareBy<Uri> { naturalName(displayName(app, it)) }.thenBy { it.toString() })
                if (mode == "tiles") require(sorted.size == rule.tiles.size) { "Select exactly ${rule.tiles.size} tile PNGs." }
                if (mode == "frames") require(sorted.size in 2..64) { "Select 2 to 64 square frame PNGs." }
                var pixels = 0L
                val images = sorted.map { uri ->
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        val bytes = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            require(bytes.size() + n <= PackCreator.MAX_IMAGE_BYTES) { "PNG files must be smaller than 8 MB." }
                            bytes.write(buffer, 0, n)
                        }
                        PackCreator.decodeTexture(bytes.toByteArray()).also { image ->
                            pixels += image.width.toLong() * image.height
                            require(pixels <= PackCreator.MAX_PROJECT_PIXELS) { "Selection is too large. Use smaller textures or fewer frames." }
                        }
                    } ?: error("Couldn't open the selected image.")
                }
                require(images.sumOf { it.width.toLong() * it.height } <= PackCreator.MAX_PROJECT_PIXELS) { "Selection is too large. Use smaller textures or fewer frames." }
                val textures = if (mode == "frames") {
                    require(images.all { it.width == it.height && it.width == images.first().width }) { "All frame PNGs must be square and the same size." }
                    listOf(Anim.stack(images))
                } else images
                textures.map { image ->
                    val asset = "${UUID.randomUUID()}.png"
                    val file = store.asset(p.id, asset)
                    file.parentFile!!.mkdirs()
                    file.writeBytes(Png.encode(image, 6))
                    CreatorTexture(asset, image.width, image.height / image.width)
                }
            }
            val updated = rule.copy(tiles = if (mode == "tiles") imported else rule.tiles.mapIndexed { i, t -> if (i == tileIndex) imported.single() else t })
            val next = p.copy(rules = p.rules.map { if (it.id == ruleId) updated else it })
            writes.withLock { withContext(Dispatchers.IO) { store.save(next) } }
            project = next
            projects = listOf(next) + projects.filterNot { it.id == p.id }
        }
    }
    fun build() {
        val p = project ?: return
        task {
            val result = withContext(Dispatchers.Default) {
                val patch = app.settings.nextPatch(app.library.idFor("created_${p.id}", false))
                PackCreator.build(p, patch) { store.asset(p.id, it).readBytes() }
            }
            val entry = withContext(Dispatchers.IO) { app.library.add(result, "Created in CTM Forge", null) }
            onBuilt(entry.id)
        }
    }
    companion object {
        private fun displayName(context: Context, uri: Uri): String = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment.orEmpty()
        private fun naturalName(name: String) = Regex("\\d+").replace(name.lowercase()) { it.value.padStart(20, '0') }
    }
}
