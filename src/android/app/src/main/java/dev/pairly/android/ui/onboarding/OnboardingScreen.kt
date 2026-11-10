package dev.pairly.android.ui.onboarding

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.ui.res.pluralStringResource
import dev.pairly.android.ui.features.PowerBenefits
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.ScreenShare
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import dev.pairly.android.ui.navigation.Motion
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.pairly.android.R
import dev.pairly.android.ui.AppState
import dev.pairly.android.ui.Links
import dev.pairly.android.ui.LocalFeatureActions
import dev.pairly.android.ui.LocaleText
import dev.pairly.android.ui.components.AppIcon
import dev.pairly.android.ui.components.CheckBadge
import dev.pairly.android.ui.components.ChoiceRow
import dev.pairly.android.ui.components.FeatureIcon
import dev.pairly.android.ui.components.ListRow
import dev.pairly.android.ui.components.PairlyBackground
import dev.pairly.android.ui.components.PairlyCard
import dev.pairly.android.ui.components.PrimaryButton
import dev.pairly.android.ui.components.QuietButton
import dev.pairly.android.ui.features.Feature
import dev.pairly.android.ui.features.FeatureHeader
import dev.pairly.android.ui.features.FeatureSetupActions
import dev.pairly.android.ui.features.FeatureSetupContent
import dev.pairly.android.ui.features.isBlockedByAndroid
import dev.pairly.android.ui.features.ShizukuGuide
import dev.pairly.android.ui.theme.Pairly
import dev.pairly.android.ui.theme.Space
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(
    step: OnboardingStep,
    state: AppState,
    pairing: PairingCallbacks,
    language: LocaleText.Language,
    onLanguage: (LocaleText.Language) -> Unit,
    onStep: (OnboardingStep) -> Unit,
    onFinish: () -> Unit,
    onGrantAll: () -> Unit = {}
) {
    // Android 14+ previews the back gesture: the step follows the finger before it changes.
    var backProgress by remember { mutableFloatStateOf(0f) }
    PredictiveBackHandler(enabled = step != OnboardingStep.Welcome) { events ->
        try {
            events.collect { backProgress = it.progress }
            onStep(step.previous())
        } finally {
            backProgress = 0f
        }
    }
    val progress by animateFloatAsState(step.ordinal / (OnboardingStep.entries.size - 1f), label = "progress")
    val style = Pairly.style

    PairlyBackground {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().height(56.dp).padding(horizontal = Space.s),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(48.dp)) {
                    if (step != OnboardingStep.Welcome) {
                        IconButton(onClick = { onStep(step.previous()) }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    }
                }
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.weight(1f).height(6.dp).clip(CircleShape),
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
                Spacer(Modifier.width(56.dp))
            }

            AnimatedContent(
                targetState = step,
                modifier = Modifier.weight(1f).graphicsLayer {
                    translationX = backProgress * 48.dp.toPx()
                    alpha = 1f - backProgress * 0.25f
                },
                transitionSpec = {
                    val forward = targetState.ordinal > initialState.ordinal
                    Motion.slideIn(style, forward).togetherWith(Motion.slideOut(style, forward))
                },
                label = "onboarding-step"
            ) { current ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Column(Modifier.widthIn(max = 560.dp).fillMaxSize()) {
                        when (current) {
                            OnboardingStep.Welcome -> WelcomeStep(language, onLanguage) { onStep(current.next()) }
                            OnboardingStep.Tour -> TourStep { onStep(current.next()) }
                            OnboardingStep.InstallPc -> InstallPcStep { onStep(current.next()) }
                            OnboardingStep.Pair -> PairStep(state, pairing) { onStep(current.next()) }
                            OnboardingStep.PowerMode -> PowerModeStep(state) { onStep(current.next()) }
                            OnboardingStep.Permissions -> PermissionsStep(state, onGrantAll) { onStep(current.next()) }
                            OnboardingStep.Done -> DoneStep(state, onFinish)
                        }
                    }
                }
            }
        }
    }
}

/** Scrollable content with the actions pinned to the bottom. */
@Composable
private fun StepLayout(
    content: @Composable ColumnScope.() -> Unit,
    actions: @Composable ColumnScope.() -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).clipToBounds().verticalScroll(rememberScrollState()).padding(horizontal = Space.xxl, vertical = Space.m),
            content = content
        )
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Space.xxl, vertical = Space.m),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Space.xs),
            content = actions
        )
    }
}

