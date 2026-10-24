@file:OptIn(ExperimentalMaterial3Api::class)

package com.taskman.root.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.taskman.root.R
import com.taskman.root.core.AndroidService
import com.taskman.root.core.InitService

private val RUNNING_GREEN = Color(0xFF2E9E4F)
private val RESTART_ORANGE = Color(0xFFE08A00)

@Composable
fun ServicesScreen(vm: MainViewModel, actions: UiActions) {
    val st by vm.services.collectAsState()
    val mode by vm.servicesMode.collectAsState()
    val query by vm.query.collectAsState()
    val root by vm.root.collectAsState()
    val rooted = root is RootState.Granted
    val q = query.trim().lowercase()

    val androidList = remember(st.android, q) {
        st.android
            .distinctBy { it.component + "#" + (it.pid ?: 0) }
            .filter { s ->
                q.isEmpty() || s.cls.lowercase().contains(q) || s.pkg.lowercase().contains(q) ||
                    (s.pid?.toString() ?: "") == q
            }
            .sortedWith(compareBy<AndroidService>({ it.pkg }, { it.cls }))
    }
    val initList = remember(st.init, q) {
        st.init
            .filter { q.isEmpty() || it.name.lowercase().contains(q) }
            .sortedWith(compareBy<InitService>({ it.state != "running" }, { it.name }))
    }

    val options = listOf(
        ServicesMode.ANDROID to stringResource(R.string.services_android),
        ServicesMode.INIT to stringResource(R.string.services_init)
    )

    Column(Modifier.fillMaxSize()) {
        ChipRow(options, mode, { vm.setServicesMode(it) }) {
            IconButton(onClick = { vm.loadServices() }) {
                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh))
            }
        }
        if (!rooted) {
            CenterMessage(stringResource(R.string.services_need_root))
        } else if (!st.loaded) {
            LoadingBox()
        } else if (mode == ServicesMode.ANDROID) {
            if (androidList.isEmpty()) {
                CenterMessage(stringResource(R.string.services_empty))
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(androidList, key = { it.component + "#" + (it.pid ?: 0) }) { s ->
                        val label = vm.appLabel(s.pkg)
                        AndroidServiceRow(s, label) { actions.onAndroidService(s, label) }
                    }
                }
            }
        } else {
            if (initList.isEmpty()) {
                CenterMessage(stringResource(R.string.services_empty))
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(initList, key = { it.name }) { s ->
                        InitServiceRow(s) { actions.onInitService(s) }
                    }
                }
            }
        }
    }
}

@Composable
private fun AndroidServiceRow(s: AndroidService, appLabel: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(s.pkg, 32.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                s.cls.substringAfterLast('.'),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                appLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                if (s.pid != null) "PID " + s.pid else "-",
                style = MaterialTheme.typography.labelMedium
            )
            if (s.foreground) {
                Text(
                    stringResource(R.string.svc_foreground),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
}

@Composable
private fun InitServiceRow(s: InitService, onClick: () -> Unit) {
    val color = when (s.state) {
        "running" -> RUNNING_GREEN
        "restarting" -> RESTART_ORANGE
        else -> MaterialTheme.colorScheme.outline
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(null, 26.dp, tint = color)
        Spacer(Modifier.width(12.dp))
        Text(
            s.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            s.state + if (s.pid != null) "  PID " + s.pid else "",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
}
