package com.taskman.root.core

import java.util.Locale

private val WS = Regex("\\s+")
private val INT_RE = Regex("-?\\d+")

// ───────────────────────── sections ─────────────────────────

/** Splits the output of tm_sample into sections ("@@NAME [inline value]" starts a section). */
fun splitSections(lines: List<String>): Map<String, List<String>> {
    val out = LinkedHashMap<String, MutableList<String>>()
    var cur: MutableList<String>? = null
    for (l in lines) {
        if (l.startsWith("@@")) {
            val sp = l.indexOf(' ')
            val name = if (sp < 0) l.substring(2) else l.substring(2, sp)
            val nl = ArrayList<String>()
            if (sp >= 0) nl.add(l.substring(sp + 1))
            out[name] = nl
            cur = nl
        } else {
            cur?.add(l)
        }
    }
    return out
}

// ───────────────────────── CPU ─────────────────────────

data class CpuTimes(
    val user: Long, val nice: Long, val system: Long, val idle: Long,
    val iowait: Long, val irq: Long, val softirq: Long, val steal: Long
) {
    val idleAll: Long get() = idle + iowait
    val total: Long get() = user + nice + system + idle + iowait + irq + softirq + steal
}

class CpuStat(val total: CpuTimes?, val cores: Map<Int, CpuTimes>)

fun parseCpuStat(lines: List<String>): CpuStat {
    var total: CpuTimes? = null
    val cores = HashMap<Int, CpuTimes>()
    for (l in lines) {
        if (!l.startsWith("cpu")) continue
        val t = l.trim().split(WS)
        val nums = t.drop(1).map { it.toLongOrNull() ?: 0L }
        if (nums.size < 4) continue
        val times = CpuTimes(
            nums[0], nums[1], nums[2], nums[3],
            nums.getOrElse(4) { 0L }, nums.getOrElse(5) { 0L },
            nums.getOrElse(6) { 0L }, nums.getOrElse(7) { 0L }
        )
        if (t[0] == "cpu") {
            total = times
        } else {
            val idx = t[0].substring(3).toIntOrNull()
            if (idx != null) cores[idx] = times
        }
    }
    return CpuStat(total, cores)
}

data class CoreFreq(val index: Int, val curKhz: Long, val maxKhz: Long, val online: Boolean)

fun parseFreq(lines: List<String>): List<CoreFreq> {
    val out = ArrayList<CoreFreq>()
    for (l in lines) {
        val p = l.split('|')
        if (p.size < 4) continue
        val idx = p[0].trim().toIntOrNull() ?: continue
        out.add(
            CoreFreq(
                idx,
                p[1].trim().toLongOrNull() ?: 0L,
                p[2].trim().toLongOrNull() ?: 0L,
                p[3].trim() != "0"
            )
        )
    }
    out.sortBy { it.index }
    return out
}

data class CoreLoad(val index: Int, val load: Float, val curKhz: Long, val maxKhz: Long, val online: Boolean)

// ───────────────────────── memory ─────────────────────────

data class MemInfo(
    val totalKb: Long, val freeKb: Long, val availKb: Long, val buffersKb: Long,
    val cachedKb: Long, val shmemKb: Long, val swapTotalKb: Long, val swapFreeKb: Long
) {
    val usedKb: Long get() = (totalKb - availKb).coerceAtLeast(0L)
    val usedPercent: Float get() = if (totalKb > 0) usedKb * 100f / totalKb else 0f
    val swapUsedKb: Long get() = (swapTotalKb - swapFreeKb).coerceAtLeast(0L)
    /** Reclaimable cache: what MemAvailable counts on top of MemFree. */
    val cacheKb: Long get() = (availKb - freeKb).coerceAtLeast(0L)
}

