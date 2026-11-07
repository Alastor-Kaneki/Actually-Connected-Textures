package com.alastor.ctmforge.ui

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.alastor.ctmforge.AppViewModel
import com.alastor.ctmforge.data.Launchers
import com.alastor.ctmforge.update.ShizukuInstaller
import com.alastor.ctmforge.update.Updater

/** Whether anything is waiting for the user: shows a badge on the Settings tab. */
fun updatesWaiting(vm: AppViewModel): Boolean = vm.updater.appUpdate != null || vm.updater.modPending

private fun installLine(install: Updater.Install): String? = when (install) {
    is Updater.Install.Downloading ->
        if (install.total > 0) "Downloading… ${(install.done * 100 / install.total).coerceIn(0, 100)}%" else "Downloading…"
    is Updater.Install.Installing -> if (install.viaShizuku) "Installing with Shizuku…" else "Waiting for Android's installer…"
    is Updater.Install.Failed -> install.message
    Updater.Install.None -> null
}

/** Cards for the Library: a new app build and a downloaded mod that LeviLauncher doesn't have yet. */
@Composable
fun UpdateCards(vm: AppViewModel) {
    val updater = vm.updater
    val context = LocalContext.current
    updater.appUpdate?.let { up ->
        val busy = updater.install is Updater.Install.Downloading || updater.install is Updater.Install.Installing
        NoticeCard(
            icon = Icons.Rounded.SystemUpdate,
            title = "CTM Forge build ${up.versionCode} is out",
            text = installLine(updater.install) ?: "Release ${up.release.name}. You have build ${com.alastor.ctmforge.BuildConfig.VERSION_CODE}.",
            container = MaterialTheme.colorScheme.primaryContainer,
            content = MaterialTheme.colorScheme.onPrimaryContainer,
            actions = {
                TextButton(onClick = { Launchers.openUrl(context, up.release.pageUrl)?.let(vm::toast) }) { Text("What's new") }
                FilledTonalButton(onClick = { updater.installApp() }, enabled = !busy) { Text("Update") }
            },
        )
    }
    if (updater.modPending) {
        val m = updater.mod
        NoticeCard(
            icon = Icons.Rounded.Extension,
            title = "Connected Textures ${m?.version} is ready",
            text = "Install it in LeviLauncher to replace the version you have" + (m?.minecraft?.let { ". It's for Minecraft $it." } ?: "."),
            container = MaterialTheme.colorScheme.tertiaryContainer,
            content = MaterialTheme.colorScheme.onTertiaryContainer,
            actions = {
                TextButton(onClick = { updater.markModSent() }) { Text("Hide") }
                FilledTonalButton(onClick = { vm.installMod(context, shareIfMissing = true) }) { Text("Install mod") }
            },
        )
    }
}

/** Rows for the "Updates" group in Settings. */
@Composable
fun UpdatesRows(vm: AppViewModel, divider: @Composable () -> Unit) {
    val s = vm.settings
    val updater = vm.updater
    val context = LocalContext.current
    // reading `changes` re-runs this when Shizuku starts, stops or gets permission
    val shizuku = ShizukuInstaller.changes.let { ShizukuInstaller.state(context) }

    updater.appUpdate?.let { up ->
        val busy = updater.install is Updater.Install.Downloading || updater.install is Updater.Install.Installing
        ListItem(
            modifier = Modifier.clickable(enabled = !busy) { updater.installApp() },
            leadingContent = { Icon(Icons.Rounded.SystemUpdate, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            headlineContent = { Text("Update to build ${up.versionCode}") },
            supportingContent = {
                Column {
                    Text(
                        installLine(updater.install) ?: (
                            "Release ${up.release.name} · ${fileSize(up.asset.size)} · " +
                                if (s.shizukuInstall && shizuku == ShizukuInstaller.State.READY) "installs with Shizuku" else "opens Android's installer"
                            ),
                    )
                    val dl = updater.install as? Updater.Install.Downloading
                    if (dl != null) {
                        val f = if (dl.total > 0) dl.done.toFloat() / dl.total else null
                        if (f == null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                        else LinearProgressIndicator(progress = { f.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                    }
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
        divider()
    }

    val checkText = when (val c = updater.check) {
        Updater.Check.Running -> "Checking…"
        is Updater.Check.Failed -> c.message
        is Updater.Check.Done -> statusLine(updater) + " · checked " + ago(c.at)
        Updater.Check.Idle -> statusLine(updater) + (updater.lastCheck.takeIf { it > 0 }?.let { " · checked " + ago(it) } ?: "")
    }
    ListItem(
        modifier = Modifier.clickable(enabled = updater.check != Updater.Check.Running) { updater.check() },
        leadingContent = { Icon(Icons.Rounded.Refresh, contentDescription = null) },
        headlineContent = { Text("Check for updates") },
        supportingContent = { Text(checkText) },
        trailingContent = { if (updater.check == Updater.Check.Running) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
    divider()
    ToggleRow(
        Icons.Rounded.Update, "Check automatically",
        "Looks on GitHub for new versions of CTM Forge and the mod when the app opens (at most every 6 hours)",
        s.autoUpdate,
    ) { s.updateAutoUpdate(it) }
    divider()
    val shizukuText = when (shizuku) {
        ShizukuInstaller.State.READY ->
            "Updates install by themselves, without Android's prompt. CTM Forge closes and opens again to finish."
        ShizukuInstaller.State.NO_PERMISSION -> "Tap to allow CTM Forge in Shizuku"
        ShizukuInstaller.State.NOT_INSTALLED -> "Install Shizuku to update without Android's prompt. Until then, updates use the normal installer."
        else -> shizuku.label + ". Until then, updates use the normal installer."
    }
    ListItem(
        modifier = Modifier.clickable {
            when (shizuku) {
                ShizukuInstaller.State.NO_PERMISSION -> ShizukuInstaller.requestPermission { ok ->
                    vm.toast(if (ok) "Shizuku is ready" else "Shizuku permission denied. Allow CTM Forge in the Shizuku app.")
                }
                ShizukuInstaller.State.NOT_INSTALLED -> Launchers.openUrl(context, "https://shizuku.rikka.app/")?.let(vm::toast)
                else -> s.updateShizukuInstall(!s.shizukuInstall)
            }
        },
        leadingContent = { Icon(Icons.Rounded.AdminPanelSettings, contentDescription = null) },
        headlineContent = { Text("Install with Shizuku") },
        supportingContent = { Text(shizukuText) },
        trailingContent = { Switch(checked = s.shizukuInstall, onCheckedChange = { s.updateShizukuInstall(it) }) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

private fun statusLine(updater: Updater): String = when {
    updater.appUpdate != null -> "Build ${updater.appUpdate?.versionCode} is out"
    updater.modPending -> "A newer mod is ready"
    updater.lastCheck > 0 -> "Up to date"
    else -> "Not checked yet"
}

private fun ago(at: Long): String =
    DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString().lowercase()

@Composable
private fun ToggleRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        modifier = Modifier.clickable { onChange(!checked) },
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(text) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
