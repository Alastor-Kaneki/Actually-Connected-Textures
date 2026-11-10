package com.alastor.ctmforge.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.alastor.ctmforge.CreatorController
import com.alastor.ctmforge.core.*
import kotlinx.coroutines.delay

@Composable
fun CreatorScreen(c: CreatorController, padding: PaddingValues) {
    val p = c.project
    val listState = rememberLazyListState()
    LaunchedEffect(c.error, p?.id) { if (c.error != null || p != null) listState.scrollToItem(0) }
    var delete by remember { mutableStateOf<CreatorProject?>(null) }
    // Persist the destination across activity recreation while Android's picker is open.
    var pickRule by rememberSaveable { mutableStateOf("") }
    var pickProject by rememberSaveable { mutableStateOf("") }
    var pickTile by rememberSaveable { mutableIntStateOf(0) }
    var pickMode by rememberSaveable { mutableStateOf("single") }
    val single = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && c.project?.id == pickProject) c.importImages(listOf(uri), pickRule, pickTile, "single")
    }
    val multiple = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (c.project?.id == pickProject) c.importImages(uris, pickRule, pickTile, pickMode)
    }
    fun pick(rule: CreatorRule, tile: Int, mode: String) {
        pickProject = p?.id.orEmpty(); pickRule = rule.id; pickTile = tile; pickMode = mode
        if (mode == "single") single.launch(arrayOf("image/png")) else multiple.launch(arrayOf("image/png"))
    }
    if (delete != null) AlertDialog(
        onDismissRequest = { delete = null },
        title = { Text("Delete draft?") },
        text = { Text("Delete ${delete!!.name} and its editable textures? Exported packs stay in your Library.") },
        confirmButton = { TextButton(onClick = { c.delete(delete!!); delete = null }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { delete = null }) { Text("Cancel") } },
    )
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (c.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        c.error?.let { message -> item {
            NoticeCard(Icons.Rounded.Info, "Couldn't finish", message,
                container = MaterialTheme.colorScheme.errorContainer, content = MaterialTheme.colorScheme.onErrorContainer)
        } }
        if (p == null) {
            item {
                Text("Make it your own", style = MaterialTheme.typography.headlineMedium)
                Text("Build packs for Connected Textures with your own PNGs. Start with a single texture, then add connections and animation.")
            }
            item { Button(onClick = c::create, enabled = !c.busy) { Icon(Icons.Rounded.Add, null); Text("New pack") } }
            items(c.projects, key = { it.id }) { draft ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(draft.name.ifBlank { "Untitled pack" }, style = MaterialTheme.typography.titleMedium)
                        Text("${draft.rules.size} block rules · editable draft")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { c.open(draft) }, enabled = !c.busy) { Text("Edit") }
                            TextButton(onClick = { delete = draft }, enabled = !c.busy) { Text("Delete") }
                        }
                    }
                }
            }
        } else {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = c::close, enabled = !c.busy) { Text("All drafts") }
                    Text(if (c.error?.startsWith("Couldn't save draft") == true) "Unsaved changes" else if (c.pendingSaves > 0) "Saving…" else "Draft saved", style = MaterialTheme.typography.labelMedium)
                }
            }
            item {
                OutlinedTextField(p.name, { c.edit(p.copy(name = it.take(80))) }, label = { Text("Pack name") }, singleLine = true,
                    enabled = !c.busy, modifier = Modifier.fillMaxWidth())
            }
            item {
                OutlinedTextField(p.description, { c.edit(p.copy(description = it.take(1000))) }, label = { Text("Description (optional)") },
                    enabled = !c.busy, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4)
            }
            item {
                NoticeCard(Icons.Rounded.Info, "Your textures, your pack",
                    "PNG width: 8–256 pixels, power of two. Animation: stack square frames vertically, or select separate frames. Multi-file imports use filename order (0, 1, 2, …). Packs require the Connected Textures LeviLauncher mod.")
            }
            items(p.rules, key = { it.id }) { rule ->
                RuleEditor(c, rule, p.rules.indexOf(rule) + 1, { tile, mode -> pick(rule, tile, mode) }, {
                    c.edit(p.copy(rules = p.rules.filterNot { it.id == rule.id }))
                })
            }
            item {
                OutlinedButton(onClick = { c.edit(p.copy(rules = p.rules + CreatorRule(block = ""))) }, enabled = !c.busy && p.rules.size < 32) {
                    Icon(Icons.Rounded.Add, null); Text("Add block rule")
                }
            }
            item {
                Button(onClick = c::build, enabled = !c.busy && c.pendingSaves == 0 && p.rules.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                    Text(if (c.busy) "Working…" else "Build .mcpack")
                }
                Text("After building, preview, save, share or install from the pack page. Rebuilding updates the same pack with a higher version.",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun RuleEditor(c: CreatorController, rule: CreatorRule, number: Int, pick: (Int, String) -> Unit, remove: () -> Unit) {
    var expanded by rememberSaveable(rule.id) { mutableStateOf(true) }
    var removeDialog by remember { mutableStateOf(false) }
    if (removeDialog) AlertDialog(onDismissRequest = { removeDialog = false }, title = { Text("Remove block rule?") },
        text = { Text("Remove this rule and its tile assignments from the draft?") },
        confirmButton = { TextButton(onClick = { removeDialog = false; remove() }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { removeDialog = false }) { Text("Cancel") } })
    fun edit(change: (CreatorRule) -> CreatorRule) = c.editRule(rule.id, change)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { expanded = !expanded }) { Text("$number · ${rule.block.ifBlank { "Choose block" }} ${if (expanded) "▾" else "▸"}") }
                IconButton(onClick = { removeDialog = true }, enabled = !c.busy) { Icon(Icons.Rounded.Delete, "Remove rule $number") }
            }
            if (expanded) {
                OutlinedTextField(rule.block, { value -> edit { it.copy(block = value.lowercase().take(100)) } }, label = { Text("Vanilla block ID") },
                    placeholder = { Text("glass, obsidian, stone…") }, singleLine = true, enabled = !c.busy, modifier = Modifier.fillMaxWidth())
                val query = rule.block.trim().removePrefix("minecraft:")
                val suggestions = remember(query) { if (query in PackCreator.blocks) emptyList() else PackCreator.blocks.filter { it.contains(query) }.take(5) }
                suggestions.forEach { block ->
                    TextButton(onClick = { edit { it.copy(block = block) } }, enabled = !c.busy, contentPadding = PaddingValues(0.dp)) { Text(block) }
                }
                CreatorChoice("Connection", rule.method.label, CreatorMethod.entries.map { it.label }, !c.busy) { label ->
                    edit { it.withMethod(CreatorMethod.entries.first { m -> m.label == label }) }
                }
                CreatorChoice("Faces", rule.faces, PackCreator.faceOptions, !c.busy) { value -> edit { it.copy(faces = value) } }
                var conditionsExpanded by remember(rule.id) { mutableStateOf(rule.biomes.isNotBlank() || rule.connectBlocks.isNotBlank() || rule.neighborBlocks.isNotBlank()) }
                TextButton(onClick = { conditionsExpanded = !conditionsExpanded }) { Text("Biome & neighbor rules ${if (conditionsExpanded) "▾" else "▸"}") }
                if (conditionsExpanded) {
                    OutlinedTextField(rule.biomes, { value -> edit { it.copy(biomes = value.take(400)) } }, label = { Text("Biomes (optional)") },
                        placeholder = { Text("desert plains · !swamp to exclude") }, enabled = !c.busy, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(rule.connectBlocks, { value -> edit { it.copy(connectBlocks = value.take(400)) } }, label = { Text("Connect to blocks (optional)") },
                        placeholder = { Text("glass tinted_glass") }, enabled = !c.busy, modifier = Modifier.fillMaxWidth())
                    Text("Blank connects to the same block. A list replaces that default, so include the target block to keep self-connections.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(rule.neighborBlocks, { value -> edit { it.copy(neighborBlocks = value.take(400)) } }, label = { Text("Only when touching (optional)") },
                        placeholder = { Text("sand gravel") }, enabled = !c.busy, modifier = Modifier.fillMaxWidth())
                    Text("Touching checks the six blocks directly beside, above and below. Biome and touching conditions must both match. The first matching conditional rule wins; an unfiltered rule is the fallback. Requires Connected Textures 1.3 or newer.", style = MaterialTheme.typography.bodySmall)
                }
                if (rule.method == CreatorMethod.RANDOM) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { edit { it.copy(tiles = it.tiles.dropLast(1)) } }, enabled = !c.busy && rule.tiles.size > 1) { Text("−") }
                    Text("${rule.tiles.size} variants", modifier = Modifier.padding(top = 12.dp))
                    TextButton(onClick = { edit { it.copy(tiles = it.tiles + listOf(null)) } }, enabled = !c.busy && rule.tiles.size < 16) { Text("+") }
                }
                if (rule.method in listOf(CreatorMethod.COMPACT, CreatorMethod.CTM)) {
                    CreatorToggle("Keep inner seams", rule.innerSeams, !c.busy) { value -> edit { it.copy(innerSeams = value) } }
                }
                Text(when (rule.method) {
                    CreatorMethod.COMPACT -> "OptiFine compact order: 0 isolated, 1 fully connected, 2 vertical, 3 horizontal, 4 inner corners."
                    CreatorMethod.CTM -> "Use OptiFine's standard tile order, 0–46."
                    CreatorMethod.HORIZONTAL -> "Tile order: 0 right connection, 1 both, 2 left connection, 3 isolated."
                    CreatorMethod.VERTICAL -> "Tile order: 0 above connection, 1 both, 2 below connection, 3 isolated."
                    CreatorMethod.RANDOM -> "Each tile is an equally weighted random variant."
                    CreatorMethod.FIXED -> "One custom texture on the selected block faces."
                }, style = MaterialTheme.typography.bodySmall)
                if (rule.tiles.size > 1) OutlinedButton(onClick = { pick(0, "tiles") }, enabled = !c.busy) { Text("Import ${rule.tiles.size} tile PNGs") }
                CreatorToggle("Animated textures", rule.animated, !c.busy) { value -> edit { it.copy(animated = value) } }
                if (rule.animated) {
                    OutlinedTextField(rule.ticks, { value -> edit { it.copy(ticks = value.take(3)) } }, label = { Text("Ticks per frame (20 = 1 second)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, enabled = !c.busy, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(rule.order, { value -> edit { it.copy(order = value.take(1024)) } }, label = { Text("Frame order (optional)") },
                        placeholder = { Text("0,1,2,1 — blank plays all frames") }, enabled = !c.busy, modifier = Modifier.fillMaxWidth())
                    CreatorToggle("Blend between frames", rule.interpolate, !c.busy) { value -> edit { it.copy(interpolate = value) } }
                    Text("Animated tiles in this rule share timing and frame order. They need the same frame count; other tiles can stay static.", style = MaterialTheme.typography.bodySmall)
                }
                rule.tiles.forEachIndexed { index, tile ->
                    key(rule.id, index) {
                        HorizontalDivider()
                        Text("Tile $index", style = MaterialTheme.typography.titleSmall)
                        if (tile != null) {
                            TexturePreview(c, tile, rule)
                            Text("${tile.size} × ${tile.size} · ${tile.frames} frame${if (tile.frames == 1) "" else "s"}" +
                                if (!rule.animated && tile.frames > 1) " · first frame only" else "", style = MaterialTheme.typography.bodySmall)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { pick(index, "single") }, enabled = !c.busy) { Text(if (tile == null) "Import PNG" else "Replace PNG") }
                            if (tile != null) TextButton(onClick = { edit { r -> r.copy(tiles = r.tiles.mapIndexed { i, t -> if (i == index) null else t }) } }, enabled = !c.busy) { Text("Clear") }
                        }
                        if (rule.animated) TextButton(onClick = { pick(index, "frames") }, enabled = !c.busy) { Text("Assemble separate PNG frames") }
                    }
                }
            }
        }
    }
}

