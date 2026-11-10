package com.alastor.ctmforge.data

import android.content.Context
import android.util.AtomicFile
import com.alastor.ctmforge.core.*
import kotlinx.serialization.json.*
import java.io.File

/** Editable projects and owned copies of their textures; no document-provider grants needed. */
class CreatorStore(context: Context) {
    private val root = File(context.applicationContext.filesDir, "creator")
    private fun dir(id: String): File {
        require(Regex("[a-zA-Z0-9-]{1,64}").matches(id))
        return File(root, id)
    }
    fun asset(project: String, name: String): File {
        require(Regex("[a-zA-Z0-9-]+\\.png").matches(name))
        return File(dir(project), name)
    }
    fun list(): List<CreatorProject> = root.listFiles().orEmpty().filter { it.isDirectory }
        .sortedByDescending { File(it, "project.json").lastModified() }.map { read(it.name) }

    fun save(p: CreatorProject) {
        val d = dir(p.id).apply { mkdirs() }
        val file = AtomicFile(File(d, "project.json"))
        val stream = file.startWrite()
        try { stream.write(encode(p).toString().toByteArray()); file.finishWrite(stream) }
        catch (e: Throwable) { file.failWrite(stream); throw e }
    }
    fun delete(id: String) { check(dir(id).deleteRecursively()) { "Couldn't delete the draft." } }
    fun read(id: String): CreatorProject = decode(Json.parseToJsonElement(
        AtomicFile(File(dir(id), "project.json")).openRead().use { it.readBytes().decodeToString() }
    ).jsonObject)

    companion object {
        fun encode(p: CreatorProject) = buildJsonObject {
            put("schema", 1); put("id", p.id); put("name", p.name); put("description", p.description)
            put("rules", JsonArray(p.rules.map { r -> buildJsonObject {
                put("id", r.id); put("block", r.block); put("method", r.method.name); put("faces", r.faces)
                put("animated", r.animated); put("ticks", r.ticks); put("order", r.order)
                put("interpolate", r.interpolate); put("innerSeams", r.innerSeams)
                put("tiles", JsonArray(r.tiles.map { t -> t?.let { buildJsonObject {
                    put("asset", it.asset); put("size", it.size); put("frames", it.frames)
                } } ?: JsonNull }))
            } }))
        }
        fun decode(o: JsonObject): CreatorProject {
            require(o["schema"]?.jsonPrimitive?.int == 1) { "This draft needs a newer CTM Forge." }
            return CreatorProject(o.getValue("id").jsonPrimitive.content, o.getValue("name").jsonPrimitive.content,
                o.getValue("description").jsonPrimitive.content, o.getValue("rules").jsonArray.map { e ->
                    val r = e.jsonObject
                    CreatorRule(
                        id = r.getValue("id").jsonPrimitive.content,
                        block = r.getValue("block").jsonPrimitive.content,
                        method = CreatorMethod.valueOf(r.getValue("method").jsonPrimitive.content),
                        faces = r.getValue("faces").jsonPrimitive.content,
                        tiles = r.getValue("tiles").jsonArray.map { t -> if (t == JsonNull) null else t.jsonObject.let {
                            CreatorTexture(it.getValue("asset").jsonPrimitive.content, it.getValue("size").jsonPrimitive.int, it.getValue("frames").jsonPrimitive.int)
                        } },
                        animated = r.getValue("animated").jsonPrimitive.boolean,
                        ticks = r.getValue("ticks").jsonPrimitive.content,
                        order = r.getValue("order").jsonPrimitive.content,
                        interpolate = r.getValue("interpolate").jsonPrimitive.boolean,
                        innerSeams = r.getValue("innerSeams").jsonPrimitive.boolean,
                    )
                })
        }
    }
}
