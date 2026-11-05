package com.alastor.ctmforge.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.alastor.ctmforge.AppViewModel
import com.alastor.ctmforge.AppViewModel.Phase
import com.alastor.ctmforge.core.BlockSheet
import com.alastor.ctmforge.data.Launchers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConvertScreen(vm: AppViewModel, onPickFile: () -> Unit) {
    val s = vm.session
    val immersive = vm.settings.immersive
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (s?.phase) {
                            is Phase.Done -> "Converted"
                            is Phase.Failed -> "Couldn't convert"
                            else -> "Convert"
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { vm.closeSession() }) { Icon(Icons.Rounded.Close, contentDescription = "Close") }
                },
                windowInsets = topInsets(immersive),
            )
        },
        contentWindowInsets = barInsets(immersive),
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (s == null) {
                Text("Nothing to convert.")
                return@Column
            }
            when (val p = s.phase) {
                is Phase.Downloading -> ProgressBlock(
                    "Downloading", s.fileName,
                    fraction = if (p.total > 0) p.done.toFloat() / p.total else null,
                    detail = if (p.total > 0) "${fileSize(p.done)} of ${fileSize(p.total)}" else fileSize(p.done),
                )
                Phase.Reading -> ProgressBlock("Reading the pack", s.fileName, null)
                Phase.Ready -> ReadyContent(vm, s)
                is Phase.Converting -> ProgressBlock(
                    "Converting", s.name,
                    fraction = if (p.total > 0) p.done.toFloat() / p.total else null,
                    detail = "Rule ${minOf(p.done + 1, p.total)} of ${p.total}",
                )
                is Phase.Done -> {
                    val entry = vm.library.find(p.entryId)
                    SuccessHeader(p.result.blocks.size, p.result.name, if (p.result.direct) "Direct layout" else "Normal layout", p.result.version.joinToString("."))
                    if (entry != null) {
                        PackResultContent(
                            vm = vm,
                            sheets = p.result.sheets,
                            blocks = p.result.blocks,
                            log = p.result.log,
                            mcpack = entry.mcpack,
                            readFile = { path -> p.result.files[path] },
                        )
                    }
                }
                is Phase.Failed -> FailedContent(vm, p, onPickFile)
            }
        }
    }
}

