package dev.pairly.android.ui

import android.content.Context
import android.content.SharedPreferences
import dev.pairly.android.network.ConnectionSettings
import dev.pairly.android.ui.onboarding.OnboardingStep
import dev.pairly.android.ui.theme.Accent
import dev.pairly.android.ui.theme.AppStyle
import dev.pairly.android.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class UiPrefs(
    val themeMode: ThemeMode = ThemeMode.System,
    val appStyle: AppStyle = AppStyle.Glass,
    val accent: Accent = Accent.Violet,
    val onboardingDone: Boolean = false,
    val onboardingStep: OnboardingStep = OnboardingStep.Welcome,
    val lastSeenVersion: String = ""
)

/**
 * Appearance and onboarding progress. SharedPreferences rather than DataStore so the first frame
 * already knows whether to show onboarding (no flash of the wrong screen).
 */
class UiPreferences private constructor(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("pairly_ui", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read(context))
    val prefsFlow: StateFlow<UiPrefs> = state.asStateFlow()
    val current: UiPrefs get() = state.value

    private fun read(context: Context): UiPrefs {
        // Phones paired before onboarding existed skip it after updating.
        val pairedBefore = ConnectionSettings(context).trustKey.isNotBlank()
        return UiPrefs(
            themeMode = enumOr(prefs.getString(KEY_THEME, null), ThemeMode.System),
            appStyle = readStyle(prefs.getString(KEY_STYLE, null)),
            accent = readAccent(),
            onboardingDone = prefs.getBoolean(KEY_ONBOARDING_DONE, pairedBefore),
            onboardingStep = enumOr(prefs.getString(KEY_ONBOARDING_STEP, null), OnboardingStep.Welcome),
            lastSeenVersion = prefs.getString(KEY_LAST_SEEN, "").orEmpty()
        )
    }

    /** Aurora and Daybreak (until 1.x) became Glass and Clay. */
    private fun readStyle(stored: String?): AppStyle = when (stored) {
        "Daybreak" -> AppStyle.Clay
        else -> enumOr(stored, AppStyle.Glass)
    }

    private fun readAccent(): Accent {
        prefs.getString(KEY_ACCENT, null)?.let { return enumOr(it, Accent.Violet) }
        // Before accents existed: wallpaper colors were a switch; Daybreak was coral.
        return when {
            prefs.getBoolean(KEY_DYNAMIC, false) -> Accent.System
            prefs.getString(KEY_STYLE, null) == "Daybreak" -> Accent.Coral
            prefs.getString(KEY_STYLE, null) == "Aurora" -> Accent.Indigo
            else -> Accent.Violet
        }
    }

    fun update(change: (UiPrefs) -> UiPrefs) {
        val next = change(state.value)
        state.value = next
        prefs.edit()
            .putString(KEY_THEME, next.themeMode.name)
            .putString(KEY_STYLE, next.appStyle.name)
            .putString(KEY_ACCENT, next.accent.name)
            .remove(KEY_DYNAMIC)
            .putBoolean(KEY_ONBOARDING_DONE, next.onboardingDone)
            .putString(KEY_ONBOARDING_STEP, next.onboardingStep.name)
            .putString(KEY_LAST_SEEN, next.lastSeenVersion)
            .apply()
    }

    companion object {
        private const val KEY_THEME = "theme_mode"
        private const val KEY_STYLE = "app_style"
        private const val KEY_DYNAMIC = "dynamic_color"
        private const val KEY_ACCENT = "accent"
        private const val KEY_ONBOARDING_DONE = "onboarding_done"
        private const val KEY_ONBOARDING_STEP = "onboarding_step"
        private const val KEY_LAST_SEEN = "last_seen_version"

        @Volatile private var instance: UiPreferences? = null

        fun get(context: Context): UiPreferences =
            instance ?: synchronized(this) {
                instance ?: UiPreferences(context.applicationContext).also { instance = it }
            }

        private inline fun <reified T : Enum<T>> enumOr(name: String?, fallback: T): T =
            enumValues<T>().firstOrNull { it.name == name } ?: fallback
    }
}
