package dev.pairly.android

import android.content.Context
import android.graphics.Bitmap
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Drives the real app on an emulator: first-run setup, the main screens, the design preview and
 * the German translation. Screenshots land in the app's files/screenshots folder; CI pulls them.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class AppSmokeTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private var scenario: ActivityScenario<MainActivity>? = null
    private var shot = 0

    @Before
    fun freshStart() {
        // A first launch: no onboarding progress, no design preview, system language.
        context.getSharedPreferences("pairly_ui", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "screenshots").deleteRecursively()
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun close() {
        scenario?.close()
    }

    private fun text(@StringRes id: Int) = context.getString(id)

    private fun exists(@StringRes id: Int): Boolean =
        compose.onAllNodesWithText(text(id)).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(@StringRes id: Int, timeoutMs: Long = 30_000) =
        compose.waitUntil(timeoutMs) { exists(id) }

    private fun click(@StringRes id: Int) {
        waitFor(id)
        compose.onAllNodesWithText(text(id)).onFirst().performClick()
        compose.waitForIdle()
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        Thread.sleep(400) // let enter animations settle
        val folder = File(context.filesDir, "screenshots").apply { mkdirs() }
        val file = File(folder, "%02d-%s.png".format(++shot, name))
        file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun firstRunSetupThenEveryMainScreen() {
        // Setup: welcome, tour, PC install, pairing (skipped).
        waitFor(R.string.onboarding_welcome_title)
        screenshot("welcome")
        click(R.string.action_lets_go)
        waitFor(R.string.tour_title)
        screenshot("tour")
        click(R.string.action_skip_tour)
        waitFor(R.string.install_title)
        screenshot("install-pc")
        click(R.string.install_done)
        waitFor(R.string.onboarding_connect_title)
        screenshot("pair")
        click(R.string.action_skip_for_now)

        // One step per permission, then the optional clipboard step: skip or continue each.
        var steps = 0
        while (!exists(R.string.onboarding_done_title)) {
            check(++steps <= 15) { "Setup did not reach the summary." }
            compose.waitUntil(30_000) {
                exists(R.string.action_skip) || exists(R.string.action_maybe_later) ||
                    exists(R.string.action_continue) || exists(R.string.onboarding_done_title)
            }
            if (exists(R.string.onboarding_done_title)) break
            screenshot("setup-step-$steps")
            when {
                exists(R.string.action_skip) -> click(R.string.action_skip)
                exists(R.string.action_maybe_later) -> click(R.string.action_maybe_later)
                else -> click(R.string.action_continue)
            }
        }
        screenshot("done")
        click(R.string.action_start_using)

        // Home, Features, a feature page, Help and Settings.
        waitFor(R.string.home_not_paired)
        screenshot("home")
        click(R.string.nav_features)
        waitFor(R.string.features_title)
        screenshot("features")
        click(R.string.feature_calls)
        waitFor(R.string.feature_why_title)
        screenshot("feature-calls")
        compose.onNodeWithContentDescription(text(R.string.action_back)).performClick()
        waitFor(R.string.features_title)
        click(R.string.nav_help)
        waitFor(R.string.help_title)
        screenshot("help")
        click(R.string.nav_settings)
        waitFor(R.string.settings_title)
        screenshot("settings")

        // Unlock the design preview (version tapped seven times) and try a dark-mode candidate.
        val list = compose.onNode(hasScrollToNodeAction())
        list.performScrollToNode(hasText(text(R.string.settings_version)))
        repeat(7) { compose.onNodeWithText(text(R.string.settings_version)).performClick() }
        compose.waitForIdle() // the section header shows the title in capitals, so wait on its content
        list.performScrollToNode(hasText(text(R.string.design_navy)))
        click(R.string.design_navy)
        screenshot("design-preview-navy")
        list.performScrollToNode(hasText(text(R.string.design_graphite)))
        click(R.string.design_graphite)
        screenshot("design-preview-graphite")

        // Tabs in both directions, including back to Home.
        click(R.string.nav_home)
        waitFor(R.string.home_not_paired)
        screenshot("home-dark")
        click(R.string.nav_features)
        waitFor(R.string.features_title)

        // "Control your phone" opens Android's settings as close to Pairly's switch as the phone allows.
        click(R.string.feature_control)
        waitFor(R.string.feature_why_title)
        screenshot("feature-control")
        click(R.string.action_open_settings)
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        check(device.wait(Until.hasObject(By.pkg("com.android.settings")), 15_000)) { "Settings did not open." }
        // Pairly may be listed below the fold (Downloaded apps), so scroll to it if needed.
        if (!device.hasObject(By.textContains("Pairly"))) {
            runCatching { UiScrollable(UiSelector().scrollable(true)).scrollTextIntoView("Pairly") }
        }
        check(device.wait(Until.hasObject(By.textContains("Pairly")), 5_000)) { "Accessibility settings did not show Pairly." }
        device.takeScreenshot(File(context.filesDir, "screenshots/%02d-accessibility-settings.png".format(++shot)))
        device.pressBack()
        waitFor(R.string.feature_why_title)
        compose.onNodeWithContentDescription(text(R.string.action_back)).performClick()
        waitFor(R.string.features_title)

        // Switch the app to German (recreates the activity on older Android versions).
        click(R.string.nav_settings)
        waitFor(R.string.settings_language)
        val settings = compose.onNode(hasScrollToNodeAction())
        settings.performScrollToNode(hasText("Deutsch"))
        compose.onNodeWithText("Deutsch").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("Einstellungen").fetchSemanticsNodes().isNotEmpty() }
        screenshot("settings-german")
    }
}
