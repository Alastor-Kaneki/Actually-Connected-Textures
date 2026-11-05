package com.alastor.ctmforge.core

import com.alastor.ctmforge.core.net.Http
import com.alastor.ctmforge.core.net.Modrinth
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Live Modrinth round trip: search, pick a version, download, convert. Needs CTM_NET=1. */
class NetTest {
    @Test
    fun modrinthSearchDownloadConvert() {
        if (System.getenv("CTM_NET") != "1") {
            println("CTM_NET not set; skipping live test")
            return
        }
        assertEquals("a b.zip", Http.fileNameFrom(null, "/x/a%20b.zip"))
        assertEquals("Glass [1.20].zip", Http.fileNameFrom("attachment; filename=\"Glass [1.20].zip\"", "/x"))
        assertEquals("ü.zip", Http.fileNameFrom("attachment; filename*=UTF-8''%C3%BC.zip", "/x"))

        val page = Modrinth.search("connected glass")
        println("search: ${page.total} hits; first: ${page.hits.take(5).map { it.title + " (" + it.downloads + ")" }}")
        assertTrue(page.hits.isNotEmpty())
        val page2 = Modrinth.search("connected glass", offset = 20, sort = Modrinth.Sort.DOWNLOADS)
        println("page 2: ${page2.hits.size} hits at offset ${page2.offset}")

        val project = Modrinth.project("clean-connected-glass")
        println("project: ${project.title}, ${project.gallery.size} images, license ${project.license}")
        val versions = Modrinth.versions(project.id)
        println("versions: ${versions.size}; latest ${versions.first().name} for ${Modrinth.versionRange(versions.first().gameVersions)}")
        val file = versions.first().files.first { it.primary }
        val dir = Files.createTempDirectory("ctmdl").toFile()
        var last = 0L
        val dl = Http.download(file.url, dir) { done, _ -> last = done }
        println("downloaded ${dl.fileName}: ${dl.file.length()} bytes ($last), type ${dl.contentType}")
        assertEquals(file.size, dl.file.length())

        val cands = JavaPack.candidates(dl.file.readBytes(), dl.fileName)
        println("candidates: ${cands.map { it.label + " (" + it.pack.ctmRuleCount + " rules)" }}")
        val result = Converter.convert(cands.first().pack, ConvertOptions(sourceName = dl.fileName))
        println("converted '${result.name}' (${result.slug}): ${result.blocks.size} blocks, ${result.files.size} files; log ${result.log}")
        assertTrue(result.blocks.containsKey("glass"))
        dir.deleteRecursively()
    }
}
