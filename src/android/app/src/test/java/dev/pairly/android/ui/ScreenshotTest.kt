package dev.pairly.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import dev.pairly.android.network.ConnectionPhase
import dev.pairly.android.network.ConnectionState
import dev.pairly.android.network.DiscoveredPc
import dev.pairly.android.ui.components.PairlyBackground
import dev.pairly.android.ui.features.Feature
import dev.pairly.android.ui.features.FeatureDetailCallbacks
import dev.pairly.android.ui.features.FeatureDetailScreen
import dev.pairly.android.ui.features.FeaturesScreen
import dev.pairly.android.ui.features.PermissionSnapshot
import dev.pairly.android.ui.features.ShizukuGuideScreen
import dev.pairly.android.ui.help.HelpCallbacks
import dev.pairly.android.ui.help.HelpScreen
import dev.pairly.android.ui.navigation.MainShell
import dev.pairly.android.ui.navigation.ShellCallbacks
import dev.pairly.android.ui.onboarding.OnboardingScreen
import dev.pairly.android.ui.onboarding.OnboardingStep
import dev.pairly.android.ui.onboarding.PairingCallbacks
import dev.pairly.android.ui.settings.SettingsCallbacks
import dev.pairly.android.ui.settings.SettingsScreen
import dev.pairly.android.ui.theme.DesignVariant
import dev.pairly.android.ui.theme.NavStyle
import dev.pairly.android.ui.theme.PairlyTheme
import dev.pairly.android.ui.theme.ThemeMode
import dev.pairly.android.ui.whatsnew.WhatsNewContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders every screen for each design candidate, light and dark, plus German, so the variants
 * can be compared side by side without a phone:
 *
 *   ./gradlew :app:recordRoborazziDebug   → app/build/outputs/roborazzi/<variant>/<screen>.png
 *   ./gradlew :app:verifyRoborazziDebug   → fails if a screen changed unexpectedly
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest(private val case: Case) {
    data class Case(val variant: DesignVariant, val mode: ThemeMode, val language: String) {
        val folder = "${variant.name.lowercase()}-${mode.name.lowercase()}-$language"
        override fun toString() = folder
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun cases(): List<Case> =
            DesignVariant.entries.flatMap { variant ->
                val modes = if (variant == DesignVariant.DarkVibrant) listOf(ThemeMode.Dark) else listOf(ThemeMode.Light, ThemeMode.Dark)
                modes.map { Case(variant, it, "en") }
            } + Case(DesignVariant.Expressive, ThemeMode.Light, "de")
    }

    @get:Rule
    val compose = createComposeRule()

    private val pc = DiscoveredPc("192.168.1.20", "Office PC", 7816, "host", 2, "AB:CD", 7817)

    private val connected = AppState(
        connection = ConnectionState(ConnectionPhase.Ready, ""),
        hasPairing = true,
        pcName = "Office PC",
        details = ConnectionDetails("wss://192.168.1.20:7817", 3, "3f2a9c", true),
        permissions = PermissionSnapshot(setOf(Feature.AppAlerts, Feature.Notifications, Feature.Messages, Feature.Photos, Feature.Background)),
        shizuku = ShizukuStatus(developerOptions = true, wirelessDebugging = true, installed = true)
    )

    private val unpaired = AppState(
        connection = ConnectionState(ConnectionPhase.Discovering, "Looking for Pairly on your network…"),
        discoveredPcs = listOf(pc)
    )

    private val pairing = PairingCallbacks({}, { _, _ -> }, {}, {}, "")

    private fun capture(name: String, content: @Composable () -> Unit) {
        if (case.language != "en") RuntimeEnvironment.setQualifiers("${case.language}-w411dp-h891dp-xxhdpi")
        compose.setContent {
            CompositionLocalProvider(LocalFeatureActions provides NoFeatureActions) {
                PairlyTheme(case.variant, case.mode) {
                    PairlyBackground { content() }
                }
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/${case.folder}/$name.png")
    }

    @Composable
    private fun Screen(content: @Composable () -> Unit) = Box(Modifier.fillMaxSize().statusBarsPadding()) { content() }

    private fun onboarding(step: OnboardingStep, state: AppState = unpaired) = capture("onboarding-${step.ordinal.toString().padStart(2, '0')}-${step.name.lowercase()}") {
        OnboardingScreen(step, state, pairing, LocaleText.Language.System, {}, {}, {})
    }

    private val shell = ShellCallbacks(pairing, {}, {}, {}, {}, {}, { "" }, { true }, {}, {}, {})

    @Test fun onboardingWelcome() = onboarding(OnboardingStep.Welcome)
    @Test fun onboardingTour() = onboarding(OnboardingStep.Tour)
    @Test fun onboardingInstall() = onboarding(OnboardingStep.InstallPc)
    @Test fun onboardingPair() = onboarding(OnboardingStep.Pair)
    @Test fun onboardingPermission() = onboarding(OnboardingStep.Notifications, connected.copy(permissions = PermissionSnapshot()))
    @Test fun onboardingClipboard() = onboarding(OnboardingStep.Clipboard, connected)
    @Test fun onboardingDone() = onboarding(OnboardingStep.Done, connected)

    @Test fun homeBottomBar() = capture("home-bottom-bar") { MainShell(connected, UiPrefs(variant = case.variant), LocaleText.Language.System, shell) }
    @Test fun homeDashboard() = capture("home-dashboard") { MainShell(connected, UiPrefs(variant = case.variant, navStyle = NavStyle.Dashboard), LocaleText.Language.System, shell) }
    @Test fun homeReconnecting() = capture("home-reconnecting") {
        MainShell(connected.copy(connection = ConnectionState(ConnectionPhase.Reconnecting, "Your PC isn’t reachable yet. Retrying automatically…")), UiPrefs(variant = case.variant), LocaleText.Language.System, shell)
    }
    @Test fun features() = capture("features") { Screen { FeaturesScreen(connected) {} } }
    @Test fun featureDetail() = capture("feature-detail") { Screen { FeatureDetailScreen(Feature.RemoteControl, connected, detailCallbacks) } }
    @Test fun clipboard() = capture("feature-clipboard") { Screen { FeatureDetailScreen(Feature.Clipboard, connected, detailCallbacks) } }
    @Test fun shizukuGuide() = capture("shizuku-guide") { Screen { ShizukuGuideScreen(connected) {} } }
    @Test fun help() = capture("help") { Screen { HelpScreen(HelpCallbacks({}, {}, null)) } }
    @Test fun settings() = capture("settings") {
        Screen {
            SettingsScreen(
                connected, UiPrefs(variant = case.variant, themeMode = case.mode, designPreviewUnlocked = true), LocaleText.Language.System,
                SettingsCallbacks({}, {}, {}, {}, {}, {}, {}, {})
            )
        }
    }
    @Test fun whatsNew() = capture("whats-new") {
        Screen { WhatsNewContent(WhatsNew("1.4.0", listOf("A new design with four styles to choose from", "German translation", "Guided setup for every feature"))) {} }
    }

    private val detailCallbacks = FeatureDetailCallbacks({}, {}, { "" }, { true }, {})
}
