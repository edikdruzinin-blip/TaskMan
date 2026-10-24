package com.taskman.root.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.taskman.root.R
import com.taskman.root.core.Fmt
import com.taskman.root.core.ProcRow
import com.taskman.root.core.uidName

private class TableCol(val key: SortKey, @StringRes val title: Int, val width: Dp, val right: Boolean)

private val COLS = listOf(
    TableCol(SortKey.NAME, R.string.col_name, 232.dp, false),
    TableCol(SortKey.PID, R.string.col_pid, 66.dp, true),
    TableCol(SortKey.STATE, R.string.col_state, 56.dp, false),
    TableCol(SortKey.USER, R.string.col_user, 92.dp, false),
    TableCol(SortKey.CPU, R.string.col_cpu, 68.dp, true),
    TableCol(SortKey.MEMORY, R.string.col_memory, 92.dp, true),
    TableCol(SortKey.THREADS, R.string.col_threads, 70.dp, true),
    TableCol(SortKey.NICE, R.string.col_nice, 56.dp, true),
    TableCol(SortKey.OOM, R.string.col_oom, 64.dp, true)
)

private val TABLE_WIDTH: Dp = COLS.fold(0.dp) { acc, c -> acc + c.width }

private fun comparator(s: SortState): Comparator<ProcRow> {
    val base: Comparator<ProcRow> = when (s.key) {
        SortKey.NAME -> compareBy<ProcRow> { it.name.lowercase() }
        SortKey.PID -> compareBy<ProcRow> { it.pid }
        SortKey.STATE -> compareBy<ProcRow> { it.state }
        SortKey.USER -> compareBy<ProcRow> { uidName(it.uid) }
        SortKey.MEMORY -> compareBy<ProcRow>({ it.privBytes }, { it.cpu })
        SortKey.THREADS -> compareBy<ProcRow> { it.threads }
        SortKey.NICE -> compareBy<ProcRow> { it.nice }
        SortKey.OOM -> compareBy<ProcRow> { it.oomAdj }
        SortKey.CPU -> compareBy<ProcRow>({ it.cpu }, { it.privBytes })
        SortKey.DISK -> compareBy<ProcRow>({ it.diskBps }, { it.cpu })
        SortKey.NETWORK -> compareBy<ProcRow>({ it.cpu }, { it.privBytes })
    }
    return if (s.desc) base.reversed() else base
}

private fun matchesProc(p: ProcRow, q: String): Boolean {
    if (q.isEmpty()) return true
    return p.name.lowercase().contains(q) ||
        p.pid.toString() == q ||
        p.cmdline.lowercase().contains(q) ||
        uidName(p.uid).lowercase().contains(q)
}

