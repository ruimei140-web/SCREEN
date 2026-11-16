package com.wordlookup.screen.data

import android.content.Context

enum class DockSide { LEFT, RIGHT }

object AppPreferences {
    private const val PREF_NAME = "screen_word_lookup_prefs"
    private const val KEY_DOCK_SIDE = "dock_side"
    private const val KEY_DOCK_Y_RATIO = "dock_y_ratio"
    private const val KEY_AUTO_EXIT = "auto_exit_after_save"
    private const val KEY_DARK_MODE = "dark_mode_override"

    fun isAutoExitAfterSave(context: Context): Boolean {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_EXIT, true)
    }

    fun setAutoExitAfterSave(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_EXIT, enabled)
            .apply()
    }

    fun isDarkMode(context: Context): Boolean {
        val sysNight = (context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DARK_MODE, sysNight)
    }

    fun setDarkMode(context: Context, dark: Boolean) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DARK_MODE, dark)
            .apply()
    }

    fun getDockSide(context: Context): DockSide {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_DOCK_SIDE, DockSide.RIGHT.name)
        return runCatching { DockSide.valueOf(raw ?: DockSide.RIGHT.name) }
            .getOrDefault(DockSide.RIGHT)
    }

    fun setDockSide(context: Context, side: DockSide) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DOCK_SIDE, side.name)
            .apply()
    }

    fun getDockYRatio(context: Context): Float {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getFloat(KEY_DOCK_Y_RATIO, 0.42f)
            .coerceIn(0.12f, 0.85f)
    }

    fun setDockYRatio(context: Context, ratio: Float) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_DOCK_Y_RATIO, ratio.coerceIn(0.12f, 0.85f))
            .apply()
    }
}
r