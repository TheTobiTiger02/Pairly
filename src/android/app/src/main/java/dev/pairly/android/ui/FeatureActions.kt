package dev.pairly.android.ui

import androidx.compose.runtime.staticCompositionLocalOf
import dev.pairly.android.network.PairingLink
import dev.pairly.android.ui.features.Feature

/** Shizuku setup actions, one per guide step. */
enum class ShizukuAction { OpenAboutPhone, OpenDeveloperOptions, GetShizuku, OpenShizuku, AllowPairly }

/**
 * Things only the activity can do (permission dialogs, system settings, other apps). MainActivity
 * implements it; screens reach it through [LocalFeatureActions], so they stay previewable.
 */
interface FeatureActions {
    fun enable(feature: Feature)
    fun shizuku(action: ShizukuAction)
    fun openAppInfo()
    fun openUrl(url: String)
    fun share(text: String)
    fun scanQr(onLink: (PairingLink) -> Unit, onInvalid: () -> Unit)
    val canScanQr: Boolean
}

object NoFeatureActions : FeatureActions {
    override fun enable(feature: Feature) = Unit
    override fun shizuku(action: ShizukuAction) = Unit
    override fun openAppInfo() = Unit
    override fun openUrl(url: String) = Unit
    override fun share(text: String) = Unit
    override fun scanQr(onLink: (PairingLink) -> Unit, onInvalid: () -> Unit) = Unit
    override val canScanQr: Boolean = true
}

val LocalFeatureActions = staticCompositionLocalOf<FeatureActions> { NoFeatureActions }

object Links {
    const val STORE = "https://apps.microsoft.com/detail/9NDLWS4D52HM"
    const val INSTALLER = "https://github.com/TheTobiTiger02/Pairly/releases/latest/download/Pairly-Setup.exe"
    const val RELEASES = "https://github.com/TheTobiTiger02/Pairly/releases/latest"
    const val PRIVACY = "https://github.com/TheTobiTiger02/Pairly/blob/main/PRIVACY.md"
    const val ISSUES = "https://github.com/TheTobiTiger02/Pairly/issues"
    const val SOURCE = "https://github.com/TheTobiTiger02/Pairly"
    const val SHIZUKU_PLAY = "https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api"
    const val SHIZUKU_DOWNLOAD = "https://shizuku.rikka.app/download/"
}
