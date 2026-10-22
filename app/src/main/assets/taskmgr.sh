# TaskMan helper functions.
# Sourced ONCE into a persistent shell (su or sh); the app then calls the tm_* functions.
# Must stay POSIX / mksh compatible: no arrays, no [[ ]], no `local`.
# Every function prints plain text that the Kotlin side parses (see core/Parsers.kt).

# Output helper that never forks: mksh (Android's sh) has no builtin printf, but has `print`.
if [ -n "$KSH_VERSION" ]; then
  tm_out() { print -r -- "$1"; }
else
  tm_out() { printf '%s\n' "$1"; }
fi

# Everything needed for one refresh tick: CPU, memory, network, GPU, zram, all processes.
tm_sample() {
  read -r tm_up tm_x < /proc/uptime
  echo "@@UP $tm_up"
  echo "@@CPU"
  grep '^cpu' /proc/stat
  echo "@@MEM"
  cat /proc/meminfo
  echo "@@NET"
  cat /proc/net/dev
  echo "@@FREQ"
  for tm_c in /sys/devices/system/cpu/cpu[0-9]*; do
    tm_f=; tm_m=; tm_o=1
    read -r tm_f < "$tm_c/cpufreq/scaling_cur_freq"
    read -r tm_m < "$tm_c/cpufreq/cpuinfo_max_freq"
    [ -r "$tm_c/online" ] && read -r tm_o < "$tm_c/online"
    tm_out "${tm_c##*/cpu}|$tm_f|$tm_m|$tm_o"
  done
  echo "@@GPU"
  # Known GPU sysfs nodes: Adreno (kgsl), Mali (Exynos / Tensor / generic), MediaTek GED, devfreq fallbacks.
  for tm_f in \
    /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage \
    /sys/class/kgsl/kgsl-3d0/gpubusy \
    /sys/class/kgsl/kgsl-3d0/devfreq/cur_freq \
    /sys/class/kgsl/kgsl-3d0/gpuclk \
    /sys/devices/platform/*mali*/utilization \
    /sys/devices/platform/*mali*/clock \
    /sys/devices/platform/*mali*/cur_freq \
    /sys/devices/platform/*mali*/devfreq/*/cur_freq \
    /sys/kernel/gpu/gpu_busy \
    /sys/kernel/gpu/gpu_clock \
    /sys/kernel/ged/hal/gpu_utilization \
    /sys/module/ged/parameters/gpu_loading \
    /sys/class/devfreq/*mali*/cur_freq \
    /sys/class/devfreq/*gpu*/cur_freq \
    /sys/class/devfreq/*gpu*/load ; do
    [ -r "$tm_f" ] || continue
    tm_v=; read -r tm_v < "$tm_f"
    tm_out "$tm_f|$tm_v"
  done
  echo "@@ZRAM"
  tm_v=; read -r tm_v < /sys/block/zram0/mm_stat; echo "$tm_v"
  tm_v=; read -r tm_v < /sys/block/zram0/disksize; echo "$tm_v"
  echo "@@DISK"
  cat /proc/diskstats
  echo "@@IO"
  # bytes each process really read from / wrote to storage (needs root for other apps' processes)
  grep -H -E '^(read_bytes|write_bytes):' /proc/[0-9]*/io
  echo "@@UIDNET"
  # per-app (uid) network counters; only kernels that still have xt_qtaguid expose them
  cat /proc/net/xt_qtaguid/stats
  echo "@@PROC"
  # one line per process: pid|oom_score_adj|statm|stat  (builtins only, no forks)
  for tm_d in /proc/[0-9]*; do
    tm_p=${tm_d#/proc/}
    tm_s=; read -r tm_s < "$tm_d/stat" || continue
    tm_m=; read -r tm_m < "$tm_d/statm"
    tm_a=0; read -r tm_a < "$tm_d/oom_score_adj"
    tm_out "$tm_p|$tm_a|$tm_m|$tm_s"
  done
  echo "@@END"
}

# Identity of processes we have not seen yet: pid|uid|cmdline   (args = pids)
tm_ident() {
  for tm_p in "$@"; do
    tm_u=
    while read -r tm_k tm_v tm_r; do
      if [ "$tm_k" = "Uid:" ]; then tm_u=$tm_v; break; fi
    done < "/proc/$tm_p/status"
    tm_cl=$(tr '\000' ' ' < "/proc/$tm_p/cmdline")
    tm_out "$tm_p|$tm_u|$tm_cl"
  done
}

# Detailed properties of one process (arg = pid): key|value lines
tm_props() {
  tm_p=$1
  echo "exe|$(readlink /proc/$tm_p/exe 2>/dev/null)"
  echo "cwd|$(readlink /proc/$tm_p/cwd 2>/dev/null)"
  echo "cmd|$(tr '\000' ' ' < /proc/$tm_p/cmdline 2>/dev/null)"
  echo "selinux|$(tr -d '\000' < /proc/$tm_p/attr/current 2>/dev/null)"
  echo "oom_score|$(cat /proc/$tm_p/oom_score 2>/dev/null)"
  echo "fds|$(ls /proc/$tm_p/fd 2>/dev/null | wc -l)"
  grep -E '^(State|PPid|Uid|Gid|Threads|VmPeak|VmSize|VmRSS|VmHWM|RssAnon|RssFile|RssShmem|VmSwap|Cpus_allowed_list|voluntary_ctxt_switches|nonvoluntary_ctxt_switches):' /proc/$tm_p/status 2>/dev/null | sed 's/:[[:space:]]*/|/'
  grep -E '^(Pss|Pss_Anon|Pss_File|Pss_Shmem|Private_Clean|Private_Dirty|Swap|SwapPss):' /proc/$tm_p/smaps_rollup 2>/dev/null | sed 's/:[[:space:]]*/|/'
  echo "cgroup|$(head -n 6 /proc/$tm_p/cgroup 2>/dev/null | tr '\n' ';')"
}

# Magisk / KernelSU / APatch modules and boot scripts:
#   MOD|id|name|disabled|remove|has_service.sh|has_post-fs-data.sh
#   SCR|path|executable
tm_modules() {
  for tm_m in /data/adb/modules/*; do
    [ -d "$tm_m" ] || continue
    tm_n=
    while IFS= read -r tm_l; do
      case $tm_l in name=*) tm_n=${tm_l#name=}; break;; esac
    done < "$tm_m/module.prop"
    tm_dis=0; [ -e "$tm_m/disable" ] && tm_dis=1
    tm_rm=0;  [ -e "$tm_m/remove" ] && tm_rm=1
    tm_sv=0;  [ -f "$tm_m/service.sh" ] && tm_sv=1
    tm_pf=0;  [ -f "$tm_m/post-fs-data.sh" ] && tm_pf=1
    tm_out "MOD|${tm_m##*/}|$tm_n|$tm_dis|$tm_rm|$tm_sv|$tm_pf"
  done
  for tm_f in /data/adb/service.d/* /data/adb/post-fs-data.d/*; do
    [ -f "$tm_f" ] || continue
    tm_x=0; [ -x "$tm_f" ] && tm_x=1
    tm_out "SCR|$tm_f|$tm_x"
  done
}

# Native init services: lines like  [init.svc.adbd]: [running]  and  [init.svc_debug_pid.adbd]: [610]
tm_initsvc() {
  getprop | grep '^\[init\.svc'
}

# Running Android (framework) services, reduced to the lines we parse.
tm_services() {
  dumpsys activity services | grep -E '^ *\* ServiceRecord\{|^ +(packageName|processName|app|isForeground)='
}

# Directory listing for the in-app file browser. First line: DIR, FILE or NONE; for DIR the `ls -lA` lines follow.
# (a symlink to a folder counts as DIR because `[ -d ]` follows links)
tm_ls() {
  if [ -d "$1" ]; then
    echo "DIR"
    ls -lA "$1/"
  elif [ -e "$1" ] || [ -L "$1" ]; then
    echo "FILE"
  else
    echo "NONE"
  fi
}

# Static GPU facts from the driver (core count / mask, model, max clock), read once:
# one "path|value" line per file that exists; line breaks inside a file are turned into ';'.
tm_gpuinfo() {
  for tm_f in \
    /sys/devices/platform/*mali*/gpuinfo \
    /sys/class/misc/mali0/device/gpuinfo \
    /sys/devices/platform/*mali*/core_mask \
    /sys/class/misc/mali0/device/core_mask \
    /sys/devices/platform/*mali*/max_clock \
    /sys/class/devfreq/*mali*/max_freq \
    /sys/class/devfreq/*gpu*/max_freq \
    /sys/class/kgsl/kgsl-3d0/gpu_model \
    /sys/class/kgsl/kgsl-3d0/max_gpuclk ; do
    [ -r "$tm_f" ] || continue
    tm_v=$(tr '\n' ';' < "$tm_f")
    tm_out "$tm_f|$tm_v"
  done
}

# Root manager detection: key=value lines
tm_rootinfo() {
  echo "uid=$(id -u)"
  [ -e /data/adb/ksud ] && echo "ksu=1"
  [ -e /data/adb/apd ] && echo "apatch=1"
  [ -e /data/adb/magisk ] && echo "magisk=1"
  [ -e /data/adb/magisk.db ] && echo "magisk=1"
  echo "gles=$(dumpsys SurfaceFlinger 2>/dev/null | grep -m 1 'GLES:')"
}
