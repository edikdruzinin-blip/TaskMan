package com.taskman.root.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.taskman.root.R
import com.taskman.root.core.Category
import com.taskman.root.core.Fmt
import com.taskman.root.core.ProcGroup
import com.taskman.root.core.ProcRow

private val CPU_W = 62.dp
private val MEM_W = 84.dp
private val DISK_W = 90.dp
private val NET_W = 96.dp
private val STATS_W = CPU_W + MEM_W + DISK_W + NET_W

/** expand arrow (36 dp) + app icon (28 dp) in front of the name */
private val LEFT_FIXED = 64.dp
private val NAME_MIN = 104.dp

/** The numeric columns share one scroll position, so header and rows slide together. */
private class StatsLayout(val width: Dp, val scroll: ScrollState)

private sealed interface ListRow {
    val key: String

    data class Header(
        val cat: Category,
        val count: Int,
        val collapsed: Boolean
    ) : ListRow {
        override val key: String get() = "h:" + cat.name
    }

    data class Group(val g: ProcGroup, val expanded: Boolean) : ListRow {
        override val key: String get() = "g:" + g.key
    }

    data class Child(val p: ProcRow, val g: ProcGroup) : ListRow {
        override val key: String get() = "p:" + p.pid
    }
}

private fun compareGroups(s: SortState): Comparator<ProcGroup> {
    val base: Comparator<ProcGroup> = when (s.key) {
        SortKey.NAME -> compareBy<ProcGroup> { it.label.lowercase() }
        SortKey.MEMORY -> compareBy<ProcGroup>({ it.priv }, { it.cpu })
        SortKey.DISK -> compareBy<ProcGroup>({ it.diskBps }, { it.cpu })
        SortKey.NETWORK -> compareBy<ProcGroup>({ it.netBps ?: -1.0 }, { it.cpu })
        else -> compareBy<ProcGroup>({ it.cpu }, { it.priv })
    }
    return if (s.desc) base.reversed() else base
}

private fun matches(g: ProcGroup, q: String): Boolean {
    if (q.isEmpty()) return true
    if (g.label.lowercase().contains(q)) return true
    val pkg = g.pkg
    if (pkg != null && pkg.lowercase().contains(q)) return true
    return g.procs.any { it.name.lowercase().contains(q) || it.pid.toString() == q }
}

private fun buildRows(
    groups: List<ProcGroup>,
    sort: SortState,
    query: String,
    showKernel: Boolean,
    expanded: Set<String>,
    collapsed: Set<Category>
): List<ListRow> {
    val q = query.trim().lowercase()
    val filtered = groups.filter { g -> (showKernel || !g.procs.first().kernel) && matches(g, q) }
    val cmp = compareGroups(sort)
    val out = ArrayList<ListRow>()
    for (cat in Category.values()) {
        val list = filtered.filter { it.category == cat }.sortedWith(cmp)
        if (list.isEmpty()) continue
        val isCollapsed = cat in collapsed
        out.add(ListRow.Header(cat, list.size, isCollapsed))
        if (isCollapsed) continue
        for (g in list) {
            val isExpanded = g.key in expanded
            out.add(ListRow.Group(g, isExpanded))
            if (isExpanded && g.procs.size > 1) {
                for (p in g.procs) out.add(ListRow.Child(p, g))
            }
        }
    }
    return out
}

/** How much of the heat map a process' private memory fills: 12 % of all RAM is "fully hot". */
private fun memFraction(priv: Long, totalBytes: Long): Float =
    if (totalBytes > 0) priv.toFloat() / (totalBytes * 0.12f) else 0f