@Composable
private fun StepTitle(title: String, body: String? = null) {
    Text(title, style = MaterialTheme.typography.headlineLarge)
    if (body != null) {
        Spacer(Modifier.height(Space.s))
        Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.height(Space.xxl))
}

@Composable
private fun WelcomeStep(language: LocaleText.Language, onLanguage: (LocaleText.Language) -> Unit, onNext: () -> Unit) {
    val reduce = Pairly.style.reduceMotion
    val appear = remember { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.55f, stiffness = 200f)) }
    StepLayout(
        content = {
            Spacer(Modifier.height(Space.xxxl))
            AppIcon(
                Modifier.size(104.dp).graphicsLayer {
                    scaleX = 0.6f + 0.4f * appear.value
                    scaleY = 0.6f + 0.4f * appear.value
                    alpha = appear.value
                    rotationZ = (1f - appear.value) * -12f
                }
            )
            Spacer(Modifier.height(Space.xxl))
            Text(stringResource(R.string.onboarding_welcome_title), style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(Space.m))
            Text(stringResource(R.string.onboarding_welcome_body), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(Space.xxl))
            Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                listOf(
                    Icons.Rounded.NotificationsActive to R.string.tour_notifications_title,
                    Icons.Rounded.PhotoLibrary to R.string.tour_photos_title,
                    Icons.Rounded.ContentPaste to R.string.tour_clipboard_title,
                    Icons.Rounded.ScreenShare to R.string.tour_screen_title
                ).forEachIndexed { index, (icon, label) ->
                    // Each row slides in a moment after the previous one.
                    val shown = remember { Animatable(if (reduce) 1f else 0f) }
                    LaunchedEffect(Unit) {
                        delay(250L + index * 90L)
                        shown.animateTo(1f, androidx.compose.animation.core.tween(320))
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.graphicsLayer { alpha = shown.value; translationX = (1f - shown.value) * 40f }
                    ) {
                        FeatureIcon(icon, size = 36.dp)
                        Spacer(Modifier.width(Space.m))
                        Text(stringResource(label), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            Spacer(Modifier.height(Space.xxl))
            Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(Space.s))
            ChoiceRow(
                options = listOf(
                    LocaleText.Language.System to stringResource(R.string.language_system),
                    LocaleText.Language.English to "English",
                    LocaleText.Language.German to "Deutsch"
                ),
                selected = language,
                onSelect = onLanguage
            )
        },
        actions = { PrimaryButton(stringResource(R.string.action_lets_go), onClick = onNext) }
    )
}

private data class TourPage(val icon: ImageVector, val title: Int, val body: Int, val scene: TourScene)

@Composable
private fun TourStep(onNext: () -> Unit) {
    val pages = listOf(
        TourPage(Icons.Rounded.NotificationsActive, R.string.tour_notifications_title, R.string.tour_notifications_body, TourScene.PhoneToPc),
        TourPage(Icons.Rounded.Call, R.string.tour_calls_title, R.string.tour_calls_body, TourScene.Ring),
        TourPage(Icons.Rounded.PhotoLibrary, R.string.tour_photos_title, R.string.tour_photos_body, TourScene.PhoneToPc),
        TourPage(Icons.Rounded.ContentPaste, R.string.tour_clipboard_title, R.string.tour_clipboard_body, TourScene.BothWays),
        TourPage(Icons.Rounded.ScreenShare, R.string.tour_screen_title, R.string.tour_screen_body, TourScene.Mirror),
        TourPage(Icons.Rounded.Lock, R.string.tour_private_title, R.string.tour_private_body, TourScene.Shield)
    )
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == pages.lastIndex
    Column(Modifier.fillMaxSize()) {
        Text(
            stringResource(R.string.tour_title),
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.padding(horizontal = Space.xxl, vertical = Space.m)
        )
        HorizontalPager(pager, Modifier.weight(1f)) { index ->
            val page = pages[index]
            val visible = pager.currentPage == index
            Column(
                Modifier.fillMaxSize().padding(horizontal = Space.xxl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                TourAnimation(page.icon, page.scene, playing = visible, modifier = Modifier.fillMaxWidth().height(240.dp))
                Spacer(Modifier.height(Space.xxxl))
                Text(stringResource(page.title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Spacer(Modifier.height(Space.m))
                Text(
                    stringResource(page.body),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = Space.m), horizontalArrangement = Arrangement.Center) {
            repeat(pages.size) { index ->
                val width by animateDpAsState(if (index == pager.currentPage) 24.dp else 8.dp, label = "dot")
                Box(
                    Modifier.padding(horizontal = 3.dp).height(8.dp).width(width).clip(CircleShape)
                        .background(if (index == pager.currentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
                )
            }
        }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Space.xxl, vertical = Space.m),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            PrimaryButton(
                stringResource(if (last) R.string.action_continue else R.string.action_next),
                onClick = { if (last) onNext() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }
            )
            QuietButton(stringResource(R.string.action_skip_tour), onClick = onNext)
        }
    }
}

@Composable
private fun InstallPcStep(onNext: () -> Unit) {
    val actions = LocalFeatureActions.current
    val shareText = stringResource(R.string.install_share_text, Links.STORE, Links.INSTALLER)
    StepLayout(
        content = {
            StepTitle(stringResource(R.string.install_title), stringResource(R.string.install_body))
            PairlyCard(Modifier.fillMaxWidth(), onClick = { actions.openUrl(Links.STORE) }) {
                ListRow(
                    title = stringResource(R.string.install_store_title),
                    subtitle = stringResource(R.string.install_store_detail),
                    icon = Icons.Rounded.Storefront
                ) { Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null) }
            }
            Spacer(Modifier.height(Space.m))
            PairlyCard(Modifier.fillMaxWidth(), onClick = { actions.openUrl(Links.INSTALLER) }) {
                ListRow(
                    title = stringResource(R.string.install_exe_title),
                    subtitle = stringResource(R.string.install_exe_detail),
                    icon = Icons.Rounded.Download
                ) { Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null) }
            }
            Spacer(Modifier.height(Space.m))
            PairlyCard(Modifier.fillMaxWidth(), onClick = { actions.share(shareText) }) {
                ListRow(
                    title = stringResource(R.string.install_share_title),
                    subtitle = stringResource(R.string.install_share_detail),
                    icon = Icons.Rounded.Share
                ) { Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null) }
            }
        },
        actions = { PrimaryButton(stringResource(R.string.install_done), onClick = onNext) }
    )
}

@Composable
private fun PairStep(state: AppState, pairing: PairingCallbacks, onNext: () -> Unit) {
    StepLayout(
        content = {
            StepTitle(stringResource(R.string.onboarding_connect_title), stringResource(R.string.onboarding_connect_body))
            if (state.hasPairing) {
                // A small pop for the moment it works.
                val pop = remember { Animatable(0.6f) }
                LaunchedEffect(Unit) { pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow)) }
                PairlyCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CheckBadge(36.dp, Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value })
                        Spacer(Modifier.width(Space.m))
                        Text(
                            stringResource(R.string.onboarding_paired_with, state.pcName.ifBlank { stringResource(R.string.paired_pc_fallback) }),
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            } else {
                PairingContent(state, pairing)
            }
        },
        actions = {
            if (state.hasPairing) PrimaryButton(stringResource(R.string.action_continue), onClick = onNext)
            else QuietButton(stringResource(R.string.action_skip_for_now), onClick = onNext)
        }
    )
}

