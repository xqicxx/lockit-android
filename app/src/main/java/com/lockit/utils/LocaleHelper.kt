package com.lockit.utils

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import java.util.Locale

object LocaleHelper {
    const val PREFS_NAME = "lockit_prefs"
    const val KEY_APP_LANGUAGE = "app_language"
    const val LANG_ZH = "zh"
    const val LANG_EN = "en"
    const val LANG_DEFAULT = LANG_ZH // Chinese is default

    /**
     * Apply the saved locale to the context.
     * Call this in Application.attachBaseContext and Activity.attachBaseContext.
     */
    fun applyLocale(context: Context): Context {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val language = prefs.getString(KEY_APP_LANGUAGE, LANG_DEFAULT) ?: LANG_DEFAULT
        return updateResources(context, language)
    }

    /**
     * Apply a specific language to the context.
     */
    fun applyLocale(context: Context, language: String): Context {
        return updateResources(context, language)
    }

    /**
     * Get the current saved language preference.
     */
    fun getSavedLanguage(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_APP_LANGUAGE, LANG_DEFAULT) ?: LANG_DEFAULT
    }

    /**
     * Save the language preference.
     */
    fun saveLanguage(context: Context, language: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_APP_LANGUAGE, language).apply()
    }

    /**
     * Update the resources with the specified language.
     */
    private fun updateResources(context: Context, language: String): Context {
        val locale = when (language) {
            LANG_EN -> Locale.ENGLISH
            LANG_ZH -> Locale.CHINESE
            else -> Locale.CHINESE // Default to Chinese
        }

        Locale.setDefault(locale)

        val config = context.resources.configuration
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocale(locale)
            config.setLayoutDirection(locale)
            return context.createConfigurationContext(config)
        } else {
            @Suppress("DEPRECATION")
            config.locale = locale
            @Suppress("DEPRECATION")
            config.setLayoutDirection(locale)
            @Suppress("DEPRECATION")
            context.resources.updateConfiguration(config, context.resources.displayMetrics)
            return context
        }
    }

    /**
     * Check if the language is valid.
     */
    fun isValidLanguage(language: String): Boolean {
        return language == LANG_ZH || language == LANG_EN
    }
}