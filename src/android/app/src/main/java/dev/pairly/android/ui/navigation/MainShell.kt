package dev.pairly.android.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
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
import dev.pairly.android.ui.components.DetailTopBar
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
import dev.pairly.android.ui.theme.DesignVariant
import dev.pairly.android.ui.theme.NavStyle
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
    val updatePrefs: ((UiPrefs) -> UiPrefs) -> Unit,
    val setLanguage: (LocaleText.Language) -> Unit,
    val replayOnboarding: () -> Unit
)

/** The app after onboarding: one set of screens, shown with a bottom bar or as a dashboard. */
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
    val bottomBar = prefs.navStyle == NavStyle.BottomBar
    val style = Pairly.style

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 720.dp
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                AnimatedVisibility(
                    visible = bottomBar && !wide && route in Routes.topLevel,
                    enter = slideInVertically { it } + fadeIn(),
                    exit = slideOutVertically { it } + fadeOut()
                ) {
                    NavigationBar(
                        containerColor = if (style.variant == DesignVariant.Glass) style.cardColor else MaterialTheme.colorScheme.surfaceContainer
                    ) {
                        tabs.forEach { tab ->
                            NavigationBarItem(
                                selected = route == tab.route,
                                onClick = { nav.navigateTab(tab.route) },
                                icon = { Icon(tab.icon, contentDescription = null) },
                                label = { Text(stringResource(tab.label)) }
                            )
                        }
                    }
                }
            }
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                if (bottomBar && wide) {
                    NavigationRail(Modifier.fillMaxHeight(), containerColor = Color.Transparent) {
                        Spacer(Modifier.height(16.dp))
                        AppIcon(Modifier.size(40.dp))
                        Spacer(Modifier.height(24.dp))
                        tabs.forEach { tab ->
                            NavigationRailItem(
                                selected = route == tab.route,
                                onClick = { nav.navigateTab(tab.route) },
                                icon = { Icon(tab.icon, contentDescription = null) },
                                label = { Text(stringResource(tab.label)) }
                            )
                        }
                    }
                }
                SharedTransitionLayout(Modifier.weight(1f)) {
                    CompositionLocalProvider(LocalSharedScope provides this) {
                        PairlyNavHost(nav, state, prefs, language, callbacks, helpIsTab = bottomBar)
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

private fun isTop(entry: NavBackStackEntry) = entry.destination.route in Routes.topLevel

@Composable
private fun PairlyNavHost(
    nav: NavHostController,
    state: AppState,
    prefs: UiPrefs,
    language: LocaleText.Language,
    callbacks: ShellCallbacks,
    helpIsTab: Boolean
) {
    val style = Pairly.style
    val reduce = style.reduceMotion

    // Tabs fade through each other; deeper screens slide in from the side and back out.
    val enter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        when {
            reduce -> fadeIn(tween(120))
            isTop(initialState) && isTop(targetState) -> fadeIn(tween(220, delayMillis = 60)) + scaleIn(tween(220, delayMillis = 60), initialScale = 0.97f)
            else -> slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, style.offsetSpring()) { it / 4 } + fadeIn()
        }
    }
    val exit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        when {
            reduce -> fadeOut(tween(120))
            isTop(initialState) && isTop(targetState) -> fadeOut(tween(90))
            else -> slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Start, style.offsetSpring()) { it / 4 } + fadeOut()
        }
    }
    val popEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        if (reduce) fadeIn(tween(120))
        else slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.End, style.offsetSpring()) { it / 4 } + fadeIn()
    }
    val popExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        if (reduce) fadeOut(tween(120))
        else slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, style.offsetSpring()) { it / 4 } + scaleOut(targetScale = 0.98f) + fadeOut()
    }

    val openFeature: (Feature) -> Unit = { nav.navigate(Routes.feature(it)) }
    val openHelp: () -> Unit = { if (helpIsTab) nav.navigateTab(Routes.HELP) else nav.navigate(Routes.HELP) }
    val openSettings: () -> Unit = { nav.navigate(Routes.SETTINGS) }

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
                    state, prefs.navStyle,
                    HomeCallbacks(
                        openFeature = openFeature,
                        openSettings = openSettings,
                        openHelp = openHelp,
                        openPairing = { nav.navigate(Routes.PAIR) },
                        reconnect = callbacks.reconnect,
                        syncNow = callbacks.syncNow
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
                    back = if (helpIsTab) null else ({ nav.popBackStack() })
                )
            )
        }
        composable(Routes.SETTINGS) {
            Column(Modifier.fillMaxSize()) {
                if (!helpIsTab) DetailTopBar(stringResource(R.string.nav_settings)) { nav.popBackStack() }
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
                        replayOnboarding = callbacks.replayOnboarding
                    )
                )
            }
        }
    }
}
