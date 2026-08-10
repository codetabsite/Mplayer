package com.tdev.mplayr

import android.app.Application
import android.content.SharedPreferences
import com.google.android.material.color.DynamicColors

class MPlayrApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Material You dinamik renk — sadece AMOLED/Dark dışı temada aktif değil;
        // ThemeManager kendi renklerini yönetir, DynamicColors çakışmaması için
        // yalnızca sistem teması "dark" seçiliyse uygula.
        val prefs = getSharedPreferences("mplayr_theme", MODE_PRIVATE)
        val themeId = prefs.getString("theme_id", "amoled") ?: "amoled"
        if (themeId == "dark") {
            DynamicColors.applyToActivitiesIfAvailable(this)
        }
    }
}
