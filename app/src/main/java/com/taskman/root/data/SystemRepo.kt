package com.taskman.root.data

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.TrafficStats
import android.net.wifi.WifiManager
import android.os.Environment
import android.os.StatFs
import android.system.Os
import android.system.OsConstants
import android.telephony.TelephonyManager
import com.taskman.root.core.AndroidService
import com.taskman.root.core.GpuStatic
import com.taskman.root.core.InitService
import com.taskman.root.core.NetTotals
import com.taskman.root.core.RootStartup
import com.taskman.root.core.ShellResult
import com.taskman.root.core.ShellSession
import com.taskman.root.core.Snapshot
import com.taskman.root.core.SnapshotEngine
import com.taskman.root.core.parseAndroidServices
import com.taskman.root.core.parseGpuStatic
import com.taskman.root.core.parseInitServices
import com.taskman.root.core.parseKeyValues
import com.taskman.root.core.parseRootStartup
import com.taskman.root.core.splitSections
import java.net.Inet4Address
import java.net.NetworkInterface

data class RootInfo(val granted: Boolean, val manager: String, val gpuName: String?)

data class BootReceiver(
    val pkg: String,
    val cls: String,
    val actions: Set<String>,
    val enabled: Boolean,
    val directBoot: Boolean
) {
    val component: String get() = "$pkg/$cls"
}

data class StartupApp(
    val pkg: String,
    val label: String,
    val system: Boolean,
    val receivers: List<BootReceiver>
) {
    val enabled: Boolean get() = receivers.any { it.enabled }
}

data class WifiDetails(
    val ssid: String?, val rssiDbm: Int?, val linkMbps: Int?, val freqMhz: Int?,
    val ip: String?, val iface: String?
)

data class MobileDetails(val operator: String?, val ip: String?, val iface: String?)

/** Free / total space of the data partition (where apps and user files live). */
data class StorageUsage(val totalBytes: Long, val freeBytes: Long) {
    val usedBytes: Long get() = (totalBytes - freeBytes).coerceAtLeast(0L)
    val usedPercent: Float get() = if (totalBytes > 0) usedBytes * 100f / totalBytes else 0f
}

/**
 * Everything that touches the system: four shell sessions (sampler / actions / loader / interactive),
 * parsing of their output, and the few APIs that do not need root.
 * All methods are blocking: call them from a background dispatcher.
 */
class SystemRepo(private val app: Application) {

    private val script: String = app.assets.open("taskmgr.sh").bufferedReader().use { it.readText() }

    // Limited (non-root) sessions until detectRoot() succeeds.
    @Volatile private var sampler = ShellSession("sh", script)
    @Volatile private var actions = ShellSession("sh", script)
    @Volatile private var loader = ShellSession("sh", script)
    // User-facing reads (properties, file browser, nice, exe) have their own shell, so a long
    // dumpsys in `loader` cannot block them.
    @Volatile private var interactive = ShellSession("sh", script)

    @Volatile
    var rooted: Boolean = false
        private set

    val index = AppIndex(app.packageManager)

    private val pageSize: Long = try {
        Os.sysconf(OsConstants._SC_PAGESIZE).takeIf { it > 0L } ?: 4096L
    } catch (e: Throwable) {
        4096L
    }
    private val hz: Long = try {
        Os.sysconf(OsConstants._SC_CLK_TCK).takeIf { it > 0L } ?: 100L
    } catch (e: Throwable) {
        100L
    }
    private val engine = SnapshotEngine(pageSize, hz, Runtime.getRuntime().availableProcessors())

    private val bootActions = listOf(
        Intent.ACTION_BOOT_COMPLETED,
        Intent.ACTION_LOCKED_BOOT_COMPLETED,
        "android.intent.action.QUICKBOOT_POWERON",
        "com.htc.intent.action.QUICKBOOT_POWERON"
    )

    // ───────────────────────── root ─────────────────────────

