package dev.pairly.android.ui

import android.content.Context
import android.content.SharedPreferences
import dev.pairly.android.network.ConnectionSettings
import dev.pairly.android.ui.onboarding.OnboardingStep
import dev.pairly.android.ui.theme.DesignVariant
import dev.pairly.android.ui.theme.NavStyle
import dev.pairly.android.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class UiPrefs(
    val variant: DesignVariant = DesignVariant.Expressive,
    val navStyle: NavStyle = NavStyle.BottomBar,
    val themeMode: ThemeMode = ThemeMode.System,
    val dynamicColor: Boolean = false,
    val onboardingDone: Boolean = false,
    val onboardingStep: OnboardingStep = OnboardingStep.Welcome,
    val designPreviewUnlocked: Boolean = false,
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
            variant = enumOr(prefs.getString(KEY_VARIANT, null), DesignVariant.Expressive),
            navStyle = enumOr(prefs.getString(KEY_NAV, null), NavStyle.BottomBar),
            themeMode = enumOr(prefs.getString(KEY_THEME, null), ThemeMode.System),
            dynamicColor = prefs.getBoolean(KEY_DYNAMIC, false),
            onboardingDone = prefs.getBoolean(KEY_ONBOARDING_DONE, pairedBefore),
            onboardingStep = enumOr(prefs.getString(KEY_ONBOARDING_STEP, null), OnboardingStep.Welcome),
            designPreviewUnlocked = prefs.getBoolean(KEY_PREVIEW, false),
            lastSeenVersion = prefs.getString(KEY_LAST_SEEN, "").orEmpty()
        )
    }

    fun update(change: (UiPrefs) -> UiPrefs) {
        val next = change(state.value)
        state.value = next
        prefs.edit()
            .putString(KEY_VARIANT, next.variant.name)
            .putString(KEY_NAV, next.navStyle.name)
            .putString(KEY_THEME, next.themeMode.name)
            .putBoolean(KEY_DYNAMIC, next.dynamicColor)
            .putBoolean(KEY_ONBOARDING_DONE, next.onboardingDone)
            .putString(KEY_ONBOARDING_STEP, next.onboardingStep.name)
            .putBoolean(KEY_PREVIEW, next.designPreviewUnlocked)
            .putString(KEY_LAST_SEEN, next.lastSeenVersion)
            .apply()
    }

    companion object {
        private const val KEY_VARIANT = "design_variant"
        private const val KEY_NAV = "nav_style"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_DYNAMIC = "dynamic_color"
        private const val KEY_ONBOARDING_DONE = "onboarding_done"
        private const val KEY_ONBOARDING_STEP = "onboarding_step"
        private const val KEY_PREVIEW = "design_preview_unlocked"
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