@Composable
private fun PowerModeStep(state: AppState, onNext: () -> Unit) {
    var showGuide by rememberSaveable { mutableStateOf(false) }
    val ready = state.shizuku.ready
    StepLayout(
        content = {
            Text(stringResource(R.string.onboarding_power_label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(Space.m))
            TourAnimation(Icons.Rounded.Bolt, TourScene.Shield, playing = !ready, modifier = Modifier.fillMaxWidth().height(160.dp))
            Spacer(Modifier.height(Space.l))
            StepTitle(
                stringResource(if (ready) R.string.power_on else R.string.power_off),
                stringResource(if (ready) R.string.onboarding_power_ready else R.string.power_off_detail)
            )
            PowerBenefits()
            if (showGuide || (state.shizuku.installed && !ready)) {
                Spacer(Modifier.height(Space.xl))
                ShizukuGuide(state.shizuku)
            }
        },
        actions = {
            when {
                ready -> PrimaryButton(stringResource(R.string.action_continue), onClick = onNext)
                showGuide || state.shizuku.installed -> QuietButton(stringResource(R.string.action_not_now), onClick = onNext)
                else -> {
                    PrimaryButton(stringResource(R.string.action_set_up_power), onClick = { showGuide = true }, icon = Icons.Rounded.Bolt)
                    QuietButton(stringResource(R.string.action_not_now), onClick = onNext)
                }
            }
        }
    )
}

@Composable
private fun PermissionsStep(state: AppState, onGrantAll: () -> Unit, onNext: () -> Unit) {
    val actions = LocalFeatureActions.current
    val missing = state.permissions.missing
    val power = state.shizuku.ready && missing.isNotEmpty()
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    StepLayout(
        content = {
            StepTitle(
                stringResource(R.string.onboarding_permissions_title),
                stringResource(if (power) R.string.onboarding_permissions_power else R.string.onboarding_permissions_body)
            )
            if (power) {
                PrimaryButton(
                    pluralStringResource(R.plurals.power_grant_all, missing.size, missing.size),
                    onClick = onGrantAll,
                    icon = Icons.Rounded.DoneAll
                )
                Spacer(Modifier.height(Space.l))
            }
            PairlyCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = Space.s)) {
                Feature.setupOrder.forEach { feature ->
                    val on = state.permissions.isOn(feature)
                    // Android refused it: open the row so the App info steps are in view.
                    val blocked = isBlockedByAndroid(feature, state.permissions)
                    LaunchedEffect(blocked) { if (blocked) expanded = feature.key }
                    val open = expanded == feature.key
                    Column(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 64.dp)
                                .clickable { expanded = if (open) null else feature.key }
                                .padding(horizontal = Space.l),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FeatureIcon(feature.icon(), size = 36.dp, highlighted = on)
                            Spacer(Modifier.width(Space.m))
                            Column(Modifier.weight(1f).padding(vertical = Space.s)) {
                                Text(stringResource(feature.title), style = MaterialTheme.typography.titleSmall)
                                Text(stringResource(feature.summary), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.width(Space.s))
                            AnimatedContent(on, label = "on-${feature.key}") { isOn ->
                                if (isOn) CheckBadge(28.dp)
                                else if (blocked) FilledTonalButton(onClick = actions::openAppInfo) { Text(stringResource(R.string.action_open_app_info)) }
                                else FilledTonalButton(onClick = { actions.enable(feature) }) { Text(stringResource(R.string.action_turn_on)) }
                            }
                        }
                        AnimatedVisibility(open && !on) {
                            FeatureSetupContent(feature, state.permissions, Modifier.padding(horizontal = Space.l, vertical = Space.s), showAction = false)
                        }
                    }
                }
            }
        },
        actions = {
            PrimaryButton(stringResource(if (missing.isEmpty()) R.string.action_continue else R.string.action_skip_for_now), onClick = onNext)
        }
    )
}