    /**
     * Asks for root. The first `su` start can block until the user confirms the request in
     * Magisk / KernelSU / APatch, hence the long timeout.
     */
    fun detectRoot(): RootInfo {
        val suActions = ShellSession("su", script)
        var info = RootInfo(false, "", null)
        if (suActions.open(60_000)) {
            val r = suActions.exec("tm_rootinfo", 30_000, false)
            val kv = HashMap<String, String>()
            for (l in r.lines) {
                val i = l.indexOf('=')
                if (i > 0) kv[l.substring(0, i)] = l.substring(i + 1)
            }
            if (kv["uid"] == "0") {
                val manager = when {
                    kv["ksu"] == "1" -> "KernelSU"
                    kv["apatch"] == "1" -> "APatch"
                    kv["magisk"] == "1" -> "Magisk"
                    else -> "root"
                }
                // "GLES: Vendor, Renderer, OpenGL ES x.y ..."
                val gles = kv["gles"]?.substringAfter("GLES:", "")?.trim()?.split(", ")
                val gpu = gles?.getOrNull(1)?.takeIf { it.isNotBlank() }
                info = RootInfo(true, manager, gpu)
            }
        }
        if (info.granted) {
            sampler.close(); actions.close(); loader.close(); interactive.close()
            actions = suActions
            sampler = ShellSession("su", script)
            loader = ShellSession("su", script)
            interactive = ShellSession("su", script)
            rooted = true
        } else {
            suActions.close()
            // do not keep a possibly still-alive root shell when root was denied/revoked
            sampler.close(); actions.close(); loader.close(); interactive.close()
            sampler = ShellSession("sh", script)
            actions = ShellSession("sh", script)
            loader = ShellSession("sh", script)
            interactive = ShellSession("sh", script)
            rooted = false
        }
        engine.reset()
        return info
    }

    fun close() {
        sampler.close(); actions.close(); loader.close(); interactive.close()
    }

    // ───────────────────────── sampling ─────────────────────────

    fun sample(): Snapshot? {
        val res = sampler.exec("tm_sample", 20_000, false)
        if (res.lines.isEmpty()) return null
        val snap = engine.build(splitSections(res.lines)) { pids ->
            sampler.exec("tm_ident ${pids.joinToString(" ")}", 30_000, false).lines
        } ?: return null
        if (snap.sys.net != null) return snap
        val fallback = trafficStatsTotals() ?: return snap
        return Snapshot(snap.procs, snap.sys.copy(net = fallback))
    }

    /** Without root /proc/net/dev is hidden on Android 10+; TrafficStats still gives device totals. */
    private fun trafficStatsTotals(): NetTotals? {
        val tRx = TrafficStats.getTotalRxBytes()
        val tTx = TrafficStats.getTotalTxBytes()
        if (tRx < 0 || tTx < 0) return null
        val mRx = TrafficStats.getMobileRxBytes().coerceAtLeast(0L)
        val mTx = TrafficStats.getMobileTxBytes().coerceAtLeast(0L)
        return NetTotals((tRx - mRx).coerceAtLeast(0L), (tTx - mTx).coerceAtLeast(0L), mRx, mTx, 0L, 0L, null, null)
    }

    @Suppress("DEPRECATION")
    fun wifiDetails(iface: String?): WifiDetails? {
        return try {
            val wm = app.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
            val info = wm.connectionInfo ?: return null
            if (info.linkSpeed <= 0 && info.rssi <= -120) return null
            val ssid = info.ssid?.trim('"')?.takeIf { it.isNotEmpty() && it != "<unknown ssid>" }
            WifiDetails(ssid, info.rssi, info.linkSpeed, info.frequency, ipv4(iface ?: "wlan0"), iface)
        } catch (e: Exception) {
            null
        }
    }

