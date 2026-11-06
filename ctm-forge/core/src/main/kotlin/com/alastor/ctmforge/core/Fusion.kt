package com.alastor.ctmforge.core

import kotlinx.serialization.json.*

/** Conservative importer for static Fusion full-cube faces with same-block connections. */
internal object Fusion {
    data class Rule(val path: String, val texture: String, val block: String, val faces: Set<String>, val layout: String)
    private val faces = Converter.FACE_NAMES
    private val grids = mapOf("full" to (8 to 6), "pieced" to (5 to 1), "compact" to (5 to 1),
        "simple" to (4 to 4), "horizontal" to (4 to 1), "vertical" to (1 to 4))
    private val full = intArrayOf(0,1,2,3,4,5,6,7,44,36,43,42,8,9,10,11,12,13,14,15,45,37,35,34,16,17,18,19,22,23,20,21,38,39,32,40,24,25,26,27,30,31,28,29,46,47,33)
    private val simple = intArrayOf(0,3,2,11,6,4,10,9,7,15,5,13,14,12,8,1)
    private val cubes = buildSet {
        addAll("glass tinted_glass iron_block lapis_block gold_block diamond_block emerald_block redstone_block coal_block netherite_block stone cobblestone mossy_cobblestone diorite andesite granite polished_diorite polished_andesite polished_granite obsidian crying_obsidian bricks end_stone end_stone_bricks netherrack nether_bricks red_nether_bricks stone_bricks mossy_stone_bricks cracked_stone_bricks chiseled_stone_bricks tuff polished_tuff tuff_bricks calcite amethyst_block".split(' '))
        for (c in "white orange magenta light_blue yellow lime pink gray light_gray cyan purple blue brown green red black".split(' '))
            for (kind in listOf("stained_glass","wool","concrete","terracotta")) add("${c}_$kind")
    }
    private val metaPath = Regex("assets/[^/]+/textures/.+\\.png\\.mcmeta")
    private fun obj(b: ByteArray): JsonObject = Json.parseToJsonElement(b.toString(Charsets.UTF_8).removePrefix("\uFEFF")) as? JsonObject
        ?: error("expected a JSON object")
    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.str(key: String, default: String? = null): String? = if (key in this) get(key).str() else default
    private fun JsonObject.zero(key: String) = key !in this || (get(key) as? JsonPrimitive)?.intOrNull == 0
    private fun isFusion(b: ByteArray): Boolean = runCatching { "fusion" in obj(b) }
        .getOrElse { b.toString(Charsets.UTF_8).contains("\"fusion\"") }
    fun texturePaths(pack: JavaPack): Set<String> = pack.files.filter { (p,b) -> metaPath.matches(p) && isFusion(b) }.keys.map { it.removeSuffix(".mcmeta") }.toSet()
    /** Includes unsupported Fusion input so the app can show conversion diagnostics. */
    fun inputCount(pack: JavaPack): Int = maxOf(texturePaths(pack).size, pack.files.count { (p,b) ->
        p.endsWith(".json") && (p.contains("/fusion/") || (p.contains("/models/") && b.toString(Charsets.UTF_8).contains("fusion:model")))
    })
    private fun resource(value: String?, kind: String): String {
        require(value != null && Regex("(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+").matches(value)) { "invalid resource location" }
        val ns = if (':' in value) value.substringBefore(':') else "minecraft"
        val name = value.substringAfter(':')
        require(name.split('/').none { it in setOf("", ".", "..") }) { "invalid resource path" }
        return "assets/$ns/$kind/$name" + if (kind == "textures") ".png" else ".json"
    }
    private data class Model(val textures: Map<String,String>, val faces: Map<String,String>, val predicate: JsonElement?)
    private fun model(pack: JavaPack, path: String, seen: Set<String> = emptySet()): Model {
        require(path !in seen && seen.size < 32) { "cyclic or excessive model inheritance" }
        val bytes = pack.get(path)
        if (bytes == null) {
            val templates = mapOf(
                "cube_all" to faces.associateWith { "#all" },
                "cube" to faces.associateWith { "#$it" },
                "cube_bottom_top" to faces.associateWith { "#" + when(it) { "down" -> "bottom"; "up" -> "top"; else -> "side" } },
                "cube_column" to faces.associateWith { "#" + if (it in setOf("up","down")) "end" else "side" },
            )
            for ((name, f) in templates) if (path == "assets/minecraft/models/block/$name.json") return Model(emptyMap(), f, null)
            if (path == "assets/minecraft/models/block/block.json") return Model(emptyMap(), emptyMap(), null)
            error("missing or unsupported model parent: $path")
        }
        val doc = obj(bytes)
        val extra = (doc.keys - setOf("loader","type","parent","textures","connections","elements","ambientocclusion","display","gui_light")).sorted()
        require(extra.isEmpty()) { "unsupported Fusion model fields: ${extra.joinToString(", ")}" }
        require(doc.str("loader", "fusion:model") == "fusion:model" && doc.str("type", "connecting") in setOf("connecting","fusion:connecting")) { "unsupported model loader/type" }
        val parent = if ("parent" in doc) model(pack, resource(doc.str("parent"), "models"), seen + path) else Model(emptyMap(),emptyMap(),null)
        val tex = parent.textures.toMutableMap()
        val textures = if ("textures" in doc) doc["textures"] as? JsonObject ?: error("model textures must be string references") else JsonObject(emptyMap())
        for ((k,v) in textures) tex[k] = v.str() ?: error("model textures must be string references")
        var fs = parent.faces
        if ("elements" in doc) {
            val elements = doc["elements"] as? JsonArray
            require(elements != null && elements.size == 1) { "only one unrotated full-cube element is supported" }
            val e = elements[0] as? JsonObject ?: error("custom geometry or rotated elements are unsupported")
            fun vector(v: JsonElement?, expected: List<Int>) = v is JsonArray && v.size == 3 && v.map { (it as? JsonPrimitive)?.doubleOrNull } == expected.map { it.toDouble() }
            require(vector(e["from"], listOf(0,0,0)) && vector(e["to"], listOf(16,16,16)) && (e.keys - setOf("from","to","faces","shade")).isEmpty()) { "custom geometry or rotated elements are unsupported" }
            val fd = e["faces"] as? JsonObject
            require(fd != null && fd.keys == faces.toSet()) { "model must have all six full-cube faces" }
            fs = fd.mapValues { (face,value) ->
                val d = value as? JsonObject ?: error("custom UVs, tinting or face connections are unsupported")
                val uv = d["uv"]
                require((d.keys - setOf("texture","cullface","uv","rotation")).isEmpty() && d.zero("rotation") &&
                    (uv == null || (uv is JsonArray && uv.map { (it as? JsonPrimitive)?.doubleOrNull } == listOf(0.0,0.0,16.0,16.0))) && d.str("cullface", face) == face) { "custom UVs, tinting or face connections are unsupported" }
                d.str("texture") ?: error("invalid resource location")
            }
        }
        require(fs.isNotEmpty()) { "model has no supported cube geometry" }
        return Model(tex, fs, doc["connections"] ?: parent.predicate)
    }
    private fun resolve(ref: String, tex: Map<String,String>): String {
        var r = ref
        val seen = hashSetOf<String>()
        while (r.startsWith('#')) {
            require(seen.add(r)) { "cyclic texture references" }
            r = tex[r.drop(1)] ?: error("invalid resource location")
        }
        return resource(r, "textures")
    }
    private fun predicate(p: JsonElement?): Boolean {
        val pred = if (p is JsonArray && p.size == 1) p[0] else p
        return pred is JsonObject && pred.keys == setOf("type") && pred.str("type") in setOf("is_same_block","fusion:is_same_block")
    }
    fun readRules(pack: JavaPack, log: MutableList<String>): List<Rule> {
        if (inputCount(pack) == 0) return emptyList()
        val metas = linkedMapOf<String,Pair<String,JsonObject>>()
        for (p in texturePaths(pack).sorted()) {
            try {
                val doc = obj(pack.files.getValue("$p.mcmeta"))
                val f = doc["fusion"] as? JsonObject ?: error("fusion must be an object")
                val typ = f.str("type")
                require(typ in setOf("connecting","fusion:connecting")) { "unsupported Fusion texture type '$typ'" }
                val layout = f.str("layout", "full")
                require(layout in grids) { "unsupported Fusion layout '$layout'" }
                require("animation" !in doc) { "animated Fusion textures are unsupported" }
                val extra = (f.keys - setOf("type","layout","connections","render_type")).sorted()
                require(extra.isEmpty()) { "unsupported Fusion texture fields: ${extra.joinToString(", ")}" }
                require(f.str("render_type", "opaque") in setOf("opaque","cutout","translucent")) { "unsupported Fusion render_type" }
                metas[p] = layout!! to f
            } catch (e: Exception) { log += "skip $p.mcmeta: ${e.message}" }
        }
        val targets = sortedSetOf<String>()
        for (p in metas.keys) Regex("assets/minecraft/textures/block/([a-z0-9_]+)\\.png").matchEntire(p)?.let { targets += it.groupValues[1] }
        for ((p, _) in pack.files) {
            Regex("assets/minecraft/(?:models/block|blockstates)/([a-z0-9_]+)\\.json").matchEntire(p)?.let { targets += it.groupValues[1] }
            if ("/fusion/" in p && p.endsWith(".json")) log += "skip $p: Fusion modifiers and overrides are unsupported"
        }
        val rules = arrayListOf<Rule>()
        val used = hashSetOf<String>()
        for (block in targets) {
            var path = "assets/minecraft/models/block/$block.json"
            val statePath = "assets/minecraft/blockstates/$block.json"
            try {
                pack.get(statePath)?.let {
                    val state = obj(it)
                    val variants = state["variants"] as? JsonObject
                    require(state.keys == setOf("variants") && variants?.keys == setOf("")) { "conditional or multipart blockstates are unsupported" }
                    val v = variants!![""] as? JsonObject ?: error("weighted or rotated blockstate models are unsupported")
                    require((v.keys - setOf("model","x","y","uvlock")).isEmpty() && v.zero("x") && v.zero("y") &&
                        ("uvlock" !in v || v["uvlock"] == JsonPrimitive(false))) { "weighted or rotated blockstate models are unsupported" }
                    path = resource(v.str("model"), "models")
                }
                if (block !in cubes) {
                    require(pack.get(path)?.toString(Charsets.UTF_8)?.contains("fusion:model") != true) { "only known vanilla full-cube blocks are supported" }
                    continue
                }
                val byTexture = linkedMapOf<String,MutableSet<String>>()
                var pred: JsonElement? = null
                if (pack.get(path) != null) {
                    val m = model(pack,path)
                    pred = m.predicate
                    for ((face,ref) in m.faces) byTexture.getOrPut(resolve(ref,m.textures)) { linkedSetOf() }.add(face)
                } else if (pack.get(statePath) != null) error("blockstate references a missing model")
                else byTexture["assets/minecraft/textures/block/$block.png"] = faces.toMutableSet()
                for ((texture,fs) in byTexture.toSortedMap()) {
                    val (layout, metadata) = metas[texture] ?: continue
                    require(predicate(pred ?: metadata["connections"])) { "only explicit is_same_block connections are supported (no state, direction, tag or compound predicates)" }
                    if ("render_type" in metadata) log += "note $texture.mcmeta: Fusion render_type is not transferred; Bedrock keeps its native block material"
                    rules += Rule("$texture.mcmeta",texture,block,fs,layout)
                    used += texture
                }
            } catch(e: Exception) { log += "skip $path: ${e.message}" }
        }
        for (texture in (metas.keys - used).sorted()) log += "skip $texture.mcmeta: no supported full-cube model/texture binding"
        return rules
    }
    fun tiles(pack: JavaPack, rule: Rule): List<Img> {
        // Decode the complete sheet. Converter.decode crops tall images as animation strips.
        val atlas = Png.decode(pack.get(rule.texture) ?: error("missing Fusion texture image"))
        val (cols,rows) = grids.getValue(rule.layout)
        val height = if (rule.layout == "full" && atlas.width == atlas.height) atlas.height * 6 / 8 else atlas.height
        require(atlas.width % cols == 0 && height % rows == 0 && atlas.width / cols == height / rows) { "Fusion ${rule.layout} requires ${cols}x$rows square tiles" }
        val size = atlas.width / cols
        require(size > 0 && (rule.layout != "pieced" || size % 2 == 0)) { "Fusion pieced tiles require an even positive size" }
        val source = List(cols*rows) { i -> val x=i%cols*size; val y=i/cols*size; atlas.crop(x,y,x+size,y+size) }
        return List(47) { idx ->
            val s = Ctm.CTM47.indexOf(idx)
            val l=s and 1 != 0; val d=s and 4 != 0; val r=s and 16 != 0; val u=s and 64 != 0
            val dl=s and 2 != 0; val dr=s and 8 != 0; val ur=s and 32 != 0; val ul=s and 128 != 0
            fun bit(b: Boolean) = if(b) 1 else 0
            if (rule.layout == "pieced") {
                val h=size/2; val tile=Img(size,size)
                val states=listOf(Triple(l,u,ul),Triple(l,d,dl),Triple(r,d,dr),Triple(r,u,ur))
                for (q in 0..3) {
                    val (a,b,c)=states[q]; val x=if(q>=2) h else 0; val y=if(q==1 || q==2) h else 0
                    val k=if(a && b) { if(c) 1 else 4 } else if(a) 3 else if(b) 2 else 0
                    tile.paste(source[k].crop(x,y,x+h,y+h),x,y)
                }
                tile
            } else {
                val k=when(rule.layout) {
                    "full" -> full[idx]
                    "simple" -> simple[bit(l)+2*bit(d)+4*bit(r)+8*bit(u)]
                    "horizontal" -> if(l && r) 2 else if(l) 3 else if(r) 1 else 0
                    "vertical" -> if(u && d) 2 else if(u) 3 else if(d) 1 else 0
                    else -> when(listOf(l,d,r,u).count { it }) {
                        2 -> if(l && r) 3 else if(u && d) 2 else 0
                        3 -> if(l && r && ((u && ul && ur)||(d && dl && dr))) 3 else if(u && d && ((l && ul && dl)||(r && ur && dr))) 2 else 0
                        4 -> if(listOf(dl,dr,ur,ul).all { it }) 1 else if(listOf(dl,dr,ur,ul).none { it }) 4 else 0
                        else -> 0
                    }
                }
                source[k]
            }
        }
    }
}
