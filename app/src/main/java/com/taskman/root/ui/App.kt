@file:OptIn(ExperimentalMaterial3Api::class)

package com.taskman.root.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.taskman.root.R

@StringRes
private fun tabTitle(t: MainTab): Int = when (t) {
    MainTab.PROCESSES -> R.string.tab_processes
    MainTab.PERFORMANCE -> R.string.tab_performance
    MainTab.STARTUP -> R.string.tab_startup
    MainTab.DETAILS -> R.string.tab_details
    MainTab.SERVICES -> R.string.tab_services
}

/** Short labels for the bottom bar: five full Russian tab names do not fit on a phone. */
@StringRes
private fun shortTabTitle(t: MainTab): Int = when (t) {
    MainTab.PROCESSES -> R.string.tab_processes_short
    MainTab.PERFORMANCE -> R.string.tab_performance_short
    MainTab.STARTUP -> R.string.tab_startup_short
    MainTab.DETAILS -> R.string.tab_details_short
    MainTab.SERVICES -> R.string.tab_services_short
}

@StringRes
private fun speedTitle(s: UpdateSpeed): Int = when (s) {
    UpdateSpeed.HIGH -> R.string.speed_high
    UpdateSpeed.NORMAL -> R.string.speed_normal
    UpdateSpeed.LOW -> R.string.speed_low
    UpdateSpeed.PAUSED -> R.string.speed_paused
}

@Composable
fun AppRoot(vm: MainViewModel) {
    val ctx = LocalContext.current
    val tab by vm.tab.collectAsState()
    val root by vm.root.collectAsState()
    val searchOpen by vm.searchOpen.collectAsState()
    val query by vm.query.collectAsState()
    val speed by vm.speed.collectAsState()
    val showKernel by vm.showKernel.collectAsState()
    val themeMode by vm.themeMode.collectAsState()
    val browser by vm.browser.collectAsState()

    val snackbar = remember { SnackbarHostState() }
    var sheet by remember { mutableStateOf<SheetModel?>(null) }
    var dialog by remember { mutableStateOf<DialogModel?>(null) }
    val actions = remember(ctx, vm) {
        UiActions(ctx, vm, { sheet = it }, { dialog = it })
    }

    LaunchedEffect(Unit) {
        vm.messages.collect { m ->
            val text = if (m.args.isEmpty()) ctx.getString(m.res) else ctx.getString(m.res, *m.args.toTypedArray())
            snackbar.showSnackbar(text)
        }
    }

    CompositionLocalProvider(LocalIcons provides vm.icons) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // Phones get bottom navigation (thumb zone). Wide screens keep the Windows-like
            // top tab row, where all five tabs fit at once.
            val phone = maxWidth < 600.dp
            Box(Modifier.fillMaxSize()) {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        TopBar(vm, searchOpen, query, speed, showKernel, root) { dialog = DialogModel.Theme }
                    },
                    bottomBar = {
                        if (phone) {
                            MainBottomBar(tab) { vm.selectTab(it) }
                        }
                    },
                    snackbarHost = { SnackbarHost(snackbar) }
                ) { pad ->
                    Column(Modifier.padding(pad).fillMaxSize()) {
                        RootBanner(root) { vm.retryRoot() }
                        if (!phone) {
                            ScrollableTabRow(selectedTabIndex = tab.ordinal, edgePadding = 0.dp) {
                                MainTab.values().forEach { t ->
                                    Tab(
                                        selected = t == tab,
                                        onClick = { vm.selectTab(t) },
                                        text = { Text(stringResource(tabTitle(t)), maxLines = 1) }
                                    )
                                }
                            }
                        }
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            when (tab) {
                                MainTab.PROCESSES -> ProcessesScreen(vm, actions)
                                MainTab.PERFORMANCE -> PerformanceScreen(vm)
                                MainTab.STARTUP -> StartupScreen(vm, actions)
                                MainTab.DETAILS -> DetailsScreen(vm, actions)
                                MainTab.SERVICES -> ServicesScreen(vm, actions)
                            }
                        }
                    }
                }

                // "Open file location": drawn above the whole app, Back goes one folder up
                val b = browser
                if (b != null) FileBrowserScreen(vm, b)
            }
        }

        val s = sheet
        if (s != null) ActionSheet(s) { sheet = null }

        when (val d = dialog) {
            is DialogModel.Confirm -> ConfirmDialog(d) { dialog = null }
            is DialogModel.Props -> PropsDialog(d, vm) { dialog = null }
            is DialogModel.Nice -> NiceDialog(d, vm) { dialog = null }
            DialogModel.Theme -> ThemeDialog(themeMode, { vm.setThemeMode(it) }) { dialog = null }
            null -> {}
        }
    }
}

