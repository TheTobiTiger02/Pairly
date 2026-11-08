package dev.pairly.android.ui.navigation

import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.pairly.android.R
import dev.pairly.android.ui.AppState
import dev.pairly.android.ui.LocaleText
import dev.pairly.android.ui.UiPrefs
import dev.pairly.android.ui.components.AppIcon
import dev.pairly.android.ui.features.Feature
import dev.pairly.android.ui.features.FeatureDetailCallbacks
import dev.pairly.android.ui.features.FeatureDetailScreen
import dev.pairly.android.ui.features.FeaturesScreen
import dev.pairly.android.ui.features.PairScreen
import dev.pairly.android.ui.features.ShizukuGuideScreen
import dev.pairly.android.ui.help.HelpCallbacks
import dev.pairly.android.ui.help.HelpScreen
import dev.pairly.android.ui.home.HomeCallbacks
import dev.pairly.android.ui.home.HomeScreen
import dev.pairly.android.ui.onboarding.PairingCallbacks
import dev.pairly.android.ui.settings.SettingsCallbacks
import dev.pairly.android.ui.settings.SettingsScreen
import dev.pairly.android.ui.theme.Pairly

object Routes {
    const val HOME = "home"
    const val FEATURES = "features"
    const val HELP = "help"
    const val SETTINGS = "settings"
    const val FEATURE = "feature/{key}"
    const val SHIZUKU = "shizuku"
    const val PAIR = "pair"

    fun feature(feature: Feature) = "feature/${feature.key}"
    val topLevel = setOf(HOME, FEATURES, HELP, SETTINGS)
}

private data class Tab(val route: String, val label: Int, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, R.string.nav_home, Icons.Rounded.Home),
    Tab(Routes.FEATURES, R.string.nav_features, Icons.Rounded.Apps),
    Tab(Routes.HELP, R.string.nav_help, Icons.AutoMirrored.Rounded.HelpOutline),
    Tab(Routes.SETTINGS, R.string.nav_settings, Icons.Rounded.Settings)
)

class ShellCallbacks(
    val pairing: PairingCallbacks,
    val reconnect: () -> Unit,
    val syncNow: () -> Unit,
    val forget: () -> Unit,
    val setAutoConnect: (Boolean) -> Unit,
    val setClipboardSync: (Boolean) -> Unit,
    val readClipboard: () -> String,
    val sendClipboard: (String) -> Boolean,
    /** Sends photos or files to the PC's Downloads\\Pairly folder. */
    val sendFiles: (List<Uri>) -> Unit,
    val updatePrefs: ((UiPrefs) -> UiPrefs) -> Unit,
    val setLanguage: (LocaleText.Language) -> Unit,
    val replayOnboarding: () -> Unit,
    val grantAllWithShizuku: () -> Unit,
    val setTurnOffWirelessDebugging: (Boolean) -> Unit
)

/** The app after onboarding: Home, Features, Help and Settings in a bottom bar (a rail on wide screens). */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun MainShell(
    state: AppState,
    prefs: UiPrefs,
    language: LocaleText.Language,
    callbacks: ShellCallbacks,
    nav: NavHostController = rememberNavController()
) {
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val style = Pairly.style
    val haptics = LocalHapticFeedback.current
    val selectTab: (String) -> Unit = { target ->
        if (target != route) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        nav.navigateTab(target)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 720.dp
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                AnimatedVisibility(
                    visible = !wide && route in Routes.topLevel,
                    enter = slideInVertically { it } + fadeIn(),
                    exit = slideOutVertically { it } + fadeOut()
                ) {
                    PairlyNavBar(
                        tabs.map { tab ->
                            NavItem(stringResource(tab.label), tab.icon, route == tab.route) { selectTab(tab.route) }
                        }
                    )
                }
            }
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                if (wide) {
                    NavigationRail(Modifier.fillMaxHeight(), containerColor = Color.Transparent) {
                        Spacer(Modifier.height(16.dp))
                        AppIcon(Modifier.size(40.dp))
                        Spacer(Modifier.height(24.dp))
                        tabs.forEach { tab ->
                            NavigationRailItem(
                                selected = route == tab.route,
                                onClick = { selectTab(tab.route) },
                                icon = { Icon(tab.icon, contentDescription = null) },
                                label = { Text(stringResource(tab.label)) },
                                colors = NavigationRailItemDefaults.colors(
                                    indicatorColor = style.navActive,
                                    selectedIconColor = style.onNavActive,
                                    selectedTextColor = MaterialTheme.colorScheme.onSurface
                                )
                            )
                        }
                    }
                }
                SharedTransitionLayout(Modifier.weight(1f)) {
                    CompositionLocalProvider(LocalSharedScope provides this) {
                        PairlyNavHost(nav, state, prefs, language, callbacks)
                    }
                }
            }
        }
    }
}

