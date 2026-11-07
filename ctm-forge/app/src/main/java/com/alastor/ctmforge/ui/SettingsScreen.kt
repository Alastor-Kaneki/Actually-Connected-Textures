package com.alastor.ctmforge.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.alastor.ctmforge.AppViewModel
import com.alastor.ctmforge.BuildConfig
import com.alastor.ctmforge.data.Launchers
import com.alastor.ctmforge.data.OpenWith
import com.alastor.ctmforge.data.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(vm: AppViewModel, padding: PaddingValues) {
    val s = vm.settings
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cache by remember { mutableLongStateOf(-1L) }
    LaunchedEffect(Unit) { cache = withContext(Dispatchers.IO) { vm.cacheBytes() } }
    val mod = vm.updater.mod
    val leviInstalled = remember { Launchers.isInstalled(context, Launchers.LEVILAUNCHER) }
    // Updates go first while something is waiting, else above About
    val waiting = updatesWaiting(vm)
    val updates: @Composable () -> Unit = {
        SectionTitle("Updates")
        Group { UpdatesRows(vm) { Divider() } }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (waiting) updates()

        SectionTitle("Appearance")
        Group {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Contrast, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Theme", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 16.dp))
                }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ThemeMode.entries.forEachIndexed { i, m ->
                        SegmentedButton(
                            selected = s.themeMode == m, onClick = { s.updateThemeMode(m) },
                            shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size), label = { Text(m.label) },
                        )
                    }
                }
            }
            Divider()
            SwitchRow(
                Icons.Rounded.Palette, "Material You colors",
                if (dynamicColorAvailable) "Colors from your wallpaper" else "Needs Android 12 or newer",
                checked = s.dynamicColor && dynamicColorAvailable, enabled = dynamicColorAvailable,
            ) { s.updateDynamicColor(it) }
            Divider()
            SwitchRow(Icons.Rounded.Contrast, "Pure black", "Black backgrounds in dark theme (nice on OLED)", s.pureBlack) { s.updatePureBlack(it) }
            Divider()
            SwitchRow(
                Icons.Rounded.Fullscreen, "Immersive mode",
                "Hide the status and navigation bars. Swipe from an edge to peek at them.", s.immersive,
            ) { s.updateImmersive(it) }
        }

        SectionTitle("Converting")
        Group {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Default layout", style = MaterialTheme.typography.bodyLarge)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(selected = !s.directLayout, onClick = { s.updateDirectLayout(false) }, shape = SegmentedButtonDefaults.itemShape(0, 2), label = { Text("Normal") })
                    SegmentedButton(selected = s.directLayout, onClick = { s.updateDirectLayout(true) }, shape = SegmentedButtonDefaults.itemShape(1, 2), label = { Text("Direct") })
                }
                Text(
                    "Normal packs fall back to plain textures without the mod. Use Direct only if normal packs don't connect for you.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Divider()
            Column(Modifier.padding(vertical = 8.dp)) {
                Text("Import packs into", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                OpenWith.entries.forEach { o ->
                    val detail = when (o) {
                        OpenWith.AUTO -> "LeviLauncher if it's installed, else Minecraft"
                        OpenWith.LEVILAUNCHER -> if (leviInstalled) null else "Not installed"
                        OpenWith.MINECRAFT -> if (Launchers.isInstalled(context, Launchers.MINECRAFT)) null else "Not installed"
                        OpenWith.ASK -> null
                    }
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = s.openWith == o, onClick = { s.updateOpenWith(o) }, role = Role.RadioButton)
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = s.openWith == o, onClick = null)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(o.label, style = MaterialTheme.typography.bodyMedium)
                            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        SectionTitle("Connected Textures mod")
        Group {
            ActionRow(
                Icons.Rounded.Extension, if (vm.updater.modPending) "Install the new version in LeviLauncher" else "Install in LeviLauncher",
                "Version ${mod?.version ?: "?"}, for Minecraft ${mod?.minecraft ?: "1.26.60"}" +
                    (if (mod != null && vm.updater.bundledMod?.version != mod.version) " · downloaded update" else "") +
                    if (leviInstalled) "" else " · LeviLauncher isn't installed",
                enabled = leviInstalled,
            ) { vm.installMod(context) }
            Divider()
            ActionRow(Icons.Rounded.Share, "Share the mod file", "ConnectedTextures.levipack") {
                scope.launch {
                    val f = withContext(Dispatchers.IO) { Launchers.modFile(context) }
                    Launchers.share(context, f)
                }
            }
        }

        SectionTitle("Storage")
        Group {
            ActionRow(
                Icons.Rounded.DeleteSweep, "Clear cache",
                if (cache < 0) "Downloads and images" else "Downloads and images · ${fileSize(cache)}",
            ) {
                vm.clearCaches { cache = 0; vm.toast("Cache cleared") }
            }
        }

        if (!waiting) updates()

        SectionTitle("About")
        Group {
            ListItem(
                leadingContent = { Icon(Icons.Rounded.Info, contentDescription = null) },
                headlineContent = { Text("CTM Forge ${BuildConfig.VERSION_NAME}") },
                supportingContent = {
                    Text(
                        "Build ${BuildConfig.VERSION_CODE}. Converts Java Edition OptiFine / Continuity / Fusion connected-texture " +
                            "packs, animated blocks included, into Bedrock packs for the Connected Textures mod.",
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            Divider()
            ListItem(
                headlineContent = { Text("Credits") },
                supportingContent = {
                    Text(
                        "Hook signatures adapted from BedrockTools by RadiantByte (MIT). Search and downloads use the Modrinth API; " +
                            "CurseForge and Planet Minecraft open in the built-in browser. Updates come from the project's GitHub " +
                            "releases and can install through Shizuku (Shizuku API by RikkaW, MIT). Packs belong to their authors: " +
                            "check a pack's license before sharing a converted copy.",
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
    }
}

@Composable
private fun Group(content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) { content() }
}

@Composable
private fun Divider() = HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

@Composable
private fun SwitchRow(icon: ImageVector, title: String, text: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    ListItem(
        modifier = Modifier.clickable(enabled = enabled) { onChange(!checked) },
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(text) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange, enabled = enabled) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun ActionRow(icon: ImageVector, title: String, text: String, enabled: Boolean = true, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(text) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
