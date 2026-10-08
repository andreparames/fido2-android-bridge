package com.fidobridge.client.ui.subscribe

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fidobridge.client.R
import com.fidobridge.client.billing.ProductIds
import com.fidobridge.client.billing.SubscriptionProduct

@Composable
fun SubscribeScreen(
    viewModel: SubscribeViewModel = hiltViewModel(),
    onEntitled: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val entitlement by viewModel.entitlement.collectAsStateWithLifecycle()
    val context = LocalContext.current

    androidx.compose.runtime.LaunchedEffect(entitlement.isEntitled) {
        if (entitlement.isEntitled) onEntitled()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth()) {
            Text(
                text = stringResource(R.string.subscribe_title),
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.subscribe_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))

            when {
                uiState.loading -> CircularProgressIndicator()
                uiState.billingUnavailable -> {
                    Text(stringResource(R.string.subscribe_billing_unavailable))
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { viewModel.refresh() }) {
                        Text(stringResource(R.string.subscribe_restore))
                    }
                }
                uiState.error -> {
                    Text(stringResource(R.string.subscribe_error))
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { viewModel.refresh() }) {
                        Text(stringResource(R.string.subscribe_restore))
                    }
                }
                else -> {
                    uiState.products.forEach { product ->
                        ProductCard(
                            product = product,
                            onBuy = { viewModel.buy(context as android.app.Activity, product.productId) }
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            TextButton(onClick = { viewModel.restore() }) {
                Text(stringResource(R.string.subscribe_restore))
            }
            TextButton(
                onClick = {
                    val intent = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/account/subscriptions")
                    )
                    context.startActivity(intent)
                }
            ) {
                Text(stringResource(R.string.subscribe_manage))
            }
        }
    }
}

@Composable
private fun ProductCard(
    product: SubscriptionProduct,
    onBuy: () -> Unit
) {
    androidx.compose.material3.ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(product.title, style = MaterialTheme.typography.titleMedium)
            val trial = product.freeTrialPeriod == ProductIds.TRIAL_PERIOD
            Text(
                text = if (trial) {
                    stringResource(R.string.subscribe_trial_copy, product.formattedPrice)
                } else {
                    product.formattedPrice
                },
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onBuy, modifier = Modifier.fillMaxWidth()) {
                Text(product.formattedPrice)
            }
        }
    }
}
