package com.alastor.ctmforge.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.alastor.ctmforge.core.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Tiny image loader for Modrinth icons and gallery shots: memory + disk cache. */
object RemoteImages {
    private lateinit var dir: File
    private val memory = object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }
    private val locks = HashMap<String, Mutex>()

    fun init(context: Context) {
        dir = File(context.cacheDir, "images").apply { mkdirs() }
        // keep the disk cache under ~60 MB
        Thread {
            val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return@Thread
            var total = 0L
            for (f in files) { total += f.length(); if (total > 60L * 1024 * 1024) f.delete() }
        }.start()
    }

    fun cached(key: String?): ImageBitmap? = key?.let { memory.get(it) }

    fun diskBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    fun clear() {
        memory.evictAll()
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun lockFor(key: String) = synchronized(locks) { locks.getOrPut(key) { Mutex() } }

    private fun hash(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    suspend fun load(url: String, maxSize: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        val key = "$url@$maxSize"
        memory.get(key)?.let { return@withContext it }
        lockFor(key).withLock {
            memory.get(key)?.let { return@withLock it }
            val file = File(dir, hash(url))
            val bytes = if (file.isFile) file.readBytes().also { file.setLastModified(System.currentTimeMillis()) } else {
                runCatching { fetch(url) }.getOrNull()?.also { runCatching { file.writeBytes(it) } }
            } ?: return@withLock null
            decode(bytes, maxSize)?.also { memory.put(key, it) }
        }
    }

    private fun fetch(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", Http.userAgent)
        try {
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            return c.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(32 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > 12 * 1024 * 1024) error("image too large")
                }
                out.toByteArray()
            }
        } finally {
            c.disconnect()
        }
    }

    fun decode(bytes: ByteArray, maxSize: Int): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxSize && bounds.outHeight / (sample * 2) >= maxSize) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
    }
}

@Composable
fun RemoteImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    maxSize: Int = 256,
    pixelated: Boolean = false,
    placeholder: @Composable () -> Unit = {},
) {
    val key = url?.let { "$it@$maxSize" }
    val bitmap by produceState(initialValue = RemoteImages.cached(key), key) {
        value = RemoteImages.cached(key) // keys changed: don't keep showing the previous image
        if (value == null && url != null) value = RemoteImages.load(url, maxSize)
    }
    val b = bitmap
    if (b != null) {
        Image(
            bitmap = b,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale,
            filterQuality = if (pixelated) FilterQuality.None else FilterQuality.Low,
        )
    } else {
        Box(modifier) { placeholder() }
    }
}
