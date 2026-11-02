package dev.pairly.android.ui.onboarding

import dev.pairly.android.ui.features.Feature

/** Onboarding, in order. The current step is saved, so a closed app resumes where it left off. */
enum class OnboardingStep(val feature: Feature? = null) {
    Welcome,
    Tour,
    InstallPc,
    Pair,
    AppAlerts(Feature.AppAlerts),
    Notifications(Feature.Notifications),
    Messages(Feature.Messages),
    Calls(Feature.Calls),
    Photos(Feature.Photos),
    Files(Feature.Files),
    RemoteControl(Feature.RemoteControl),
    Mirroring(Feature.Mirroring),
    Background(Feature.Background),
    Clipboard(Feature.Clipboard),
    Done;

    fun next(): OnboardingStep = entries.getOrElse(ordinal + 1) { Done }
    fun previous(): OnboardingStep = entries.getOrElse(ordinal - 1) { Welcome }

    companion object {
        val permissionSteps = entries.filter { it.feature != null && !it.feature.advanced }
    }
}