fun parseMemInfo(lines: List<String>): MemInfo? {
    val m = HashMap<String, Long>()
    for (l in lines) {
        val i = l.indexOf(':')
        if (i <= 0) continue
        val v = l.substring(i + 1).trim().split(' ')[0].toLongOrNull() ?: continue
        m[l.substring(0, i)] = v
    }
    val total = m["MemTotal"] ?: return null
    val free = m["MemFree"] ?: 0L
    val buffers = m["Buffers"] ?: 0L
    val cached = m["Cached"] ?: 0L
    val avail = m["MemAvailable"] ?: (free + buffers + cached)
    return MemInfo(
        total, free, avail, buffers, cached, m["Shmem"] ?: 0L,
        m["SwapTotal"] ?: 0L, m["SwapFree"] ?: 0L
    )
}

data class ZramInfo(val origBytes: Long, val comprBytes: Long, val memUsedBytes: Long, val diskSizeBytes: Long) {
    val ratio: Float get() = if (comprBytes > 0) origBytes.toFloat() / comprBytes else 0f
}

/** Section lines: [0] = /sys/block/zram0/mm_stat, [1] = /sys/block/zram0/disksize */
fun parseZram(lines: List<String>): ZramInfo? {
    val mm = lines.getOrNull(0)?.trim().orEmpty()
    if (mm.isEmpty()) return null
    val f = mm.split(WS).map { it.toLongOrNull() ?: 0L }
    if (f.size < 3) return null
    val disk = lines.getOrNull(1)?.trim()?.toLongOrNull() ?: 0L
    return ZramInfo(f[0], f[1], f[2], disk)
}

// ───────────────────────── network ─────────────────────────

enum class NetKind { WIFI, MOBILE, ETHERNET, OTHER }

fun classifyIface(name: String): NetKind {
    val n = name.lowercase(Locale.ROOT)
    return when {
        // 464XLAT, tunnels, tethering, loopback and the IPA/MHI transport devices would double count traffic
        n.startsWith("v4-") || n.startsWith("tun") || n.startsWith("ipsec") || n == "lo" -> NetKind.OTHER
        n.startsWith("rmnet_ipa") || n.startsWith("rmnet_mhi") -> NetKind.OTHER
        n.startsWith("wlan") || n.startsWith("wifi") -> NetKind.WIFI
        n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("pdp") ||
            n.startsWith("ppp") || n.startsWith("wwan") || n.startsWith("rev_rmnet") -> NetKind.MOBILE
        n.startsWith("eth") -> NetKind.ETHERNET
        else -> NetKind.OTHER
    }
}

data class NetTotals(
    val wifiRx: Long, val wifiTx: Long,
    val mobRx: Long, val mobTx: Long,
    val ethRx: Long, val ethTx: Long,
    val wifiIface: String?, val mobIface: String?
)

fun parseNetDev(lines: List<String>): NetTotals? {
    var wRx = 0L; var wTx = 0L; var mRx = 0L; var mTx = 0L; var eRx = 0L; var eTx = 0L
    var wName: String? = null; var wBest = -1L
    var mName: String? = null; var mBest = -1L
    var seen = false
    for (l in lines) {
        val c = l.indexOf(':')
        if (c <= 0) continue
        val name = l.substring(0, c).trim()
        val f = l.substring(c + 1).trim().split(WS)
        if (f.size < 9) continue
        val rx = f[0].toLongOrNull() ?: continue
        val tx = f[8].toLongOrNull() ?: continue
        seen = true
        when (classifyIface(name)) {
            NetKind.WIFI -> {
                wRx += rx; wTx += tx
                if (rx + tx > wBest) { wBest = rx + tx; wName = name }
            }
            NetKind.MOBILE -> {
                mRx += rx; mTx += tx
                if (rx + tx > mBest) { mBest = rx + tx; mName = name }
            }
            NetKind.ETHERNET -> { eRx += rx; eTx += tx }
            NetKind.OTHER -> {}
        }
    }
    return if (seen) NetTotals(wRx, wTx, mRx, mTx, eRx, eTx, wName, mName) else null
}

// ───────────────────────── GPU ─────────────────────────

data class GpuInfo(val loadPercent: Float?, val freqMhz: Float?, val source: String)

private fun firstInt(v: String): Float? = INT_RE.find(v)?.value?.toFloatOrNull()

