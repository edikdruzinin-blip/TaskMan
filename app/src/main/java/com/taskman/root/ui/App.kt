@file:OptIn(ExperimentalMaterial3Api::class)

package com.taskman.root.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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

@DrawableRes
private fun tabIcon(t: MainTab): Int = when (t) {
    MainTab.PROCESSES -> R.drawable.ic_tab_processes
    MainTab.PERFORMANCE -> R.drawable.ic_tab_performance
    MainTab.STARTUP -> R.drawable.ic_tab_startup
    MainTab.DETAILS -> R.drawable.ic_tab_details
    MainTab.SERVICES -> R.drawable.ic_tab_services
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
    var settingsOpen by remember { mutableStateOf(false) }
    // 0 = automatic (wide enough -> expanded), 1 = expanded, 2 = collapsed; the hamburger button sets 1 / 2
    var navPref by rememberSaveable { mutableStateOf(0) }
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
            // Phones: title row on top and a bottom bar in the thumb zone. Wide screens: the Windows 11
            // layout, a title bar with the search box and a navigation pane on the left.
            val phone = maxWidth < 600.dp
            val shortScreen = maxHeight < 480.dp
            val navExpanded = when (navPref) {
                1 -> true
                2 -> false
                else -> maxWidth >= 840.dp
            }
            Box(Modifier.fillMaxSize()) {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        if (phone) {
                            PhoneTopBar(
                                tab = tab,
                                root = root,
                                searchOpen = searchOpen,
                                query = query,
                                onQuery = { vm.setQuery(it) },
                                onToggleSearch = { vm.setSearchOpen(!searchOpen) },
                                onSettings = { settingsOpen = true }
                            )
                        } else {
                            TitleBar(
                                root = root,
                                query = query,
                                onQuery = { vm.setQuery(it) },
                                onSettings = { settingsOpen = true }
                            )
                        }
                    },
                    bottomBar = {
                        if (phone) {
                            FluentBottomBar(tab) { vm.selectTab(it) }
                        }
                    },
                    snackbarHost = { SnackbarHost(snackbar) }
                ) { pad ->
                    if (phone) {
                        ContentLayer(
                            Modifier
                                .padding(pad)
                                .padding(start = 8.dp, end = 8.dp, bottom = 4.dp)
                                .fillMaxSize()
                        ) {
                            PageContent(vm, actions, tab, root)
                        }
                    } else {
                        Row(Modifier.padding(pad).fillMaxSize()) {
                            NavPane(tab, navExpanded, { navPref = if (navExpanded) 2 else 1 }) { vm.selectTab(it) }
                            ContentLayer(
                                Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .padding(end = 8.dp, bottom = 8.dp)
                            ) {
                                Column(Modifier.fillMaxSize()) {
                                    if (!shortScreen) {
                                        PageHeader(tab)
                                    }
                                    PageContent(vm, actions, tab, root)
                                }
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
            null -> {}
        }

        if (settingsOpen) {
            SettingsSheet(
                speed = speed,
                showKernel = showKernel,
                themeMode = themeMode,
                onSpeed = { vm.setSpeed(it) },
                onShowKernel = { vm.setShowKernel(it) },
                onTheme = { vm.setThemeMode(it) },
                onRecheckRoot = { vm.retryRoot() },
                onDismiss = { settingsOpen = false }
            )
        }
    }
}

/** The page itself: the root info bar on top and the screen of the selected tab below it. */
@Composable
private fun PageContent(vm: MainViewModel, actions: UiActions, tab: MainTab, root: RootState) {
    Column(Modifier.fillMaxSize()) {
        RootBanner(root) { vm.retryRoot() }
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

// ───────────────────────── top bars ─────────────────────────

/** Phone: logo, the title of the current page with the root status under it, search and settings. */
@Composable
private fun PhoneTopBar(
    tab: MainTab,
    root: RootState,
    searchOpen: Boolean,
    query: String,
    onQuery: (String) -> Unit,
    onToggleSearch: () -> Unit,
    onSettings: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .topBarInsets()
            .heightIn(min = 56.dp)
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (searchOpen) {
            SearchField(
                value = query,
                onValueChange = onQuery,
                hint = stringResource(R.string.search_hint),
                modifier = Modifier.weight(1f),
                autoFocus = true
            )
            IconButton(onClick = onToggleSearch) {
                Icon(
                    painter = painterResource(R.drawable.ic_fl_close),
                    contentDescription = stringResource(R.string.close),
                    modifier = Modifier.size(20.dp)
                )
            }
        } else {
            AppLogo(30.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(tabTitle(tab)),
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                RootStatus(root)
            }
            IconButton(onClick = onToggleSearch) {
                Icon(
                    painter = painterResource(R.drawable.ic_fl_search),
                    contentDescription = stringResource(R.string.search),
                    modifier = Modifier.size(22.dp)
                )
            }
            IconButton(onClick = onSettings) {
                Icon(
                    painter = painterResource(R.drawable.ic_fl_settings),
                    contentDescription = stringResource(R.string.settings_title),
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

/** Wide screens: like the Windows 11 title bar, with the always visible search box in the middle. */
@Composable
private fun TitleBar(
    root: RootState,
    query: String,
    onQuery: (String) -> Unit,
    onSettings: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .topBarInsets()
            .heightIn(min = 52.dp)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppLogo(24.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 220.dp)
        )
        Box(
            Modifier
                .weight(1f)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            SearchField(
                value = query,
                onValueChange = onQuery,
                hint = stringResource(R.string.search_hint),
                modifier = Modifier.widthIn(max = 460.dp).fillMaxWidth()
            )
        }
        RootStatus(root)
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = onSettings) {
            Icon(
                painter = painterResource(R.drawable.ic_fl_settings),
                contentDescription = stringResource(R.string.settings_title),
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

/** Page title above the content on wide screens ("Processes", "Performance" ...). */
@Composable
private fun PageHeader(tab: MainTab) {
    Text(
        stringResource(tabTitle(tab)),
        style = MaterialTheme.typography.titleLarge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 4.dp)
    )
}

// ───────────────────────── navigation ─────────────────────────

/** Phone: a flat bar on the Mica background; the selected page has a short accent line above its icon. */
@Composable
private fun FluentBottomBar(selected: MainTab, onSelect: (MainTab) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .bottomBarInsets()
            .padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        MainTab.values().forEach { t ->
            BottomItem(t, t == selected, Modifier.weight(1f)) { onSelect(t) }
        }
    }
}

@Composable
private fun BottomItem(t: MainTab, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val pill by animateDpAsState(if (selected) 18.dp else 0.dp, tween(150), label = "navPill")
    Column(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(top = 4.dp, bottom = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .width(pill)
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(cs.primary)
        )
        Spacer(Modifier.height(5.dp))
        Icon(
            painter = painterResource(tabIcon(t)),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = if (selected) cs.primary else cs.onSurfaceVariant
        )
        Spacer(Modifier.height(2.dp))
        Text(
            stringResource(shortTabTitle(t)),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) cs.onSurface else cs.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Wide screens: the Windows 11 navigation pane, collapsible with the hamburger button. */
@Composable
private fun NavPane(
    selected: MainTab,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSelect: (MainTab) -> Unit
) {
    Column(
        Modifier
            .width(if (expanded) 232.dp else 56.dp)
            .fillMaxHeight()
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        NavRow(
            iconRes = R.drawable.ic_fl_menu,
            label = stringResource(R.string.menu),
            selected = false,
            expanded = false,
            onClick = onToggle
        )
        Spacer(Modifier.height(6.dp))
        MainTab.values().forEach { t ->
            NavRow(
                iconRes = tabIcon(t),
                label = stringResource(tabTitle(t)),
                selected = t == selected,
                expanded = expanded,
                onClick = { onSelect(t) }
            )
        }
    }
}

@Composable
private fun NavRow(
    @DrawableRes iconRes: Int,
    label: String,
    selected: Boolean,
    expanded: Boolean,
    onClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(if (selected) cs.onSurface.copy(alpha = 0.07f) else Color.Transparent)
            .clickable(onClick = onClick)
    ) {
        if (selected) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .width(3.dp)
                    .height(16.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(cs.primary)
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = label,
                modifier = Modifier.size(20.dp),
                tint = if (selected) cs.primary else cs.onSurface
            )
            if (expanded) {
                Spacer(Modifier.width(12.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// ───────────────────────── root info bar ─────────────────────────

@Composable
private fun RootBanner(root: RootState, onRetry: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    when (root) {
        RootState.Checking -> InfoBar(cs.primaryContainer) {
            Text(
                stringResource(R.string.root_checking),
                style = MaterialTheme.typography.bodySmall,
                color = cs.onPrimaryContainer,
                modifier = Modifier.weight(1f).padding(vertical = 10.dp)
            )
        }
        is RootState.Granted -> {}
        RootState.Denied -> InfoBar(cs.errorContainer) {
            Text(
                stringResource(R.string.root_denied),
                style = MaterialTheme.typography.bodySmall,
                color = cs.onErrorContainer,
                modifier = Modifier.weight(1f).padding(vertical = 10.dp)
            )
            TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
        }
    }
}

/** WinUI InfoBar: a rounded, tinted strip with a message and an optional action. */
@Composable
private fun InfoBar(color: Color, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(color)
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}
