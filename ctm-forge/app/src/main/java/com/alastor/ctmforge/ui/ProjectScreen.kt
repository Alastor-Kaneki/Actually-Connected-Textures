package com.alastor.ctmforge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.alastor.ctmforge.AppViewModel
import com.alastor.ctmforge.core.net.Modrinth
import com.alastor.ctmforge.data.Launchers

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ProjectScreen(vm: AppViewModel, hit: Modrinth.Hit) {
    val st = remember(hit.id) { vm.projectState(hit) }
    val context = LocalContext.current
    val immersive = vm.settings.immersive
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(hit.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { vm.pop() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = { Launchers.openUrl(context, hit.pageUrl)?.let(vm::toast) }) {
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = "Open on Modrinth")
                    }
                },
                windowInsets = topInsets(immersive),
                scrollBehavior = scroll,
            )
        },
        contentWindowInsets = barInsets(immersive),
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "header") {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    RemoteImage(
                        url = hit.iconUrl, contentDescription = null, maxSize = 256,
                        modifier = Modifier.size(80.dp).clipRounded(20),
                        placeholder = { PackIcon(null, 80.dp) },
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(hit.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        Text("by ${hit.author}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Stat(Icons.Rounded.Download, compactCount(st.project?.downloads ?: hit.downloads))
                            Stat(Icons.Rounded.Favorite, compactCount(st.project?.followers ?: hit.follows))
                            Stat(Icons.Rounded.Update, relativeDate(st.project?.updated?.takeIf { it.isNotEmpty() } ?: hit.dateModified))
                        }
                    }
                }
            }
            item(key = "desc") {
                Text(st.project?.description ?: hit.description, style = MaterialTheme.typography.bodyLarge)
            }
            item(key = "tags") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (st.project?.categories ?: hit.categories).distinct().take(8).forEach { c ->
                        SuggestionChip(onClick = {}, label = { Text(c) })
                    }
                }
            }
            val gallery = st.project?.gallery.orEmpty()
            if (gallery.isNotEmpty()) {
                item(key = "gallery") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(gallery, key = { it.url }) { g ->
                            RemoteImage(
                                url = g.url, contentDescription = g.title, maxSize = 720, contentScale = ContentScale.Crop,
                                modifier = Modifier.width(260.dp).height(146.dp).clipRounded(16),
                                placeholder = {
                                    Box(Modifier.width(260.dp).height(146.dp)) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
                                },
                            )
                        }
                    }
                }
            }
            st.project?.license?.let { lic ->
                item(key = "license") { Stat(Icons.Rounded.Gavel, "License: $lic") }
            }
            item(key = "versions-title") { SectionTitle("Versions") }
            val versions = st.versions
            when {
                st.error != null -> item(key = "error") {
                    NoticeCard(
                        icon = Icons.Rounded.CloudOff, title = "Couldn't load this pack", text = st.error ?: "",
                        container = MaterialTheme.colorScheme.errorContainer, content = MaterialTheme.colorScheme.onErrorContainer,
                        actions = { TextButton(onClick = { vm.loadProject(st) }) { Text("Try again") } },
                    )
                }
                versions == null -> item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
                versions.isEmpty() -> item(key = "none") { Text("This pack has no downloadable versions.") }
                else -> {
                    items(versions, key = { it.id }) { v ->
                        VersionCard(v, latest = v === versions.first()) { file -> vm.downloadModrinth(hit, v, file) }
                    }
                }
            }
        }
    }
}

@Composable
private fun VersionCard(v: Modrinth.Version, latest: Boolean, onConvert: (Modrinth.FileRef) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (latest) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(v.name.ifBlank { v.number }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            val bits = listOfNotNull(
                Modrinth.versionRange(v.gameVersions).takeIf { it.isNotEmpty() }?.let { "Java $it" },
                relativeDate(v.datePublished).takeIf { it.isNotEmpty() },
                v.type.takeIf { it != "release" },
            )
            Text(bits.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val files = v.files.sortedByDescending { it.primary }
            files.forEach { f ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(f.fileName, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(fileSize(f.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (latest && f === files.first()) {
                        Button(onClick = { onConvert(f) }, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                            Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                            Text("Convert", modifier = Modifier.padding(start = ButtonDefaults.IconSpacing))
                        }
                    } else {
                        FilledTonalButton(onClick = { onConvert(f) }) { Text("Convert") }
                    }
                }
            }
        }
    }
}
