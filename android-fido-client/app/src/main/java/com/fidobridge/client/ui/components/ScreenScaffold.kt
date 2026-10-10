package com.fidobridge.client.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Shared layout constants so screens don't drift apart. */
object ScreenDimens {
    val MaxContentWidth = 600.dp
    val ContentPadding = 16.dp
}

/**
 * Consistent full-screen container: window insets from [Scaffold], content
 * centered and capped at [maxWidth] for tablets/foldables, and (by default)
 * vertically scrollable so content stays reachable at large font scales,
 * in landscape, and on short screens.
 *
 * Pass [scrollable] = false when the content owns its own scrolling (e.g. a
 * weighted `LazyColumn`).
 */
@Composable
fun ScreenScaffold(
    modifier: Modifier = Modifier,
    maxWidth: Dp = ScreenDimens.MaxContentWidth,
    scrollable: Boolean = true,
    horizontalAlignment: Alignment.Horizontal = Alignment.CenterHorizontally,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    contentPadding: PaddingValues = PaddingValues(ScreenDimens.ContentPadding),
    content: @Composable ColumnScope.() -> Unit
) {
    Scaffold(modifier = modifier) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            val columnModifier = Modifier
                .fillMaxSize()
                .widthIn(max = maxWidth)
                .padding(contentPadding)
            Column(
                modifier = if (scrollable) {
                    columnModifier.verticalScroll(rememberScrollState())
                } else {
                    columnModifier
                },
                horizontalAlignment = horizontalAlignment,
                verticalArrangement = verticalArrangement,
                content = content
            )
        }
    }
}
