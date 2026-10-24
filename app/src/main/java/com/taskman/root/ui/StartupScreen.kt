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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.taskman.root.R
import com.taskman.root.core.BootScript
import com.taskman.root.core.Fmt
import com.taskman.root.core.RootModule
import com.taskman.root.data.StartupApp

@Composable
fun StartupScreen(vm: MainViewModel, actions: UiActions) {
    val st by vm.startup.collectAsState()
    val filter by vm.startupFilter.collectAsState()
    val query by vm.query.collectAsState()
    val live by vm.live.collectAsState()
    val root by vm.root.collectAsState()
    val rooted = root is RootState.Granted

    val running: Map<String, Long> = remember(live.groups) {
        live.groups.mapNotNull { g -> g.pkg?.let { it to g.priv } }.toMap()
    }
    val q = query.trim().lowercase()

    val apps = remember(st.apps, filter, q) {
        st.apps.filter { a ->
            val byFilter = when (filter) {
                StartupFilter.ALL -> true
                StartupFilter.USER -> !a.system
                StartupFilter.SYSTEM -> a.system
                StartupFilter.DISABLED -> !a.enabled
            }
            byFilter && (q.isEmpty() || a.label.lowercase().contains(q) || a.pkg.lowercase().contains(q))
        }
    }
    val modules = remember(st.root, q) {
        st.root?.modules.orEmpty().filter {
            q.isEmpty() || it.name.lowercase().contains(q) || it.id.lowercase().contains(q)
        }
    }
    val scripts = remember(st.root, q) {
        st.root?.scripts.orEmpty().filter { q.isEmpty() || it.path.lowercase().contains(q) }
    }

    val options = listOf(
        StartupFilter.ALL to stringResource(R.string.filter_all),
        StartupFilter.USER to stringResource(R.string.filter_user),
        StartupFilter.SYSTEM to stringResource(R.string.filter_system),
        StartupFilter.DISABLED to stringResource(R.string.filter_disabled)
    )

    Column(Modifier.fillMaxSize()) {
        ChipRow(options, filter, { vm.setStartupFilter(it) }) {
            IconButton(onClick = { vm.loadStartup() }) {
                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh))
            }
        }
        if (!st.loaded) {
            LoadingBox()
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                if (!rooted) {
                    item(key = "note_root_needed") {
                        Text(
                            stringResource(R.string.startup_need_root_toggle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
                if (modules.isNotEmpty() || scripts.isNotEmpty()) {
                    item(key = "hdr_root") { SectionHeader(stringResource(R.string.startup_root_header)) }
                    item(key = "note_reboot") {
                        Text(
                            stringResource(R.string.startup_reboot_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                    items(modules, key = { "m:" + it.id }) { m ->
                        ModuleRow(m, rooted, onOpen = { vm.browse("/data/adb/modules/" + m.id) }) { disabled ->
                            vm.setModuleDisabled(m.id, disabled)
                        }
                    }
                    items(scripts, key = { "s:" + it.path }) { s ->
                        ScriptRow(s, rooted, onOpen = { vm.browse(s.path) }) { exec ->
                            vm.setScriptExecutable(s.path, exec)
                        }
                    }
                }
                item(key = "hdr_apps") {
                    SectionHeader(stringResource(R.string.startup_apps_header) + " (" + apps.size + ")")
                }
                if (apps.isEmpty()) {
                    item(key = "empty_apps") {
                        Text(
                            stringResource(R.string.startup_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
                items(apps, key = { "a:" + it.pkg }) { a ->
                    StartupRow(
                        a = a,
                        runningBytes = running[a.pkg],
                        rooted = rooted,
                        onClick = { actions.onStartup(a, running[a.pkg]) },
                        onToggle = { on -> vm.setComponents(a.receivers.map { it.component }, on) }
                    )
                }
            }
        }
    }
}

@Composable
private fun StartupRow(
    a: StartupApp,
    runningBytes: Long?,
    rooted: Boolean,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit
) {
    val triggers = a.receivers.flatMap { it.actions }.distinct().joinToString(", ") { it.substringAfterLast('.') }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(a.pkg, 36.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(a.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                triggers,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (runningBytes != null) {
                Text(
                    stringResource(R.string.startup_running_now, Fmt.bytes(runningBytes)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1
                )
            }
        }
        Switch(checked = a.enabled, onCheckedChange = onToggle, enabled = rooted)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
}

@Composable
private fun ModuleRow(m: RootModule, rooted: Boolean, onOpen: () -> Unit, onDisabled: (Boolean) -> Unit) {
    val scripts = listOfNotNull(
        if (m.hasPostFs) "post-fs-data.sh" else null,
        if (m.hasService) "service.sh" else null
    ).joinToString(", ")
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(null, 32.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(m.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                m.id + if (scripts.isNotEmpty()) "  ($scripts)" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (m.pendingRemove) {
                Text(
                    stringResource(R.string.module_pending_remove),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        Switch(checked = !m.disabled, onCheckedChange = { on -> onDisabled(!on) }, enabled = rooted)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
}

@Composable
private fun ScriptRow(s: BootScript, rooted: Boolean, onOpen: () -> Unit, onExecutable: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(null, 32.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                s.path.substringAfterLast('/'),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                s.path.substringBeforeLast('/'),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Switch(checked = s.executable, onCheckedChange = onExecutable, enabled = rooted)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
}
