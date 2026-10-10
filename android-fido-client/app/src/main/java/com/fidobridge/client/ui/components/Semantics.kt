package com.fidobridge.client.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics

/** Marks a text node as a heading so screen readers can navigate by section. */
fun Modifier.heading(): Modifier = semantics { heading() }

/**
 * Describes an indeterminate progress indicator to accessibility services,
 * which would otherwise announce nothing while loading.
 */
fun Modifier.progressSemantics(label: String): Modifier = semantics {
    contentDescription = label
    progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
}
