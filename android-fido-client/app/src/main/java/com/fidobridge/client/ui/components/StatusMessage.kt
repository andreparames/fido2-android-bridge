package com.fidobridge.client.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.fidobridge.client.ui.theme.semanticColors

enum class StatusMessageTone { ERROR, WARNING, SUCCESS, INFO }

private data class ToneVisuals(
    val container: Color,
    val content: Color,
    val icon: ImageVector
)

/**
 * A status/error banner that is announced to screen readers via a live region.
 * Use ERROR for failures the user must notice (assertively announced) and the
 * other tones for confirmations/hints (politely announced).
 */
@Composable
fun StatusMessage(
    text: String,
    modifier: Modifier = Modifier,
    tone: StatusMessageTone = StatusMessageTone.ERROR,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    testTag: String? = null
) {
    val visuals = toneVisuals(tone)
    val tagged = if (testTag != null) modifier.testTag(testTag) else modifier
    Surface(
        color = visuals.container,
        shape = RoundedCornerShape(12.dp),
        modifier = tagged
            .fillMaxWidth()
            .semantics {
                liveRegion = if (tone == StatusMessageTone.ERROR) {
                    LiveRegionMode.Assertive
                } else {
                    LiveRegionMode.Polite
                }
            }
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                visuals.icon,
                contentDescription = null,
                tint = visuals.content,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = visuals.content,
                modifier = Modifier.weight(1f)
            )
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onAction) {
                    Text(actionLabel, color = visuals.content)
                }
            }
        }
    }
}

@Composable
private fun toneVisuals(tone: StatusMessageTone): ToneVisuals {
    val semantic = semanticColors()
    return when (tone) {
        StatusMessageTone.ERROR -> ToneVisuals(
            container = MaterialTheme.colorScheme.errorContainer,
            content = MaterialTheme.colorScheme.onErrorContainer,
            icon = Icons.Outlined.ErrorOutline
        )
        StatusMessageTone.WARNING -> ToneVisuals(
            container = semantic.warningContainer,
            content = semantic.onWarning,
            icon = Icons.Outlined.Warning
        )
        StatusMessageTone.SUCCESS -> ToneVisuals(
            container = semantic.successContainer,
            content = semantic.onSuccess,
            icon = Icons.Outlined.CheckCircle
        )
        StatusMessageTone.INFO -> ToneVisuals(
            container = MaterialTheme.colorScheme.secondaryContainer,
            content = MaterialTheme.colorScheme.onSecondaryContainer,
            icon = Icons.Outlined.Info
        )
    }
}
