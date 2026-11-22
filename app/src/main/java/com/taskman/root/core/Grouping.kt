package com.taskman.root.core

data class AppMeta(val pkg: String, val label: String, val uid: Int, val system: Boolean)

/** Maps a process (name + uid) to an installed package. */
class AppLookup(val byPkg: Map<String, AppMeta>) {
    private val byAppId: Map<Int, List<AppMeta>> = byPkg.values.groupBy { it.uid % 100000 }

    fun find(procName: String, uid: Int): AppMeta? {
        val direct = byPkg[procName.substringBefore(':')]
        if (direct != null) return direct
        val appId = uid % 100000
        if (appId < 10000) return null
        return byAppId[appId]?.firstOrNull()
    }

    companion object {
        val EMPTY = AppLookup(emptyMap())
    }
}

enum class Category { APPS, BACKGROUND, SYSTEM }

data class ProcGroup(
    val key: String,
    /** clean name (app label or process name) */
    val label: String,
    /** label plus " (n)" when the group has several processes */
    val title: String,
    val subtitle: String,
    val pkg: String?,
    val category: Category,
    val cpu: Float,
    val priv: Long,
    val procs: List<ProcRow>,
    /** bytes per second all processes of the group move to / from storage */
    val diskBps: Float = 0f,
    /** network bytes per second of the app (its uid); null for native processes and when unsupported */
    val netBps: Double? = null
) {
    val isApp: Boolean get() = pkg != null
}

/**
 * Category rules (oom_score_adj is what the Android lowmemorykiller uses):
 *  - not an installed package (native daemons, kernel threads)         -> SYSTEM
 *  - persistent processes (oom_score_adj < 0: SystemUI, Phone, ...)    -> SYSTEM
 *  - foreground / visible / perceptible (oom_score_adj <= 200)         -> APPS
 *  - everything else (services, cached)                                -> BACKGROUND
 */
fun buildGroups(
    procs: List<ProcRow>,
    lookup: AppLookup,
    showKernel: Boolean,
    uidNetBps: Map<Int, Double>? = null
): List<ProcGroup> {
    val buckets = LinkedHashMap<String, MutableList<ProcRow>>()
    val metas = HashMap<String, AppMeta>()
    for (p in procs) {
        if (p.kernel && !showKernel) continue
        val app = if (p.kernel) null else lookup.find(p.name, p.uid)
        val key = if (app != null) "app:" + app.pkg else "proc:" + p.pid
        buckets.getOrPut(key) { ArrayList() }.add(p)
        if (app != null) metas[key] = app
    }
    val out = ArrayList<ProcGroup>(buckets.size)
    for ((key, list) in buckets) {
        val app = metas[key]
        val sorted = list.sortedWith(compareByDescending<ProcRow> { it.cpu }.thenByDescending { it.privBytes })
        val cat = when {
            app == null -> Category.SYSTEM
            list.minOf { it.oomAdj } < 0 -> Category.SYSTEM
            list.minOf { it.oomAdj } <= 200 -> Category.APPS
            else -> Category.BACKGROUND
        }
        val first = sorted.first()
        val label = app?.label ?: first.name
        out.add(
            ProcGroup(
                key = key,
                label = label,
                title = if (list.size > 1) "$label (${list.size})" else label,
                subtitle = app?.pkg ?: "PID ${first.pid}",
                pkg = app?.pkg,
                category = cat,
                cpu = list.sumOf { it.cpu.toDouble() }.toFloat(),
                priv = list.sumOf { it.privBytes },
                procs = sorted,
                diskBps = list.sumOf { it.diskBps.toDouble() }.toFloat(),
                netBps = if (app != null && uidNetBps != null) (uidNetBps[app.uid] ?: 0.0) else null
            )
        )
    }
    return out
}

/** Process names that are dangerous to kill (the device reboots or loses its UI/radio/adb). */
val CRITICAL_PROCESSES = setOf(
    "init", "zygote", "zygote64", "zygote_secondary", "system_server", "surfaceflinger",
    "servicemanager", "hwservicemanager", "vndservicemanager", "vold", "netd", "logd", "lmkd",
    "adbd", "ueventd", "healthd", "installd", "keystore", "keystore2", "statsd", "ksud", "magiskd",
    "com.android.systemui", "com.android.phone"
)