@Composable
fun DetailsScreen(vm: MainViewModel, actions: UiActions) {
    val data by vm.listData.collectAsState()
    val sort by vm.detailsSort.collectAsState()
    val query by vm.query.collectAsState()
    val showKernel by vm.showKernel.collectAsState()

    val list = remember(data.procs, sort, query, showKernel) {
        val q = query.trim().lowercase()
        data.procs
            .filter { (showKernel || !it.kernel) && matchesProc(it, q) }
            .sortedWith(comparator(sort))
    }
    val totalBytes: Long = (data.sys?.mem?.totalKb ?: 0L) * 1024L
    val hScroll = rememberScrollState()

    // refreshed every few seconds (see ListData) and standing still while the list is being scrolled
    val listState = rememberLazyListState()
    val scrolling = listState.isScrollInProgress
    var shown by remember { mutableStateOf(list) }
    LaunchedEffect(list, scrolling) {
        if (!scrolling) shown = list
    }

    if (!data.ready) {
        if (data.error) {
            ErrorBox(stringResource(R.string.error_sampling)) { vm.retry() }
        } else {
            CenterMessage(stringResource(R.string.loading))
        }
        return
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val phone = maxWidth < 600.dp
        if (phone) {
            Column(Modifier.fillMaxSize()) {
                if (data.error) {
                    ErrorBanner(stringResource(R.string.error_sampling), { vm.retry() }, Modifier.fillMaxWidth())
                }
                SortBar(
                    current = sort,
                    keys = listOf(
                        SortKey.NAME, SortKey.PID, SortKey.STATE, SortKey.USER, SortKey.CPU,
                        SortKey.MEMORY, SortKey.THREADS, SortKey.NICE, SortKey.OOM
                    ),
                    onSort = { vm.sortDetails(it) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                )
                if (shown.isEmpty()) {
                    CenterMessage(stringResource(R.string.empty_list))
                } else {
                    LazyColumn(Modifier.fillMaxSize(), state = listState) {
                        items(shown, key = { it.pid }) { p ->
                            val app = vm.appOf(p)
                            PhoneDetailsRow(p, app?.pkg, vm.ownPid) {
                                actions.onProc(p, app?.pkg, app?.label ?: p.name)
                            }
                        }
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxSize().horizontalScroll(hScroll)) {
                if (data.error) {
                    ErrorBanner(
                        stringResource(R.string.error_sampling),
                        { vm.retry() },
                        Modifier.width(TABLE_WIDTH)
                    )
                }
                Row(Modifier.width(TABLE_WIDTH).background(MaterialTheme.colorScheme.surface)) {
                    COLS.forEach { c ->
                        val arrow = if (sort.key == c.key) (if (sort.desc) " \u25BC" else " \u25B2") else ""
                        Box(
                            Modifier
                                .width(c.width)
                                .clickable { vm.sortDetails(c.key) }
                                .padding(horizontal = 6.dp, vertical = 12.dp),
                            contentAlignment = if (c.right) Alignment.CenterEnd else Alignment.CenterStart
                        ) {
                            Text(
                                stringResource(c.title) + arrow,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                }
                HorizontalDivider(Modifier.width(TABLE_WIDTH))
                LazyColumn(Modifier.width(TABLE_WIDTH).weight(1f), state = listState) {
                    items(shown, key = { it.pid }) { p ->
                        DetailsRow(p, vm.appOf(p)?.pkg, totalBytes, vm.ownPid) {
                            val app = vm.appOf(p)
                            actions.onProc(p, app?.pkg, app?.label ?: p.name)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PhoneDetailsRow(p: ProcRow, pkg: String?, ownPid: Int, onClick: () -> Unit) {
    val threadsLabel = stringResource(R.string.col_threads)
    val niceLabel = stringResource(R.string.col_nice)
    val oomLabel = stringResource(R.string.col_oom)
    val line1 = "PID ${p.pid} · ${uidName(p.uid)} · ${p.state}"
    val line2 = "$threadsLabel ${p.threads} · $niceLabel ${p.nice} · $oomLabel ${p.oomAdj}"
    val cpuText = stringResource(R.string.col_cpu) + " " + Fmt.pct(p.cpu)
    val memText = stringResource(R.string.col_memory) + " " + Fmt.bytes(p.privBytes)

    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(pkg, 24.dp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                p.name + if (p.pid == ownPid) " *" else "",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                line1,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                line2,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(cpuText, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            Text(
                memText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
}

@Composable
private fun DetailsRow(p: ProcRow, pkg: String?, totalBytes: Long, ownPid: Int, onClick: () -> Unit) {
    val memFraction = if (totalBytes > 0) p.privBytes.toFloat() / (totalBytes * 0.12f) else 0f
    Row(
        Modifier.width(TABLE_WIDTH).height(38.dp).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            Modifier.width(COLS[0].width).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIcon(pkg, 22.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                p.name + if (p.pid == ownPid) " *" else "",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Cell(p.pid.toString(), COLS[1])
        Cell(p.state.toString(), COLS[2])
        Cell(uidName(p.uid), COLS[3])
        Cell(Fmt.pct(p.cpu), COLS[4], p.cpu / 25f)
        Cell(Fmt.bytes(p.privBytes), COLS[5], memFraction)
        Cell(p.threads.toString(), COLS[6])
        Cell(p.nice.toString(), COLS[7])
        Cell(p.oomAdj.toString(), COLS[8])
    }
    HorizontalDivider(Modifier.width(TABLE_WIDTH), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
}

@Composable
private fun Cell(text: String, col: TableCol, heat: Float = 0f) {
    val bg = if (heat > 0f) Palette.heat.copy(alpha = heat.coerceIn(0f, 1f) * 0.6f) else Color.Transparent
    Box(
        Modifier.width(col.width).fillMaxHeight().background(bg).padding(horizontal = 6.dp),
        contentAlignment = if (col.right) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
