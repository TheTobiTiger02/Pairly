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
fun FeatureSetupContent(feature: Feature, granted: Boolean, modifier: Modifier = Modifier, showAction: Boolean = true) {
    val actions = LocalFeatureActions.current
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
            targetState = granted,
            transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.92f)).togetherWith(fadeOut()) },
            label = "feature-state"
        ) { on ->
            if (on) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    CheckBadge()
                    Spacer(Modifier.width(Space.m))
                    Text(stringResource(R.string.feature_is_on), style = MaterialTheme.typography.titleMedium, color = Pairly.style.success)
                }
            } else if (feature.steps.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                    Text(stringResource(R.string.feature_how_title), style = MaterialTheme.typography.titleMedium)
                    NumberedSteps(feature.steps.map { stringResource(it) })
                    if (feature.restrictedSetting) RestrictedSettingHint(onOpenAppInfo = actions::openAppInfo)
                }
            }
        }
        if (showAction && !granted) {
            PrimaryButton(stringResource(feature.action), onClick = { actions.enable(feature) })
        }
    }
}

/** Android 13+ blocks notification access and accessibility for sideloaded apps until allowed. */
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
