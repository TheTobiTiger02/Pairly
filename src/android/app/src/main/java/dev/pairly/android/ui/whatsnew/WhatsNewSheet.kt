package dev.pairly.android.ui.whatsnew

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.pairly.android.R
import dev.pairly.android.ui.WhatsNew
import dev.pairly.android.ui.components.FeatureIcon
import dev.pairly.android.ui.components.PrimaryButton
import dev.pairly.android.ui.components.StatusDot
import dev.pairly.android.ui.theme.Space

/** Shown once after an update, with the release's "What's new" lines. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhatsNewSheet(whatsNew: WhatsNew, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        WhatsNewContent(whatsNew, onDismiss)
    }
}

@Composable
fun WhatsNewContent(whatsNew: WhatsNew, onDismiss: () -> Unit) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Space.xxl, vertical = Space.m)) {
        FeatureIcon(Icons.Rounded.AutoAwesome, size = 56.dp, highlighted = true)
        Spacer(Modifier.height(Space.l))
        Text(stringResource(R.string.whats_new_title, whatsNew.version), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(Space.l))
        Column(
            Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Space.m)
        ) {
            whatsNew.notes.forEach { note ->
                Row(verticalAlignment = Alignment.Top) {
                    Spacer(Modifier.width(2.dp))
                    Column(Modifier.padding(top = 8.dp)) { StatusDot(MaterialTheme.colorScheme.primary, size = 6.dp) }
                    Spacer(Modifier.width(Space.m))
                    Text(note, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        Spacer(Modifier.height(Space.xxl))
        PrimaryButton(stringResource(R.string.action_got_it), onClick = onDismiss)
        Spacer(Modifier.size(Space.m))
    }
}
