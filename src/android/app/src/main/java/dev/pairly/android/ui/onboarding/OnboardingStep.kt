package dev.pairly.android.ui.onboarding

/**
 * Onboarding, in order. The current step is saved, so a closed app resumes where it left off; a
 * saved step from an older version that no longer exists starts over at Welcome.
 */
enum class OnboardingStep {
    Welcome,
    Tour,
    InstallPc,
    Pair,
    /** Optional, recommended: Shizuku, which unlocks the rest and makes permissions one tap. */
    PowerMode,
    /** Every permission on one screen; skipped ones are asked for when first needed. */
    Permissions,
    Done;

    fun next(): OnboardingStep = entries.getOrElse(ordinal + 1) { Done }
    fun previous(): OnboardingStep = entries.getOrElse(ordinal - 1) { Welcome }
}