@Composable
private fun MainBottomBar(selected: MainTab, onSelect: (MainTab) -> Unit) {
    NavigationBar {
        MainTab.values().forEach { t ->
            NavigationBarItem(
                selected = t == selected,
                onClick = { onSelect(t) },
                icon = { TabIcon(t) },
                label = { Text(stringResource(shortTabTitle(t)), maxLines = 1) }
            )
        }
    }
}

@Composable
private fun TabIcon(t: MainTab) {
    when (t) {
        MainTab.PROCESSES -> Icon(painterResource(R.drawable.ic_tab_processes), contentDescription = null)
        MainTab.PERFORMANCE -> Icon(painterResource(R.drawable.ic_tab_performance), contentDescription = null)
        MainTab.STARTUP -> Icon(Icons.Default.PlayArrow, contentDescription = null)
        MainTab.DETAILS -> Icon(Icons.Default.Info, contentDescription = null)
        MainTab.SERVICES -> Icon(Icons.Default.Settings, contentDescription = null)
    }
}

@Composable
private fun TopBar(
    vm: MainViewModel,
    searchOpen: Boolean,
    query: String,
    speed: UpdateSpeed,
    showKernel: Boolean,
    root: RootState,
    onTheme: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(searchOpen) {
        if (searchOpen) {
            try {
                focus.requestFocus()
            } catch (e: Exception) {
                // the field is not attached yet; the user can tap it
            }
        }
    }

    TopAppBar(
        title = {
            if (searchOpen) {
                TextField(
                    value = query,
                    onValueChange = { vm.setQuery(it) },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.search_hint)) },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus)
                )
            } else {
                Column {
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
                    val status = when (root) {
                        RootState.Checking -> stringResource(R.string.root_checking_short)
                        is RootState.Granted -> stringResource(R.string.root_active, root.manager)
                        RootState.Denied -> stringResource(R.string.root_none)
                    }
                    Text(
                        status,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        actions = {
            IconButton(onClick = { vm.setSearchOpen(!searchOpen) }) {
                Icon(
                    imageVector = if (searchOpen) Icons.Default.Close else Icons.Default.Search,
                    contentDescription = stringResource(R.string.search)
                )
            }
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.menu))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                Text(
                    stringResource(R.string.update_speed),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
                UpdateSpeed.values().forEach { s ->
                    DropdownMenuItem(
                        text = { Text((if (s == speed) "\u2713  " else "      ") + stringResource(speedTitle(s))) },
                        onClick = {
                            vm.setSpeed(s)
                            menu = false
                        }
                    )
                }
                Text(
                    stringResource(R.string.list_refresh_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text((if (showKernel) "\u2713  " else "      ") + stringResource(R.string.show_kernel)) },
                    onClick = {
                        vm.setShowKernel(!showKernel)
                        menu = false
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.menu_theme)) },
                    onClick = {
                        onTheme()
                        menu = false
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.recheck_root)) },
                    onClick = {
                        vm.retryRoot()
                        menu = false
                    }
                )
            }
        }
    )
}

@Composable
private fun RootBanner(root: RootState, onRetry: () -> Unit) {
    when (root) {
        RootState.Checking -> Surface(color = MaterialTheme.colorScheme.primaryContainer) {
            Text(
                stringResource(R.string.root_checking),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
        is RootState.Granted -> {}
        RootState.Denied -> Surface(color = MaterialTheme.colorScheme.errorContainer) {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.root_denied),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f).padding(vertical = 8.dp)
                )
                TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
            }
        }
    }
}
