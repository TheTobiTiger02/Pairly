package dev.pairly.android.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pairly.android.R
import dev.pairly.android.network.ConnectionPhase
import dev.pairly.android.network.PairingLink
import dev.pairly.android.network.PairlyConnection
import dev.pairly.android.ui.components.PairlyBackground
import dev.pairly.android.ui.navigation.MainShell
import dev.pairly.android.ui.navigation.ShellCallbacks
import dev.pairly.android.ui.onboarding.OnboardingScreen
import dev.pairly.android.ui.onboarding.OnboardingStep
import dev.pairly.android.ui.onboarding.PairingCallbacks
import dev.pairly.android.ui.theme.PairlyTheme
import dev.pairly.android.ui.whatsnew.WhatsNewSheet
import kotlinx.coroutines.delay

@Composable
fun PairlyApp(
    viewModel: AppViewModel,
    language: LocaleText.Language,
    onLanguage: (LocaleText.Language) -> Unit,
    incomingPairingLink: String? = null,
    onPairingLinkHandled: () -> Unit = {}
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val prefs by viewModel.preferences.prefsFlow.collectAsStateWithLifecycle()
    val whatsNew by viewModel.whatsNew.collectAsStateWithLifecycle()
    var linkAwaitingConfirmation by remember { mutableStateOf<PairingLink?>(null) }
    val invalidLink = stringResource(R.string.scan_not_pairing_code)

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    val pairing = PairingCallbacks(
        discover = viewModel::discover,
        pair = viewModel::pair,
        pairWithLink = { link ->
            if (state.hasPairing) linkAwaitingConfirmation = link else viewModel.pairWithLink(link)
        },
        rememberManualAddress = viewModel::rememberManualAddress,
        savedManualAddress = viewModel.savedManualAddress
    )

    // A pairly://pair link opened from the camera app or another app.
    LaunchedEffect(incomingPairingLink) {
        if (incomingPairingLink == null) return@LaunchedEffect
        val link = PairingLink.parse(incomingPairingLink)
        onPairingLinkHandled()
        when {
            link == null -> PairlyConnection.updateState(ConnectionPhase.Rejected, invalidLink)
            state.hasPairing -> linkAwaitingConfirmation = link
            else -> {
                if (!prefs.onboardingDone) viewModel.updatePrefs { it.copy(onboardingStep = OnboardingStep.Pair) }
                viewModel.pairWithLink(link)
            }
        }
    }

    // Just paired during onboarding: celebrate briefly, then continue with the features.
    var wasPaired by remember { mutableStateOf(state.hasPairing) }
    LaunchedEffect(state.hasPairing) {
        val justPaired = state.hasPairing && !wasPaired
        wasPaired = state.hasPairing
        if (justPaired && !prefs.onboardingDone && prefs.onboardingStep == OnboardingStep.Pair) {
            delay(1_200)
            viewModel.updatePrefs { it.copy(onboardingStep = OnboardingStep.Pair.next()) }
        }
    }

    PairlyTheme(prefs.variant, prefs.themeMode, prefs.dynamicColor) {
        PairlyBackground {
            AnimatedContent(
                targetState = prefs.onboardingDone,
                transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.96f)).togetherWith(fadeOut()) },
                label = "onboarding-or-app"
            ) { done ->
                if (!done) {
                    OnboardingScreen(
                        step = prefs.onboardingStep,
                        state = state,
                        pairing = pairing,
                        language = language,
                        onLanguage = onLanguage,
                        onStep = { step -> viewModel.updatePrefs { it.copy(onboardingStep = step) } },
                        onFinish = { viewModel.updatePrefs { it.copy(onboardingDone = true, onboardingStep = OnboardingStep.Welcome) } }
                    )
                } else {
                    MainShell(
                        state = state,
                        prefs = prefs,
                        language = language,
                        callbacks = ShellCallbacks(
                            pairing = pairing,
                            reconnect = viewModel::reconnect,
                            syncNow = viewModel::syncNow,
                            forget = viewModel::forget,
                            setAutoConnect = viewModel::setAutoConnect,
                            setClipboardSync = viewModel::setClipboardSync,
                            readClipboard = viewModel::readClipboard,
                            sendClipboard = viewModel::sendClipboard,
                            updatePrefs = viewModel::updatePrefs,
                            setLanguage = onLanguage,
                            replayOnboarding = { viewModel.updatePrefs { it.copy(onboardingDone = false, onboardingStep = OnboardingStep.Welcome) } }
                        )
                    )
                }
            }

            if (prefs.onboardingDone) {
                whatsNew?.let { WhatsNewSheet(it, onDismiss = viewModel::dismissWhatsNew) }
            }

            linkAwaitingConfirmation?.let { link ->
                AlertDialog(
                    onDismissRequest = { linkAwaitingConfirmation = null },
                    title = { Text(stringResource(R.string.repair_dialog_title, link.pcName)) },
                    text = { Text(stringResource(R.string.repair_dialog_body, state.pcName.ifBlank { stringResource(R.string.paired_pc_fallback) })) },
                    confirmButton = {
                        TextButton(onClick = { linkAwaitingConfirmation = null; viewModel.pairWithLink(link) }) {
                            Text(stringResource(R.string.action_pair))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { linkAwaitingConfirmation = null }) { Text(stringResource(R.string.action_cancel)) }
                    }
                )
            }
        }
    }
}
