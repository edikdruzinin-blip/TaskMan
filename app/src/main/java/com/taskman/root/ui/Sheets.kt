@file:OptIn(ExperimentalMaterial3Api::class)

package com.taskman.root.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.taskman.root.R
import com.taskman.root.core.AndroidService
import com.taskman.root.core.CRITICAL_PROCESSES
import com.taskman.root.core.Category
import com.taskman.root.core.Fmt
import com.taskman.root.core.InitService
import com.taskman.root.core.ProcGroup
import com.taskman.root.core.ProcRow
import com.taskman.root.core.uidName
import com.taskman.root.data.StartupApp

data class SheetAction(val label: String, val danger: Boolean = false, val onClick: () -> Unit)

data class SheetModel(
    val title: String,
    val subtitle: String,
    val pkg: String?,
    val info: List<Pair<String, String>>,
    val actions: List<SheetAction>
)

sealed interface DialogModel {
    data class Confirm(
        val title: String,
        val text: String,
        val confirm: String,
        val onConfirm: () -> Unit
    ) : DialogModel

    data class Props(val title: String, val pid: Int) : DialogModel
    data class Nice(val proc: ProcRow) : DialogModel
    data object Theme : DialogModel
}

// ───────────────────────── composables ─────────────────────────

@Composable
fun ActionSheet(model: SheetModel, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppIcon(model.pkg, 40.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        model.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        model.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (model.info.isNotEmpty()) {
                StatGrid(model.info, Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            }
            HorizontalDivider()
            model.actions.forEach { a ->
                ListItem(
                    headlineContent = {
                        Text(
                            a.label,
                            color = if (a.danger) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurface
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable {
                        onDismiss()
                        a.onClick()
                    }
                )
            }
        }
    }
}

@Composable
fun ConfirmDialog(m: DialogModel.Confirm, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(m.title) },
        text = { Text(m.text) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                m.onConfirm()
            }) { Text(m.confirm, color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
fun PropsDialog(m: DialogModel.Props, vm: MainViewModel, onDismiss: () -> Unit) {
    val data by produceState<List<Pair<String, String>>?>(initialValue = null, key1 = m.pid) {
        value = vm.props(m.pid)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(m.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            val d = data
            if (d == null) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (d.isEmpty()) {
                Text(stringResource(R.string.props_empty))
            } else {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    d.forEach { (k, v) ->
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Text(
                                k,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                v,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } }
    )
}

@Composable
fun NiceDialog(m: DialogModel.Nice, vm: MainViewModel, onDismiss: () -> Unit) {
    val options = listOf(
        -10 to R.string.prio_high,
        -5 to R.string.prio_above,
        0 to R.string.prio_normal,
        5 to R.string.prio_below,
        10 to R.string.prio_low,
        19 to R.string.prio_lowest
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.act_priority)) },
        text = {
            Column {
                options.forEach { (value, res) ->
                    Text(
                        stringResource(res) + " (" + value + ")",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onDismiss()
                                vm.setNice(m.proc, value)
                            }
                            .padding(vertical = 12.dp)
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
fun ThemeDialog(current: ThemeMode, onSelect: (ThemeMode) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.theme_title)) },
        text = {
            Column {
                ThemeMode.values().forEach { m ->
                    val label = when (m) {
                        ThemeMode.SYSTEM -> R.string.theme_system
                        ThemeMode.LIGHT -> R.string.theme_light
                        ThemeMode.DARK -> R.string.theme_dark
                        ThemeMode.AMOLED -> R.string.theme_amoled
                    }
                    val hint = when (m) {
                        ThemeMode.DARK -> R.string.theme_dark_hint
                        ThemeMode.AMOLED -> R.string.theme_amoled_hint
                        else -> 0
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelect(m)
                                onDismiss()
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = m == current,
                            onClick = {
                                onSelect(m)
                                onDismiss()
                            }
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
                            if (hint != 0) {
                                Text(
                                    stringResource(hint),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

// ───────────────────────── model builders ─────────────────────────

/** Builds the sheets / dialogs for every tab, so that all tabs behave the same way. */
class UiActions(
    private val ctx: Context,
    private val vm: MainViewModel,
    private val setSheet: (SheetModel?) -> Unit,
    private val setDialog: (DialogModel?) -> Unit
) {
    private fun str(@StringRes id: Int): String = ctx.getString(id)
    private fun str(@StringRes id: Int, vararg args: Any): String = ctx.getString(id, *args)

    private fun searchQuery(name: String): String = "\"" + name + "\" " + str(R.string.search_suffix)

    private fun needsWarning(p: ProcRow): Boolean =
        p.name in CRITICAL_PROCESSES || (p.uid % 100000) < 10000

    private fun confirm(text: String, run: () -> Unit) {
        setDialog(DialogModel.Confirm(str(R.string.confirm_title), text, str(R.string.confirm_yes), run))
    }

    private fun confirmThen(p: ProcRow, run: () -> Unit) {
        if (needsWarning(p)) confirm(str(R.string.confirm_system_text, p.name), run) else run()
    }

    private fun stateText(c: Char): String = when (c) {
        'R' -> str(R.string.state_running)
        'T', 't' -> str(R.string.state_suspended)
        'Z' -> str(R.string.state_zombie)
        else -> str(R.string.state_sleeping)
    }

    /** Tap on a row of the Processes tab. */
    fun onGroup(g: ProcGroup) {
        val pkg = g.pkg
        if (pkg == null) {
            onProc(g.procs.first(), null, g.label)
            return
        }
        val info = listOf(
            str(R.string.info_cpu) to Fmt.pct(g.cpu),
            str(R.string.info_memory) to Fmt.bytes(g.priv),
            str(R.string.col_disk) to (if (vm.isRooted) Fmt.diskRate(g.diskBps.toDouble()) else "-"),
            str(R.string.col_network) to (g.netBps?.let { Fmt.netRate(it) } ?: "-"),
            str(R.string.info_processes) to g.procs.size.toString(),
            str(R.string.info_package) to pkg
        )
        val actions = ArrayList<SheetAction>()
        actions.add(SheetAction(str(R.string.act_end_task), danger = true) {
            val critical = g.category == Category.SYSTEM && g.procs.any { needsWarning(it) }
            if (critical) {
                confirm(str(R.string.confirm_system_text, g.label)) { vm.forceStop(pkg, g.label) }
            } else {
                vm.forceStop(pkg, g.label)
            }
        })
        actions.add(SheetAction(str(R.string.act_open_location)) { vm.openAppLocation(pkg) })
        actions.add(SheetAction(str(R.string.act_open_data)) { vm.openAppLocation(pkg, true) })
        actions.add(SheetAction(str(R.string.act_search_online)) { Intents.searchWeb(ctx, searchQuery(pkg)) })
        actions.add(SheetAction(str(R.string.act_app_info)) { Intents.appInfo(ctx, pkg) })
        actions.add(SheetAction(str(R.string.act_open_app)) { Intents.launch(ctx, pkg) })
        actions.add(SheetAction(str(R.string.act_go_details)) { vm.showInDetails(pkg) })
        setSheet(SheetModel(g.label, pkg, pkg, info, actions))
    }

    /** Tap on a single process (child row, native process, Details tab). */
    fun onProc(p: ProcRow, pkg: String?, title: String) {
        val info = listOf(
            str(R.string.info_pid) to p.pid.toString(),
            str(R.string.info_user) to uidName(p.uid),
            str(R.string.info_state) to stateText(p.state),
            str(R.string.info_cpu) to Fmt.pct(p.cpu),
            str(R.string.info_memory) to Fmt.bytes(p.privBytes),
            str(R.string.col_disk) to (if (vm.isRooted) Fmt.diskRate(p.diskBps.toDouble()) else "-"),
            str(R.string.info_rss) to Fmt.bytes(p.rssBytes),
            str(R.string.info_threads) to p.threads.toString(),
            str(R.string.info_nice) to p.nice.toString(),
            str(R.string.info_oom) to p.oomAdj.toString(),
            str(R.string.info_runtime) to Fmt.age(p.ageSec)
        )
        val actions = ArrayList<SheetAction>()
        actions.add(SheetAction(str(R.string.act_end_process), danger = true) {
            confirmThen(p) { vm.signal(p, 9) }
        })
        actions.add(SheetAction(str(R.string.act_terminate)) { confirmThen(p) { vm.signal(p, 15) } })
        if (p.state == 'T' || p.state == 't') {
            actions.add(SheetAction(str(R.string.act_resume)) { vm.signal(p, 18) })
        } else {
            actions.add(SheetAction(str(R.string.act_suspend)) { confirmThen(p) { vm.signal(p, 19) } })
        }
        actions.add(SheetAction(str(R.string.act_priority)) { setDialog(DialogModel.Nice(p)) })
        if (pkg != null) {
            actions.add(SheetAction(str(R.string.act_open_location)) { vm.openAppLocation(pkg) })
        } else if (!p.kernel) {
            actions.add(SheetAction(str(R.string.act_open_location)) { vm.openProcessLocation(p.pid) })
        }
        actions.add(SheetAction(str(R.string.act_properties)) { setDialog(DialogModel.Props(title, p.pid)) })
        actions.add(SheetAction(str(R.string.act_search_online)) { Intents.searchWeb(ctx, searchQuery(pkg ?: p.name)) })
        if (pkg != null) {
            actions.add(SheetAction(str(R.string.act_app_info)) { Intents.appInfo(ctx, pkg) })
        }
        setSheet(SheetModel(title, p.cmdline.ifBlank { p.comm }, pkg, info, actions))
    }

    fun onStartup(a: StartupApp, runningBytes: Long?) {
        val triggers = a.receivers.flatMap { it.actions }.distinct().joinToString(", ") { it.substringAfterLast('.') }
        val info = listOf(
            str(R.string.info_package) to a.pkg,
            str(R.string.info_triggers) to triggers,
            str(R.string.info_receivers) to a.receivers.size.toString(),
            str(R.string.info_memory) to (runningBytes?.let { Fmt.bytes(it) } ?: str(R.string.startup_not_running))
        )
        val actions = ArrayList<SheetAction>()
        val components = a.receivers.map { it.component }
        if (a.enabled) {
            actions.add(SheetAction(str(R.string.act_autostart_off), danger = true) {
                vm.setComponents(components, false)
            })
        } else {
            actions.add(SheetAction(str(R.string.act_autostart_on)) { vm.setComponents(components, true) })
        }
        if (runningBytes != null) {
            actions.add(SheetAction(str(R.string.act_force_stop)) { vm.forceStop(a.pkg, a.label) })
        }
        actions.add(SheetAction(str(R.string.act_open_location)) { vm.openAppLocation(a.pkg) })
        actions.add(SheetAction(str(R.string.act_open_data)) { vm.openAppLocation(a.pkg, true) })
        actions.add(SheetAction(str(R.string.act_search_online)) { Intents.searchWeb(ctx, searchQuery(a.pkg)) })
        actions.add(SheetAction(str(R.string.act_app_info)) { Intents.appInfo(ctx, a.pkg) })
        setSheet(SheetModel(a.label, a.pkg, a.pkg, info, actions))
    }

    fun onAndroidService(s: AndroidService, appLabel: String) {
        val info = listOf(
            str(R.string.info_component) to s.component,
            str(R.string.info_process) to (s.process ?: "-"),
            str(R.string.info_pid) to (s.pid?.toString() ?: "-"),
            str(R.string.info_foreground) to str(if (s.foreground) R.string.yes else R.string.no)
        )
        val actions = ArrayList<SheetAction>()
        actions.add(SheetAction(str(R.string.act_stop_service)) { vm.stopService(s.component) })
        actions.add(SheetAction(str(R.string.act_force_stop), danger = true) { vm.forceStop(s.pkg, appLabel) })
        actions.add(SheetAction(str(R.string.act_disable_component), danger = true) {
            confirm(str(R.string.confirm_disable_text, s.cls)) { vm.setComponents(listOf(s.component), false) }
        })
        actions.add(SheetAction(str(R.string.act_open_location)) { vm.openAppLocation(s.pkg) })
        actions.add(SheetAction(str(R.string.act_search_online)) { Intents.searchWeb(ctx, searchQuery(s.cls)) })
        actions.add(SheetAction(str(R.string.act_app_info)) { Intents.appInfo(ctx, s.pkg) })
        setSheet(SheetModel(s.cls.substringAfterLast('.'), appLabel, s.pkg, info, actions))
    }

    fun onInitService(s: InitService) {
        val info = listOf(
            str(R.string.info_state) to s.state,
            str(R.string.info_pid) to (s.pid?.toString() ?: "-")
        )
        val critical = s.name in CRITICAL_PROCESSES
        val actions = ArrayList<SheetAction>()
        if (s.state == "running") {
            actions.add(SheetAction(str(R.string.act_stop), danger = true) {
                if (critical) confirm(str(R.string.confirm_system_text, s.name)) { vm.initService(s.name, "stop") }
                else vm.initService(s.name, "stop")
            })
            actions.add(SheetAction(str(R.string.act_restart)) {
                if (critical) confirm(str(R.string.confirm_system_text, s.name)) { vm.initService(s.name, "restart") }
                else vm.initService(s.name, "restart")
            })
        } else {
            actions.add(SheetAction(str(R.string.act_start)) { vm.initService(s.name, "start") })
        }
        val runningPid = s.pid
        if (runningPid != null) {
            actions.add(SheetAction(str(R.string.act_open_location)) { vm.openProcessLocation(runningPid) })
        }
        actions.add(SheetAction(str(R.string.act_search_online)) { Intents.searchWeb(ctx, searchQuery(s.name)) })
        setSheet(SheetModel(s.name, str(R.string.services_init), null, info, actions))
    }
}
