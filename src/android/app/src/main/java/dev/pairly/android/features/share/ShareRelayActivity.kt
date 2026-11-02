package dev.pairly.android.features.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pairly.android.R
import dev.pairly.android.features.clipboard.ClipboardImagePreparer
import dev.pairly.android.features.clipboard.ClipboardSyncController
import dev.pairly.android.network.ConnectionSettings
import dev.pairly.android.network.PairlyConnectionService
import dev.pairly.android.ui.LocaleText
import dev.pairly.android.ui.UiPreferences
import dev.pairly.android.ui.components.PrimaryButton
import dev.pairly.android.ui.theme.Pairly
import dev.pairly.android.ui.theme.PairlyTheme
import dev.pairly.android.ui.theme.Space

/**
 * Pairly in Android's share sheet. Text and a single image go to the PC's clipboard (as before);
 * anything else, or several items, is saved to Downloads\Pairly on the PC with a progress sheet.
 */
class ShareRelayActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleText.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ConnectionSettings(this).trustKey.isBlank()) {
            finishWithToast(R.string.share_pair_first)
            return
        }

        if (savedInstanceState == null) {
            when (val route = ShareRouting.route(intent.action, intent.type, streams(intent), intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString())) {
                is ShareRouting.Route.Text -> {
                    val queued = ClipboardSyncController.sendPhoneClipboard(this, route.text)
                    finishWithToast(if (queued) R.string.share_text_queued else R.string.share_not_relayed)
                    return
                }
                is ShareRouting.Route.ClipboardImage -> {
                    relayImage(route.uri)
                    return
                }
                is ShareRouting.Route.Files -> FileShareSender.send(this, route.uris)
                ShareRouting.Route.Nothing -> {
                    finishWithToast(R.string.share_unsupported)
                    return
                }
            }
        }

        val prefs = UiPreferences.get(this).current
        setContent {
            PairlyTheme(prefs.themeMode, prefs.dynamicColor, prefs.glassDark) {
                ShareSheet(onClose = ::finish)
            }
        }
    }

    private fun streams(intent: Intent): List<Uri> = buildList {
        if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
            }
            list?.let(::addAll)
        } else {
            val single = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            single?.let(::add)
        }
    }

    private fun relayImage(uri: Uri) {
        Thread({
            val prepared = ClipboardImagePreparer.prepare(this, uri)
            runOnUiThread {
                prepared.onSuccess {
                    PairlyConnectionService.sendClipboardImage(this, it.file.absolutePath, it.fileName)
                    finishWithToast(R.string.share_image_queued)
                }.onFailure {
                    Toast.makeText(this, it.message ?: getString(R.string.share_not_relayed), Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }, "ClipboardImagePrepare").start()
    }

    private fun finishWithToast(message: Int) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        finish()
    }
}

/** Decides what a share becomes. Kept free of Android UI so it can be unit-tested. */
object ShareRouting {
    sealed interface Route {
        data class Text(val text: String) : Route
        data class ClipboardImage(val uri: Uri) : Route
        data class Files(val uris: List<Uri>) : Route
        data object Nothing : Route
    }

    fun route(action: String?, type: String?, streams: List<Uri>, text: String?): Route = when {
        streams.isEmpty() && !text.isNullOrBlank() -> Route.Text(text)
        streams.isEmpty() -> Route.Nothing
        action == Intent.ACTION_SEND && streams.size == 1 && type?.startsWith("image/") == true && type != "image/gif" -> Route.ClipboardImage(streams.single())
        else -> Route.Files(streams)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareSheet(onClose: () -> Unit) {
    val state by FileShareSender.state.collectAsStateWithLifecycle()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(state.done) {
        if (state.done && state.failed == 0) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Space.xl).navigationBarsPadding().padding(bottom = Space.l)) {
            val count = state.files.size
            AnimatedContent(
                targetState = when {
                    !state.done -> 0
                    state.failed == 0 -> 1
                    else -> 2
                },
                transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.96f)).togetherWith(fadeOut()) },
                label = "share-title"
            ) { phase ->
                Text(
                    when (phase) {
                        0 -> pluralStringResource(R.plurals.share_sending, count, count)
                        1 -> pluralStringResource(R.plurals.share_saved, count, count)
                        else -> pluralStringResource(R.plurals.share_some_failed, state.failed, state.failed)
                    },
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                )
            }
            Spacer(Modifier.height(Space.xs))
            Text(
                stringResource(R.string.share_destination),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Space.l))
            if (state.active) {
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
                Spacer(Modifier.height(Space.l))
            }
            LazyColumn(Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                items(state.files, key = { it.id }) { file -> SharedFileRow(file) }
            }
            Spacer(Modifier.height(Space.xl))
            PrimaryButton(
                stringResource(if (state.active) R.string.share_hide else R.string.action_done),
                onClick = {
                    if (!state.active) FileShareSender.clearFinished()
                    onClose()
                }
            )
        }
    }
}

@Composable
private fun SharedFileRow(file: SharedFile) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = Space.xs)) {
        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
            when (file.status) {
                SharedFile.Status.Saved -> Icon(Icons.Rounded.CheckCircle, stringResource(R.string.share_status_saved), tint = Pairly.style.success)
                SharedFile.Status.Failed -> Icon(Icons.Rounded.ErrorOutline, stringResource(R.string.share_status_failed), tint = MaterialTheme.colorScheme.error)
                else -> Icon(Icons.AutoMirrored.Rounded.InsertDriveFile, null, tint = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
            Text(file.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val detail = when {
                file.status == SharedFile.Status.Failed -> file.error.orEmpty()
                file.size >= 0 -> formatSize(file.size)
                else -> ""
            }
            if (detail.isNotBlank()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (file.status == SharedFile.Status.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (file.status == SharedFile.Status.Sending) {
                Spacer(Modifier.height(Space.xs))
                LinearProgressIndicator(progress = { file.progress }, modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape))
            }
        }
    }
}

internal fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
}
