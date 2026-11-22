package com.taskman.root.core

import java.util.Locale

object Fmt {
    private fun ru(): Boolean = Locale.getDefault().language == "ru"

    fun bytes(b: Long): String {
        val u = if (ru()) arrayOf("Б", "КБ", "МБ", "ГБ", "ТБ") else arrayOf("B", "KB", "MB", "GB", "TB")
        var v = b.toDouble()
        var i = 0
        while (v >= 1024.0 && i < u.size - 1) {
            v /= 1024.0
            i++
        }
        val pattern = when {
            i == 0 -> "%.0f %s"
            v >= 100 -> "%.0f %s"
            v >= 10 -> "%.1f %s"
            else -> "%.2f %s"
        }
        return String.format(Locale.getDefault(), pattern, v, u[i])
    }

    fun kb(kb: Long): String = bytes(kb * 1024L)

    /** CPU-like percentage for table cells: 0%, 1,3%, 27% */
    fun pct(v: Float): String = when {
        v < 0.05f -> "0%"
        v < 10f -> String.format(Locale.getDefault(), "%.1f%%", v)
        else -> String.format(Locale.getDefault(), "%.0f%%", v)
    }

    fun pct0(v: Float): String = String.format(Locale.getDefault(), "%.0f%%", v)

    fun mhz(mhz: Float): String =
        if (mhz >= 1000f) String.format(Locale.getDefault(), "%.2f %s", mhz / 1000f, if (ru()) "ГГц" else "GHz")
        else String.format(Locale.getDefault(), "%.0f %s", mhz, if (ru()) "МГц" else "MHz")

    fun khz(khz: Long): String = mhz(khz / 1000f)

    /** Bytes per second for disk transfers: 12,3 MB/s */
    fun byteRate(bytesPerSec: Double): String = bytes(bytesPerSec.toLong()) + (if (ru()) "/с" else "/s")

    /** Disk rate for table cells: "0 MB/s" when idle, otherwise 512 KB/s, 1,2 MB/s ... */
    fun diskRate(bytesPerSec: Double): String =
        if (bytesPerSec < 1024.0) (if (ru()) "0 МБ/с" else "0 MB/s") else byteRate(bytesPerSec)

    /** Network rate for table cells: "0 Mbps" when idle, otherwise 12 Kbps, 1,2 Mbps ... (input in BYTES per second). */
    fun netRate(bytesPerSec: Double): String =
        if (bytesPerSec * 8.0 < 1000.0) (if (ru()) "0 Мбит/с" else "0 Mbps") else bps(bytesPerSec * 8.0)

    /** Milliseconds, e.g. a disk response time. */
    fun ms(v: Float): String = String.format(Locale.getDefault(), if (v < 10f) "%.1f %s" else "%.0f %s", v, if (ru()) "мс" else "ms")

    /** Bits per second, like Windows (Kbps / Mbps). */
    fun bps(bitsPerSec: Double): String {
        val loc = Locale.getDefault()
        val k = if (ru()) "Кбит/с" else "Kbps"
        val m = if (ru()) "Мбит/с" else "Mbps"
        val g = if (ru()) "Гбит/с" else "Gbps"
        val b = if (ru()) "бит/с" else "bps"
        return when {
            bitsPerSec >= 1e9 -> String.format(loc, "%.2f %s", bitsPerSec / 1e9, g)
            bitsPerSec >= 1e6 -> String.format(loc, "%.1f %s", bitsPerSec / 1e6, m)
            bitsPerSec >= 1e3 -> String.format(loc, "%.0f %s", bitsPerSec / 1e3, k)
            else -> String.format(loc, "%.0f %s", bitsPerSec, b)
        }
    }

    /** d:hh:mm:ss like the Windows "Up time" field. */
    fun uptime(sec: Long): String {
        val s = sec.coerceAtLeast(0L)
        val d = s / 86400
        val h = (s % 86400) / 3600
        val m = (s % 3600) / 60
        val x = s % 60
        return String.format(Locale.ROOT, "%d:%02d:%02d:%02d", d, h, m, x)
    }

    /** Short age: 45s, 12m, 3h 20m, 2d 4h */
    fun age(sec: Long): String {
        val s = sec.coerceAtLeast(0L)
        return when {
            s < 60 -> "${s}${if (ru()) "с" else "s"}"
            s < 3600 -> "${s / 60}${if (ru()) "м" else "m"}"
            s < 86400 -> "${s / 3600}${if (ru()) "ч" else "h"} ${(s % 3600) / 60}${if (ru()) "м" else "m"}"
            else -> "${s / 86400}${if (ru()) "д" else "d"} ${(s % 86400) / 3600}${if (ru()) "ч" else "h"}"
        }
    }
}

private val UID_NAMES = mapOf(
    0 to "root", 1000 to "system", 1001 to "radio", 1002 to "bluetooth", 1003 to "graphics",
    1004 to "input", 1005 to "audio", 1006 to "camera", 1007 to "log", 1010 to "wifi",
    1013 to "media", 1017 to "keystore", 1019 to "drm", 1021 to "gps", 1023 to "media_rw",
    1027 to "nfc", 1036 to "logd", 1041 to "audioserver", 1046 to "mediacodec",
    1047 to "cameraserver", 1058 to "tombstoned", 1066 to "statsd", 1069 to "lmkd",
    1072 to "gpu_service", 1073 to "network_stack", 2000 to "shell", 2001 to "cache",
    3001 to "net_bt_admin", 3002 to "net_bt", 3003 to "inet", 3004 to "net_raw",
    3005 to "net_admin", 9999 to "nobody"
)

fun uidName(uid: Int): String {
    val user = uid / 100000
    val appId = uid % 100000
    return when {
        appId in 10000..19999 -> "u${user}_a${appId - 10000}"
        appId in 90000..99999 -> "u${user}_i${appId - 90000}"
        else -> UID_NAMES[appId] ?: "uid$uid"
    }
}
