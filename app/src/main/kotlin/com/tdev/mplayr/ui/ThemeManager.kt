package com.tdev.mplayr.ui

import android.content.Context
import android.content.SharedPreferences

object ThemeManager {

    private const val PREFS = "mplayr_theme"
    private const val KEY   = "theme_id"

    data class AppTheme(
        val id: String,
        val label: String,
        val bg: Int,
        val surface: Int,
        val accent: Int,
        val textPrimary: Int,
        val textDim: Int
    )

    val themes = listOf(
        AppTheme("amoled",  "AMOLED Black",  0xFF000000.toInt(), 0xFF0E0E0E.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFF888888.toInt()),
        AppTheme("dark",    "Dark",          0xFF121212.toInt(), 0xFF1E1E1E.toInt(), 0xFFBB86FC.toInt(), 0xFFFFFFFF.toInt(), 0xFF9E9E9E.toInt()),
        AppTheme("light",   "Light",          0xFFF5F5F5.toInt(), 0xFFFFFFFF.toInt(), 0xFF6200EE.toInt(), 0xFF212121.toInt(), 0xFF757575.toInt()),
        AppTheme("purple",  "Purple Night",  0xFF0D0014.toInt(), 0xFF1A0028.toInt(), 0xFFCE93D8.toInt(), 0xFFEDE7F6.toInt(), 0xFF9C64A6.toInt()),
        AppTheme("blue",    "Ocean Blue",    0xFF001020.toInt(), 0xFF002040.toInt(), 0xFF4FC3F7.toInt(), 0xFFE1F5FE.toInt(), 0xFF4DD0E1.toInt()),
        AppTheme("green",   "Forest",        0xFF001208.toInt(), 0xFF002010.toInt(), 0xFF66BB6A.toInt(), 0xFFE8F5E9.toInt(), 0xFF4CAF50.toInt()),
        AppTheme("red",     "Crimson",       0xFF120000.toInt(), 0xFF200000.toInt(), 0xFFEF5350.toInt(), 0xFFFFEBEE.toInt(), 0xFFE57373.toInt())
    )

    fun getSaved(ctx: Context): AppTheme {
        val id = prefs(ctx).getString(KEY, "amoled") ?: "amoled"
        return themes.firstOrNull { it.id == id } ?: themes[0]
    }

    fun save(ctx: Context, theme: AppTheme) {
        prefs(ctx).edit().putString(KEY, theme.id).apply()
    }

    fun apply(ctx: Context, theme: AppTheme) {
        // Tüm açık view'ların rengini değiştirmek için Activity.recreate() çağrılır.
        // Burada sadece SharedPreferences'e yaz; uygulama her onCreate'de okur.
        save(ctx, theme)
    }

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
