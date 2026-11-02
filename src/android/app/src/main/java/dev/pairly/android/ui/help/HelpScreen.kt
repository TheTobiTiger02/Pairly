package dev.pairly.android.ui.help

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.pairly.android.R
import dev.pairly.android.ui.Links
import dev.pairly.android.ui.LocalFeatureActions
import dev.pairly.android.ui.components.DetailTopBar
import dev.pairly.android.ui.components.ExpandableItem
import dev.pairly.android.ui.components.ListRow
import dev.pairly.android.ui.components.PairlyCard
import dev.pairly.android.ui.components.ScreenList
import dev.pairly.android.ui.components.ScreenTitle
import dev.pairly.android.ui.components.SectionLabel

private val faq = listOf(
    R.string.faq_find_pc_q to R.string.faq_find_pc_a,
    R.string.faq_drops_q to R.string.faq_drops_a,
    R.string.faq_restricted_q to R.string.faq_restricted_a,
    R.string.faq_clipboard_q to R.string.faq_clipboard_a,
    R.string.faq_mirror_q to R.string.faq_mirror_a,
    R.string.faq_devices_q to R.string.faq_devices_a,
    R.string.faq_private_q to R.string.faq_private_a,
    R.string.faq_updates_q to R.string.faq_updates_a
)

class HelpCallbacks(
    val replayOnboarding: () -> Unit,
    val openShizukuGuide: () -> Unit,
    /** Null when Help is a top-level tab (no back arrow). */
    val back: (() -> Unit)?
)

@Composable
fun HelpScreen(callbacks: HelpCallbacks) {
    val actions = LocalFeatureActions.current
    Column(Modifier.fillMaxSize()) {
        callbacks.back?.let { DetailTopBar(stringResource(R.string.nav_help), it) }
        ScreenList {
            item { ScreenTitle(stringResource(R.string.help_title), stringResource(R.string.help_subtitle)) }
            item { SectionLabel(stringResource(R.string.help_faq)) }
            faq.forEach { (q, a) ->
                item(key = q) { ExpandableItem(stringResource(q), stringResource(a)) }
            }
            item { SectionLabel(stringResource(R.string.help_more)) }
            item {
                PairlyCard(Modifier.fillMaxWidth()) {
                    ListRow(stringResource(R.string.help_replay_onboarding), subtitle = stringResource(R.string.help_replay_detail), icon = Icons.Rounded.Replay, onClick = callbacks.replayOnboarding)
                    ListRow(stringResource(R.string.shizuku_title), subtitle = stringResource(R.string.feature_clipboard_summary), icon = Icons.Rounded.ContentPaste, onClick = callbacks.openShizukuGuide)
                    ListRow(stringResource(R.string.help_report), subtitle = stringResource(R.string.help_report_detail), icon = Icons.Rounded.BugReport, onClick = { actions.openUrl(Links.ISSUES) })
                }
            }
        }
    }
}