    fun mobileDetails(iface: String?): MobileDetails {
        val operator = try {
            (app.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager)
                ?.networkOperatorName?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
        return MobileDetails(operator, ipv4(iface), iface)
    }

    private fun ipv4(name: String?): String? {
        if (name == null) return null
        return try {
            NetworkInterface.getByName(name)?.inetAddresses?.toList()
                ?.firstOrNull { it is Inet4Address }?.hostAddress
        } catch (e: Exception) {
            null
        }
    }

    // ───────────────────────── actions / loaders ─────────────────────────

    /** Runs a command in the "actions" session (kill, am, pm, start/stop ...). */
    fun run(cmd: String, timeoutMs: Long = 20_000): ShellResult = actions.exec(cmd, timeoutMs, true)

    fun loadProps(pid: Int): List<Pair<String, String>> =
        parseKeyValues(interactive.exec("tm_props $pid", 15_000, false).lines)

    fun loadRootStartup(): RootStartup =
        parseRootStartup(loader.exec("tm_modules", 20_000, false).lines)

    fun loadAndroidServices(): List<AndroidService> =
        parseAndroidServices(loader.exec("tm_services", 40_000, false).lines)

    fun loadInitServices(): List<InitService> =
        parseInitServices(loader.exec("tm_initsvc", 15_000, false).lines)

    /** Reads the current nice value of a process (field 19 of /proc/<pid>/stat). */
    fun readNice(pid: Int): Int? {
        val r = interactive.exec("cat /proc/$pid/stat", 5_000, false)
        val s = r.lines.firstOrNull() ?: return null
        val close = s.lastIndexOf(')')
        if (close < 0 || close + 2 > s.length) return null
        return s.substring(close + 2).trim().split(' ').getOrNull(16)?.toIntOrNull()
    }

    fun storageUsage(): StorageUsage? {
        return try {
            val sf = StatFs(Environment.getDataDirectory().path)
            StorageUsage(sf.totalBytes, sf.availableBytes)
        } catch (e: Exception) {
            null
        }
    }

    /** Shader core count / mask and model from the GPU driver's sysfs files (root). */
    fun gpuStatic(): GpuStatic? = parseGpuStatic(loader.exec("tm_gpuinfo", 10_000, false).lines)

    /** Directory listing for the file browser (see tm_ls: DIR / FILE / NONE, then `ls -lA` lines). */
    fun listDir(path: String): ShellResult =
        interactive.exec("tm_ls " + ShellSession.quote(path), 20_000, true)

    /** Executable of a process (target of /proc/<pid>/exe), or null when it cannot be read. */
    fun exePath(pid: Int): String? {
        val r = interactive.exec("readlink /proc/$pid/exe", 5_000, false)
        val s = r.lines.firstOrNull()?.trim().orEmpty().removeSuffix(" (deleted)")
        return if (s.isEmpty()) null else s
    }

    // ───────────────────────── autostart of apps (no root needed to read) ─────────────────────────

    @Suppress("DEPRECATION")
    fun loadStartupApps(): List<StartupApp> {
        val pm = app.packageManager
        val flags = PackageManager.MATCH_DISABLED_COMPONENTS or
            PackageManager.MATCH_DIRECT_BOOT_AWARE or
            PackageManager.MATCH_DIRECT_BOOT_UNAWARE

        class Acc(val pkg: String, val cls: String, val enabled: Boolean, val directBoot: Boolean) {
            val actions = LinkedHashSet<String>()
        }

        val byComponent = LinkedHashMap<String, Acc>()
        for (action in bootActions) {
            val list = try {
                pm.queryBroadcastReceivers(Intent(action), flags)
            } catch (e: Exception) {
                emptyList()
            }
            for (ri in list) {
                val ai = ri.activityInfo ?: continue
                val key = ai.packageName + "/" + ai.name
                val acc = byComponent.getOrPut(key) {
                    Acc(ai.packageName, ai.name, componentEnabled(pm, ai), ai.directBootAware)
                }
                acc.actions.add(action)
            }
        }

        val perPkg = LinkedHashMap<String, MutableList<BootReceiver>>()
        for (a in byComponent.values) {
            perPkg.getOrPut(a.pkg) { ArrayList() }
                .add(BootReceiver(a.pkg, a.cls, a.actions, a.enabled, a.directBoot))
        }
        val lookup = index.lookup
        return perPkg.map { (pkg, list) ->
            val meta = lookup.byPkg[pkg]
            StartupApp(pkg, meta?.label ?: pkg, meta?.system ?: false, list.sortedBy { it.cls })
        }.sortedBy { it.label.lowercase() }
    }

    private fun componentEnabled(pm: PackageManager, ai: ActivityInfo): Boolean {
        val state = try {
            pm.getComponentEnabledSetting(ComponentName(ai.packageName, ai.name))
        } catch (e: Exception) {
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        }
        val component = when (state) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> false
            else -> ai.enabled
        }
        return component && (ai.applicationInfo?.enabled ?: true)
    }
}