@Composable
private fun ReadyContent(vm: AppViewModel, s: AppViewModel.Session) {
    val c = s.candidates.getOrNull(s.selected) ?: return
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            PackIcon(c.icon, 64.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(s.fileName, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (c.description.isNotBlank()) {
                    Text(c.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    "${c.rules} connected-texture rule${if (c.rules == 1) "" else "s"}" + (s.source?.let { " · from $it" } ?: ""),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }

    if (s.candidates.size > 1) {
        SectionTitle("This download has ${s.candidates.size} packs")
        Column {
            s.candidates.forEachIndexed { i, cand ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(selected = s.selected == i, onClick = { vm.selectCandidate(i) }, role = Role.RadioButton)
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = s.selected == i, onClick = null)
                    Spacer(Modifier.size(8.dp))
                    PackIcon(cand.icon, 36.dp)
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(cand.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${cand.rules} rules", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    OutlinedTextField(
        value = s.name,
        onValueChange = { s.name = it; s.nameEdited = true },
        label = { Text("Pack name") },
        supportingText = { Text("Shown in Minecraft as \"${s.name.trim().ifEmpty { "…" }} (CTM${if (s.direct) " direct" else ""})\"") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth(),
    )

    SectionTitle("Layout")
    LayoutOption(
        selected = !s.direct, title = "Normal", badge = "Recommended",
        text = "Connects with the mod running, and looks like the pack's plain glass without it.",
        onClick = { s.direct = false },
    )
    LayoutOption(
        selected = s.direct, title = "Direct", badge = null,
        text = "Fallback for when a normal pack doesn't connect. Looks scrambled if the mod isn't running.",
        onClick = { s.direct = true },
    )

    Button(
        onClick = { vm.convert() },
        enabled = s.name.isNotBlank(),
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(18.dp),
        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
    ) {
        Icon(Icons.Rounded.AutoAwesome, contentDescription = null)
        Text("Convert", modifier = Modifier.padding(start = ButtonDefaults.IconSpacing), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun LayoutOption(selected: Boolean, title: String, badge: String?, text: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.secondary) else null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = null)
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    if (badge != null) {
                        Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primary) {
                            Text(
                                badge, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                Text(text, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun SuccessHeader(blocks: Int, name: String, layout: String, version: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
        Column {
            Text("$blocks block${if (blocks == 1) "" else "s"} connected", style = MaterialTheme.typography.headlineSmall)
            Text("$name · $layout · v$version", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FailedContent(vm: AppViewModel, p: Phase.Failed, onPickFile: () -> Unit) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(40.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(p.title, style = MaterialTheme.typography.headlineSmall)
            Text(p.message, style = MaterialTheme.typography.bodyLarge)
        }
    }
    if (p.log.isNotEmpty()) NotesCard(p.log, startExpanded = true)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = { vm.closeSession() }) { Text("Close") }
        Button(onClick = onPickFile, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
            Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Text("Open another file", modifier = Modifier.padding(start = ButtonDefaults.IconSpacing))
        }
    }
}

/** Preview, actions and details of a converted pack (right after converting, and in the library). */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PackResultContent(
    vm: AppViewModel,
    sheets: List<BlockSheet>,
    blocks: Map<String, String>,
    log: List<String>,
    mcpack: File,
    readFile: (String) -> ByteArray?,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var index by rememberSaveable(mcpack.path) { mutableIntStateOf(0) }
    var connected by rememberSaveable { mutableStateOf(true) }
    val sheet = sheets.getOrNull(index.coerceIn(0, (sheets.size - 1).coerceAtLeast(0)))

    if (sheet != null) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val image = rememberPackImage(mcpack.path + sheet.sheetPath) { readFile(sheet.sheetPath + ".png") }
                CtmWallPreview(image, sheet.spec, connected)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = connected, onClick = { connected = true },
                        shape = SegmentedButtonDefaults.itemShape(0, 2), label = { Text("With the mod") },
                    )
                    SegmentedButton(
                        selected = !connected, onClick = { connected = false },
                        shape = SegmentedButtonDefaults.itemShape(1, 2), label = { Text("Without") },
                    )
                }
                if (sheets.size > 1) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(sheets, key = { _, it -> it.sheetPath }) { i, sh ->
                            FilterChip(selected = i == index, onClick = { index = i }, label = { Text(prettyBlock(sh.block)) })
                        }
                    }
                }
                Text(
                    "${prettyBlock(sheet.block)} · ${prettySpec(sheet.spec)}" + if (sheet.spec.endsWith("-s")) " · inner seams" else "",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    val target = Launchers.importTarget(context, vm.settings.openWith)
    Button(
        onClick = { Launchers.open(context, mcpack, target)?.let(vm::toast) },
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(18.dp),
        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
    ) {
        Icon(Icons.Rounded.PlayArrow, contentDescription = null)
        Text(Launchers.importLabel(context, vm.settings.openWith), modifier = Modifier.padding(start = ButtonDefaults.IconSpacing), style = MaterialTheme.typography.titleMedium)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        SaveFileButton(mcpack, "Save .mcpack", Modifier.weight(1f)) { vm.toast(it) }
        OutlinedButton(
            onClick = { Launchers.share(context, mcpack) },
            modifier = Modifier.weight(1f),
            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
        ) {
            Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Text("Share", modifier = Modifier.padding(start = ButtonDefaults.IconSpacing))
        }
    }

    if (target != Launchers.MINECRAFT) {
        NoticeCard(
            icon = Icons.Rounded.Extension,
            title = "Needs the Connected Textures mod",
            text = "Glass only connects while the mod runs in LeviLauncher (Minecraft 1.26.60). Install it once, then turn the pack on in Global Resources.",
            actions = {
                TextButton(onClick = {
                    scope.launch {
                        val f = withContext(Dispatchers.IO) { Launchers.modFile(context) }
                        Launchers.open(context, f, Launchers.LEVILAUNCHER)?.let(vm::toast)
                    }
                }) { Text("Install mod") }
            },
        )
    }

    ExpandableCard(icon = Icons.Rounded.GridView, title = "Connected blocks (${blocks.size})") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            blocks.keys.forEach { b -> SuggestionChip(onClick = {}, label = { Text(prettyBlock(b)) }) }
        }
    }
    if (log.isNotEmpty()) NotesCard(log, startExpanded = false)
}

@Composable
fun NotesCard(log: List<String>, startExpanded: Boolean) {
    val skipped = log.count { it.startsWith("skip ") }
    ExpandableCard(
        icon = if (skipped > 0) Icons.Rounded.Warning else Icons.AutoMirrored.Rounded.Notes,
        title = "Notes (${log.size})" + if (skipped > 0) " · $skipped rule${if (skipped == 1) "" else "s"} skipped" else "",
        startExpanded = startExpanded,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            log.forEach { line ->
                Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
fun ExpandableCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    startExpanded: Boolean = false,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(startExpanded) }
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = if (expanded) "Collapse" else "Expand")
        }
        if (expanded) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) { content() }
        }
    }
}
