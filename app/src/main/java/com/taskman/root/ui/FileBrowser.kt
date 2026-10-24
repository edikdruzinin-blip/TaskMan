@file:OptIn(ExperimentalMaterial3Api::class)

package com.taskman.root.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.taskman.root.R
import com.taskman.root.core.FileEntry
import com.taskman.root.core.Fmt
import com.taskman.root.core.joinPath

/**
 * Read-only folder browser used by "Open file location". It runs `ls` through the root shell, so it can
 * show /data/app, /data/data, /system and other folders no regular file manager can enter.
 * Drawn above the whole app; Back goes one folder up, the X closes it.
 */
@Composable
fun FileBrowserScreen(vm: MainViewModel, state: BrowserState) {
    val ctx = LocalContext.current
    val listState = rememberLazyListState()
    BackHandler { vm.browserUp() }

    LaunchedEffect(state.entries, state.highlight) {
        val i = state.entries.indexOfFirst { it.name == state.highlight }
        listState.scrollToItem(if (i >= 0) i else 0)
    }

    val copyTarget = state.highlight?.let { joinPath(state.path, it) } ?: state.path

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        state.path,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { vm.closeBrowser() }) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close))
                    }
                },
                actions = {
                    IconButton(onClick = { vm.browserUp() }) {
                        Icon(
                            Icons.Default.KeyboardArrowUp,
                            contentDescription = stringResource(R.string.browser_up)
                        )
                    }
                }
            )
        },
        bottomBar = {
            Surface(
                // The Scaffold does not inset a custom bottom bar; keep the buttons above the
                // navigation bar on edge-to-edge devices.
                modifier = Modifier.navigationBarsPadding(),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { Intents.copyText(ctx, copyTarget) }) {
                        Text(stringResource(R.string.browser_copy_path))
                    }
                    TextButton(onClick = { Intents.openFolder(ctx, state.path) }) {
                        Text(stringResource(R.string.browser_open_external))
                    }
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            if (state.loading) {
                LoadingBox()
            } else if (state.error != null) {
                CenterMessage(
                    if (state.error == "NONE") stringResource(R.string.browser_not_found) else state.error
                )
            } else if (state.entries.isEmpty()) {
                CenterMessage(stringResource(R.string.browser_empty))
            } else {
                LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    items(state.entries, key = { it.name }) { e ->
                        EntryRow(e, e.name == state.highlight) {
                            val full = joinPath(state.path, e.name)
                            if (e.isDir || e.isLink) vm.browse(full) else Intents.copyText(ctx, full)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryRow(e: FileEntry, highlighted: Boolean, onClick: () -> Unit) {
    val bg = if (highlighted) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    Row(
        Modifier
            .fillMaxWidth()
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                e.name + if (e.isDir) "/" else "",
                style = MaterialTheme.typography.bodyMedium,
                color = if (e.isDir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            val sizeText = e.size?.let { if (e.isDir) "" else "  " + Fmt.bytes(it) } ?: ""
            Text(
                e.mode + "  " + e.owner + ":" + e.group + sizeText + "  " + e.date,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val target = e.linkTarget
            if (target != null) {
                Text(
                    "\u2192 " + target,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (e.isDir || e.isLink) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
}