private fun gpuLoadFrom(path: String, v: String): Float? {
    if (v.isEmpty()) return null
    return when {
        path.endsWith("/gpubusy") -> {
            val n = INT_RE.findAll(v).map { it.value.toDoubleOrNull() ?: 0.0 }.toList()
            if (n.size >= 2 && n[1] > 0) (n[0] * 100.0 / n[1]).toFloat().coerceIn(0f, 100f) else null
        }
        path.endsWith("/load") || path.contains("utilization") || path.endsWith("gpu_busy") ||
            path.endsWith("gpu_busy_percentage") || path.endsWith("gpu_loading") ->
            firstInt(v)?.coerceIn(0f, 100f)
        else -> null
    }
}

private fun toMhz(v: Double): Float = when {
    v >= 1e7 -> (v / 1e6).toFloat()
    v >= 1e4 -> (v / 1e3).toFloat()
    else -> v.toFloat()
}

private fun gpuFreqFrom(path: String, v: String): Float? {
    val name = path.substringAfterLast('/')
    if (name != "cur_freq" && name != "gpuclk" && name != "clock" && name != "gpu_clock") return null
    val d = INT_RE.find(v)?.value?.toDoubleOrNull() ?: return null
    return if (d > 0) toMhz(d) else null
}

/** Lines are "path|value" for every GPU node that exists on this device. */
fun parseGpu(lines: List<String>): GpuInfo? {
    val map = LinkedHashMap<String, String>()
    for (l in lines) {
        val i = l.indexOf('|')
        if (i > 0) map[l.substring(0, i)] = l.substring(i + 1).trim()
    }
    if (map.isEmpty()) return null
    var load: Float? = null
    var src = ""
    for ((p, v) in map) {
        val c = gpuLoadFrom(p, v)
        if (c != null) { load = c; src = p; break }
    }
    var freq: Float? = null
    for ((p, v) in map) {
        val f = gpuFreqFrom(p, v)
        if (f != null) { freq = f; if (src.isEmpty()) src = p; break }
    }
    if (load == null && freq == null) return null
    return GpuInfo(load, freq, src)
}

// ───────────────────────── processes ─────────────────────────

data class RawProc(
    val pid: Int, val ppid: Int, val comm: String, val state: Char,
    val utime: Long, val stime: Long, val nice: Int, val threads: Int,
    val startTicks: Long, val vsizeBytes: Long,
    val rssPages: Long, val sharedPages: Long, val oomAdj: Int
) {
    val ticks: Long get() = utime + stime
}

/** Line format from tm_sample: pid|oom_score_adj|statm|stat */
fun parseProcLine(line: String): RawProc? {
    val parts = line.split('|', limit = 4)
    if (parts.size < 4) return null
    val pid = parts[0].toIntOrNull() ?: return null
    val adj = parts[1].trim().toIntOrNull() ?: 0
    val statm = parts[2].trim().split(' ').filter { it.isNotEmpty() }
    val stat = parts[3]
    val open = stat.indexOf('(')
    val close = stat.lastIndexOf(')')
    if (open < 0 || close < open || close + 2 > stat.length) return null
    val comm = stat.substring(open + 1, close)
    val f = stat.substring(close + 2).trim().split(' ')
    if (f.size < 22) return null
    val rssFromStat = f[21].toLongOrNull() ?: 0L
    val resident = statm.getOrNull(1)?.toLongOrNull() ?: rssFromStat
    val shared = statm.getOrNull(2)?.toLongOrNull() ?: 0L
    return RawProc(
        pid = pid,
        ppid = f[1].toIntOrNull() ?: 0,
        comm = comm,
        state = f[0].firstOrNull() ?: '?',
        utime = f[11].toLongOrNull() ?: 0L,
        stime = f[12].toLongOrNull() ?: 0L,
        nice = f[16].toIntOrNull() ?: 0,
        threads = f[17].toIntOrNull() ?: 1,
        startTicks = f[19].toLongOrNull() ?: 0L,
        vsizeBytes = f[20].toLongOrNull() ?: 0L,
        rssPages = resident,
        sharedPages = shared,
        oomAdj = adj
    )
}

fun isKernelThread(r: RawProc): Boolean = r.pid == 2 || r.ppid == 2

// ───────────────────────── services / startup ─────────────────────────

