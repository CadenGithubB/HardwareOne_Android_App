package com.hardwareone.console.ui

import android.content.Context

/** User's theme choice. SYSTEM follows the OS light/dark setting (the default). */
enum class ThemePreference { SYSTEM, LIGHT, DARK }

/** Tiny SharedPreferences-backed store for the theme choice (no extra dependencies). */
object ThemeStore {
    private const val PREFS = "hw_console_prefs"
    private const val KEY = "theme_pref"

    fun load(context: Context): ThemePreference = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        ThemePreference.valueOf(raw ?: ThemePreference.SYSTEM.name)
    }.getOrDefault(ThemePreference.SYSTEM)

    fun save(context: Context, pref: ThemePreference) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, pref.name)
            .apply()
    }
}
