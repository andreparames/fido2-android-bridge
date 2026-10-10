package com.fidobridge.client.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.fidobridge.client.R
import com.fidobridge.client.ui.components.progressSemantics
import com.fidobridge.client.ui.theme.FidoBridgeTheme

object LoadingTags {
    const val ROOT = "loading_root"
}

/**
 * Covers the app while the entitlement/subscription status is being resolved.
 * The indicator is described to screen readers via [progressSemantics].
 */
@Composable
fun LoadingScreen(modifier: Modifier = Modifier) {
    val label = stringResource(R.string.loading_entitlement)
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(LoadingTags.ROOT),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(modifier = Modifier.progressSemantics(label))
        Spacer(Modifier.height(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Preview(name = "Loading", showBackground = true, widthDp = 411, heightDp = 800, locale = "en")
@Composable
private fun LoadingPreview() {
    FidoBridgeTheme {
        LoadingScreen()
    }
}