data class AndroidService(
    val component: String,      // pkg/.Class
    val pkg: String,
    val cls: String,
    val process: String?,
    val pid: Int?,
    val foreground: Boolean
)

private val SVC_HEAD = Regex("\\* ServiceRecord\\{\\S+ u(\\d+) (\\S+?)(?: c:\\S+)?\\}")
private val APP_REC = Regex("app=ProcessRecord\\{\\S+ (\\d+):([^/}\\s]+)")

/** Parses the reduced `dumpsys activity services` output produced by tm_services. */
fun parseAndroidServices(lines: List<String>): List<AndroidService> {
    val out = ArrayList<AndroidService>()
    var comp: String? = null
    var pkg: String? = null
    var proc: String? = null
    var pid: Int? = null
    var fg = false

    fun flush() {
        val c = comp ?: return
        val p = pkg ?: c.substringBefore('/')
        val rawCls = c.substringAfter('/', "")
        val cls = if (rawCls.startsWith(".")) p + rawCls else rawCls
        out.add(AndroidService(c, p, cls, proc, pid, fg))
    }

    for (l in lines) {
        val head = SVC_HEAD.find(l)
        if (head != null) {
            flush()
            comp = head.groupValues[2]
            pkg = null; proc = null; pid = null; fg = false
            continue
        }
        if (comp == null) continue
        val t = l.trim()
        when {
            t.startsWith("packageName=") -> if (pkg == null) pkg = t.substringAfter('=').trim()
            t.startsWith("processName=") -> if (proc == null) proc = t.substringAfter('=').trim()
            t.startsWith("app=") -> {
                if (pid == null) {
                    val m = APP_REC.find(t)
                    if (m != null) {
                        pid = m.groupValues[1].toIntOrNull()
                        if (proc == null) proc = m.groupValues[2]
                    }
                }
            }
            t.startsWith("isForeground=") -> fg = t.startsWith("isForeground=true")
        }
    }
    flush()
    return out
}

data class InitService(val name: String, val state: String, val pid: Int?)

private val PROP_RE = Regex("^\\[init\\.(svc|svc_debug_pid)\\.([^\\]]+)\\]: \\[([^\\]]*)\\]\\s*$")

fun parseInitServices(lines: List<String>): List<InitService> {
    val states = LinkedHashMap<String, String>()
    val pids = HashMap<String, Int>()
    for (l in lines) {
        val m = PROP_RE.find(l.trim()) ?: continue
        val (kind, name, value) = m.destructured
        if (kind == "svc") states[name] = value else value.toIntOrNull()?.let { pids[name] = it }
    }
    return states.map { (n, s) -> InitService(n, s, pids[n]?.takeIf { s == "running" }) }
        .sortedBy { it.name }
}

data class RootModule(
    val id: String, val name: String, val disabled: Boolean, val pendingRemove: Boolean,
    val hasService: Boolean, val hasPostFs: Boolean
)

data class BootScript(val path: String, val executable: Boolean)

class RootStartup(val modules: List<RootModule>, val scripts: List<BootScript>)

fun parseRootStartup(lines: List<String>): RootStartup {
    val mods = ArrayList<RootModule>()
    val scr = ArrayList<BootScript>()
    for (l in lines) {
        if (l.startsWith("MOD|")) {
            val p = l.split('|')
            if (p.size < 7) continue
            mods.add(RootModule(p[1], p[2].ifBlank { p[1] }, p[3] == "1", p[4] == "1", p[5] == "1", p[6] == "1"))
        } else if (l.startsWith("SCR|")) {
            val p = l.split('|')
            if (p.size < 3) continue
            scr.add(BootScript(p[1], p[2] == "1"))
        }
    }
    return RootStartup(mods, scr)
}

/** Key|value lines from tm_props. */
fun parseKeyValues(lines: List<String>): List<Pair<String, String>> =
    lines.mapNotNull {
        val i = it.indexOf('|')
        if (i <= 0) null else it.substring(0, i) to it.substring(i + 1).trim()
    }.filter { it.second.isNotEmpty() }

// ───────────────────────── storage (disk) ─────────────────────────

