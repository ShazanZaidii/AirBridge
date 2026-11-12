package com.shazan.airbridge

import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * The app's own language, picked in Settings › Language.
 *
 * Android 13+ keeps it in the system's per-app language (the same one under system Settings › Apps › AirBridge ›
 * Language, so the two always agree) and restarts the screen by itself. Older Android has no such thing, so the choice
 * is kept in the app's preferences and applied to each screen as it opens ([wrap]).
 */
object AppLanguage {
    /** Language tag to the name written in that language. The first entry, "", means "follow the phone". */
    val choices: List<Pair<String, String>> = listOf(
        "" to "",
        "en" to "English",
        "hi" to "हिन्दी",
        "mr" to "मराठी",
        "gu" to "ગુજરાતી",
        "bn" to "বাংলা",
        "ta" to "தமிழ்",
        "te" to "తెలుగు",
        "kn" to "ಕನ್ನಡ",
        "fr" to "Français",
        "de" to "Deutsch",
    )

    private const val PREFS = "AirBridgePrefs"
    private const val KEY = "APP_LANGUAGE"

    /** The chosen tag, or "" for the phone's own language. */
    fun current(ctx: Context): String {
        if (Build.VERSION.SDK_INT >= 33) {
            val list = ctx.getSystemService(LocaleManager::class.java)?.applicationLocales
            val tag = if (list == null || list.isEmpty) "" else list[0].language
            return if (choices.any { it.first == tag }) tag else ""
        }
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
    }

    /** Switches the language. On Android 13+ the system recreates the screens; before that, [recreate] is called. */
    fun set(ctx: Context, tag: String, recreate: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.getSystemService(LocaleManager::class.java)?.applicationLocales =
                if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        } else {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, tag).apply()
            recreate()
        }
    }

    /** For `attachBaseContext` on Android 12 and older: the same context with the chosen language applied. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val tag = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
        if (tag.isEmpty()) return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(locale)
        return ContextWrapper(base.createConfigurationContext(cfg))
    }
}
