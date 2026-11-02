package dev.pairly.android.ui

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * The app language: follow the system (default), English or German.
 *
 * On Android 13+ the choice is stored by the system ([LocaleManager]), so it also shows up under
 * Settings → Apps → Pairly → Language and applies to every context, including services. On older
 * versions it is stored here and applied by wrapping contexts with [of] / [wrap].
 */
object LocaleText {
    enum class Language(val tag: String) { System(""), English("en"), German("de") }

    private const val PREFS = "pairly_ui"
    private const val KEY_LANGUAGE = "language"

    fun language(context: Context): Language {
        val tag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java)?.applicationLocales
                ?.takeIf { !it.isEmpty }?.get(0)?.language.orEmpty()
        } else {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LANGUAGE, "").orEmpty()
        }
        return Language.entries.firstOrNull { it.tag == tag && tag.isNotEmpty() } ?: Language.System
    }

    /** Stores the choice. The caller recreates the activity on Android 12 and older. */
    fun setLanguage(context: Context, language: Language) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java)?.applicationLocales =
                if (language == Language.System) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(language.tag)
        } else {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LANGUAGE, language.tag).apply()
        }
    }

    /** Wraps an activity's base context (attachBaseContext) on Android 12 and older. */
    fun wrap(base: Context): Context = of(base)

    /** A context whose resources use the chosen app language. */
    fun of(context: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return context
        val language = language(context)
        if (language == Language.System) return context
        val locale = Locale.forLanguageTag(language.tag)
        val configuration = Configuration(context.resources.configuration).apply { setLocale(locale) }
        return context.createConfigurationContext(configuration)
    }
}
