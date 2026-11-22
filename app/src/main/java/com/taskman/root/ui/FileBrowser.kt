@file:OptIn(ExperimentalMaterial3Api::class)

package com.taskman.root.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.taskman.root.R
import com.taskman.root.core.FileEntry
import com.taskman.root.core.Fmt
import com.taskman.root.core.joinPath

private val FOLDER_COLOR = Color(0xFFE3A21A)

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
            BrowserBar(state.path, onUp = { vm.browserUp() }, onClose = { vm.closeBrowser() })
        },
        bottomBar = {
            Row(
                // The Scaffold does not inset a custom bottom bar; keep the buttons above the
                // navigation bar on edge-to-edge devices.
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .bottomBarInsets()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                WinButton(
                    text = stringResource(R.string.browser_copy_path),
                    onClick = { Intents.copyText(ctx, copyTarget) },
                    modifier = Modifier.weight(1f)
                )
                WinButton(
                    text = stringResource(R.string.browser_open_external),
                    onClick = { Intents.openFolder(ctx, state.path) },
                    modifier = Modifier.weight(1f),
                    style = WinButtonStyle.Accent
                )
            }
        }
    ) { pad ->
        ContentLayer(
            Modifier
                .padding(pad)
                .padding(start = 8.dp, end = 8.dp, bottom = 4.dp)
                .fillMaxSize()
        ) {
            Box(Modifier.fillMaxSize()) {
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
}

/** Explorer-like bar: "up one folder", the address box with the current path, and close. */
@Composable
private fun BrowserBar(path: String, onUp: () -> Unit, onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(4.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .topBarInsets()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onUp) {
            Icon(
                painter = painterResource(R.drawable.ic_fl_up),
                contentDescription = stringResource(R.string.browser_up),
                modifier = Modifier.size(22.dp)
            )
        }
        Box(
            Modifier
                .weight(1f)
                .heightIn(min = 40.dp)
                .clip(shape)
                .background(cs.surfaceContainerLowest)
                .border(1.dp, cs.outlineVariant, shape)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                path,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onClose) {
            Icon(
                painter = painterResource(R.drawable.ic_fl_close),
                contentDescription = stringResource(R.string.close),
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun EntryRow(e: FileEntry, highlighted: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val bg = if (highlighted) cs.primaryContainer else Color.Transparent
    Row(
        Modifier
            .fillMaxWidth()
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(if (e.isDir) R.drawable.ic_fl_folder else R.drawable.ic_fl_file),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = if (e.isDir) FOLDER_COLOR else cs.onSurfaceVariant
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                e.name + if (e.isDir) "/" else "",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            val sizeText = e.size?.let { if (e.isDir) "" else "  " + Fmt.bytes(it) } ?: ""
            Text(
                e.mode + "  " + e.owner + ":" + e.group + sizeText + "  " + e.date,
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val target = e.linkTarget
            if (target != null) {
                Text(
                    "\u2192 " + target,
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (e.isDir || e.isLink) {
            ExpandChevron(expanded = false, modifier = Modifier.padding(start = 8.dp))
        }
    }
    HorizontalDivider(Modifier.padding(start = 50.dp))
}