@Composable
private fun DoneStep(state: AppState, onFinish: () -> Unit) {
    val reduce = Pairly.style.reduceMotion
    val pop = remember { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(Unit) { pop.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.45f, stiffness = 260f)) }
    StepLayout(
        content = {
            Spacer(Modifier.height(Space.xl))
            Box(
                Modifier.size(96.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value }
                    .clip(CircleShape).background(Pairly.style.heroBrush),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Rounded.Check, contentDescription = null, tint = Pairly.style.onHero, modifier = Modifier.size(52.dp)) }
            Spacer(Modifier.height(Space.xxl))
            StepTitle(stringResource(R.string.onboarding_done_title), stringResource(R.string.onboarding_done_body))
            PairlyCard(Modifier.fillMaxWidth()) {
                (Feature.setupOrder + Feature.Clipboard).forEach { feature ->
                    val on = state.permissions.isOn(feature)
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        FeatureIcon(feature.icon(), size = 32.dp, highlighted = on)
                        Spacer(Modifier.width(Space.m))
                        Text(stringResource(feature.title), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text(
                            stringResource(if (on) R.string.permission_on else R.string.onboarding_skipped),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (on) Pairly.style.success else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        actions = { PrimaryButton(stringResource(R.string.action_start_using), onClick = onFinish) }
    )
}
