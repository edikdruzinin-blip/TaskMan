package com.taskman.root.ui

import android.content.Context

/** Tiny SharedPreferences wrapper for the few settings the app keeps. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("taskman", Context.MODE_PRIVATE)

    fun theme(): ThemeMode = enumOf(sp.getString("theme", null), ThemeMode.SYSTEM)

    fun speed(): UpdateSpeed = enumOf(sp.getString("speed", null), UpdateSpeed.HIGH)

    fun showKernel(): Boolean = sp.getBoolean("show_kernel", false)

    fun putTheme(m: ThemeMode) {
        sp.edit().putString("theme", m.name).apply()
    }

    /** "Paused" is deliberately not stored: the app should not start paused. */
    fun putSpeed(s: UpdateSpeed) {
        if (s != UpdateSpeed.PAUSED) sp.edit().putString("speed", s.name).apply()
    }

    fun putShowKernel(v: Boolean) {
        sp.edit().putBoolean("show_kernel", v).apply()
    }

    private inline fun <reified E : Enum<E>> enumOf(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default
}
