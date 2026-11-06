package com.alastor.ctmforge.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.alastor.ctmforge.AppViewModel
import com.alastor.ctmforge.AppViewModel.Route
import com.alastor.ctmforge.data.Launchers
import com.alastor.ctmforge.data.LibraryEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun rememberEntryIcon(entry: LibraryEntry): ImageBitmap? {
    val icon by produceState<ImageBitmap?>(null, entry.id, entry.createdAt) {
        value = withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(entry.icon.path)?.asImageBitmap() }.getOrNull()
        }
    }
    return icon
}

@Composable
fun LibraryScreen(vm: AppViewModel, padding: PaddingValues, onPickFile: () -> Unit) {
    val library = vm.library
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val entries = library.entries
    val leviInstalled = Launchers.isInstalled(context, Launchers.LEVILAUNCHER)
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 96.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!vm.settings.modCardDismissed) {
            item(key = "mod") {
                NoticeCard(
                    icon = Icons.Rounded.Extension,
                    title = "Connected Textures mod",
                    text = "Converted packs connect only while this mod runs in LeviLauncher (Minecraft 1.26.60). " +
                        if (leviInstalled) "Install it once." else "LeviLauncher isn't installed on this phone.",
                    container = MaterialTheme.colorScheme.tertiaryContainer,
                    content = MaterialTheme.colorScheme.onTertiaryContainer,
                    actions = {
                        TextButton(onClick = { vm.settings.updateModCardDismissed(true) }) { Text("Hide") }
                        FilledTonalButton(onClick = {
                            scope.launch {
                                val f = withContext(Dispatchers.IO) { Launchers.modFile(context) }
                                if (leviInstalled) Launchers.open(context, f, Launchers.LEVILAUNCHER)?.let(vm::toast)
                                else Launchers.share(context, f)
                            }
                        }) { Text(if (leviInstalled) "Install mod" else "Share mod file") }
                    },
                )
            }
        }
        when {
            !library.loaded -> item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            entries.isEmpty() -> item(key = "empty") {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Rounded.Inventory2, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("No converted packs yet", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Find a connected-textures pack in Browse, or open a Java pack .zip you already have.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { vm.tab = AppViewModel.Tab.BROWSE }) {
                            Icon(Icons.Rounded.Explore, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("Browse", modifier = Modifier.padding(start = 8.dp))
                        }
                        Button(onClick = onPickFile) {
                            Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("Open a file", modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
            else -> items(entries, key = { it.id }) { e -> EntryCard(vm, e) }
        }
    }
}

@Composable
private fun EntryCard(vm: AppViewModel, e: LibraryEntry) {
    val context = LocalContext.current
    Card(
        onClick = { vm.push(Route.Pack(e.id)) },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            PackIcon(rememberEntryIcon(e), 56.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(e.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${resultContents(e.blocks.size, e.animated.size)} · ${e.layoutLabel}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    listOfNotNull(e.source, relativeDate(e.createdAt)).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalIconButton(onClick = {
                Launchers.open(context, e.mcpack, Launchers.importTarget(context, vm.settings.openWith))?.let(vm::toast)
            }) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = Launchers.importLabel(context, vm.settings.openWith))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackScreen(vm: AppViewModel, id: String) {
    val entry = vm.library.find(id)
    val context = LocalContext.current
    val immersive = vm.settings.immersive
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    if (entry == null) {
        LaunchedEffect(id) { vm.routes.remove(Route.Pack(id)) }
        return
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = { vm.pop() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
                actions = {
                    entry.sourceUrl?.takeIf { it.startsWith("http") }?.let { url ->
                        IconButton(onClick = { Launchers.openUrl(context, url)?.let(vm::toast) }) {
                            Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = "Open the pack's page")
                        }
                    }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Rounded.Delete, contentDescription = "Delete") }
                },
                windowInsets = topInsets(immersive),
            )
        },
        contentWindowInsets = barInsets(immersive),
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                PackIcon(rememberEntryIcon(entry), 64.dp)
                Column {
                    Text(resultHeadline(entry.blocks.size, entry.animated.size), style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "${entry.layoutLabel} · v${entry.versionLabel} · ${fileSize(entry.mcpack.length())}",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        listOfNotNull(entry.source?.let { "From $it" }, "converted ${relativeDate(entry.createdAt)}").joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            PackResultContent(
                vm = vm,
                sheets = entry.sheets,
                blocks = entry.blocks,
                animated = entry.animated,
                log = entry.log,
                mcpack = entry.mcpack,
                readFile = { path -> entry.readPackFile(path) },
            )
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
            title = { Text("Delete ${entry.name}?") },
            text = { Text("This removes the converted pack from CTM Forge. Copies already imported into Minecraft stay there.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deleteEntry(entry.id) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
