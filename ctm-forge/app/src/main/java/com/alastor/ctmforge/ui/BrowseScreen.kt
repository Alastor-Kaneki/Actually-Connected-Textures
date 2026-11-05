package com.alastor.ctmforge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.alastor.ctmforge.AppViewModel
import com.alastor.ctmforge.AppViewModel.Route
import com.alastor.ctmforge.AppViewModel.Source
import com.alastor.ctmforge.core.net.Modrinth

private val QUICK_SEARCHES = listOf("connected glass", "connected textures", "ctm", "continuity", "clear glass", "bookshelf")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(vm: AppViewModel, padding: PaddingValues, onPickFile: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(padding)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                Source.entries.forEachIndexed { i, s ->
                    SegmentedButton(
                        selected = vm.source == s,
                        onClick = { vm.source = s },
                        shape = SegmentedButtonDefaults.itemShape(i, Source.entries.size),
                        icon = {},
                        label = { Text(s.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
            IconButton(onClick = onPickFile) { Icon(Icons.Rounded.FolderOpen, contentDescription = "Open a pack file") }
        }
        when (vm.source) {
            Source.MODRINTH -> ModrinthPane(vm, Modifier.weight(1f))
            Source.CURSEFORGE -> SitePane(vm, WebSite.CURSEFORGE, Modifier.weight(1f))
            Source.PLANET -> SitePane(vm, WebSite.PLANET, Modifier.weight(1f))
        }
    }
}

@Composable
private fun SitePane(vm: AppViewModel, site: WebSite, modifier: Modifier) {
    val holder = remember(site) { vm.webHolders.getOrPut(site) { WebHolder(site) } }
    var showTip by rememberSaveable(site) { mutableStateOf(true) }
    Column(modifier) {
        if (showTip) {
            NoticeCard(
                icon = Icons.Rounded.Lightbulb,
                title = "Tap a pack's download button",
                text = "Look for Java packs made for OptiFine or Continuity. CTM Forge catches the download and converts it.",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                actions = { TextButton(onClick = { showTip = false }) { Text("Got it") } },
            )
        }
        WebPane(
            holder = holder,
            onDownload = { url, ua -> vm.onWebDownload(site, url, ua, holder.url) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ModrinthPane(vm: AppViewModel, modifier: Modifier) {
    val listState = rememberLazyListState()
    val focus = LocalFocusManager.current
    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 4
        }
    }
    LaunchedEffect(nearEnd, vm.hits.size) { if (nearEnd) vm.loadMore() }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "search") {
            OutlinedTextField(
                value = vm.query,
                onValueChange = { vm.query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search Modrinth resource packs") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    if (vm.query.isNotEmpty()) {
                        IconButton(onClick = { vm.query = "" }) { Icon(Icons.Rounded.Close, contentDescription = "Clear") }
                    }
                },
                singleLine = true,
                shape = CircleShape,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.search(); focus.clearFocus() }),
            )
        }
        item(key = "chips") {
            var sortOpen by remember { mutableStateOf(false) }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Box {
                        AssistChip(
                            onClick = { sortOpen = true },
                            label = { Text(vm.sort.label) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Sort, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        )
                        DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                            Modrinth.Sort.entries.forEach { s ->
                                DropdownMenuItem(text = { Text(s.label) }, onClick = {
                                    sortOpen = false
                                    if (vm.sort != s) { vm.sort = s; vm.search() }
                                })
                            }
                        }
                    }
                }
                items(QUICK_SEARCHES) { q ->
                    SuggestionChip(onClick = { vm.query = q; vm.search(); focus.clearFocus() }, label = { Text(q) })
                }
            }
        }
        val error = vm.searchError
        if (error != null && vm.hits.isEmpty()) {
            item(key = "error") {
                NoticeCard(
                    icon = Icons.Rounded.CloudOff,
                    title = "Couldn't search Modrinth",
                    text = error,
                    container = MaterialTheme.colorScheme.errorContainer,
                    content = MaterialTheme.colorScheme.onErrorContainer,
                    actions = { TextButton(onClick = { vm.search() }) { Text("Try again") } },
                )
            }
        }
        items(vm.hits, key = { it.id }) { hit -> HitCard(hit) { vm.push(Route.Project(hit)) } }
        if (vm.searching) {
            item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
        } else if (error != null && vm.hits.isNotEmpty()) {
            item(key = "more-error") {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TextButton(onClick = { vm.loadMore() }) { Text("Couldn't load more. Try again") }
                }
            }
        } else if (vm.totalHits == 0) {
            item(key = "empty") {
                Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.SearchOff, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.size(8.dp))
                    Text("No packs found", style = MaterialTheme.typography.titleMedium)
                    Text("Try \"connected\" or \"ctm\".", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun HitCard(hit: Modrinth.Hit, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            RemoteImage(
                url = hit.iconUrl,
                contentDescription = null,
                maxSize = 160,
                modifier = Modifier.size(60.dp).clipRounded(14),
                placeholder = { PackIcon(null, 60.dp) },
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(hit.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("by ${hit.author}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                Text(hit.description, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Stat(Icons.Rounded.Download, compactCount(hit.downloads))
                    Spacer(Modifier.width(12.dp))
                    Stat(Icons.Rounded.Update, relativeDate(hit.dateModified))
                    Spacer(Modifier.width(12.dp))
                    Text(
                        Modrinth.versionRange(hit.gameVersions), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
fun Stat(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

fun Modifier.clipRounded(dp: Int) = this.then(Modifier.clip(RoundedCornerShape(dp.dp)))
