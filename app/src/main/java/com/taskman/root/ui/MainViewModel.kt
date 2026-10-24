package com.taskman.root.ui

import android.app.Application
import android.os.Build
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.taskman.root.R
import com.taskman.root.core.AndroidService
import com.taskman.root.core.AppMeta
import com.taskman.root.core.Category
import com.taskman.root.core.DiskSpeed
import com.taskman.root.core.DiskStats
import com.taskman.root.core.FileEntry
import com.taskman.root.core.GpuStatic
import com.taskman.root.core.InitService
import com.taskman.root.core.NetTotals
import com.taskman.root.core.ProcGroup
import com.taskman.root.core.ProcRow
import com.taskman.root.core.RootStartup
import com.taskman.root.core.ShellResult
import com.taskman.root.core.ShellSession
import com.taskman.root.core.Snapshot
import com.taskman.root.core.SysSample
import com.taskman.root.core.buildGroups
import com.taskman.root.core.diskSpeed
import com.taskman.root.core.joinPath
import com.taskman.root.core.normalizePath
import com.taskman.root.core.parentOf
import com.taskman.root.core.parseLsLine
import com.taskman.root.core.sortEntries
import com.taskman.root.data.GlInfo
import com.taskman.root.data.GlProbe
import com.taskman.root.data.MobileDetails
import com.taskman.root.data.StartupApp
import com.taskman.root.data.StorageUsage
import com.taskman.root.data.SystemRepo
import com.taskman.root.data.WifiDetails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

enum class MainTab { PROCESSES, PERFORMANCE, STARTUP, DETAILS, SERVICES }

enum class UpdateSpeed(val ms: Long) { HIGH(1000L), NORMAL(2000L), LOW(5000L), PAUSED(0L) }

enum class PerfPage { CPU, MEMORY, STORAGE, MOBILE, WIFI, GPU }

enum class SortKey { NAME, CPU, MEMORY, DISK, NETWORK, PID, STATE, USER, THREADS, NICE, OOM }

data class SortState(val key: SortKey, val desc: Boolean)

enum class StartupFilter { ALL, USER, SYSTEM, DISABLED }

enum class ServicesMode { ANDROID, INIT }

sealed interface RootState {
    data object Checking : RootState
    data class Granted(val manager: String) : RootState
    data object Denied : RootState
}

data class NetSpeed(val rxBps: Double = 0.0, val txBps: Double = 0.0)

data class PerfSeries(
    val cpu: List<Float> = emptyList(),
    val cores: List<List<Float>> = emptyList(),
    val mem: List<Float> = emptyList(),
    val gpu: List<Float> = emptyList(),
    val diskActive: List<Float> = emptyList(),
    val diskRead: List<Float> = emptyList(),
    val diskWrite: List<Float> = emptyList(),
    val wifiRx: List<Float> = emptyList(),
    val wifiTx: List<Float> = emptyList(),
    val mobRx: List<Float> = emptyList(),
    val mobTx: List<Float> = emptyList()
)

data class Live(
    val snap: Snapshot? = null,
    val groups: List<ProcGroup> = emptyList(),
    val series: PerfSeries = PerfSeries(),
    val wifi: NetSpeed = NetSpeed(),
    val mobile: NetSpeed = NetSpeed(),
    val disk: DiskSpeed = DiskSpeed(),
    val storage: StorageUsage? = null,
    val wifiInfo: WifiDetails? = null,
    val mobileInfo: MobileDetails? = null,
    val error: Boolean = false
)

/**
 * What the Processes and Details tabs show. It is refreshed no more often than every [MainViewModel.LIST_REFRESH_MS]
 * so that rows do not jump around under the finger, and the CPU column is the average over that window.
 */
data class ListData(
    val procs: List<ProcRow> = emptyList(),
    val groups: List<ProcGroup> = emptyList(),
    val sys: SysSample? = null,
    val ready: Boolean = false,
    /** disk "active time" of the busiest device, averaged over the window */
    val diskActivePct: Float = 0f,
    /** total network traffic in bits per second, averaged over the window */
    val netTotalBits: Double = 0.0,
    /** the last sampling attempt failed; [procs] may still hold the previous snapshot */
    val error: Boolean = false
)

