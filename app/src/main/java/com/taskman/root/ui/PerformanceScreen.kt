package com.taskman.root.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.taskman.root.R
import com.taskman.root.core.CoreLoad
import com.taskman.root.core.Fmt
import com.taskman.root.core.GpuStatic
import com.taskman.root.core.SysSample
import com.taskman.root.data.GlInfo
import java.util.Locale

private class Tile(
    val page: PerfPage,
    val title: String,
    val subtitle: String,
    val series: List<List<Float>>,
    val colors: List<Color>,
    val max: Float
)

private fun autoMax(values: List<Float>): Float = maxOf((values.maxOrNull() ?: 0f) * 1.25f, 100_000f)

private fun autoMaxBytes(values: List<Float>): Float = maxOf((values.maxOrNull() ?: 0f) * 1.25f, 1_000_000f)

private fun avgKhz(cores: List<CoreLoad>): Long {
    val f = cores.filter { it.online && it.curKhz > 0 }
    return if (f.isEmpty()) 0L else f.sumOf { it.curKhz } / f.size
}

@Composable
fun PerformanceScreen(vm: MainViewModel) {
    val live by vm.live.collectAsState()
    val page by vm.perfPage.collectAsState()
    val perCore by vm.perCore.collectAsState()
    val gpuName by vm.gpuName.collectAsState()
    val gpuStatic by vm.gpuStatic.collectAsState()
    val gpuGl by vm.gpuGl.collectAsState()
    val speed by vm.speed.collectAsState()
    val sys = live.snap?.sys

    if (sys == null) {
        if (live.error) {
            ErrorBox(stringResource(R.string.error_sampling)) { vm.retry() }
        } else {
            CenterMessage(stringResource(R.string.loading))
        }
        return
    }

    val mem = sys.mem
    val gpu = sys.gpu
    val series = live.series
    val up = "\u2191 "
    val down = "\u2193 "

    val cpuAvg = avgKhz(sys.cores)
    val cpuSub = (if (sys.cpuAvailable) Fmt.pct0(sys.cpuTotal) else "-") +
        (if (cpuAvg > 0) "  " + Fmt.khz(cpuAvg) else "")
    val memSub = if (mem != null) {
        Fmt.kb(mem.usedKb) + "/" + Fmt.kb(mem.totalKb) + " (" + Fmt.pct0(mem.usedPercent) + ")"
    } else "-"
    val gpuSub = if (gpu == null) "-" else {
        (gpu.loadPercent?.let { Fmt.pct0(it) } ?: "") +
            (gpu.freqMhz?.let { "  " + Fmt.mhz(it) } ?: "")
    }
    val disk = sys.disk
    val usage = live.storage
    val storageSub = buildString {
        if (disk != null) append(Fmt.pct0(live.disk.activePct) + "  ")
        if (usage != null) append(Fmt.bytes(usage.usedBytes) + "/" + Fmt.bytes(usage.totalBytes))
        if (disk != null) {
            append("\n" + down + Fmt.byteRate(live.disk.readBps) + "  " + up + Fmt.byteRate(live.disk.writeBps))
        }
        if (isEmpty()) append("-")
    }
    val mobMax = autoMax(series.mobRx + series.mobTx)
    val wifiMax = autoMax(series.wifiRx + series.wifiTx)

    val tiles = listOf(
        Tile(PerfPage.CPU, stringResource(R.string.perf_cpu), cpuSub, listOf(series.cpu), listOf(Palette.cpu), 100f),
        Tile(PerfPage.MEMORY, stringResource(R.string.perf_memory), memSub, listOf(series.mem), listOf(Palette.memory), 100f),
        Tile(PerfPage.STORAGE, stringResource(R.string.perf_storage), storageSub, listOf(series.diskActive), listOf(Palette.storage), 100f),
        Tile(
            PerfPage.MOBILE, stringResource(R.string.perf_mobile),
            down + Fmt.bps(live.mobile.rxBps) + "\n" + up + Fmt.bps(live.mobile.txBps),
            listOf(series.mobRx, series.mobTx), listOf(Palette.mobile, Palette.mobile.copy(alpha = 0.55f)), mobMax
        ),
        Tile(
            PerfPage.WIFI, stringResource(R.string.perf_wifi),
            down + Fmt.bps(live.wifi.rxBps) + "\n" + up + Fmt.bps(live.wifi.txBps),
            listOf(series.wifiRx, series.wifiTx), listOf(Palette.wifi, Palette.wifi.copy(alpha = 0.55f)), wifiMax
        ),
        Tile(PerfPage.GPU, stringResource(R.string.perf_gpu), gpuSub, listOf(series.gpu), listOf(Palette.gpu), 100f)
    )

    val spanSeconds = if (speed.ms > 0) (speed.ms * MainViewModel.HISTORY / 1000L).toInt() else 0

    val detail: @Composable () -> Unit = {
        when (page) {
            PerfPage.CPU -> CpuDetail(live, sys, perCore, { vm.setPerCore(it) }, vm.device, spanSeconds)
            PerfPage.MEMORY -> MemoryDetail(live, sys, spanSeconds)
            PerfPage.STORAGE -> StorageDetail(live, sys, spanSeconds)
            PerfPage.MOBILE -> NetDetail(false, live, sys, spanSeconds)
            PerfPage.WIFI -> NetDetail(true, live, sys, spanSeconds)
            PerfPage.GPU -> GpuDetail(live, sys, gpuName, gpuStatic, gpuGl, spanSeconds)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= 640.dp) {
            Row(Modifier.fillMaxSize()) {
                Column(
                    Modifier.width(260.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(6.dp)
                ) {
                    tiles.forEach { t ->
                        TileView(t, t.page == page, Modifier.fillMaxWidth()) { vm.setPerfPage(t.page) }
                    }
                }
                Column(
                    Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(12.dp)
                ) { detail() }
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                // A vertical 2-column grid keeps all six tiles discoverable without a
                // horizontal strip that hides Wi-Fi/GPU off-screen on a phone.
                Column(Modifier.fillMaxWidth().padding(6.dp)) {
                    tiles.chunked(2).forEach { pair ->
                        Row(Modifier.fillMaxWidth()) {
                            pair.forEach { t ->
                                PhoneTile(t, t.page == page, Modifier.weight(1f)) {
                                    vm.setPerfPage(t.page)
                                }
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
                Column(Modifier.padding(12.dp)) { detail() }
            }
        }
    }
}

@Composable
private fun PhoneTile(t: Tile, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Column(
        modifier
            .padding(4.dp)
            .clip(shape)
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface
            )
            .border(
                1.dp,
                if (selected) t.colors.first() else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                shape
            )
            .clickable(onClick = onClick)
            .padding(8.dp)
    ) {
        Text(
            t.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(4.dp))
        LineGraph(t.series, t.colors, t.max, Modifier.fillMaxWidth().height(46.dp))
        Spacer(Modifier.height(4.dp))
        Text(
            t.subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun TileView(t: Tile, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Row(
        modifier
            .padding(4.dp)
            .clip(shape)
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface
            )
            .border(
                1.dp,
                if (selected) t.colors.first() else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                shape
            )
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LineGraph(t.series, t.colors, t.max, Modifier.width(56.dp).height(46.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(t.title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Text(
                t.subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ───────────────────────── shared bits ─────────────────────────

@Composable
private fun DetailTitle(title: String, subtitle: String) {
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun GraphCaption(left: String, right: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            left,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            right,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SpanCaption(seconds: Int) {
    GraphCaption(
        if (seconds > 0) stringResource(R.string.graph_span, seconds) else "",
        "0"
    )
}

@Composable
private fun Notice(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(12.dp)
    )
}

@Composable
private fun LegendDot(color: Color, text: String) {
    Row(Modifier.padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium)
    }
}

// ───────────────────────── CPU ─────────────────────────

@Composable
private fun CpuDetail(
    live: Live,
    sys: SysSample,
    perCore: Boolean,
    onPerCore: (Boolean) -> Unit,
    device: DeviceInfo,
    spanSeconds: Int
) {
    DetailTitle(stringResource(R.string.perf_cpu), device.soc)
    if (!sys.cpuAvailable) Notice(stringResource(R.string.cpu_unavailable_no_root))

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.cpu_per_core), Modifier.weight(1f))
        Switch(checked = perCore, onCheckedChange = onPerCore)
    }

    if (!perCore) {
        GraphCaption(stringResource(R.string.cpu_utilization), "100")
        LineGraph(listOf(live.series.cpu), listOf(Palette.cpu), 100f, Modifier.fillMaxWidth().height(190.dp))
        SpanCaption(spanSeconds)
    } else {
        sys.cores.withIndex().chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth()) {
                pair.forEach { iv ->
                    CoreCell(iv.value, live.series.cores.getOrNull(iv.index) ?: emptyList(), Modifier.weight(1f))
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        SpanCaption(spanSeconds)
    }

    val online = sys.cores.count { it.online }
    val maxKhz = sys.cores.maxOfOrNull { it.maxKhz } ?: 0L
    val avg = avgKhz(sys.cores)
    StatGrid(
        listOf(
            stringResource(R.string.cpu_utilization) to
                (if (sys.cpuAvailable) Fmt.pct0(sys.cpuTotal) else "-"),
            stringResource(R.string.cpu_speed) to (if (avg > 0) Fmt.khz(avg) else "-"),
            stringResource(R.string.cpu_processes) to sys.processCount.toString(),
            stringResource(R.string.cpu_threads) to sys.threadCount.toString(),
            stringResource(R.string.cpu_uptime) to Fmt.uptime(sys.uptimeSec.toLong()),
            stringResource(R.string.cpu_cores) to (sys.cores.size.toString() + " (" + online + ")"),
            stringResource(R.string.cpu_max_speed) to (if (maxKhz > 0) Fmt.khz(maxKhz) else "-"),
            stringResource(R.string.cpu_arch) to device.abi,
            stringResource(R.string.cpu_system) to device.android,
            stringResource(R.string.cpu_kernel) to device.kernel
        ),
        Modifier.padding(top = 12.dp)
    )
}

@Composable
private fun CoreCell(core: CoreLoad, series: List<Float>, modifier: Modifier) {
    Column(modifier.padding(4.dp)) {
        val head = if (core.online) {
            Fmt.pct0(core.load) + (if (core.curKhz > 0) "  " + Fmt.khz(core.curKhz) else "")
        } else stringResource(R.string.core_offline)
        GraphCaption(stringResource(R.string.core_label, core.index), head)
        LineGraph(listOf(series), listOf(Palette.cpu), 100f, Modifier.fillMaxWidth().height(64.dp))
    }
}

// ───────────────────────── memory ─────────────────────────

@Composable
private fun MemoryDetail(live: Live, sys: SysSample, spanSeconds: Int) {
    val mem = sys.mem
    DetailTitle(stringResource(R.string.perf_memory), if (mem != null) Fmt.kb(mem.totalKb) else "")
    if (mem == null) {
        Notice(stringResource(R.string.no_data))
        return
    }
    GraphCaption(stringResource(R.string.mem_in_use), Fmt.kb(mem.totalKb))
    LineGraph(listOf(live.series.mem), listOf(Palette.memory), 100f, Modifier.fillMaxWidth().height(190.dp))
    SpanCaption(spanSeconds)

    Text(
        stringResource(R.string.mem_composition),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
    )
    val total = mem.totalKb.coerceAtLeast(1L).toFloat()
    Row(Modifier.fillMaxWidth().height(26.dp).border(1.dp, Palette.memory)) {
        Box(Modifier.weight(maxOf(mem.usedKb / total, 0.0001f)).fillMaxHeight().background(Palette.memory))
        Box(Modifier.weight(maxOf(mem.cacheKb / total, 0.0001f)).fillMaxHeight().background(Palette.memory.copy(alpha = 0.4f)))
        Box(Modifier.weight(maxOf(mem.freeKb / total, 0.0001f)).fillMaxHeight())
    }
    Row(Modifier.padding(top = 6.dp)) {
        LegendDot(Palette.memory, stringResource(R.string.mem_in_use))
        LegendDot(Palette.memory.copy(alpha = 0.4f), stringResource(R.string.mem_cached))
        LegendDot(MaterialTheme.colorScheme.outline, stringResource(R.string.mem_free))
    }

    val items = ArrayList<Pair<String, String>>()
    items.add(stringResource(R.string.mem_in_use) to Fmt.kb(mem.usedKb))
    items.add(stringResource(R.string.mem_available) to Fmt.kb(mem.availKb))
    items.add(stringResource(R.string.mem_cached) to Fmt.kb(mem.cacheKb))
    items.add(stringResource(R.string.mem_free) to Fmt.kb(mem.freeKb))
    items.add(
        stringResource(R.string.mem_swap) to
            (if (mem.swapTotalKb > 0) Fmt.kb(mem.swapUsedKb) + " / " + Fmt.kb(mem.swapTotalKb) else "-")
    )
    items.add(stringResource(R.string.mem_total) to Fmt.kb(mem.totalKb))
    val z = sys.zram
    if (z != null) {
        items.add(stringResource(R.string.mem_zram_data) to Fmt.bytes(z.origBytes))
        items.add(stringResource(R.string.mem_zram_used) to Fmt.bytes(z.memUsedBytes))
        items.add(
            stringResource(R.string.mem_zram_ratio) to
                (if (z.ratio > 0f) String.format(Locale.getDefault(), "%.2f : 1", z.ratio) else "-")
        )
        items.add(stringResource(R.string.mem_zram_size) to Fmt.bytes(z.diskSizeBytes))
    }
    StatGrid(items, Modifier.padding(top = 12.dp))
}

// ───────────────────────── storage ─────────────────────────

@Composable
private fun StorageDetail(live: Live, sys: SysSample, spanSeconds: Int) {
    val disk = sys.disk
    val usage = live.storage
    DetailTitle(stringResource(R.string.perf_storage), disk?.mainDevice ?: "")
    if (disk == null) Notice(stringResource(R.string.storage_need_root))

    if (disk != null) {
        GraphCaption(stringResource(R.string.storage_active), "100")
        LineGraph(listOf(live.series.diskActive), listOf(Palette.storage), 100f, Modifier.fillMaxWidth().height(150.dp))
        SpanCaption(spanSeconds)

        val max = autoMaxBytes(live.series.diskRead + live.series.diskWrite)
        GraphCaption(stringResource(R.string.storage_throughput), Fmt.byteRate(max.toDouble()))
        LineGraph(
            listOf(live.series.diskRead, live.series.diskWrite),
            listOf(Palette.storage, Palette.storage.copy(alpha = 0.55f)),
            max,
            Modifier.fillMaxWidth().height(150.dp)
        )
        SpanCaption(spanSeconds)
        Row(Modifier.padding(top = 6.dp)) {
            LegendDot(Palette.storage, stringResource(R.string.storage_read))
            LegendDot(Palette.storage.copy(alpha = 0.55f), stringResource(R.string.storage_write))
        }
    }

    if (usage != null) {
        Text(
            stringResource(R.string.storage_usage),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
        )
        val total = usage.totalBytes.coerceAtLeast(1L).toFloat()
        Row(Modifier.fillMaxWidth().height(26.dp).border(1.dp, Palette.storage)) {
            Box(Modifier.weight(maxOf(usage.usedBytes / total, 0.0001f)).fillMaxHeight().background(Palette.storage))
            Box(Modifier.weight(maxOf(usage.freeBytes / total, 0.0001f)).fillMaxHeight())
        }
        Row(Modifier.padding(top = 6.dp)) {
            LegendDot(Palette.storage, stringResource(R.string.storage_used))
            LegendDot(MaterialTheme.colorScheme.outline, stringResource(R.string.storage_free))
        }
    }

    val items = ArrayList<Pair<String, String>>()
    if (disk != null) {
        val sp = live.disk
        items.add(stringResource(R.string.storage_active) to Fmt.pct0(sp.activePct))
        items.add(stringResource(R.string.storage_response) to Fmt.ms(sp.avgResponseMs))
        items.add(stringResource(R.string.storage_read) to Fmt.byteRate(sp.readBps))
        items.add(stringResource(R.string.storage_write) to Fmt.byteRate(sp.writeBps))
    }
    if (usage != null) {
        items.add(stringResource(R.string.storage_capacity) to Fmt.bytes(usage.totalBytes))
        items.add(stringResource(R.string.storage_used) to Fmt.bytes(usage.usedBytes) + " (" + Fmt.pct0(usage.usedPercent) + ")")
        items.add(stringResource(R.string.storage_free) to Fmt.bytes(usage.freeBytes))
    }
    if (disk != null) {
        items.add(stringResource(R.string.storage_read_total) to Fmt.bytes(disk.readBytes))
        items.add(stringResource(R.string.storage_written_total) to Fmt.bytes(disk.writeBytes))
        items.add(stringResource(R.string.storage_device) to disk.devices.joinToString(", ") { it.name })
    }
    StatGrid(items, Modifier.padding(top = 12.dp))
}

// ───────────────────────── network ─────────────────────────

private fun bandOf(mhz: Int?): String? = when {
    mhz == null -> null
    mhz in 2400..2500 -> "2.4 GHz"
    mhz in 4900..5900 -> "5 GHz"
    mhz >= 5925 -> "6 GHz"
    else -> null
}

@Composable
private fun NetDetail(wifi: Boolean, live: Live, sys: SysSample, spanSeconds: Int) {
    val speed = if (wifi) live.wifi else live.mobile
    val rx = if (wifi) live.series.wifiRx else live.series.mobRx
    val tx = if (wifi) live.series.wifiTx else live.series.mobTx
    val color = if (wifi) Palette.wifi else Palette.mobile
    val net = sys.net
    val adapter = (if (wifi) net?.wifiIface else net?.mobIface) ?: "-"
    val max = autoMax(rx + tx)

    DetailTitle(
        stringResource(if (wifi) R.string.perf_wifi else R.string.perf_mobile),
        adapter
    )
    if (net == null) {
        Notice(stringResource(R.string.no_data))
        return
    }
    GraphCaption(stringResource(R.string.net_throughput), Fmt.bps(max.toDouble()))
    LineGraph(
        listOf(rx, tx), listOf(color, color.copy(alpha = 0.55f)), max,
        Modifier.fillMaxWidth().height(190.dp)
    )
    SpanCaption(spanSeconds)
    Row(Modifier.padding(top = 6.dp)) {
        LegendDot(color, stringResource(R.string.net_receive))
        LegendDot(color.copy(alpha = 0.55f), stringResource(R.string.net_send))
    }

    val items = ArrayList<Pair<String, String>>()
    items.add(stringResource(R.string.net_receive) to Fmt.bps(speed.rxBps))
    items.add(stringResource(R.string.net_send) to Fmt.bps(speed.txBps))
    items.add(
        stringResource(R.string.net_total_rx) to Fmt.bytes(if (wifi) net.wifiRx else net.mobRx)
    )
    items.add(
        stringResource(R.string.net_total_tx) to Fmt.bytes(if (wifi) net.wifiTx else net.mobTx)
    )
    items.add(stringResource(R.string.net_adapter) to adapter)

    if (wifi) {
        val w = live.wifiInfo
        items.add(stringResource(R.string.wifi_ssid) to (w?.ssid ?: "-"))
        items.add(stringResource(R.string.wifi_signal) to (w?.rssiDbm?.let { "$it dBm" } ?: "-"))
        items.add(
            stringResource(R.string.wifi_link_speed) to
                (w?.linkMbps?.takeIf { it > 0 }?.let { Fmt.bps(it * 1_000_000.0) } ?: "-")
        )
        items.add(stringResource(R.string.wifi_band) to (bandOf(w?.freqMhz) ?: "-"))
        items.add(stringResource(R.string.net_ip) to (w?.ip ?: "-"))
    } else {
        val m = live.mobileInfo
        items.add(stringResource(R.string.mobile_operator) to (m?.operator ?: "-"))
        items.add(stringResource(R.string.net_ip) to (m?.ip ?: "-"))
    }
    StatGrid(items, Modifier.padding(top = 12.dp))

    if (wifi) {
        val ctx = LocalContext.current
        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { }
        val hasLocation = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasLocation && live.wifiInfo?.ssid == null) {
            TextButton(onClick = {
                // Android 12+ ignores a request that contains only FINE: ask for both.
                launcher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            }) {
                Text(stringResource(R.string.wifi_grant_location))
            }
        }
    }
}

// ───────────────────────── GPU ─────────────────────────

@Composable
private fun GpuDetail(
    live: Live,
    sys: SysSample,
    name: String?,
    stat: GpuStatic?,
    gl: GlInfo?,
    spanSeconds: Int
) {
    val gpu = sys.gpu
    val title = name ?: stat?.model ?: gl?.renderer ?: ""
    DetailTitle(stringResource(R.string.perf_gpu), title)
    if (gpu == null) {
        Notice(stringResource(R.string.gpu_unavailable))
    } else if (gpu.loadPercent != null) {
        GraphCaption(stringResource(R.string.gpu_utilization), "100")
        LineGraph(listOf(live.series.gpu), listOf(Palette.gpu), 100f, Modifier.fillMaxWidth().height(190.dp))
        SpanCaption(spanSeconds)
    }

    val items = ArrayList<Pair<String, String>>()
    if (gpu != null) {
        items.add(stringResource(R.string.gpu_utilization) to (gpu.loadPercent?.let { Fmt.pct0(it) } ?: "-"))
        items.add(stringResource(R.string.gpu_clock) to (gpu.freqMhz?.let { Fmt.mhz(it) } ?: "-"))
    }

    // Cores: from the driver's sysfs (Mali: shader core count and mask), or from the renderer name.
    val cores = stat?.cores ?: gl?.cores
    val coresText = if (cores == null) {
        "-"
    } else {
        val enabled = stat?.enabledCores
        if (enabled != null && enabled != cores) {
            cores.toString() + " (" + stringResource(R.string.gpu_cores_enabled, enabled) + ")"
        } else {
            cores.toString()
        }
    }
    items.add(stringResource(R.string.gpu_cores) to coresText)

    // Threads: the OpenGL ES compute limits are what the hardware really supports.
    items.add(stringResource(R.string.gpu_threads) to (gl?.maxInvocations?.toString() ?: "-"))
    items.add(stringResource(R.string.gpu_workgroup) to (gl?.maxGroupSize?.joinToString(" \u00D7 ") ?: "-"))
    items.add(stringResource(R.string.gpu_shared_mem) to (gl?.sharedMemBytes?.let { Fmt.bytes(it.toLong()) } ?: "-"))

    val maxMhz = stat?.maxMhz
    items.add(stringResource(R.string.gpu_max_clock) to (if (maxMhz != null) Fmt.mhz(maxMhz) else "-"))
    items.add(stringResource(R.string.gpu_model) to title.ifEmpty { "-" })
    items.add(stringResource(R.string.gpu_vendor) to (gl?.vendor?.ifEmpty { "-" } ?: "-"))
    items.add(stringResource(R.string.gpu_gl) to (gl?.version?.ifEmpty { "-" } ?: "-"))
    if (gpu != null) items.add(stringResource(R.string.gpu_source) to gpu.source)
    StatGrid(items, Modifier.padding(top = 12.dp))

    Text(
        stringResource(R.string.gpu_cores_note),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp)
    )
}
