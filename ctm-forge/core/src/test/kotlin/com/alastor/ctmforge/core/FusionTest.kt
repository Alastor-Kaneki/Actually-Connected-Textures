package com.alastor.ctmforge.core

import kotlinx.serialization.json.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class FusionTest {
    private val fixtures = File(System.getProperty("ctm.fusionFixtures"))
    private val golden = File(System.getProperty("ctm.fusionGolden"))
    private fun load(name: String) = JavaPack.fromEntries(File(fixtures,name).walkTopDown().filter { it.isFile }.associate { it.relativeTo(File(fixtures,name)).invariantSeparatorsPath to it.readBytes() })
    private fun json(bytes: ByteArray) = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8))
    private fun zip(files: Map<String,ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z -> for ((p,b) in files) {z.putNextEntry(ZipEntry(p));z.write(b);z.closeEntry()} }
    }.toByteArray()
    private fun synthetic(layout: String, size: Int = 4): Pair<JavaPack,Img> {
        val (cols,rows) = when(layout) {"full" -> 8 to 6; "simple" -> 4 to 4; "horizontal" -> 4 to 1; "vertical" -> 1 to 4; else -> 5 to 1}
        val atlas=Img(cols*size,rows*size)
        for(y in 0 until atlas.height) for(x in 0 until atlas.width) atlas[x,y] = (0xff00007bL or (x.toLong() shl 16) or (y.toLong() shl 8)).toInt()
        val files=load("glass").files.toMutableMap()
        files["assets/minecraft/textures/block/glass.png"]=Png.encode(atlas)
        files["assets/minecraft/textures/block/glass.png.mcmeta"]="""{"fusion":{"type":"connecting","layout":"$layout","connections":{"type":"is_same_block"}}}""".toByteArray()
        return JavaPack.fromEntries(files) to atlas
    }
    @Test fun officialExamplesMatchPythonPixelsJsonAndReports() {
        for (case in listOf("glass","compact","full","glass-legacy","compact-legacy","full-legacy")) for(direct in listOf(false,true)) {
            val name=case+if(direct) "-direct" else ""
            val file=File(golden,"$name.mcpack")
            assertTrue(file.isFile,"Missing Python golden: run python tests/fusion_goldens.py")
            val expected=JavaPack.readZip(file.inputStream())
            val result=Converter.convert(load(case),ConvertOptions(name="Fixture",slug="fixture",direct=direct))
            assertEquals(expected.keys,result.files.keys,name)
            for((p,b) in expected) {
                val actual=result.files.getValue(p)
                when {p.endsWith(".png") -> assertEquals(Png.decode(b),Png.decode(actual),"$name $p")
                    p.endsWith(".json") -> assertEquals(json(b),json(actual),"$name $p")
                    else -> assertContentEquals(b,actual,"$name $p")}
            }
            val summary=json(File(golden,"$name.json").readBytes()).jsonObject
            assertEquals(summary["blocks"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content },result.blocks,name)
            assertEquals(summary["methods"]!!.jsonObject.mapValues { it.value.jsonPrimitive.int },result.methods,name)
            assertEquals(summary["log"]!!.jsonArray.map {it.jsonPrimitive.content},result.log,name)
        }
    }
    @Test fun allNeighbourMasksMatchUpstreamOracle() {
        val oracle=json(File(fixtures,"layout-oracle.json").readBytes()).jsonObject
        for(layout in listOf("full","simple","compact","pieced","horizontal","vertical")) {
            val(pack,atlas)=synthetic(layout)
            val rule=Fusion.readRules(pack,mutableListOf()).single()
            val tiles=Fusion.tiles(pack,rule)
            for(mask in 0..255) {
                val expected=if(layout=="pieced") {
                    val out=Img(4,4)
                    val table=intArrayOf(0,3,2,4,0,3,2,1)
                    for(a in listOf(intArrayOf(0,0,0,6,7),intArrayOf(0,2,0,2,1),intArrayOf(2,2,4,2,3),intArrayOf(2,0,4,6,5))) {
                        val(x,y,h,v,c)=a
                        val k=table[((mask shr h) and 1)+2*((mask shr v) and 1)+4*((mask shr c) and 1)]
                        out.paste(atlas.crop(k*4+x,y,k*4+x+2,y+2),x,y)
                    };out
                } else {
                    val k=oracle.getValue(layout).jsonArray[mask].jsonPrimitive.int
                    val cols=atlas.width/4;val x=k%cols*4;val y=k/cols*4
                    atlas.crop(x,y,x+4,y+4)
                }
                assertEquals(expected,tiles[Ctm.CTM47[mask]],"$layout mask $mask")
            }
        }
    }
    @Test fun appRecognizesFusionOnlyAndNestedPacksAndKeepsModels() {
        for(case in listOf("glass","glass-legacy")) {
            val input=zip(load(case).files.mapKeys {"wrapped/"+it.key})
            val nested=zip(mapOf("Pack.zip" to input))
            val candidate=JavaPack.candidates(nested,"Download").first()
            assertTrue(candidate.pack.ctmRuleCount>0)
            if(case.endsWith("legacy")) assertNotNull(candidate.pack.get("assets/minecraft/models/block/glass.json"))
            assertEquals(setOf("glass"),Converter.convert(candidate.pack,ConvertOptions(name="Test")).blocks.keys)
        }
    }
    @Test fun unsupportedOfficialPacksHaveActionableNotes() {
        for((case,reason) in listOf("animated" to "animated","grass" to "supported","random" to "type","overlay" to "overlay")) {
            val p=load(case)
            assertTrue(p.ctmRuleCount>0)
            val e=assertFailsWith<ConversionException> {Converter.convert(p,ConvertOptions(name="Unsupported"))}
            assertTrue(e.log.any {reason in it},"$case ${e.log}")
        }
    }
    @Test fun dimensionsAndMissingImagesAreReportedWithoutCrashingBatch() {
        val(pack,_)=synthetic("vertical")
        assertEquals(47,Fusion.tiles(pack,Fusion.readRules(pack,mutableListOf()).single()).size)
        val path="assets/minecraft/textures/block/glass.png"
        for(files in listOf(pack.files-path, pack.files+(path to load("glass").files.getValue(path)))) {
            val e=assertFailsWith<ConversionException> {Converter.convert(JavaPack.fromEntries(files),ConvertOptions(name="Broken"))}
            assertTrue(e.log.any {it.startsWith("skip ")})
        }
        val(odd,_)=synthetic("pieced",3)
        val e=assertFailsWith<ConversionException> {Converter.convert(odd,ConvertOptions(name="Odd"))}
        assertTrue(e.log.any {"even" in it})
    }
    @Test fun modelAliasesInheritanceAndConditionalStates() {
        val (p,_)=synthetic("pieced")
        val path="assets/minecraft/models/block/glass.json"
        val files=p.files.toMutableMap()
        files[path]="""{"loader":"fusion:model","parent":"test:block/base","textures":{"all":"#alias","alias":"block/glass"},"connections":{"type":"is_same_block"}}""".toByteArray()
        files["assets/test/models/block/base.json"]="""{"parent":"block/cube_all"}""".toByteArray()
        assertEquals(1,Fusion.readRules(JavaPack.fromEntries(files),mutableListOf()).size)
        files[path]=files.getValue(path).toString(Charsets.UTF_8).replace("\"alias\":\"block/glass\"","\"alias\":\"#all\"").toByteArray()
        var log=mutableListOf<String>()
        assertTrue(Fusion.readRules(JavaPack.fromEntries(files),log).isEmpty())
        assertTrue(log.any {"cyclic texture" in it})
        files.remove(path)
        files["assets/minecraft/blockstates/glass.json"]="""{"variants":{"facing=north":{"model":"block/glass"}}}""".toByteArray()
        log=mutableListOf()
        assertTrue(Fusion.readRules(JavaPack.fromEntries(files),log).isEmpty())
        assertTrue(log.any {"conditional" in it})
    }
    @Test fun mixedOptifinePackDoesNotUseUnsupportedAtlasAsBase() {
        val(p,_)=synthetic("overlay")
        val files=p.files.toMutableMap()
        files["assets/minecraft/optifine/ctm/glass/a.properties"]="matchBlocks=glass\nmethod=fixed\ntiles=0".toByteArray()
        files["assets/minecraft/optifine/ctm/glass/0.png"]=Png.encode(Img(4,4,IntArray(16){0xff010203.toInt()}))
        val r=Converter.convert(JavaPack.fromEntries(files),ConvertOptions(name="Fixture"))
        assertEquals(mapOf("glass" to "fixed-1x1-1"),r.blocks)
        assertTrue(r.log.any {"overlay" in it})
        assertFalse("textures/blocks/glass.png" in r.files)
        assertEquals(0xff010203.toInt(),Png.decode(r.files.getValue("textures/blocks/ctm/base/fixture_glass.png"))[0,0])
    }
    @Test fun modelOnlyDiagnosticsAndOptifineDefaultGuard() {
        val p=JavaPack.fromEntries(mapOf("assets/example/models/item/test.json" to """{"loader":"fusion:model","type":"unknown"}""".toByteArray()))
        assertTrue(p.ctmRuleCount>0)
        val log=mutableListOf<String>()
        assertTrue(Fusion.readRules(p,log).isEmpty())
        assertTrue(log.any {"item/entity/custom" in it})
        val(glass,_)=synthetic("pieced")
        val files=glass.files+("assets/minecraft/optifine/ctm/glass/a.properties" to "matchBlocks=glass\nmethod=fixed\ntiles=<default>".toByteArray())
        val result=Converter.convert(JavaPack.fromEntries(files),ConvertOptions(name="Test"))
        assertEquals(mapOf("glass" to "ctm-8x6-47"),result.blocks)
        assertTrue(result.log.any {"missing tile" in it})
    }
    @Test fun malformedMetadataAndUnsupportedPredicatesDoNotSilentlyConvert() {
        for(meta in listOf("null","{\"type\":\"connecting\",\"layout\":[\"pieced\"]}","{\"type\":\"connecting\",\"connections\":{\"type\":\"is_same_state\"}}","{\"type\":\"connecting\",\"emissive\":true}")) {
            val(p,_)=synthetic("pieced")
            val files=p.files+("assets/minecraft/textures/block/glass.png.mcmeta" to "{\"fusion\":$meta}".toByteArray())
            val log=mutableListOf<String>()
            assertTrue(Fusion.readRules(JavaPack.fromEntries(files),log).isEmpty())
            assertTrue(log.isNotEmpty())
        }
    }
}