data class StartupState(
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val apps: List<StartupApp> = emptyList(),
    val root: RootStartup? = null
)

data class ServicesState(
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val android: List<AndroidService> = emptyList(),
    val init: List<InitService> = emptyList()
)

/** State of the in-app file browser ("Open file location"). */
data class BrowserState(
    val path: String,
    /** file name to highlight and scroll to (the executable / apk we came for) */
    val highlight: String? = null,
    val loading: Boolean = true,
    val entries: List<FileEntry> = emptyList(),
    /** "NONE" = path does not exist, otherwise the raw message printed by ls */
    val error: String? = null
)

data class DeviceInfo(val soc: String, val abi: String, val android: String, val kernel: String)

data class Msg(@StringRes val res: Int, val args: List<Any> = emptyList())

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SystemRepo(app)
    private val prefs = Prefs(app)
    val icons = IconLoader(app.packageManager)

    // ── UI state ──
    private val _tab = MutableStateFlow(MainTab.PROCESSES)
    val tab: StateFlow<MainTab> = _tab.asStateFlow()

    private val _root = MutableStateFlow<RootState>(RootState.Checking)
    val root: StateFlow<RootState> = _root.asStateFlow()

    private val _speed = MutableStateFlow(prefs.speed())
    val speed: StateFlow<UpdateSpeed> = _speed.asStateFlow()

    private val _showKernel = MutableStateFlow(prefs.showKernel())
    val showKernel: StateFlow<Boolean> = _showKernel.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _searchOpen = MutableStateFlow(false)
    val searchOpen: StateFlow<Boolean> = _searchOpen.asStateFlow()

    private val _procSort = MutableStateFlow(SortState(SortKey.CPU, true))
    val procSort: StateFlow<SortState> = _procSort.asStateFlow()

    private val _detailsSort = MutableStateFlow(SortState(SortKey.CPU, true))
    val detailsSort: StateFlow<SortState> = _detailsSort.asStateFlow()

    private val _expanded = MutableStateFlow<Set<String>>(emptySet())
    val expanded: StateFlow<Set<String>> = _expanded.asStateFlow()

    private val _collapsedCats = MutableStateFlow<Set<Category>>(emptySet())
    val collapsedCats: StateFlow<Set<Category>> = _collapsedCats.asStateFlow()

    private val _themeMode = MutableStateFlow(prefs.theme())
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _browser = MutableStateFlow<BrowserState?>(null)
    val browser: StateFlow<BrowserState?> = _browser.asStateFlow()
    private val browseSeq = AtomicInteger()

    private val _perfPage = MutableStateFlow(PerfPage.CPU)
    val perfPage: StateFlow<PerfPage> = _perfPage.asStateFlow()

    private val _perCore = MutableStateFlow(false)
    val perCore: StateFlow<Boolean> = _perCore.asStateFlow()

    private val _startupFilter = MutableStateFlow(StartupFilter.ALL)
    val startupFilter: StateFlow<StartupFilter> = _startupFilter.asStateFlow()

    private val _servicesMode = MutableStateFlow(ServicesMode.ANDROID)
    val servicesMode: StateFlow<ServicesMode> = _servicesMode.asStateFlow()

    private val _gpuName = MutableStateFlow<String?>(null)
    val gpuName: StateFlow<String?> = _gpuName.asStateFlow()

    private val _gpuStatic = MutableStateFlow<GpuStatic?>(null)
    val gpuStatic: StateFlow<GpuStatic?> = _gpuStatic.asStateFlow()

    private val _gpuGl = MutableStateFlow<GlInfo?>(null)
    val gpuGl: StateFlow<GlInfo?> = _gpuGl.asStateFlow()
    @Volatile private var glRequested = false

    private val _listData = MutableStateFlow(ListData())
    val listData: StateFlow<ListData> = _listData.asStateFlow()

    // ── data ──
    private val _live = MutableStateFlow(Live())
    val live: StateFlow<Live> = _live.asStateFlow()

    private val _startup = MutableStateFlow(StartupState())
    val startup: StateFlow<StartupState> = _startup.asStateFlow()

    private val _services = MutableStateFlow(ServicesState())
    val services: StateFlow<ServicesState> = _services.asStateFlow()

    private val _messages = MutableSharedFlow<Msg>(extraBufferCapacity = 16)
    val messages: SharedFlow<Msg> = _messages.asSharedFlow()

    val device: DeviceInfo = DeviceInfo(
        soc = buildString {
            if (Build.VERSION.SDK_INT >= 31 && Build.SOC_MODEL.isNotBlank() && Build.SOC_MODEL != "unknown") {
                append(Build.SOC_MODEL)
            } else {
                append(Build.HARDWARE)
            }
        },
        abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
        android = "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")",
        kernel = System.getProperty("os.version").orEmpty()
    )

    val ownPid: Int = android.os.Process.myPid()
    val ownPackage: String = app.packageName
    val isRooted: Boolean get() = repo.rooted

    // ── loop ──
    private val _foreground = MutableStateFlow(true)
    private var loopJob: Job? = null
    private val tickLock = Mutex()
    private var prevNet: NetTotals? = null
    private var prevDisk: DiskStats? = null
    private var prevUptime = 0.0

    // average CPU of every process over the list refresh window (see ListData)
    private val cpuAccum = HashMap<Int, Double>()
    private val diskAccum = HashMap<Int, Double>()
    private val netAccum = HashMap<Int, Double>()
    private var netTotalAccum = 0.0
    private var diskActiveAccum = 0.0
    private var accumDt = 0.0
    private var prevAccumUptime = 0.0
    private var lastListAt = 0L
    @Volatile private var forceList = false
    @Volatile private var indexLoadedAt = 0L

    init {
        viewModelScope.launch(Dispatchers.IO) {
            repo.index.reload()
            icons.clearMissing()
            indexLoadedAt = System.currentTimeMillis()
            val info = repo.detectRoot()
            _gpuName.value = info.gpuName
            _root.value = if (info.granted) RootState.Granted(info.manager) else RootState.Denied
            if (info.granted) _gpuStatic.value = repo.gpuStatic()
            refreshRootTabs()
        }
        startLoop()
    }

    private fun startLoop() {
        loopJob?.cancel()
        loopJob = viewModelScope.launch(Dispatchers.Default) {
            // No polling in the background / while paused: the loop only runs while a sample
            // is actually due, and it is restarted when the speed, root state or visibility changes.
            combine(_speed, _foreground, _root) { speed, foreground, root ->
                Triple(speed, foreground, root)
            }.collectLatest { state ->
                val speed = state.first
                val foreground = state.second
                val root = state.third
                if (!foreground || speed == UpdateSpeed.PAUSED || root == RootState.Checking) {
                    return@collectLatest
                }
                while (isActive) {
                    val started = SystemClock.elapsedRealtime()
                    tick()
                    val elapsed = SystemClock.elapsedRealtime() - started
                    // start-to-start interval, so that the graphs get evenly spaced samples
                    delay((speed.ms - elapsed).coerceAtLeast(50L))
                }
            }
        }
    }

    fun setForeground(value: Boolean) {
        _foreground.value = value
        val now = System.currentTimeMillis()
        if (value && now - indexLoadedAt > 60_000L && _root.value != RootState.Checking) {
            indexLoadedAt = now
            viewModelScope.launch(Dispatchers.IO) {
                repo.index.reload()
                icons.clearMissing()
            }
        }
    }

    /** Installed package a process belongs to (null for native daemons and kernel threads). */
    fun appOf(p: ProcRow): AppMeta? = if (p.kernel) null else repo.index.lookup.find(p.name, p.uid)

    fun appLabel(pkg: String): String = repo.index.label(pkg)

    private suspend fun tick() {
        tickLock.withLock {
            val snap = withContext(Dispatchers.IO) { repo.sample() }
            if (snap == null) {
                _live.update { it.copy(error = true) }
                _listData.update { it.copy(error = true) }
                return
            }
            val groups = buildGroups(snap.procs, repo.index.lookup, true)

            // time since the previous sample, from /proc/uptime (the same clock the counters use)
            val up = snap.sys.uptimeSec
            val dt = if (prevUptime > 0.0 && up > prevUptime) up - prevUptime else 0.0
            prevUptime = up

            // network speeds from the cumulative interface counters
            val n = snap.sys.net
            val pn = prevNet
            var wifi = NetSpeed()
            var mob = NetSpeed()
            if (n != null && pn != null && dt > 0.0) {
                wifi = NetSpeed(rate(n.wifiRx, pn.wifiRx, dt), rate(n.wifiTx, pn.wifiTx, dt))
                mob = NetSpeed(rate(n.mobRx, pn.mobRx, dt), rate(n.mobTx, pn.mobTx, dt))
            }
            prevNet = n

            // disk activity from /proc/diskstats (root)
            val disk = diskSpeed(prevDisk, snap.sys.disk, dt)
            prevDisk = snap.sys.disk

            val onPerf = _tab.value == MainTab.PERFORMANCE
            var wifiInfo = _live.value.wifiInfo
            var mobInfo = _live.value.mobileInfo
            var storage = _live.value.storage
            if (onPerf) {
                withContext(Dispatchers.IO) {
                    wifiInfo = repo.wifiDetails(n?.wifiIface)
                    mobInfo = repo.mobileDetails(n?.mobIface)
                    storage = repo.storageUsage()
                }
            }

            val prev = _live.value
            _live.value = Live(
                snap = snap,
                groups = groups,
                series = pushSeries(prev.series, snap.sys, wifi, mob, disk),
                wifi = wifi,
                mobile = mob,
                disk = disk,
                storage = storage,
                wifiInfo = wifiInfo,
                mobileInfo = mobInfo,
                error = false
            )

            // the process lists are refreshed on their own, slower clock
            accumulate(snap, wifi.rxBps + wifi.txBps + mob.rxBps + mob.txBps, disk.activePct)
            val nowMs = SystemClock.elapsedRealtime()
            if (forceList || !_listData.value.ready || nowMs - lastListAt >= LIST_REFRESH_MS - 250L) {
                publishList(snap)
                lastListAt = nowMs
                forceList = false
            }
        }
    }

    /** Adds this sample's per-process CPU / disk and per-app network, weighted by the time it covers. */
    private fun accumulate(snap: Snapshot, netTotalBits: Double, diskActive: Float) {
        val up = snap.sys.uptimeSec
        if (prevAccumUptime > 0.0 && up > prevAccumUptime) {
            val dt = up - prevAccumUptime
            accumDt += dt
            for (p in snap.procs) {
                cpuAccum[p.pid] = (cpuAccum[p.pid] ?: 0.0) + p.cpu * dt
                if (p.diskBps > 0f) diskAccum[p.pid] = (diskAccum[p.pid] ?: 0.0) + p.diskBps * dt
            }
            snap.sys.uidNetBps?.forEach { (uid, bps) ->
                if (bps > 0.0) netAccum[uid] = (netAccum[uid] ?: 0.0) + bps * dt
            }
            netTotalAccum += netTotalBits * dt
            diskActiveAccum += diskActive * dt
        }
        prevAccumUptime = up
    }

    /**
     * Publishes the list: memory is the latest value; CPU, disk and network are averages over the window
     * since the last publish.
     */
    private fun publishList(snap: Snapshot) {
        val window = accumDt
        val procs = if (window > 0.0) {
            snap.procs.map { p ->
                p.copy(
                    cpu = ((cpuAccum[p.pid] ?: 0.0) / window).toFloat().coerceIn(0f, 100f),
                    diskBps = ((diskAccum[p.pid] ?: 0.0) / window).toFloat()
                )
            }
        } else {
            snap.procs
        }
        val uidNet: Map<Int, Double>? = when {
            snap.sys.uidNetBps == null -> null
            window > 0.0 -> netAccum.mapValues { it.value / window }
            else -> snap.sys.uidNetBps
        }
        val netTotal = if (window > 0.0) netTotalAccum / window else 0.0
        val diskActive = if (window > 0.0) (diskActiveAccum / window).toFloat() else 0f
        cpuAccum.clear()
        diskAccum.clear()
        netAccum.clear()
        netTotalAccum = 0.0
        diskActiveAccum = 0.0
        accumDt = 0.0
        _listData.value = ListData(
            procs = procs,
            groups = buildGroups(procs, repo.index.lookup, true, uidNet),
            sys = snap.sys,
            ready = true,
            diskActivePct = diskActive,
            netTotalBits = netTotal,
            error = false
        )
    }

    private fun rate(cur: Long, prev: Long, dt: Double): Double =
        (cur - prev).coerceAtLeast(0L) * 8.0 / dt

    private fun List<Float>.push(v: Float): List<Float> =
        if (size >= HISTORY) drop(size - HISTORY + 1) + v else this + v

    private fun pushSeries(p: PerfSeries, s: SysSample, wifi: NetSpeed, mob: NetSpeed, disk: DiskSpeed): PerfSeries {
        val cores = s.cores.mapIndexed { i, c -> (p.cores.getOrNull(i) ?: emptyList()).push(c.load) }
        val gpuLoad = s.gpu?.loadPercent
        return PerfSeries(
            cpu = p.cpu.push(s.cpuTotal),
            cores = cores,
            mem = s.mem?.let { p.mem.push(it.usedPercent) } ?: p.mem,
            gpu = if (gpuLoad != null) p.gpu.push(gpuLoad) else p.gpu,
            diskActive = if (s.disk != null) p.diskActive.push(disk.activePct) else p.diskActive,
            diskRead = if (s.disk != null) p.diskRead.push(disk.readBps.toFloat()) else p.diskRead,
            diskWrite = if (s.disk != null) p.diskWrite.push(disk.writeBps.toFloat()) else p.diskWrite,
            wifiRx = p.wifiRx.push(wifi.rxBps.toFloat()),
            wifiTx = p.wifiTx.push(wifi.txBps.toFloat()),
            mobRx = p.mobRx.push(mob.rxBps.toFloat()),
            mobTx = p.mobTx.push(mob.txBps.toFloat())
        )
    }

    private fun kickRefresh() {
        forceList = true
        viewModelScope.launch(Dispatchers.Default) {
            delay(350L)
            if (_foreground.value && _speed.value != UpdateSpeed.PAUSED) tick()
        }
    }

    // ── navigation / settings ──
    fun selectTab(t: MainTab) {
        _tab.value = t
        if (t == MainTab.STARTUP && !_startup.value.loaded) loadStartup()
        if (t == MainTab.SERVICES && !_services.value.loaded) loadServices()
    }

    fun setSpeed(s: UpdateSpeed) {
        _speed.value = s
        prefs.putSpeed(s)
    }

    fun setShowKernel(v: Boolean) {
        _showKernel.value = v
        prefs.putShowKernel(v)
    }

    fun setThemeMode(m: ThemeMode) {
        _themeMode.value = m
        prefs.putTheme(m)
    }
    fun setQuery(q: String) { _query.value = q }
    fun setSearchOpen(v: Boolean) {
        _searchOpen.value = v
        if (!v) _query.value = ""
    }
    fun setPerfPage(p: PerfPage) {
        _perfPage.value = p
        if (p == PerfPage.GPU && _gpuGl.value == null && !glRequested) {
            glRequested = true
            viewModelScope.launch(Dispatchers.IO) {
                val info = GlProbe.query()
                _gpuGl.value = info
                // let a later tap retry when the driver/context was not available
                if (info == null) glRequested = false
            }
        }
    }
    fun setPerCore(v: Boolean) { _perCore.value = v }
    fun setStartupFilter(f: StartupFilter) { _startupFilter.value = f }
    fun setServicesMode(m: ServicesMode) { _servicesMode.value = m }

    fun sortProcesses(key: SortKey) {
        _procSort.update { if (it.key == key) it.copy(desc = !it.desc) else SortState(key, key != SortKey.NAME) }
    }

    fun sortDetails(key: SortKey) {
        _detailsSort.update {
            if (it.key == key) it.copy(desc = !it.desc)
            else SortState(key, key != SortKey.NAME && key != SortKey.USER && key != SortKey.STATE)
        }
    }

    fun toggleExpanded(key: String) {
        _expanded.update { if (key in it) it - key else it + key }
    }

    fun toggleCategory(c: Category) {
        _collapsedCats.update { if (c in it) it - c else it + c }
    }

    /** "Go to details": jump to the Details tab filtered by a PID or a name. */
    fun showInDetails(query: String) {
        _query.value = query
        _searchOpen.value = true
        _tab.value = MainTab.DETAILS
    }

    /** Re-checks root and restarts sampling; used by the error banner. */
    fun retry() {
        if (_root.value == RootState.Checking) return
        retryRoot()
    }

    fun retryRoot() {
        if (_root.value == RootState.Checking) return
        _root.value = RootState.Checking
        viewModelScope.launch(Dispatchers.IO) {
            val info = repo.detectRoot()
            _gpuName.value = info.gpuName
            _root.value = if (info.granted) RootState.Granted(info.manager) else RootState.Denied
            if (info.granted) _gpuStatic.value = repo.gpuStatic()
            refreshRootTabs()
        }
    }

    /** Root state changed: root-dependent tabs are rebuilt, and the visible one immediately. */
    private fun refreshRootTabs() {
        _startup.update { it.copy(loaded = false) }
        _services.update { it.copy(loaded = false) }
        when (_tab.value) {
            MainTab.STARTUP -> loadStartup()
            MainTab.SERVICES -> loadServices()
            else -> Unit
        }
    }

    // ── "Open file location": built-in read-only file browser ──

    /**
     * Shows [path] in the browser. A folder is listed; a file opens its parent folder with the file
     * highlighted; a symlink is followed (`[ -d ]` / `[ -e ]` resolve it).
     */
    fun browse(path: String, highlight: String? = null) {
        val p = normalizePath(path)
        val my = browseSeq.incrementAndGet()
        _browser.value = BrowserState(path = p, highlight = highlight, loading = true)
        viewModelScope.launch(Dispatchers.IO) {
            val r = repo.listDir(p)
            if (my != browseSeq.get()) return@launch
            when (r.lines.firstOrNull()) {
                "DIR" -> {
                    val rest = r.lines.drop(1)
                    val entries = sortEntries(rest.mapNotNull { parseLsLine(it) })
                    val err = if (entries.isEmpty()) rest.firstOrNull { it.startsWith("ls:") } else null
                    _browser.value = BrowserState(p, highlight, false, entries, err)
                }
                "FILE" -> browse(parentOf(p), p.substringAfterLast('/'))
                else -> _browser.value = BrowserState(p, highlight, false, emptyList(), "NONE")
            }
        }
    }

    fun browserUp() {
        val s = _browser.value ?: return
        if (s.path == "/") closeBrowser() else browse(parentOf(s.path), s.path.substringAfterLast('/'))
    }

    fun closeBrowser() {
        browseSeq.incrementAndGet()
        _browser.value = null
    }

    fun openEntry(dir: String, e: FileEntry): String = joinPath(dir, e.name)

    /** APK folder of an app (default) or its data folder. No root needed to learn the path. */
    @Suppress("DEPRECATION")
    fun openAppLocation(pkg: String, dataDir: Boolean = false) {
        val path = try {
            val ai = getApplication<Application>().packageManager.getApplicationInfo(pkg, 0)
            if (dataDir) ai.dataDir else ai.sourceDir
        } catch (e: Exception) {
            null
        }
        if (path.isNullOrEmpty()) emit(Msg(R.string.msg_no_file)) else browse(path)
    }

    /** Folder of the executable a (native) process was started from. */
    fun openProcessLocation(pid: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val exe = repo.exePath(pid)
            if (exe == null) emit(Msg(R.string.msg_no_file)) else browse(exe)
        }
    }

    // ── loaders ──
    fun loadStartup() {
        if (_startup.value.loading) return
        _startup.update { it.copy(loading = true) }
        viewModelScope.launch(Dispatchers.IO) {
            var apps: List<StartupApp> = emptyList()
            var rs: RootStartup? = null
            val rootedAtStart = repo.rooted
            try {
                apps = repo.loadStartupApps()
                rs = if (repo.rooted) repo.loadRootStartup() else null
            } catch (e: Exception) {
                // keep the empty lists: a broken loader must not crash the whole app
            } finally {
                _startup.value = StartupState(loading = false, loaded = true, apps = apps, root = rs)
                // root state changed (granted / lost) while this load was running: rebuild
                if (rootedAtStart != repo.rooted) loadStartup()
            }
        }
    }

    fun loadServices() {
        if (_services.value.loading) return
        _services.update { it.copy(loading = true) }
        viewModelScope.launch(Dispatchers.IO) {
            var android: List<AndroidService> = emptyList()
            var init: List<InitService> = emptyList()
            val rootedAtStart = repo.rooted
            try {
                if (repo.rooted) {
                    android = repo.loadAndroidServices()
                    init = repo.loadInitServices()
                }
            } catch (e: Exception) {
                // keep the empty lists: a broken dumpsys must not crash the whole app
            } finally {
                _services.value = ServicesState(loading = false, loaded = true, android = android, init = init)
                // root state changed (granted / lost) while this load was running: rebuild
                if (rootedAtStart != repo.rooted) loadServices()
            }
        }
    }

    suspend fun props(pid: Int): List<Pair<String, String>> =
        withContext(Dispatchers.IO) { repo.loadProps(pid) }

    // ── actions (all need root) ──
    private fun emit(m: Msg) {
        _messages.tryEmit(m)
    }

    private fun requireRoot(): Boolean {
        if (repo.rooted) return true
        emit(Msg(R.string.msg_need_root))
        return false
    }

    private fun hasError(r: ShellResult): Boolean =
        !r.ok || r.lines.any {
            it.startsWith("Error") || it.startsWith("Exception") || it.contains("Exception: ")
        }

    private fun report(r: ShellResult, @StringRes okRes: Int, vararg args: Any) {
        if (hasError(r)) {
            val first = r.lines.firstOrNull { it.isNotBlank() }?.take(140) ?: ("exit " + r.exitCode)
            emit(Msg(R.string.msg_failed, listOf(first)))
        } else {
            emit(Msg(okRes, args.toList()))
        }
    }

    fun signal(p: ProcRow, sig: Int) {
        if (!requireRoot()) return
        if (p.pid == ownPid) {
            emit(Msg(R.string.msg_self_protected))
            return
        }
        if (p.pid <= 2) {
            emit(Msg(R.string.msg_protected))
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val r = repo.run("kill -$sig ${p.pid}")
            report(r, R.string.msg_signal_sent, sig, p.name, p.pid)
            kickRefresh()
        }
    }

    fun forceStop(pkg: String, label: String) {
        if (!requireRoot()) return
        if (pkg == ownPackage) {
            emit(Msg(R.string.msg_self_protected))
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val r = repo.run("am force-stop " + ShellSession.quote(pkg))
            report(r, R.string.msg_force_stopped, label)
            kickRefresh()
        }
    }

    fun setNice(p: ProcRow, target: Int) {
        if (!requireRoot()) return
        viewModelScope.launch(Dispatchers.IO) {
            // the list snapshot may be a few seconds old: renice works with a delta, so take
            // the current nice value right before changing it
            val current = repo.readNice(p.pid) ?: p.nice
            val r = repo.run("renice -n ${target - current} -p ${p.pid}")
            val now = repo.readNice(p.pid)
            if (now != null && !r.timedOut) emit(Msg(R.string.msg_nice_now, listOf(p.name, now)))
            else report(r, R.string.msg_done)
            kickRefresh()
        }
    }

    /** Enables / disables manifest components (boot receivers, services) with `pm`. */
    fun setComponents(components: List<String>, enable: Boolean) {
        if (!requireRoot() || components.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val verb = if (enable) "enable" else "disable"
            var changed = 0
            var failure: String? = null
            // one pm call per component, so a single failure cannot be hidden by the exit code
            // of the last command (and a slow device does not hit one 30 s timeout)
            for (c in components) {
                val r = repo.run("pm $verb " + ShellSession.quote(c), 10_000L)
                if (!hasError(r)) {
                    changed++
                } else if (failure == null) {
                    failure = r.lines.firstOrNull { it.isNotBlank() } ?: ("exit " + r.exitCode)
                }
            }
            val failed = failure
            if (failed != null) {
                emit(Msg(R.string.msg_failed, listOf(failed.take(140))))
            } else {
                emit(Msg(if (enable) R.string.msg_enabled else R.string.msg_disabled, listOf(changed)))
            }
            val apps = repo.loadStartupApps()
            _startup.update { it.copy(apps = apps) }
            if (_tab.value == MainTab.SERVICES) {
                _services.update { it.copy(loaded = false) }
                loadServices()
            }
        }
    }

    fun stopService(component: String) {
        if (!requireRoot()) return
        viewModelScope.launch(Dispatchers.IO) {
            val r = repo.run("am stopservice -n " + ShellSession.quote(component))
            // am prints the Intent to stdout and the result to stderr; report the result,
            // and do not trust the exit code: am returns non-zero even for "Service stopped".
            val problem = r.lines.firstOrNull {
                it.startsWith("Error") || it.contains("Service not stopped")
            }
            if (problem != null) {
                emit(Msg(R.string.msg_output, listOf(problem)))
            } else {
                emit(Msg(R.string.msg_done))
            }
            _services.update { it.copy(loaded = false) }
            loadServices()
        }
    }

    /** op = "start" | "stop" | "restart" */
    fun initService(name: String, op: String) {
        if (!requireRoot()) return
        viewModelScope.launch(Dispatchers.IO) {
            val q = ShellSession.quote(name)
            val cmd = if (op == "restart") "stop $q; start $q" else "$op $q"
            val r = repo.run(cmd)
            report(r, R.string.msg_done)
            delay(400L)
            _services.update { it.copy(loaded = false) }
            loadServices()
        }
    }

    fun setModuleDisabled(id: String, disabled: Boolean) {
        if (!requireRoot()) return
        viewModelScope.launch(Dispatchers.IO) {
            val path = ShellSession.quote("/data/adb/modules/$id/disable")
            val r = repo.run(if (disabled) "touch $path" else "rm -f $path")
            report(r, R.string.msg_reboot_needed)
            _startup.update { it.copy(loaded = false) }
            loadStartup()
        }
    }

    fun setScriptExecutable(path: String, executable: Boolean) {
        if (!requireRoot()) return
        viewModelScope.launch(Dispatchers.IO) {
            val r = repo.run((if (executable) "chmod 755 " else "chmod 644 ") + ShellSession.quote(path))
            report(r, R.string.msg_reboot_needed)
            _startup.update { it.copy(loaded = false) }
            loadStartup()
        }
    }

    override fun onCleared() {
        loopJob?.cancel()
        // close() waits for the shell lock: a running tm_sample/dumpsys can take tens of
        // seconds. Doing that on the main thread would freeze the exit (ANR), so let it
        // finish on a daemon thread.
        val r = repo
        Thread({ r.close() }, "taskman-repo-close").apply { isDaemon = true }.start()
        super.onCleared()
    }

    companion object {
        const val HISTORY = 60

        /** The process lists refresh at most this often. */
        const val LIST_REFRESH_MS = 3000L
    }
}
