package com.taskman.root.core

data class ProcRow(
    val pid: Int,
    val ppid: Int,
    val name: String,
    val cmdline: String,
    val comm: String,
    val uid: Int,
    val state: Char,
    val threads: Int,
    val nice: Int,
    val oomAdj: Int,
    val rssBytes: Long,
    val privBytes: Long,
    val vsizeBytes: Long,
    /** percent of TOTAL cpu capacity (all cores), like the Windows Task Manager */
    val cpu: Float,
    val ageSec: Long,
    val kernel: Boolean,
    /** bytes per second this process moved to / from storage (average since the previous sample) */
    val diskBps: Float = 0f
)

data class SysSample(
    val uptimeSec: Double,
    val cpuAvailable: Boolean,
    val cpuTotal: Float,
    val cores: List<CoreLoad>,
    val mem: MemInfo?,
    val zram: ZramInfo?,
    val net: NetTotals?,
    val gpu: GpuInfo?,
    val disk: DiskStats?,
    /** /proc/<pid>/io could be read (root) */
    val ioAvailable: Boolean,
    /** uid -> network bytes per second (rx + tx); null when the kernel has no per-app counters */
    val uidNetBps: Map<Int, Double>?,
    val processCount: Int,
    val threadCount: Int
)

class Snapshot(val procs: List<ProcRow>, val sys: SysSample)

/**
 * Stateful differ. Feed it the sections of every tm_sample run; it keeps the previous counters
 * and returns CPU percentages. Identity (uid + command line) of a process is fetched only once
 * per (pid, start time) through [build]'s `fetchIdent` callback.
 */