/** One block device from /proc/diskstats (counters since boot; sectors are always 512 bytes). */
data class DiskDev(
    val name: String,
    val readIos: Long, val readSectors: Long, val readMs: Long,
    val writeIos: Long, val writeSectors: Long, val writeMs: Long,
    /** milliseconds the device had at least one request in flight (iostat's "busy time") */
    val ioMs: Long
)

// Whole flash devices only: partitions (mmcblk0p1, sda1), boot / rpmb areas, device-mapper (dm-*), loop,
// ram and zram devices would count the same bytes twice or are not storage at all.
private val DISK_RE = Regex("^(mmcblk\\d+|sd[a-z]+|nvme\\d+n\\d+|vd[a-z]+)$")

fun isPhysicalDisk(name: String): Boolean = DISK_RE.matches(name)

fun parseDiskStats(lines: List<String>): List<DiskDev> {
    val out = ArrayList<DiskDev>()
    for (l in lines) {
        val f = l.trim().split(WS)
        if (f.size < 13) continue
        val name = f[2]
        if (!isPhysicalDisk(name)) continue
        fun n(i: Int): Long = f[i].toLongOrNull() ?: 0L
        out.add(DiskDev(name, n(3), n(5), n(6), n(7), n(9), n(10), n(12)))
    }
    return out
}

class DiskStats(val devices: List<DiskDev>) {
    val readBytes: Long get() = devices.sumOf { it.readSectors } * 512L
    val writeBytes: Long get() = devices.sumOf { it.writeSectors } * 512L

    /** The device that moved the most data since boot: the main flash chip (userdata lives there). */
    val mainDevice: String? get() = devices.maxByOrNull { it.readSectors + it.writeSectors }?.name
}

data class DiskSpeed(
    val readBps: Double = 0.0,
    val writeBps: Double = 0.0,
    /** like Windows "Active time": share of the interval the busiest device had requests in flight */
    val activePct: Float = 0f,
    /** average time one request took (reading + writing), milliseconds */
    val avgResponseMs: Float = 0f
)

fun diskSpeed(prev: DiskStats?, cur: DiskStats?, dtSec: Double): DiskSpeed {
    if (prev == null || cur == null || dtSec <= 0.0) return DiskSpeed()
    val before = prev.devices.associateBy { it.name }
    var readBytes = 0L
    var writeBytes = 0L
    var ios = 0L
    var ms = 0L
    var busiest = 0.0
    for (d in cur.devices) {
        val p = before[d.name] ?: continue
        readBytes += (d.readSectors - p.readSectors).coerceAtLeast(0L) * 512L
        writeBytes += (d.writeSectors - p.writeSectors).coerceAtLeast(0L) * 512L
        ios += (d.readIos - p.readIos).coerceAtLeast(0L) + (d.writeIos - p.writeIos).coerceAtLeast(0L)
        ms += (d.readMs - p.readMs).coerceAtLeast(0L) + (d.writeMs - p.writeMs).coerceAtLeast(0L)
        val busy = (d.ioMs - p.ioMs).coerceAtLeast(0L) / (dtSec * 1000.0) * 100.0
        if (busy > busiest) busiest = busy
    }
    return DiskSpeed(
        readBps = readBytes / dtSec,
        writeBps = writeBytes / dtSec,
        activePct = busiest.toFloat().coerceIn(0f, 100f),
        avgResponseMs = if (ios > 0) ms.toFloat() / ios else 0f
    )
}

// ───────────────────────── GPU: cores and model (static facts) ─────────────────────────

data class GpuStatic(
    val model: String?,
    /** shader cores present */
    val cores: Int?,
    /** shader cores currently enabled (can be fewer when the kernel parks cores) */
    val enabledCores: Int?,
    val maxMhz: Float?
)

private val CORES_RE = Regex("(\\d+)\\s+cores", RegexOption.IGNORE_CASE)
private val MP_RE = Regex("\\bM[PC](\\d+)\\b")
private val AVAILABLE_MASK_RE = Regex("Available core mask\\s*:\\s*0x([0-9A-Fa-f]+)")
private val JS0_MASK_RE = Regex("Current core mask \\(JS0\\)\\s*:\\s*0x([0-9A-Fa-f]+)")
private val ADRENO_RE = Regex("^([A-Za-z]+)(\\d+)$")