private fun NavHostController.navigateTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** Position in the bottom bar, or null for screens that aren't tabs. */
private fun tabIndex(entry: NavBackStackEntry): Int? = tabs.indexOfFirst { it.route == entry.destination.route }.takeIf { it >= 0 }

/**
 * Whether a change moves to the right: towards a tab further right, or (for anything else) deeper
 * into the app. Going back, or to a tab on the left (like Home), slides the other way.
 */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.forward(pop: Boolean): Boolean {
    val from = tabIndex(initialState)
    val to = tabIndex(targetState)
    return if (from != null && to != null) to > from else !pop
}

@Composable
private fun PairlyNavHost(
    nav: NavHostController,
    state: AppState,
    prefs: UiPrefs,
    language: LocaleText.Language,
    callbacks: ShellCallbacks
) {
    val style = Pairly.style
    // Every change slides; the direction follows the bottom bar's order or how deep the screen is.
    val enter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = { Motion.slideIn(style, forward(pop = false)) }
    val exit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = { Motion.slideOut(style, forward(pop = false)) }
    val popEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = { Motion.slideIn(style, forward(pop = true)) }
    val popExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = { Motion.slideOut(style, forward(pop = true)) }

    val openFeature: (Feature) -> Unit = { nav.navigate(Routes.feature(it)) }
    val openHelp: () -> Unit = { nav.navigateTab(Routes.HELP) }

    NavHost(
        navController = nav,
        startDestination = Routes.HOME,
        enterTransition = enter,
        exitTransition = exit,
        popEnterTransition = popEnter,
        popExitTransition = popExit
    ) {
        composable(Routes.HOME) {
            CompositionLocalProvider(LocalAnimatedScope provides this) {
                HomeScreen(
                    state,
                    HomeCallbacks(
                        openFeature = openFeature,
                        openHelp = openHelp,
                        openPairing = { nav.navigate(Routes.PAIR) },
                        reconnect = callbacks.reconnect,
                        syncNow = callbacks.syncNow,
                        sendClipboard = { callbacks.sendClipboard(callbacks.readClipboard()) },
                        sendFiles = callbacks.sendFiles,
                        openPowerSetup = { nav.navigate(Routes.SHIZUKU) },
                        grantAllWithShizuku = callbacks.grantAllWithShizuku
                    )
                )
            }
        }
        composable(Routes.FEATURES) {
            CompositionLocalProvider(LocalAnimatedScope provides this) { FeaturesScreen(state, openFeature) }
        }
        composable(Routes.FEATURE) { backStack ->
            val feature = Feature.fromKey(backStack.arguments?.getString("key")) ?: Feature.Notifications
            CompositionLocalProvider(LocalAnimatedScope provides this) {
                FeatureDetailScreen(
                    feature, state,
                    FeatureDetailCallbacks(
                        back = { nav.popBackStack() },
                        openShizukuGuide = { nav.navigate(Routes.SHIZUKU) },
                        readClipboard = callbacks.readClipboard,
                        sendClipboard = callbacks.sendClipboard,
                        setClipboardSync = callbacks.setClipboardSync
                    )
                )
            }
        }
        composable(Routes.SHIZUKU) { ShizukuGuideScreen(state) { nav.popBackStack() } }
        composable(Routes.PAIR) { PairScreen(state, callbacks.pairing) { nav.popBackStack() } }
        composable(Routes.HELP) {
            HelpScreen(
                HelpCallbacks(
                    replayOnboarding = callbacks.replayOnboarding,
                    openShizukuGuide = { nav.navigate(Routes.SHIZUKU) },
                    back = null
                )
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                state, prefs, language,
                SettingsCallbacks(
                    setAutoConnect = callbacks.setAutoConnect,
                    reconnect = callbacks.reconnect,
                    pairAnother = { nav.navigate(Routes.PAIR) },
                    forget = callbacks.forget,
                    updatePrefs = callbacks.updatePrefs,
                    setLanguage = callbacks.setLanguage,
                    openHelp = openHelp,
                    replayOnboarding = callbacks.replayOnboarding,
                    openPowerSetup = { nav.navigate(Routes.SHIZUKU) },
                    grantAllWithShizuku = callbacks.grantAllWithShizuku,
                    setTurnOffWirelessDebugging = callbacks.setTurnOffWirelessDebugging
                )
            )
        }
    }
}