@Composable
private fun CreatorChoice(label: String, value: String, choices: List<String>, enabled: Boolean, select: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("$label: $value ▾") }
        DropdownMenu(expanded, { expanded = false }) {
            choices.forEach { choice -> DropdownMenuItem(text = { Text(choice) }, onClick = { expanded = false; select(choice) }) }
        }
    }
}

@Composable
private fun CreatorToggle(label: String, value: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(value, change, enabled = enabled)
    }
}

@Composable
private fun TexturePreview(c: CreatorController, tile: CreatorTexture, rule: CreatorRule) {
    val projectId = c.project?.id ?: return
    val image = rememberPackImage(tile.asset) { c.store.asset(projectId, tile.asset).readBytes() }
    val order = remember(tile.frames, rule.order) { runCatching { PackCreator.sequence(rule, tile.frames) }.getOrElse { listOf(0) } }
    var frame by remember(tile.asset) { mutableIntStateOf(0) }
    var playing by rememberSaveable(tile.asset) { mutableStateOf(true) }
    LaunchedEffect(tile.asset, rule.animated, rule.ticks, order, playing) {
        frame = 0
        if (rule.animated && tile.frames > 1 && playing) {
            var step = 0
            while (true) {
                frame = order[step++ % order.size]
                delay((rule.ticks.toLongOrNull() ?: 2L).coerceIn(1, 200) * 50)
            }
        }
    }
    if (image != null) Canvas(Modifier.size(112.dp)) {
        val cell = size.width / 8
        for (y in 0..7) for (x in 0..7) drawRect(if ((x + y) % 2 == 0) Color.LightGray else Color.Gray,
            topLeft = Offset(x * cell, y * cell), size = Size(cell, cell))
        drawImage(image, srcOffset = IntOffset(0, frame * tile.size), srcSize = IntSize(tile.size, tile.size),
            dstSize = IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.None)
    }
    if (rule.animated && tile.frames > 1) TextButton(onClick = { playing = !playing }) { Text(if (playing) "Pause preview · frame $frame" else "Play preview") }
}
