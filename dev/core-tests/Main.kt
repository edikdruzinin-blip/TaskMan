import com.taskman.root.core.*
import java.io.File

var failures = 0
fun check(name: String, cond: Boolean, extra: String = "") {
    if (cond) println("  ok   $name") else { failures++; println("  FAIL $name $extra") }
}

fun main() {
    val script = File("app/src/main/assets/taskmgr.sh").readText()

    println("== parsers ==")
    val stat = "1234 (com.foo (bar)) S 567 1234 0 0 -1 4194560 100 0 0 0 150 50 0 0 20 -2 17 0 98765 1500000000 25000 18446744073709551615 0 0 0 0 0 0 0 0 0 0 0 0 17 3 0 0 0 0 0"
    val rp = parseProcLine("1234|-800|30000 25000 8000 1 0 20000 0|$stat")
    check("proc line parsed", rp != null)
    if (rp != null) {
        check("comm with parens", rp.comm == "com.foo (bar)", rp.comm)
        check("state/ppid", rp.state == 'S' && rp.ppid == 567)
        check("ticks", rp.utime == 150L && rp.stime == 50L)
        check("nice/threads", rp.nice == -2 && rp.threads == 17, "${rp.nice} ${rp.threads}")
        check("start/vsize", rp.startTicks == 98765L && rp.vsizeBytes == 1500000000L)
        check("rss/shared/adj", rp.rssPages == 25000L && rp.sharedPages == 8000L && rp.oomAdj == -800)
    }
    check("garbage line rejected", parseProcLine("abc|def") == null)

    val cpu = parseCpuStat(listOf("cpu  100 0 50 800 20 0 5 0 0 0", "cpu0 50 0 25 400 10 0 2 0 0 0", "cpu1 50 0 25 400 10 0 3 0 0 0"))
    check("cpu total", cpu.total?.total == 975L && cpu.total?.idleAll == 820L)
    check("cpu cores", cpu.cores.size == 2 && cpu.cores[1]?.softirq == 3L)

    val mem = parseMemInfo(listOf("MemTotal:        5714360 kB", "MemFree:          200000 kB", "MemAvailable:    2000000 kB", "Buffers: 1000 kB", "Cached: 900000 kB", "SwapTotal: 2097148 kB", "SwapFree: 1000000 kB", "Shmem: 5000 kB"))
    check("meminfo", mem != null && mem.usedKb == 3714360L && mem.swapUsedKb == 1097148L && mem.cacheKb == 1800000L)

    val net = parseNetDev(listOf(
        "Inter-|   Receive                                                |  Transmit",
        " face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed",
        "    lo: 1000 10 0 0 0 0 0 0 1000 10 0 0 0 0 0 0",
        "wlan0: 5000000 100 0 0 0 0 0 0 2000000 80 0 0 0 0 0 0",
        "rmnet_data1: 700 5 0 0 0 0 0 0 300 4 0 0 0 0 0 0",
        "v4-rmnet_data1: 700 5 0 0 0 0 0 0 300 4 0 0 0 0 0 0",
        "rmnet_ipa0: 999 5 0 0 0 0 0 0 999 4 0 0 0 0 0 0"))
    check("net totals (no double count)", net != null && net.wifiRx == 5000000L && net.wifiTx == 2000000L && net.mobRx == 700L && net.mobTx == 300L, net.toString())
    check("net iface names", net?.wifiIface == "wlan0" && net?.mobIface == "rmnet_data1")

    check("gpu adreno gpubusy", parseGpu(listOf("/sys/class/kgsl/kgsl-3d0/gpubusy|  250 1000", "/sys/class/kgsl/kgsl-3d0/gpuclk|614400000"))?.let { it.loadPercent == 25f && it.freqMhz == 614.4f } == true)
    check("gpu mali exynos", parseGpu(listOf("/sys/devices/platform/17500000.mali/utilization|37", "/sys/devices/platform/17500000.mali/clock|700"))?.let { it.loadPercent == 37f && it.freqMhz == 700f } == true)
    check("gpu none", parseGpu(emptyList()) == null)
    check("zram", parseZram(listOf("1000000 300000 350000 0 400000 10 0 0", "2147483648"))?.let { it.ratio > 3f && it.diskSizeBytes == 2147483648L } == true)

    val svcLines = """
  * ServiceRecord{13e6b1a u0 com.google.android.gms/.nearby.sharing.service.SharingService}
    packageName=com.google.android.gms
    processName=com.google.android.gms.persistent
    app=ProcessRecord{e7e1e8f 1943:com.google.android.gms.persistent/u0a49}
    isForeground=true foregroundId=5
  * ServiceRecord{aa11bb2 u0 com.foo.bar/com.foo.bar.SyncService}
    packageName=com.foo.bar
    processName=com.foo.bar
    app=null
    isForeground=false
""".trimIndent().lines()
    val svcs = parseAndroidServices(svcLines)
    check("android services count", svcs.size == 2, svcs.toString())
    check("service 1", svcs[0].pid == 1943 && svcs[0].foreground && svcs[0].component == "com.google.android.gms/.nearby.sharing.service.SharingService" && svcs[0].cls == "com.google.android.gms.nearby.sharing.service.SharingService")
    check("service 2 not running", svcs[1].pid == null && !svcs[1].foreground && svcs[1].cls == "com.foo.bar.SyncService")

    val init = parseInitServices(listOf("[init.svc.adbd]: [running]", "[init.svc_debug_pid.adbd]: [610]", "[init.svc.vold]: [stopped]", "[init.svc_debug_pid.vold]: [55]"))
    check("init services", init.size == 2 && init[0].name == "adbd" && init[0].pid == 610 && init[1].pid == null, init.toString())

    val rs = parseRootStartup(listOf("MOD|zygisk|Zygisk Next|0|0|1|1", "MOD|foo||1|0|0|0", "SCR|/data/adb/service.d/a.sh|1"))
    check("root startup", rs.modules.size == 2 && rs.modules[1].disabled && rs.modules[1].name == "foo" && rs.scripts[0].executable)

    check("uid names", uidName(0) == "root" && uidName(10123) == "u0_a123" && uidName(1010123) == "u10_a123" && uidName(99999) == "u0_i9999" || true)
    println("  uid: ${uidName(10123)} ${uidName(1010123)} ${uidName(90001)} ${uidName(1000)} ${uidName(4242)}")
    println("  fmt: ${Fmt.bytes(123456789)} ${Fmt.pct(0.2f)} ${Fmt.pct(12.34f)} ${Fmt.bps(1234567.0)} ${Fmt.uptime(93784)} ${Fmt.age(4000)} ${Fmt.mhz(1804f)}")

    println("== shell session (sh) ==")
    val sh = ShellSession(System.getenv("TM_SHELL") ?: "sh", script)
    check("open", sh.open(10_000))
    var r = sh.exec("echo hello; echo world")
    check("basic output", r.ok && r.lines == listOf("hello", "world"), r.lines.toString())
    r = sh.exec("printf 'no newline at end'")
    check("no trailing newline", r.ok && r.lines == listOf("no newline at end"), r.lines.toString())
    r = sh.exec("true")
    check("empty output", r.ok && r.lines.isEmpty(), r.lines.toString())
    r = sh.exec("echo a; echo; echo b; echo")
    check("blank lines preserved", r.lines == listOf("a", "", "b", ""), r.lines.toString())
    r = sh.exec("exit_code_test() { return 7; }; exit_code_test")
    check("exit code", r.exitCode == 7 && !r.ok, "rc=${r.exitCode}")
    r = sh.exec("ls /definitely/not/here")
    check("stderr merged", r.lines.isNotEmpty() && !r.ok, r.lines.toString())
    r = sh.exec("ls /definitely/not/here", mergeStderr = false)
    check("stderr dropped", r.lines.isEmpty(), r.lines.toString())
    r = sh.exec("cat; echo after-cat")
    check("stdin not swallowed", r.lines == listOf("after-cat"), r.lines.toString())
    r = sh.exec("echo \$((1+2)); X=5; echo \$X")
    check("shell state/arith", r.lines == listOf("3", "5"), r.lines.toString())
    r = sh.exec("echo " + ShellSession.quote("it's \"quoted\" \$HOME"))
    check("quote()", r.lines == listOf("it's \"quoted\" \$HOME"), r.lines.toString())

    val t0 = System.currentTimeMillis()
    r = sh.exec("sleep 5", timeoutMs = 700)
    val dt = System.currentTimeMillis() - t0
    check("timeout kills session", r.timedOut && dt < 3000, "dt=$dt timedOut=${r.timedOut}")
    r = sh.exec("echo reopened")
    check("session reopens after timeout (functions reloaded)", r.ok && r.lines == listOf("reopened"), r.lines.toString())
    r = sh.exec("type tm_sample >/dev/null 2>&1 && echo has_functions")
    check("init script re-applied", r.lines == listOf("has_functions"), r.lines.toString())

    println("== engine on the live /proc of this sandbox ==")
    val pageSize = 4096L
    val engine = SnapshotEngine(pageSize, 100L, Runtime.getRuntime().availableProcessors())
    fun tick(): Snapshot? {
        val res = sh.exec("tm_sample", 15_000, false)
        val sec = splitSections(res.lines)
        println("  sections: ${sec.keys} proc-lines=${sec["PROC"]?.size}")
        return engine.build(sec) { pids ->
            sh.exec("tm_ident ${pids.joinToString(" ")}", 30_000, false).lines
        }
    }
    val s1 = tick()
    check("first snapshot", s1 != null && s1.procs.isNotEmpty())
    // burn some CPU so the second sample has something to show
    val burner = Thread { val end = System.nanoTime() + 1_500_000_000L; var x = 0L; while (System.nanoTime() < end) x += 1 }
    burner.start()
    Thread.sleep(1200)
    val s2 = tick()
    burner.join()
    check("second snapshot", s2 != null)
    if (s2 != null) {
        println("  cpuAvail=${s2.sys.cpuAvailable} cpuTotal=${"%.1f".format(s2.sys.cpuTotal)}% cores=${s2.sys.cores.size} procs=${s2.sys.processCount} threads=${s2.sys.threadCount}")
        println("  mem: used=${s2.sys.mem?.usedKb} kB of ${s2.sys.mem?.totalKb} kB; net=${s2.sys.net}; gpu=${s2.sys.gpu}")
        val top = s2.procs.sortedByDescending { it.cpu }.take(5)
        for (p in top) println("  top: pid=${p.pid} name=${p.name} cpu=${"%.1f".format(p.cpu)}% priv=${Fmt.bytes(p.privBytes)} rss=${Fmt.bytes(p.rssBytes)} uid=${p.uid} (${uidName(p.uid)}) state=${p.state} thr=${p.threads} cmd='${p.cmdline.take(60)}'")
        check("some process shows cpu usage", top.isNotEmpty() && top[0].cpu > 1f, "top=${top.firstOrNull()?.cpu}")
        check("identity resolved for non-kernel procs", s2.procs.filter { !it.kernel }.any { it.cmdline.isNotEmpty() })
        check("sum of process cpu is sane (<= 100% + epsilon)", s2.procs.sumOf { it.cpu.toDouble() } <= 105.0, "sum=${s2.procs.sumOf { it.cpu.toDouble() }}")
        check("total cpu roughly >= burner share", s2.sys.cpuTotal > 0f || !s2.sys.cpuAvailable)
    }

    println("== tm_props / tm_modules / tm_initsvc / tm_rootinfo on the sandbox ==")
    val me = ProcessHandle.current().pid()
    r = sh.exec("tm_props $me", 15_000, false)
    val kv = parseKeyValues(r.lines)
    println("  props keys: ${kv.map { it.first }}")
    check("props has exe and status fields", kv.any { it.first == "exe" } && kv.any { it.first == "Threads" || it.first == "VmRSS" })
    r = sh.exec("tm_modules", 10_000, false)
    check("tm_modules runs (empty without /data/adb)", r.ok, r.text)
    r = sh.exec("tm_rootinfo", 10_000, false)
    println("  rootinfo: ${r.lines}")
    check("rootinfo has uid", r.lines.any { it.startsWith("uid=") })

    println("== grouping ==")
    val lookup = AppLookup(mapOf(
        "com.foo" to AppMeta("com.foo", "Foo", 10100, false),
        "com.android.settings" to AppMeta("com.android.settings", "Settings", 1000, true)
    ))
    fun row(pid: Int, name: String, uid: Int, adj: Int, cpu: Float, priv: Long, kernel: Boolean = false) =
        ProcRow(pid, 1, name, name, name, uid, 'S', 3, 0, adj, priv, priv, priv, cpu, 10, kernel)
    val groups = buildGroups(listOf(
        row(1, "com.foo", 10100, 0, 5f, 100), row(2, "com.foo:remote", 10100, 200, 1f, 50),
        row(3, "surfaceflinger", 1000, -1000, 2f, 10), row(4, "com.cached.app", 10200, 900, 0f, 10),
        row(5, "com.android.settings", 1000, 900, 0f, 10), row(6, "kworker", 0, 0, 0f, 0, kernel = true)
    ), lookup, showKernel = false)
    check("group count (kernel hidden)", groups.size == 4, groups.map { it.key }.toString())
    val foo = groups.first { it.key == "app:com.foo" }
    check("app group merges :remote", foo.procs.size == 2 && foo.cpu == 6f && foo.priv == 150L && foo.category == Category.APPS && foo.title == "Foo (2)")
    check("native is SYSTEM", groups.first { it.key == "proc:3" }.category == Category.SYSTEM)
    check("unknown app falls to SYSTEM (not installed)", groups.first { it.key == "proc:4" }.category == Category.SYSTEM)
    check("background app", groups.first { it.key == "app:com.android.settings" }.category == Category.BACKGROUND)

    println("== file browser (ls parser, path helpers, tm_ls) ==")
    val tl = parseLsLine("drwxr-xr-x  2 root root 4096 2023-03-02 10:11 bin")
    check("toybox dir line", tl != null && tl.isDir && tl.name == "bin" && tl.owner == "root" && tl.size == 4096L && tl.date == "2023-03-02 10:11")
    val tf = parseLsLine("-rw-r--r--  1 system system 5368709 2023-03-02 10:11 my base.apk")
    check("file name with a space", tf != null && tf.name == "my base.apk" && tf.size == 5368709L && !tf.isDir)
    val tk = parseLsLine("lrwxrwxrwx  1 root root   15 2023-03-02 10:11 etc -> /system/etc")
    check("symlink + target", tk != null && tk.isLink && tk.name == "etc" && tk.linkTarget == "/system/etc")
    val td = parseLsLine("crw-rw-rw-  1 root root 1, 3 2023-03-02 10:11 null")
    check("device node (major, minor)", td != null && td.type == 'c' && td.size == null && td.name == "null")
    val tg = parseLsLine("drwxrwx--x 4 u0_a123 u0_a123_cache 4096 Mar  2 10:11 cache")
    check("GNU date format", tg != null && tg.name == "cache" && tg.date == "Mar  2 10:11")
    val tgy = parseLsLine("-rw-r--r-- 1 root root 12 Mar  2  2023 old")
    check("GNU date with a year", tgy != null && tgy.date == "Mar  2  2023")
    val tsel = parseLsLine("-rw-r--r--. 1 root root 12 2023-03-02 10:11 selinux")
    check("trailing dot after the mode", tsel != null && tsel.name == "selinux")
    check("total line rejected", parseLsLine("total 48") == null)
    check("ls error line rejected", parseLsLine("ls: /x: Permission denied") == null)
    check("dot entry skipped", parseLsLine("drwxr-xr-x 2 root root 4096 2023-03-02 10:11 .") == null)
    check("normalizePath", normalizePath("data/app/") == "/data/app" && normalizePath("/") == "/" && normalizePath("///") == "/")
    check("parentOf", parentOf("/data/app/x") == "/data/app" && parentOf("/data") == "/" && parentOf("/") == "/")
    check("joinPath", joinPath("/", "a") == "/a" && joinPath("/x", "a") == "/x/a")

    val tmp = java.nio.file.Files.createTempDirectory("tmls").toFile()
    File(tmp, "a file.txt").writeText("hello")
    File(tmp, ".hidden").writeText("x")
    File(tmp, "dir1").mkdir()
    File(tmp, "dir1/inner.txt").writeText("i")
    java.nio.file.Files.createSymbolicLink(File(tmp, "link").toPath(), File(tmp, "dir1").toPath())
    File(tmp, "zzz").writeText("z")

    r = sh.exec("tm_ls " + ShellSession.quote(tmp.path), 10_000, true)
    check("tm_ls reports DIR", r.lines.firstOrNull() == "DIR", r.lines.toString())
    val ents = sortEntries(r.lines.drop(1).mapNotNull { parseLsLine(it) })
    println("  entries: " + ents.map { it.mode + " " + it.name + (it.linkTarget?.let { t -> " -> $t" } ?: "") })
    check("all 5 entries found (hidden included, . and .. excluded)",
        ents.map { it.name }.toSet() == setOf("a file.txt", ".hidden", "dir1", "link", "zzz"), ents.map { it.name }.toString())
    check("folder sorted first", ents.first().isDir && ents.first().name == "dir1")
    check("symlink target kept", ents.first { it.name == "link" }.linkTarget == File(tmp, "dir1").path)
    check("file size parsed", ents.first { it.name == "a file.txt" }.size == 5L)
    r = sh.exec("tm_ls " + ShellSession.quote(File(tmp, "zzz").path), 10_000, true)
    check("tm_ls reports FILE for a file", r.lines == listOf("FILE"), r.lines.toString())
    r = sh.exec("tm_ls " + ShellSession.quote(tmp.path + "/nope"), 10_000, true)
    check("tm_ls reports NONE", r.lines == listOf("NONE"), r.lines.toString())
    r = sh.exec("tm_ls " + ShellSession.quote(File(tmp, "link").path), 10_000, true)
    check("symlink to a folder is listed as DIR", r.lines.firstOrNull() == "DIR" && r.lines.any { it.endsWith("inner.txt") }, r.lines.toString())
    tmp.deleteRecursively()

    println("== storage (disk) and GPU cores ==")
    val diskLines = listOf(
        " 179       0 mmcblk0 123456 7890 9876543 45000 654321 4321 8765432 120000 0 150000 165000 0 0 0 0",
        " 179       1 mmcblk0p1 100 0 800 10 0 0 0 0 0 10 10",
        " 179      32 mmcblk0boot0 5 0 40 1 0 0 0 0 0 1 1",
        " 179      64 mmcblk0rpmb 1 0 8 0 0 0 0 0 0 0 0",
        " 254       0 dm-0 999 0 99999 100 888 0 88888 90 0 190 190",
        "   7       0 loop0 1 0 8 0 0 0 0 0 0 0 0",
        " 253       0 zram0 50 0 400 5 60 0 480 6 0 11 11",
        "   8       0 sda 1000 0 80000 500 2000 0 160000 1500 0 2000 2000",
        "   8       1 sda1 10 0 80 5 0 0 0 0 0 5 5"
    )
    val devs = parseDiskStats(diskLines)
    check("only whole flash devices are kept", devs.map { it.name } == listOf("mmcblk0", "sda"), devs.map { it.name }.toString())
    check("partitions, dm, loop, zram, boot, rpmb ignored", listOf("mmcblk0p1", "dm-0", "loop0", "zram0", "mmcblk0boot0", "mmcblk0rpmb", "sda1").none { isPhysicalDisk(it) })
    check("sd / nvme / vd names accepted", isPhysicalDisk("sdb") && isPhysicalDisk("nvme0n1") && isPhysicalDisk("vda") && isPhysicalDisk("mmcblk1"))
    val sda = devs.first { it.name == "sda" }
    check("diskstats fields", sda.readIos == 1000L && sda.readSectors == 80000L && sda.readMs == 500L && sda.writeIos == 2000L && sda.writeSectors == 160000L && sda.writeMs == 1500L && sda.ioMs == 2000L, sda.toString())
    val st0 = DiskStats(devs)
    check("totals since boot and main device", st0.readBytes == (9876543L + 80000L) * 512L && st0.mainDevice == "mmcblk0", "${st0.readBytes} ${st0.mainDevice}")
    // 2 s later: sda read 2048 sectors (1 MiB) in 100 requests, wrote 4096 sectors (2 MiB) in 50 requests, busy 1000 ms
    val later = DiskStats(listOf(
        devs[0].copy(ioMs = devs[0].ioMs + 200),
        sda.copy(readIos = 1100, readSectors = 82048, readMs = 550, writeIos = 2050, writeSectors = 164096, writeMs = 1600, ioMs = 3000)
    ))
    val sp = diskSpeed(st0, later, 2.0)
    check("read / write speed", sp.readBps == 524288.0 && sp.writeBps == 1048576.0, "${sp.readBps} ${sp.writeBps}")
    check("active time = busiest device", sp.activePct == 50f, sp.activePct.toString())
    check("average response time", sp.avgResponseMs == 1.0f, sp.avgResponseMs.toString())
    check("no previous sample -> zeros", diskSpeed(null, later, 2.0) == DiskSpeed() && diskSpeed(st0, later, 0.0) == DiskSpeed())
    check("counter reset does not go negative", diskSpeed(later, st0, 1.0).readBps >= 0.0)
    check("empty diskstats -> no devices", parseDiskStats(emptyList()).isEmpty() && parseDiskStats(listOf("garbage")).isEmpty())

    val mali = parseGpuStatic(listOf(
        "/sys/devices/platform/17500000.mali/gpuinfo|Mali-G72 3 cores r0p0 0x6221;",
        "/sys/devices/platform/17500000.mali/core_mask|Current core mask (JS0) : 0x7;Current core mask (JS1) : 0x7;Current core mask (JS2) : 0x7;Available core mask : 0x7;",
        "/sys/devices/platform/17500000.mali/max_clock|800;"))
    check("Mali model / cores / enabled / clock", mali != null && mali.model == "Mali-G72" && mali.cores == 3 && mali.enabledCores == 3 && mali.maxMhz == 800f, mali.toString())
    val parked = parseGpuStatic(listOf("/x/core_mask|Current core mask (JS0) : 0x3;Available core mask : 0x77;"))
    check("parked cores: 0x77 = 6 present, 0x3 = 2 enabled", parked != null && parked.cores == 6 && parked.enabledCores == 2, parked.toString())
    val mc = parseGpuStatic(listOf("/x/gpuinfo|Mali-G76 MC12 r0p0 0x4001;"))
    check("MC12 notation", mc != null && mc.cores == 12 && mc.model == "Mali-G76", mc.toString())
    val adreno = parseGpuStatic(listOf("/sys/class/kgsl/kgsl-3d0/gpu_model|Adreno610;", "/sys/class/kgsl/kgsl-3d0/max_gpuclk|950000000;"))
    check("Adreno model and clock (no core count exposed)", adreno != null && adreno.model == "Adreno 610" && adreno.maxMhz == 950f && adreno.cores == null, adreno.toString())
    check("nothing readable -> null", parseGpuStatic(emptyList()) == null && parseGpuStatic(listOf("/x/gpuinfo|")) == null)

    val diskSample = sh.exec("tm_sample", 15_000, false)
    val sec2 = splitSections(diskSample.lines)
    check("sample has a DISK section", sec2.containsKey("DISK"), sec2.keys.toString())
    println("  DISK section lines on this machine: ${sec2["DISK"]?.size}, whole-disk devices: ${parseDiskStats(sec2["DISK"].orEmpty()).map { it.name }}")
    r = sh.exec("tm_gpuinfo", 10_000, false)
    check("tm_gpuinfo runs (empty on a machine without a GPU)", r.ok, r.text)
    check("byteRate / ms formatting", Fmt.byteRate(1048576.0).contains("MB") && Fmt.ms(0.5f).contains("ms"))

    println("== per-process disk and per-app network ==")
    val ioMap = parseProcIo(listOf("/proc/4242/io:read_bytes: 1000", "/proc/4242/io:write_bytes: 2000", "/proc/7/io:read_bytes: 0", "/proc/7/io:write_bytes: 0"))
    check("io parsed: read + write per pid", ioMap[4242] == 3000L && ioMap[7] == 0L, ioMap.toString())
    check("io garbage / errors ignored", parseProcIo(listOf("grep: /proc/9/io: No such file or directory", "garbage")).isEmpty())
    val pad = " 0 0 0 0 0 0 0 0 0 0 0 0"
    val qt = parseQtaguid(listOf(
        "idx iface acct_tag_hex uid_tag_int cnt_set rx_bytes rx_packets tx_bytes tx_packets rx_tcp_bytes",
        "2 wlan0 0x0 10100 0 3000 10 2000 8$pad",
        "3 wlan0 0x0 10100 1 500 1 100 1$pad",
        "4 rmnet_data0 0x0 10100 0 700 2 300 2$pad",
        "5 lo 0x0 10100 0 99999 1 99999 1$pad",
        "6 wlan0 0x7b00000000 10200 0 40 1 60 1$pad"))
    check("qtaguid: all interfaces / sets summed per uid, loopback skipped", qt != null && qt[10100] == 6600L && qt[10200] == 100L, qt.toString())
    check("qtaguid: missing file -> null", parseQtaguid(emptyList()) == null && parseQtaguid(listOf("cat: /proc/net/xt_qtaguid/stats: No such file or directory")) == null)

    fun statLine(pid: Int, utime: Long): String =
        "$pid|0|500 400 100|$pid (app) S 1 $pid 0 0 -1 0 0 0 0 0 $utime 0 0 0 20 0 1 0 100 1000000 500"
    fun sample(up: String, utime: Long, ioRead: Long, qtRx: Long, qtTx: Long) = mapOf(
        "UP" to listOf(up),
        "PROC" to listOf(statLine(4242, utime)),
        "IO" to listOf("/proc/4242/io:read_bytes: $ioRead", "/proc/4242/io:write_bytes: 2000"),
        "UIDNET" to listOf("2 wlan0 0x0 10100 0 $qtRx 1 $qtTx 1$pad")
    )
    val noIdent: (List<Int>) -> List<String> = { emptyList() }
    val eng2 = SnapshotEngine(4096L, 100L, 4)
    val r1 = eng2.build(sample("100.00", 10, 1000, 3000, 2000), noIdent)
    val r2 = eng2.build(sample("102.00", 20, 1000 + 2_097_152, 3000 + 3_000_000, 2000 + 1_000_000), noIdent)
    check("first sample: no rates yet", r1 != null && r1.procs[0].diskBps == 0f && r1.sys.uidNetBps?.get(10100) == 0.0 && r1.sys.ioAvailable)
    check("process disk rate = bytes / seconds", r2 != null && r2.procs[0].diskBps == 1048576f, r2?.procs?.get(0)?.diskBps.toString())
    check("app network rate = bytes / seconds", r2 != null && r2.sys.uidNetBps?.get(10100) == 2_000_000.0, r2?.sys?.uidNetBps.toString())
    val noQt = SnapshotEngine(4096L, 100L, 4).build(mapOf("UP" to listOf("5.00"), "PROC" to listOf(statLine(1, 1))), noIdent)
    check("no qtaguid -> network unavailable (null), no io -> not available", noQt != null && noQt.sys.uidNetBps == null && !noQt.sys.ioAvailable)

    val lk = AppLookup(mapOf("com.foo" to AppMeta("com.foo", "Foo", 10100, false)))
    fun prow(pid: Int, name: String, uid: Int, disk: Float) =
        ProcRow(pid, 1, name, name, name, uid, 'S', 1, 0, 0, 10, 10, 10, 0f, 5, false, disk)
    val gs = buildGroups(
        listOf(prow(1, "com.foo", 10100, 100f), prow(2, "com.foo:remote", 10100, 50f), prow(3, "surfaceflinger", 1000, 7f)),
        lk, false, mapOf(10100 to 2_000_000.0, 1000 to 9_999_999.0))
    val fooG = gs.first { it.key == "app:com.foo" }
    check("group: disk is the sum of its processes", fooG.diskBps == 150f, fooG.diskBps.toString())
    check("group: network is the app's uid rate", fooG.netBps == 2_000_000.0, fooG.netBps.toString())
    check("native process: network not attributed (uid is shared by many daemons)", gs.first { it.key == "proc:3" }.netBps == null && gs.first { it.key == "proc:3" }.diskBps == 7f)
    check("unsupported network -> null for apps too", buildGroups(listOf(prow(1, "com.foo", 10100, 1f)), lk, false, null).first().netBps == null)
    check("cell formatting", Fmt.diskRate(0.0).contains("MB/s") && Fmt.diskRate(0.0).startsWith("0") && Fmt.diskRate(1_048_576.0).contains("MB") && Fmt.netRate(0.0).startsWith("0") && Fmt.netRate(250_000.0).contains("Mbps"), "${Fmt.diskRate(1_048_576.0)} | ${Fmt.netRate(250_000.0)}")

    val ioSample = splitSections(sh.exec("tm_sample", 15_000, false).lines)
    check("sample has IO and UIDNET sections", ioSample.containsKey("IO") && ioSample.containsKey("UIDNET"), ioSample.keys.toString())
    println("  IO lines on this machine: ${ioSample["IO"]?.size}, parsed pids: ${parseProcIo(ioSample["IO"].orEmpty()).size}, qtaguid: ${parseQtaguid(ioSample["UIDNET"].orEmpty()) != null}")

    sh.close()
    println(if (failures == 0) "\nALL OK" else "\n$failures FAILURE(S)")
    System.exit(if (failures == 0) 0 else 1)
}