class SnapshotEngine(
    private val pageSize: Long,
    private val hz: Long,
    private val fallbackCores: Int
) {
    private class Ident(val startTicks: Long, val uid: Int, val cmdline: String)

    private var prevTotal: CpuTimes? = null
    private var prevCores: Map<Int, CpuTimes> = emptyMap()
    private var prevUptime: Double = -1.0
    private var prevTicks: Map<Int, Long> = emptyMap()
    private var prevStart: Map<Int, Long> = emptyMap()
    private var prevIo: Map<Int, Long> = emptyMap()
    private var prevUidNet: Map<Int, Long>? = null
    private val idents = HashMap<Int, Ident>()

    fun reset() {
        prevTotal = null
        prevCores = emptyMap()
        prevUptime = -1.0
        prevTicks = emptyMap()
        prevStart = emptyMap()
        prevIo = emptyMap()
        prevUidNet = null
        idents.clear()
    }

    fun build(sections: Map<String, List<String>>, fetchIdent: (List<Int>) -> List<String>): Snapshot? {
        val uptime = sections["UP"]?.firstOrNull()?.trim()?.toDoubleOrNull() ?: 0.0
        val raws = sections["PROC"].orEmpty().mapNotNull { parseProcLine(it) }
        if (raws.isEmpty() && sections["CPU"].isNullOrEmpty() && sections["MEM"].isNullOrEmpty()) return null

        // ── identity (uid / command line), fetched once per new process ──
        val missing = raws.filter { !isKernelThread(it) && idents[it.pid]?.startTicks != it.startTicks }.map { it.pid }
        if (missing.isNotEmpty()) {
            val startOf = HashMap<Int, Long>()
            for (r in raws) startOf[r.pid] = r.startTicks
            val answered = HashSet<Int>()
            for (l in fetchIdent(missing)) {
                val p = l.split('|', limit = 3)
                if (p.size < 3) continue
                val pid = p[0].toIntOrNull() ?: continue
                val st = startOf[pid] ?: continue
                idents[pid] = Ident(st, p[1].trim().toIntOrNull() ?: 0, p[2].trim())
                answered.add(pid)
            }
            // Do not cache failures: a process that was unreadable for one sample (transient
            // timeout, race with exit) must be retried on the next one.
            for (pid in missing) {
                if (pid !in answered) idents.remove(pid)
            }
        }
        val alive = HashSet<Int>()
        for (r in raws) alive.add(r.pid)
        idents.keys.retainAll(alive)

        // ── cpu ──
        val stat = parseCpuStat(sections["CPU"].orEmpty())
        val freq = parseFreq(sections["FREQ"].orEmpty())
        val coreCount = when {
            freq.isNotEmpty() -> freq.size
            stat.cores.isNotEmpty() -> stat.cores.size
            else -> fallbackCores
        }.coerceAtLeast(1)

        val curTotal = stat.total
        val pt = prevTotal
        val dUptime = if (prevUptime >= 0) uptime - prevUptime else 0.0

        var cpuTotal = 0f
        var capTicks = 0.0
        if (curTotal != null && pt != null) {
            val dt = curTotal.total - pt.total
            if (dt > 0) {
                capTicks = dt.toDouble()
                val dIdle = curTotal.idleAll - pt.idleAll
                cpuTotal = ((1.0 - dIdle.toDouble() / dt) * 100.0).toFloat().coerceIn(0f, 100f)
            }
        }
        if (capTicks <= 0.0 && dUptime > 0) capTicks = dUptime * hz * coreCount

        val freqByIdx = freq.associateBy { it.index }
        val indices = (freq.map { it.index } + stat.cores.keys).distinct().sorted()
        val cores = indices.map { i ->
            val cur = stat.cores[i]
            val prev = prevCores[i]
            var load = 0f
            if (cur != null && prev != null) {
                val dt = cur.total - prev.total
                if (dt > 0) {
                    load = ((1.0 - (cur.idleAll - prev.idleAll).toDouble() / dt) * 100.0).toFloat().coerceIn(0f, 100f)
                }
            }
            val f = freqByIdx[i]
            CoreLoad(i, load, f?.curKhz ?: 0L, f?.maxKhz ?: 0L, f?.online ?: (cur != null))
        }

        // ── per-process disk and per-app network rates ──
        val io = parseProcIo(sections["IO"].orEmpty())
        val uidNetCum = parseQtaguid(sections["UIDNET"].orEmpty())
        val pu = prevUidNet
        val uidNetBps: Map<Int, Double>? = when {
            uidNetCum == null -> null
            pu == null || dUptime <= 0.0 -> uidNetCum.mapValues { 0.0 }
            else -> uidNetCum.mapValues { (uid, v) -> (v - (pu[uid] ?: v)).coerceAtLeast(0L) / dUptime }
        }

        // ── processes ──
        val rows = ArrayList<ProcRow>(raws.size)
        var threads = 0
        var procCount = 0
        for (r in raws) {
            val kernel = isKernelThread(r)
            val id = idents[r.pid]
            val cmd = id?.cmdline.orEmpty()
            val sameProc = prevStart[r.pid] == r.startTicks
            val dTicks = if (sameProc) r.ticks - (prevTicks[r.pid] ?: r.ticks) else 0L
            val cpu = if (capTicks > 0 && dTicks > 0) (dTicks * 100.0 / capTicks).toFloat().coerceIn(0f, 100f) else 0f
            val ioNow = io[r.pid]
            val ioBefore = prevIo[r.pid]
            val diskBps = if (sameProc && ioNow != null && ioBefore != null && dUptime > 0.0) {
                ((ioNow - ioBefore).coerceAtLeast(0L) / dUptime).toFloat()
            } else {
                0f
            }
            val age = if (uptime > 0 && hz > 0) (uptime - r.startTicks.toDouble() / hz).toLong().coerceAtLeast(0L) else 0L
            rows.add(
                ProcRow(
                    pid = r.pid, ppid = r.ppid,
                    name = displayName(cmd, r.comm), cmdline = cmd, comm = r.comm,
                    uid = id?.uid ?: 0,
                    state = r.state, threads = r.threads, nice = r.nice, oomAdj = r.oomAdj,
                    rssBytes = r.rssPages * pageSize,
                    privBytes = (r.rssPages - r.sharedPages).coerceAtLeast(0L) * pageSize,
                    vsizeBytes = r.vsizeBytes,
                    cpu = cpu, ageSec = age, kernel = kernel, diskBps = diskBps
                )
            )
            if (!kernel) {
                threads += r.threads
                procCount++
            }
        }

        // ── remember for the next tick ──
        prevTotal = curTotal
        prevCores = stat.cores
        prevUptime = uptime
        prevTicks = raws.associate { it.pid to it.ticks }
        prevStart = raws.associate { it.pid to it.startTicks }
        prevIo = io
        prevUidNet = uidNetCum

        val sys = SysSample(
            uptimeSec = uptime,
            cpuAvailable = curTotal != null,
            cpuTotal = cpuTotal,
            cores = cores,
            mem = parseMemInfo(sections["MEM"].orEmpty()),
            zram = parseZram(sections["ZRAM"].orEmpty()),
            net = parseNetDev(sections["NET"].orEmpty()),
            gpu = parseGpu(sections["GPU"].orEmpty()),
            disk = parseDiskStats(sections["DISK"].orEmpty()).let { if (it.isEmpty()) null else DiskStats(it) },
            ioAvailable = io.isNotEmpty(),
            uidNetBps = uidNetBps,
            processCount = procCount,
            threadCount = threads
        )
        return Snapshot(rows, sys)
    }

    companion object {
        fun displayName(cmdline: String, comm: String): String {
            val first = cmdline.trim().substringBefore(' ')
            if (first.isEmpty()) return comm
            return if (first.startsWith("/")) first.substringAfterLast('/') else first
        }
    }
}
