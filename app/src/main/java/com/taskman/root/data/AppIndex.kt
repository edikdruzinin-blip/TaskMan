package com.taskman.root.data

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.taskman.root.core.AppLookup
import com.taskman.root.core.AppMeta

/** Snapshot of installed packages. Needs QUERY_ALL_PACKAGES (declared in the manifest). */
class AppIndex(private val pm: PackageManager) {

    @Volatile
    var lookup: AppLookup = AppLookup.EMPTY
        private set

    @Suppress("DEPRECATION")
    fun reload() {
        val map = HashMap<String, AppMeta>()
        val apps: List<ApplicationInfo> = try {
            pm.getInstalledApplications(0)
        } catch (e: Exception) {
            emptyList()
        }
        for (ai in apps) {
            val label = try {
                pm.getApplicationLabel(ai).toString()
            } catch (e: Exception) {
                ai.packageName
            }
            val system = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            map[ai.packageName] = AppMeta(ai.packageName, label, ai.uid, system)
        }
        lookup = AppLookup(map)
    }

    fun label(pkg: String): String = lookup.byPkg[pkg]?.label ?: pkg

    fun isSystem(pkg: String): Boolean = lookup.byPkg[pkg]?.system ?: false
}
