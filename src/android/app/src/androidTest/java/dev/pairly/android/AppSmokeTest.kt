package dev.pairly.android

import android.content.Context
import android.graphics.Bitmap
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
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

    // While another app is in front (or Pairly is still coming back), there is no Compose
    // hierarchy at all; that counts as "not there yet", not as a failure.
    private fun exists(@StringRes id: Int): Boolean =
        runCatching { compose.onAllNodesWithText(text(id)).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)

    private fun waitFor(@StringRes id: Int, timeoutMs: Long = 30_000) =
        compose.waitUntil(timeoutMs) { exists(id) }

    private fun click(@StringRes id: Int) {
        waitFor(id)
        compose.onAllNodesWithText(text(id)).onFirst().performClick()
        compose.waitForIdle()
    }

    /** A tab of the bottom bar. Its label can also be a page title, so the tab role is part of the match. */
    private fun tab(@StringRes id: Int) {
        val matcher = hasText(text(id)) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        compose.waitUntil(30_000) { runCatching { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
        compose.onAllNodes(matcher).onFirst().performClick()
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

        // Power mode (optional), then every permission on one screen.
        waitFor(R.string.onboarding_power_label)
        screenshot("power-mode")
        click(R.string.action_not_now)
        waitFor(R.string.onboarding_permissions_title)
        screenshot("permissions")
        click(R.string.action_skip_for_now)
        waitFor(R.string.onboarding_done_title)
        screenshot("done")
        click(R.string.action_start_using)

        // Home, a feature page, Settings and Help.
        waitFor(R.string.home_not_paired)
        screenshot("home")
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text(R.string.feature_calls)))
        click(R.string.feature_calls)
        waitFor(R.string.feature_why_title)
        screenshot("feature-calls")
        compose.onNodeWithContentDescription(text(R.string.action_back)).performClick()
        waitFor(R.string.home_not_paired)
        tab(R.string.nav_settings)
        waitFor(R.string.settings_title)
        screenshot("settings")
        val list = compose.onNode(hasScrollToNodeAction())
        list.performScrollToNode(hasText(text(R.string.nav_help)))
        click(R.string.nav_help)
        waitFor(R.string.help_title)
        screenshot("help")
        compose.onNodeWithContentDescription(text(R.string.action_back)).performClick()
        waitFor(R.string.settings_title)

        // Dark mode through the real theme choice.
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text(R.string.theme_dark)))
        click(R.string.theme_dark)
        screenshot("settings-dark")

        // Back to Home through the tab bar.
        tab(R.string.nav_home)
        waitFor(R.string.home_not_paired)
        screenshot("home-dark")
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text(R.string.feature_control)))

        // "Control your phone" opens Android's settings as close to Pairly's switch as the phone allows.
        click(R.string.feature_control)
        waitFor(R.string.feature_why_title)
        screenshot("feature-control")
        click(R.string.action_open_settings)
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        // A cold start of Settings can take a while on CI emulators.
        val deadline = System.currentTimeMillis() + 30_000
        while (device.currentPackageName == context.packageName && System.currentTimeMillis() < deadline) Thread.sleep(250)
        check(device.currentPackageName != context.packageName) { "Settings did not open." }
        device.waitForIdle()
        // Pairly may be listed below the fold (Downloaded apps), and the list can still be loading:
        // keep trying to scroll to it for a while.
        val listDeadline = System.currentTimeMillis() + 15_000
        while (!device.hasObject(By.textContains("Pairly")) && System.currentTimeMillis() < listDeadline) {
            // A busy CI emulator sometimes puts an "isn't responding" dialog for another app (the
            // launcher) over Settings; waiting it out keeps Settings in front.
            device.findObject(By.res("android", "aerr_wait"))?.click()
            runCatching { UiScrollable(UiSelector().scrollable(true)).scrollTextIntoView("Pairly") }
            Thread.sleep(500)
        }
        if (!device.wait(Until.hasObject(By.textContains("Pairly")), 2_000)) {
            // Keep evidence of what Android showed instead.
            device.takeScreenshot(File(context.filesDir, "screenshots/%02d-accessibility-failed.png".format(++shot)))
            runCatching { device.dumpWindowHierarchy(File(context.filesDir, "screenshots/accessibility-window.xml")) }
            error("Accessibility settings did not show Pairly (showing ${device.currentPackageName}).")
        }
        device.takeScreenshot(File(context.filesDir, "screenshots/%02d-accessibility-settings.png".format(++shot)))
        // Wait for Pairly to be in front again before asking Compose about it. Settings can ignore
        // Back while it is still opening or sits one page deeper, so press again if nothing happens.
        val pairly = By.pkg(context.packageName).depth(0)
        check((1..3).any { device.pressBack(); device.wait(Until.hasObject(pairly), 15_000) }) { "Pairly did not come back from Settings." }
        waitFor(R.string.feature_why_title)
        compose.onNodeWithContentDescription(text(R.string.action_back)).performClick()
        waitFor(R.string.home_not_paired)

        // Switch the app to German (recreates the activity on older Android versions).
        tab(R.string.nav_settings)
        compose.waitForIdle() // Settings keeps its scroll position, so scroll to the language row
        val settings = compose.onNode(hasScrollToNodeAction())
        settings.performScrollToNode(hasText("Deutsch"))
        compose.onNodeWithText("Deutsch").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("Einstellungen").fetchSemanticsNodes().isNotEmpty() }
        screenshot("settings-german")
    }
}