@Composable
fun ProcessesScreen(vm: MainViewModel, actions: UiActions) {
    val data by vm.listData.collectAsState()
    val sort by vm.procSort.collectAsState()
    val query by vm.query.collectAsState()
    val showKernel by vm.showKernel.collectAsState()
    val expanded by vm.expanded.collectAsState()
    val collapsed by vm.collapsedCats.collectAsState()

    val rows = remember(data.groups, sort, query, showKernel, expanded, collapsed) {
        buildRows(data.groups, sort, query, showKernel, expanded, collapsed)
    }
    // The data itself changes only every few seconds (see ListData); on top of that the list stands still
    // while it is being scrolled, so that a row cannot move away just before it is tapped.
    val listState = rememberLazyListState()
    val scrolling = listState.isScrollInProgress
    var shown by remember { mutableStateOf(rows) }
    LaunchedEffect(rows, scrolling) {
        if (!scrolling) shown = rows
    }
    val sys = data.sys
    val cpuTotal: Float? = if (sys != null && sys.cpuAvailable) sys.cpuTotal else null
    val memPct: Float? = sys?.mem?.usedPercent
    val totalBytes: Long = (sys?.mem?.totalKb ?: 0L) * 1024L
    // header totals, like the Windows Task Manager: disk = "active time", network = traffic of the whole phone
    val diskPct: Float? = if (sys?.disk != null) data.diskActivePct else null
    val netText = Fmt.netRate(data.netTotalBits / 8.0)
    val netFraction = (data.netTotalBits / 20_000_000.0).toFloat()
    val ioOk = sys?.ioAvailable == true

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val phone = maxWidth < 600.dp
        val room = maxWidth - LEFT_FIXED - NAME_MIN
        val statsWidth = if (room < 120.dp) 120.dp else if (room > STATS_W) STATS_W else room
        val statsScroll = rememberScrollState()
        val stats = remember(statsWidth, statsScroll) { StatsLayout(statsWidth, statsScroll) }

        Column(Modifier.fillMaxSize()) {
            if (phone) {
                // A phone is too narrow for name + four columns: show the totals as heat-map tiles (a tap
                // sorts by that column), the rows as cards, and keep the full sort list in a menu.
                PhoneSummary(sort, cpuTotal, memPct, diskPct, netText, netFraction) { vm.sortProcesses(it) }
                SortBar(
                    current = sort,
                    keys = listOf(SortKey.NAME, SortKey.CPU, SortKey.MEMORY, SortKey.DISK, SortKey.NETWORK),
                    onSort = { vm.sortProcesses(it) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                )
                HorizontalDivider()
            } else {
                TableHeader(sort, cpuTotal, memPct, diskPct, netText, netFraction, stats) { vm.sortProcesses(it) }
                HorizontalDivider()
            }
            if (ioOk && sys?.uidNetBps == null) {
                Text(
                    stringResource(R.string.net_unsupported_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
            if (data.error && shown.isNotEmpty()) {
                ErrorBanner(stringResource(R.string.error_sampling), { vm.retry() }, Modifier.fillMaxWidth())
            }
            if (shown.isEmpty()) {
                if (data.error) {
                    ErrorBox(stringResource(R.string.error_sampling)) { vm.retry() }
                } else {
                    CenterMessage(
                        if (!data.ready) stringResource(R.string.loading)
                        else stringResource(R.string.empty_list)
                    )
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(bottom = 8.dp)
                ) {
                    items(shown, key = { it.key }) { r ->
                        when (r) {
                            is ListRow.Header -> CategoryHeader(r) { vm.toggleCategory(r.cat) }
                            is ListRow.Group -> if (phone) {
                                PhoneGroupRow(
                                    g = r.g,
                                    expanded = r.expanded,
                                    totalBytes = totalBytes,
                                    ioOk = ioOk,
                                    onToggle = { vm.toggleExpanded(r.g.key) },
                                    onClick = { actions.onGroup(r.g) }
                                )
                            } else {
                                GroupRow(
                                    g = r.g,
                                    expanded = r.expanded,
                                    totalBytes = totalBytes,
                                    ioOk = ioOk,
                                    stats = stats,
                                    onToggle = { vm.toggleExpanded(r.g.key) },
                                    onClick = { actions.onGroup(r.g) }
                                )
                            }
                            is ListRow.Child -> if (phone) {
                                PhoneChildRow(r.p, r.g.pkg, totalBytes, ioOk) {
                                    actions.onProc(r.p, r.g.pkg, r.g.label + " / " + r.p.name)
                                }
                            } else {
                                ChildRow(r.p, r.g.pkg, totalBytes, ioOk, stats) {
                                    actions.onProc(r.p, r.g.pkg, r.g.label + " / " + r.p.name)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Phone: the four totals as heat-map tiles. A tap sorts the list by that column. */
@Composable
private fun PhoneSummary(
    sort: SortState,
    cpu: Float?,
    mem: Float?,
    disk: Float?,
    net: String,
    netFraction: Float,
    onSort: (SortKey) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        SummaryTile(
            stringResource(R.string.col_cpu),
            if (cpu != null) Fmt.pct0(cpu) else "-",
            Palette.cpu,
            (cpu ?: 0f) / 100f,
            sort.key == SortKey.CPU,
            sort.desc,
            { onSort(SortKey.CPU) },
            Modifier.weight(1f)
        )
        SummaryTile(
            stringResource(R.string.col_memory),
            if (mem != null) Fmt.pct0(mem) else "-",
            Palette.memory,
            (mem ?: 0f) / 100f,
            sort.key == SortKey.MEMORY,
            sort.desc,
            { onSort(SortKey.MEMORY) },
            Modifier.weight(1f)
        )
        SummaryTile(
            stringResource(R.string.col_disk),
            if (disk != null) Fmt.pct0(disk) else "-",
            Palette.storage,
            (disk ?: 0f) / 100f,
            sort.key == SortKey.DISK,
            sort.desc,
            { onSort(SortKey.DISK) },
            Modifier.weight(1f)
        )
        SummaryTile(
            stringResource(R.string.col_network),
            net,
            Palette.wifi,
            netFraction,
            sort.key == SortKey.NETWORK,
            sort.desc,
            { onSort(SortKey.NETWORK) },
            Modifier.weight(1f)
        )
    }
}

@Composable
private fun SummaryTile(
    label: String,
    value: String,
    color: Color,
    fraction: Float,
    sorted: Boolean,
    desc: Boolean,
    onClick: () -> Unit,
    modifier: Modifier
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(6.dp)
    Column(
        modifier
            .clip(shape)
            .background(cs.surfaceVariant.copy(alpha = 0.55f))
            .background(heatBackground(fraction))
            .border(1.dp, if (sorted) cs.primary else Color.Transparent, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(Modifier.width(5.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = if (fraction >= 0.3f) cs.onSurface else cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (sorted) SortChevron(desc)
        }
        Text(
            value,
            style = if (value.length <= 7) MaterialTheme.typography.titleMedium
            else MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Phone row: name + package on the left, CPU and memory as heat-map pills on the right. */
@Composable
private fun PhoneGroupRow(
    g: ProcGroup,
    expanded: Boolean,
    totalBytes: Long,
    ioOk: Boolean,
    onToggle: () -> Unit,
    onClick: () -> Unit
) {
    val expandable = g.procs.size > 1
    val diskLabel = stringResource(R.string.col_disk)
    val netLabel = stringResource(R.string.col_network)
    val cpuLabel = stringResource(R.string.col_cpu)
    val memLabel = stringResource(R.string.col_memory)
    val net = g.netBps
    val metrics = ArrayList<String>(2)
    if (ioOk) metrics += "$diskLabel ${Fmt.diskRate(g.diskBps.toDouble())}"
    if (net != null) metrics += "$netLabel ${Fmt.netRate(net)}"

    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(onClick = onClick)
            .padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(44.dp).clickable(enabled = expandable, onClick = onToggle),
            contentAlignment = Alignment.Center
        ) {
            if (expandable) ExpandChevron(expanded)
        }
        AppIcon(g.pkg, 32.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                g.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                g.subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (metrics.isNotEmpty()) {
                Text(
                    metrics.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            HeatPill(cpuLabel, Fmt.pct(g.cpu), g.cpu / 25f)
            HeatPill(memLabel, Fmt.bytes(g.priv), memFraction(g.priv, totalBytes))
        }
    }
    HorizontalDivider(Modifier.padding(start = 48.dp))
}

@Composable
private fun PhoneChildRow(
    p: ProcRow,
    pkg: String?,
    totalBytes: Long,
    ioOk: Boolean,
    onClick: () -> Unit
) {
    val diskLabel = stringResource(R.string.col_disk)
    val threadsLabel = stringResource(R.string.col_threads)
    val cpuLabel = stringResource(R.string.col_cpu)
    val memLabel = stringResource(R.string.col_memory)
    val second = buildString {
        append("PID ").append(p.pid)
        append(" · ").append(threadsLabel).append(' ').append(p.threads)
        if (ioOk) {
            append(" · ").append(diskLabel).append(' ').append(Fmt.diskRate(p.diskBps.toDouble()))
        }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(start = 48.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(pkg, 20.dp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                p.name,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                second,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            HeatPill(cpuLabel, Fmt.pct(p.cpu), p.cpu / 25f)
            HeatPill(memLabel, Fmt.bytes(p.privBytes), memFraction(p.privBytes, totalBytes))
        }
    }
    HorizontalDivider(Modifier.padding(start = 48.dp))
}

@Composable
private fun TableHeader(
    sort: SortState,
    cpu: Float?,
    mem: Float?,
    disk: Float?,
    net: String,
    netFraction: Float,
    stats: StatsLayout,
    onSort: (SortKey) -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom
    ) {
        Spacer(Modifier.width(LEFT_FIXED))
        HeaderCell(
            modifier = Modifier.weight(1f).padding(start = 8.dp),
            label = stringResource(R.string.col_name),
            value = null,
            fraction = 0f,
            sort = sort,
            key = SortKey.NAME,
            align = Alignment.Start,
            onSort = onSort
        )
        Row(
            Modifier.width(stats.width).horizontalScroll(stats.scroll),
            verticalAlignment = Alignment.Bottom
        ) {
            HeaderCell(
                modifier = Modifier.width(CPU_W),
                label = stringResource(R.string.col_cpu),
                value = if (cpu != null) Fmt.pct0(cpu) else "-",
                fraction = (cpu ?: 0f) / 100f,
                sort = sort,
                key = SortKey.CPU,
                align = Alignment.End,
                onSort = onSort
            )
            HeaderCell(
                modifier = Modifier.width(MEM_W),
                label = stringResource(R.string.col_memory),
                value = if (mem != null) Fmt.pct0(mem) else "-",
                fraction = (mem ?: 0f) / 100f,
                sort = sort,
                key = SortKey.MEMORY,
                align = Alignment.End,
                onSort = onSort
            )
            HeaderCell(
                modifier = Modifier.width(DISK_W),
                label = stringResource(R.string.col_disk),
                value = if (disk != null) Fmt.pct0(disk) else "-",
                fraction = (disk ?: 0f) / 100f,
                sort = sort,
                key = SortKey.DISK,
                align = Alignment.End,
                onSort = onSort
            )
            HeaderCell(
                modifier = Modifier.width(NET_W),
                label = stringResource(R.string.col_network),
                value = net,
                fraction = netFraction,
                sort = sort,
                key = SortKey.NETWORK,
                align = Alignment.End,
                onSort = onSort
            )
        }
    }
}

/**
 * Column header like in Windows 11: the sorted column has a small chevron above it, the big number is the
 * total load and the cell is tinted by that load.
 */
@Composable
private fun HeaderCell(
    modifier: Modifier,
    label: String,
    value: String?,
    fraction: Float,
    sort: SortState,
    key: SortKey,
    align: Alignment.Horizontal,
    onSort: (SortKey) -> Unit
) {
    Column(
        modifier
            .background(heatBackground(fraction))
            .clickable { onSort(key) }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = align
    ) {
        if (sort.key == key) SortChevron(sort.desc) else Spacer(Modifier.height(12.dp))
        if (value != null) {
            Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 1)
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (fraction >= 0.3f) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

@Composable
private fun CategoryHeader(r: ListRow.Header, onClick: () -> Unit) {
    val title = when (r.cat) {
        Category.APPS -> R.string.cat_apps
        Category.BACKGROUND -> R.string.cat_background
        Category.SYSTEM -> R.string.cat_system
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 42.dp)
            .padding(start = 14.dp, end = 12.dp, top = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ExpandChevron(expanded = !r.collapsed, size = 14.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            stringResource(title) + " (" + r.count + ")",
            style = MaterialTheme.typography.titleSmall
        )
    }
}

/**
 * The four numeric columns of a row (CPU, memory, disk, network). Disk is read from /proc/<pid>/io, so it needs
 * root; network is counted per app (uid) by the kernel, so only app rows have it.
 */
@Composable
private fun StatCells(
    cpu: Float,
    priv: Long,
    totalBytes: Long,
    diskBps: Float,
    ioOk: Boolean,
    netBps: Double?,
    stats: StatsLayout
) {
    Row(Modifier.width(stats.width).fillMaxHeight().horizontalScroll(stats.scroll)) {
        HeatCell(Fmt.pct(cpu), cpu / 25f, CPU_W)
        HeatCell(Fmt.bytes(priv), memFraction(priv, totalBytes), MEM_W)
        HeatCell(
            if (ioOk) Fmt.diskRate(diskBps.toDouble()) else "-",
            diskBps / 30_000_000f,
            DISK_W
        )
        HeatCell(
            if (netBps != null) Fmt.netRate(netBps) else "-",
            if (netBps != null) (netBps * 8.0 / 20_000_000.0).toFloat() else 0f,
            NET_W
        )
    }
}

@Composable
private fun GroupRow(
    g: ProcGroup,
    expanded: Boolean,
    totalBytes: Long,
    ioOk: Boolean,
    stats: StatsLayout,
    onToggle: () -> Unit,
    onClick: () -> Unit
) {
    val expandable = g.procs.size > 1
    Row(
        Modifier.fillMaxWidth().height(54.dp).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(36.dp).clickable(enabled = expandable, onClick = onToggle),
            contentAlignment = Alignment.Center
        ) {
            if (expandable) ExpandChevron(expanded)
        }
        AppIcon(g.pkg, 28.dp)
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Text(
                g.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                g.subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        StatCells(g.cpu, g.priv, totalBytes, g.diskBps, ioOk, g.netBps, stats)
    }
    HorizontalDivider()
}

@Composable
private fun ChildRow(
    p: ProcRow,
    pkg: String?,
    totalBytes: Long,
    ioOk: Boolean,
    stats: StatsLayout,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().height(42.dp).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(Modifier.width(40.dp))
        AppIcon(pkg, 20.dp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                p.name,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "PID " + p.pid,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // the kernel counts network per app (uid), not per process: nothing to show for a single process
        StatCells(p.cpu, p.privBytes, totalBytes, p.diskBps, ioOk, null, stats)
    }
    HorizontalDivider()
}
