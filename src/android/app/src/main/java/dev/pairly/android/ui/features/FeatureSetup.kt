package dev.pairly.android.ui.features

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.pairly.android.R
import dev.pairly.android.ui.LocalFeatureActions
import dev.pairly.android.ui.components.CheckBadge
import dev.pairly.android.ui.components.FeatureIcon
import dev.pairly.android.ui.components.NumberedSteps
import dev.pairly.android.ui.components.PairlyCard
import dev.pairly.android.ui.components.PillTone
import dev.pairly.android.ui.components.PrimaryButton
import dev.pairly.android.ui.components.QuietButton
import dev.pairly.android.ui.components.StatusPill
import dev.pairly.android.ui.navigation.sharedElement
import dev.pairly.android.ui.theme.Pairly
import dev.pairly.android.ui.theme.Space

/** Header with the feature's icon, name and whether it is on. */
@Composable
fun FeatureHeader(feature: Feature, granted: Boolean, modifier: Modifier = Modifier) {
    Column(modifier) {
        FeatureIcon(feature.icon(), size = 64.dp, highlighted = granted, modifier = Modifier.sharedElement("icon-${feature.key}"))
        Spacer(Modifier.height(Space.l))
        Text(stringResource(feature.title), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(Space.s))
        StatusPill(
            stringResource(if (granted) R.string.permission_on else R.string.permission_off),
            if (granted) PillTone.Positive else PillTone.Neutral
        )
    }
}

/**
 * What a feature does, why it needs its permission, and how to turn it on. Shared by the feature
 * screens and onboarding so both explain it the same way.
 */
@Composable
fun FeatureSetupContent(feature: Feature, permissions: PermissionSnapshot, modifier: Modifier = Modifier, showAction: Boolean = true) {
    val actions = LocalFeatureActions.current
    val granted = permissions.isOn(feature)
    val blocked = isBlockedByAndroid(feature, permissions)

    // Back from App info with restricted settings now allowed: ask again right away, once.
    var lastRestricted by remember { mutableStateOf(permissions.restricted) }
    LaunchedEffect(permissions.restricted) {
        val nowAllowed = lastRestricted == RestrictedState.Blocked && permissions.restricted == RestrictedState.Allowed
        lastRestricted = permissions.restricted
        if (nowAllowed && !granted && RestrictedSettings.attempted[feature] == true) {
            RestrictedSettings.attempted.remove(feature)
            actions.enable(feature)
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.l)) {
        Text(stringResource(feature.description), style = MaterialTheme.typography.bodyLarge)
        PairlyCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Rounded.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(Space.m))
                Column {
                    Text(stringResource(R.string.feature_why_title), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(Space.xs))
                    Text(stringResource(feature.why), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        AnimatedContent(
            targetState = when {
                granted -> SetupView.On
                blocked -> SetupView.Blocked
                else -> SetupView.Steps
            },
            transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.92f)).togetherWith(fadeOut()) },
            label = "feature-state"
        ) { view ->
            when (view) {
                SetupView.On -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    CheckBadge()
                    Spacer(Modifier.width(Space.m))
                    Text(stringResource(R.string.feature_is_on), style = MaterialTheme.typography.titleMedium, color = Pairly.style.success)
                }
                SetupView.Blocked -> RestrictedGuide(feature)
                SetupView.Steps -> {
                    val steps = setupSteps(feature)
                    if (steps.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                            Text(stringResource(R.string.feature_how_title), style = MaterialTheme.typography.titleMedium)
                            NumberedSteps(steps)
                            if (feature.restrictedNow && permissions.restricted != RestrictedState.Allowed) {
                                RestrictedSettingHint(onOpenAppInfo = actions::openAppInfo)
                            }
                        }
                    }
                }
            }
        }
        if (showAction && !granted) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) { FeatureSetupActions(feature, permissions) }
        }
    }
}

private enum class SetupView { On, Blocked, Steps }

/** Android stopped the last attempt to turn this on: only App info can unblock it now. */
fun isBlockedByAndroid(feature: Feature, permissions: PermissionSnapshot): Boolean =
    !permissions.isOn(feature) && feature.restrictedNow &&
        permissions.restricted == RestrictedState.Blocked && RestrictedSettings.attempted[feature] == true

/** The buttons under a feature's setup: its action, or App info and Try again while Android blocks it. */
@Composable
fun FeatureSetupActions(feature: Feature, permissions: PermissionSnapshot) {
    val actions = LocalFeatureActions.current
    if (isBlockedByAndroid(feature, permissions)) {
        PrimaryButton(stringResource(R.string.action_open_app_info), onClick = actions::openAppInfo)
        QuietButton(stringResource(R.string.action_try_again), onClick = { actions.enable(feature) })
    } else {
        PrimaryButton(stringResource(feature.action), onClick = { actions.enable(feature) })
    }
}

/** The steps for this phone; for "Control your phone" they depend on which settings page Pairly can open. */
@Composable
private fun setupSteps(feature: Feature): List<String> {
    if (feature != Feature.RemoteControl) return feature.steps.map { stringResource(it) }
    val context = LocalContext.current
    val route = remember { AccessibilityRoute.best(context) }
    val open = stringResource(R.string.step_open_settings)
    val turnOn = stringResource(R.string.feature_control_step3)
    return when {
        route is AccessibilityRoute.Direct -> listOf(open, turnOn)
        route is AccessibilityRoute.InstalledList -> listOf(open, stringResource(R.string.feature_control_step_tap_pairly), turnOn)
        AccessibilityRoute.isOneUi() -> listOf(open, stringResource(R.string.feature_control_step_oneui), turnOn)
        route is AccessibilityRoute.Highlighted -> listOf(open, stringResource(R.string.feature_control_step_highlighted), turnOn)
        else -> feature.steps.map { stringResource(it) }
    }
}

/** Shown after Android blocked the permission: the exact taps in App info, with matching icons. */
@Composable
private fun RestrictedGuide(feature: Feature) {
    PairlyCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Lock, contentDescription = null, tint = Pairly.style.warning)
            Spacer(Modifier.width(Space.m))
            Text(stringResource(R.string.restricted_guide_title), style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(Space.s))
        Text(stringResource(R.string.restricted_guide_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(Space.l))
        val steps = listOf(
            Icons.Rounded.Info to stringResource(R.string.restricted_step_app_info),
            Icons.Rounded.MoreVert to stringResource(R.string.restricted_step_menu),
            Icons.Rounded.LockOpen to stringResource(R.string.restricted_step_allow),
            Icons.Rounded.Replay to stringResource(R.string.restricted_step_return, stringResource(feature.title))
        )
        Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
            steps.forEachIndexed { index, (icon, text) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FeatureIcon(icon, size = 36.dp)
                    Spacer(Modifier.width(Space.m))
                    Text("${index + 1}. $text", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** Before any attempt: a short note that Android may ask for one extra step. */
@Composable
private fun RestrictedSettingHint(onOpenAppInfo: () -> Unit) {
    PairlyCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.Info, contentDescription = null, tint = Pairly.style.warning)
            Spacer(Modifier.width(Space.m))
            Column {
                Text(stringResource(R.string.restricted_title), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(Space.xs))
                Text(stringResource(R.string.restricted_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                QuietButton(stringResource(R.string.action_open_app_info), onClick = onOpenAppInfo)
            }
        }
    }
}
