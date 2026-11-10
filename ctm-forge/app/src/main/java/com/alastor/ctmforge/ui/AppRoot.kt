package com.alastor.ctmforge.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.outlined.AddBox
import androidx.compose.material.icons.rounded.AddBox
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.alastor.ctmforge.AppViewModel
import com.alastor.ctmforge.AppViewModel.Route
import com.alastor.ctmforge.AppViewModel.Tab
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.merge

private fun routeKey(route: Route?): String = when (route) {
    null -> "main"
    is Route.Project -> "project:${route.hit.id}"
    is Route.Pack -> "pack:${route.id}"
    Route.Convert -> "convert"
}

@Composable
fun AppRoot(vm: AppViewModel, onPickFile: () -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { merge(vm.messages, vm.updater.messages).collectLatest { snackbar.showSnackbar(it) } }

    val top = vm.routes.lastOrNull()
    BackHandler(enabled = top != null) { vm.pop() }
    BackHandler(enabled = top == null && vm.tab != Tab.BROWSE) { vm.tab = Tab.BROWSE }

    BackHandler(enabled = top == null && vm.tab == Tab.CREATE && vm.creator.project != null) { vm.creator.close() }

    val saveable = rememberSaveableStateHolder()
    Box(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = top,
            transitionSpec = {
                (fadeIn(tween(220, delayMillis = 50)) + scaleIn(tween(220, delayMillis = 50), initialScale = 0.97f)) togetherWith
                    fadeOut(tween(90))
            },
            contentKey = { routeKey(it) },
            label = "routes",
        ) { route ->
            saveable.SaveableStateProvider(routeKey(route)) {
                when (route) {
                    null -> MainTabs(vm, onPickFile)
                    is Route.Project -> ProjectScreen(vm, route.hit)
                    is Route.Pack -> PackScreen(vm, route.id)
                    Route.Convert -> ConvertScreen(vm, onPickFile)
                }
            }
        }
        SnackbarHost(
            snackbar,
            Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(bottomInsets(vm.settings.immersive))
                .padding(bottom = if (top == null) 88.dp else 8.dp),
        )
    }
}

private class TabItem(val tab: Tab, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

private val TABS = listOf(
    TabItem(Tab.BROWSE, "Browse", Icons.Outlined.Explore, Icons.Rounded.Explore),
    TabItem(Tab.CREATE, "Create", Icons.Outlined.AddBox, Icons.Rounded.AddBox),
    TabItem(Tab.LIBRARY, "Library", Icons.Outlined.Inventory2, Icons.Rounded.Inventory2),
    TabItem(Tab.SETTINGS, "Settings", Icons.Outlined.Settings, Icons.Rounded.Settings),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainTabs(vm: AppViewModel, onPickFile: () -> Unit) {
    val immersive = vm.settings.immersive
    val tabState = rememberSaveableStateHolder()
    Scaffold(
        topBar = {
            if (vm.tab != Tab.BROWSE) {
                TopAppBar(
                    title = { Text(when (vm.tab) { Tab.LIBRARY -> "Library"; Tab.CREATE -> "Pack creator"; else -> "Settings" }) },
                    windowInsets = topInsets(immersive),
                )
            }
        },
        bottomBar = {
            NavigationBar(windowInsets = bottomInsets(immersive)) {
                TABS.forEach { t ->
                    val selected = vm.tab == t.tab
                    val badge = t.tab == Tab.SETTINGS && updatesWaiting(vm)
                    NavigationBarItem(
                        selected = selected,
                        onClick = { vm.tab = t.tab },
                        icon = {
                            BadgedBox(badge = { if (badge) Badge() }) {
                                Icon(if (selected) t.selectedIcon else t.icon, contentDescription = if (badge) "Updates available" else null)
                            }
                        },
                        label = { Text(t.label) },
                    )
                }
            }
        },
        floatingActionButton = {
            if (vm.tab == Tab.LIBRARY && vm.library.entries.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = onPickFile,
                    icon = { Icon(Icons.Rounded.FolderOpen, contentDescription = null) },
                    text = { Text("Open a file") },
                )
            }
        },
        contentWindowInsets = barInsets(immersive),
    ) { padding ->
        tabState.SaveableStateProvider(vm.tab.name) {
            when (vm.tab) {
                Tab.BROWSE -> BrowseScreen(vm, padding, onPickFile)
                Tab.CREATE -> CreatorScreen(vm.creator, padding)
                Tab.LIBRARY -> LibraryScreen(vm, padding, onPickFile)
                Tab.SETTINGS -> SettingsScreen(vm, padding)
            }
        }
    }
}