private fun bitCount(hex: String): Int? = hex.toLongOrNull(16)?.let { java.lang.Long.bitCount(it) }

/**
 * Lines are "path|value" from tm_gpuinfo. Mali (kbase) drivers report the shader core count in
 * `gpuinfo` ("Mali-G72 3 cores r0p0 0x6221") and the core bit masks in `core_mask`.
 */
fun parseGpuStatic(lines: List<String>): GpuStatic? {
    var model: String? = null
    var cores: Int? = null
    var enabled: Int? = null
    var maxMhz: Float? = null
    for (l in lines) {
        val i = l.indexOf('|')
        if (i <= 0) continue
        val name = l.substring(0, i).substringAfterLast('/')
        val v = l.substring(i + 1).trim().trimEnd(';').trim()
        if (v.isEmpty()) continue
        when (name) {
            "gpuinfo" -> {
                val first = v.substringBefore(' ')
                if (first.startsWith("Mali", ignoreCase = true)) model = first
                val m = CORES_RE.find(v) ?: MP_RE.find(v)
                val c = m?.groupValues?.get(1)?.toIntOrNull()
                if (c != null && c > 0) cores = c
            }
            "core_mask" -> {
                AVAILABLE_MASK_RE.find(v)?.groupValues?.get(1)?.let { h -> bitCount(h)?.takeIf { it > 0 }?.let { cores = it } }
                JS0_MASK_RE.find(v)?.groupValues?.get(1)?.let { h -> bitCount(h)?.let { enabled = it } }
            }
            "gpu_model" -> {
                val m = ADRENO_RE.find(v)
                model = if (m != null) m.groupValues[1] + " " + m.groupValues[2] else v
            }
            "max_clock", "max_gpuclk", "max_freq" -> {
                val d = INT_RE.find(v)?.value?.toDoubleOrNull()
                if (d != null && d > 0) maxMhz = toMhz(d)
            }
        }
    }
    if (model == null && cores == null && enabled == null && maxMhz == null) return null
    return GpuStatic(model, cores, enabled, maxMhz)
}

// ───────────────────────── per-process disk and per-app network ─────────────────────────

private val IO_RE = Regex("^/proc/(\\d+)/io:(read_bytes|write_bytes):\\s*(\\d+)\\s*$")

/** pid -> read_bytes + write_bytes: what the process really moved to / from storage (not page-cache hits). */
fun parseProcIo(lines: List<String>): Map<Int, Long> {
    val out = HashMap<Int, Long>()
    for (l in lines) {
        val m = IO_RE.find(l) ?: continue
        val pid = m.groupValues[1].toIntOrNull() ?: continue
        val v = m.groupValues[3].toLongOrNull() ?: continue
        out[pid] = (out[pid] ?: 0L) + v
    }
    return out
}

/**
 * uid -> rx + tx bytes since boot from /proc/net/xt_qtaguid/stats (loopback excluded). Returns null when the
 * file is not there: newer kernels count per-app traffic in eBPF maps that a shell cannot read.
 * Columns: idx iface acct_tag_hex uid_tag_int cnt_set rx_bytes rx_packets tx_bytes tx_packets ...
 */
fun parseQtaguid(lines: List<String>): Map<Int, Long>? {
    val out = HashMap<Int, Long>()
    var seen = false
    for (l in lines) {
        val f = l.trim().split(WS)
        if (f.size < 9 || f[0] == "idx") continue
        // Skip loopback and tunnel/transport interfaces: a VPN exposes the same bytes on tun*
        // and on the physical interface, so summing everything would double count them.
        if (classifyIface(f[1]) == NetKind.OTHER) continue
        val uid = f[3].toLongOrNull()?.let { (it and 0xFFFFFFFFL).toInt() } ?: continue
        val rx = f[5].toLongOrNull() ?: continue
        val tx = f[7].toLongOrNull() ?: continue
        seen = true
        out[uid] = (out[uid] ?: 0L) + rx + tx
    }
    return if (seen) out else null
}
