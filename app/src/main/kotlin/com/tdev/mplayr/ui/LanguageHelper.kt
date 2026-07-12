package com.tdev.mplayr.ui

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

object LanguageHelper {

    private const val PREF_NAME = "app_prefs"
    private const val KEY_LANGUAGE = "selected_language"

    fun setLanguage(context: Context, langCode: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LANGUAGE, langCode).apply()
        applyLanguage(context, langCode)
    }

    fun getSavedLanguage(context: Context): String {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LANGUAGE, "system") ?: "system"
    }

    fun applyLanguage(context: Context, langCode: String): Context {
        if (langCode == "system") {
            val sysLocale = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                context.resources.configuration.locales[0]
            } else {
                @Suppress("DEPRECATION")
                context.resources.configuration.locale
            }
            return updateResources(context, sysLocale)
        }
        val locale = Locale(langCode)
        Locale.setDefault(locale)
        return updateResources(context, locale)
    }

    private fun updateResources(context: Context, locale: Locale): Context {
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }

    fun applyOnAttach(context: Context): Context {
        val lang = getSavedLanguage(context)
        return applyLanguage(context, lang)
    }

    val supportedLanguages = listOf(
        Pair("system", "System Default"),
        Pair("en", "English"),
        Pair("tr", "Türkçe"),
        Pair("de", "Deutsch"),
        Pair("zh", "中文"),
        Pair("ja", "日本語"),
        Pair("es", "Español"),
    )
}
