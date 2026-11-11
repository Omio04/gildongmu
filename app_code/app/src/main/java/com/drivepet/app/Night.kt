package com.drivepet.app

import android.content.Context
import android.content.res.Configuration

/**
 * Light or dark: 자동 follows the car's own day/night setting (the system's uiMode), the other two are fixed.
 * The room (night sky outside the window, the lamp lit) and the map collection look at [isDark].
 */
object Night {
    const val AUTO = 0
    const val LIGHT = 1
    const val DARK = 2
    private const val FILE = "ui"

    @Volatile var mode = AUTO
        private set
    private var loaded = false

    private fun load(c: Context) {
        if (loaded) return
        loaded = true
        mode = try { c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt("night", LIGHT) } catch (_: Exception) { LIGHT }
        if (mode == AUTO) mode = LIGHT   // there is no automatic mode any more: the driver switches with the button
    }

    fun set(c: Context, m: Int) {
        load(c)
        mode = m
        try { c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putInt("night", m).apply() } catch (_: Exception) {}
    }

    fun current(c: Context): Int { load(c); return mode }

    /** dark only when the driver has switched it on with the button */
    fun isDark(c: Context): Boolean { load(c); return mode == DARK }
}
