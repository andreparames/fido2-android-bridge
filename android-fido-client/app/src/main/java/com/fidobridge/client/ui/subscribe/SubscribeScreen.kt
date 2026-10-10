package com.fidobridge.client.ui.subscribe

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fidobridge.client.BuildConfig
import com.fidobridge.client.R
import com.fidobridge.client.billing.InviteCodeValidator
import com.fidobridge.client.billing.ProductIds
import com.fidobridge.client.billing.SubscriptionProduct
import com.fidobridge.client.ui.components.ScreenScaffold
import com.fidobridge.client.ui.components.StatusMessage
import com.fidobridge.client.ui.components.StatusMessageTone
import com.fidobridge.client.ui.components.heading
import com.fidobridge.client.ui.components.progressSemantics
import com.fidobridge.client.util.findActivity
import com.fidobridge.client.util.launchExternalUrl
import com.fidobridge.client.ui.theme.FidoBridgeTheme

private const val PLAY_SUBSCRIPTIONS_URL =
    "https://play.google.com/store/account/subscriptions"

object SubscribeTags {
    const val LOADING = "subscribe_loading"
    const val RESTORE = "subscribe_restore"
    const val MANAGE = "subscribe_manage"
    const val INVITE = "subscribe_invite"
    const val INVITE_FIELD = "invite_code_field"
    const val INVITE_SUBMIT = "invite_code_submit"
    fun buy(productId: String) = "buy_$productId"
}

@Composable
fun SubscribeScreen(
    viewModel: SubscribeViewModel = hiltViewModel(),
    onEntitled: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val entitlement by viewModel.entitlement.collectAsStateWithLifecycle()
    val inviteDialogVisible by viewModel.inviteDialogVisible.collectAsStateWithLifecycle()
    val inviteCodeInput by viewModel.inviteCodeInput.collectAsStateWithLifecycle()
    val inviteError by viewModel.inviteError.collectAsStateWithLifecycle()
    val inviteSubmitting by viewModel.inviteSubmitting.collectAsStateWithLifecycle()
    val activity = LocalContext.current.findActivity()

    LaunchedEffect(entitlement.isEntitled) {
        if (entitlement.isEntitled) onEntitled()
    }

    SubscribeScreenContent(
        uiState = uiState,
        inviteDialogVisible = inviteDialogVisible,
        inviteCodeInput = inviteCodeInput,
        inviteError = inviteError,
        inviteSubmitting = inviteSubmitting,
        onRetry = viewModel::refresh,
        onBuy = { productId -> activity?.let { viewModel.buy(it, productId) } },
        onRestore = viewModel::restore,
        onShowInvite = viewModel::showInviteDialog,
        onInviteCodeChange = viewModel::onInviteCodeChange,
        onSubmitInvite = viewModel::submitInviteCode,
        onHideInvite = viewModel::hideInviteDialog
    )
}

@Composable
internal fun SubscribeScreenContent(
    uiState: SubscribeUiState,
    inviteDialogVisible: Boolean,
    inviteCodeInput: String,
    inviteError: InviteError?,
    inviteSubmitting: Boolean,
    onRetry: () -> Unit,
    onBuy: (String) -> Unit,
    onRestore: () -> Unit,
    onShowInvite: () -> Unit,
    onInviteCodeChange: (String) -> Unit,
    onSubmitInvite: () -> Unit,
    onHideInvite: () -> Unit
) {
    val context = LocalContext.current

    ScreenScaffold(
        maxWidth = 480.dp,
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = stringResource(R.string.subscribe_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.heading()
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.subscribe_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))

        when {
            uiState.loading -> LoadingStatus()
            uiState.billingUnavailable -> {
                StatusMessage(
                    text = stringResource(R.string.subscribe_billing_unavailable),
                    tone = StatusMessageTone.WARNING,
                    actionLabel = stringResource(R.string.subscribe_retry),
                    onAction = onRetry,
                    testTag = "subscribe_status_billing"
                )
            }
            uiState.error -> {
                StatusMessage(
                    text = stringResource(R.string.subscribe_error),
                    actionLabel = stringResource(R.string.subscribe_retry),
                    onAction = onRetry,
                    testTag = "subscribe_status_error"
                )
            }
            else -> {
                if (uiState.purchaseFailed) {
                    StatusMessage(
                        text = stringResource(R.string.subscribe_purchase_error),
                        testTag = "subscribe_status_purchase"
                    )
                    Spacer(Modifier.height(8.dp))
                }
                uiState.products.forEachIndexed { index, product ->
                    ProductCard(
                        product = product,
                        purchasing = uiState.purchasing,
                        onBuy = { onBuy(product.productId) }
                    )
                    if (index < uiState.products.lastIndex) {
                        Spacer(Modifier.height(8.dp))
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.subscribe_renewal_disclosure),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        TextButton(
            onClick = onRestore,
            modifier = Modifier.testTag(SubscribeTags.RESTORE)
        ) {
            Text(stringResource(R.string.subscribe_restore))
        }
        TextButton(
            onClick = { context.launchExternalUrl(PLAY_SUBSCRIPTIONS_URL) },
            modifier = Modifier.testTag(SubscribeTags.MANAGE)
        ) {
            Text(stringResource(R.string.subscribe_manage))
        }
        if (BuildConfig.PLAY_BILLING_REQUIRED) {
            TextButton(
                onClick = onShowInvite,
                modifier = Modifier.testTag(SubscribeTags.INVITE)
            ) {
                Text(stringResource(R.string.invite_code_button))
            }
        }
    }

    if (inviteDialogVisible) {
        InviteCodeDialog(
            value = inviteCodeInput,
            error = inviteError,
            submitting = inviteSubmitting,
            onValueChange = onInviteCodeChange,
            onSubmit = onSubmitInvite,
            onDismiss = onHideInvite
        )
    }
}

@Composable
private fun LoadingStatus() {
    val label = stringResource(R.string.loading_entitlement)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.testTag(SubscribeTags.LOADING)
    ) {
        CircularProgressIndicator(
            modifier = Modifier
                .size(20.dp)
                .progressSemantics(label)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun InviteCodeDialog(
    value: String,
    error: InviteError?,
    submitting: Boolean,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        if (!submitting) focusRequester.requestFocus()
    }

    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text(stringResource(R.string.invite_code_title)) },
        text = {
            Column {
                Text(stringResource(R.string.invite_code_helper))
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    label = { Text(stringResource(R.string.invite_code_label)) },
                    isError = error != null,
                    supportingText = if (error != null) {
                        { Text(stringResource(inviteErrorString(error))) }
                    } else {
                        null
                    },
                    placeholder = { Text(stringResource(R.string.invite_code_hint)) },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { onSubmit() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .testTag(SubscribeTags.INVITE_FIELD)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onSubmit,
                enabled = !submitting,
                modifier = Modifier.testTag(SubscribeTags.INVITE_SUBMIT)
            ) {
                if (submitting) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(18.dp)
                            .progressSemantics(stringResource(R.string.invite_code_submitting)),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(R.string.invite_code_activate))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !submitting
            ) {
                Text(stringResource(R.string.invite_code_cancel))
            }
        }
    )
}

private fun inviteErrorString(error: InviteError): Int = when (error) {
    InviteError.INVALID -> R.string.invite_code_invalid
    InviteError.FAILED -> R.string.invite_code_failed
}

@Composable
private fun ProductCard(
    product: SubscriptionProduct,
    purchasing: Boolean,
    onBuy: () -> Unit
) {
    val trial = product.freeTrialPeriod == ProductIds.TRIAL_PERIOD
    val priceText = if (trial) {
        stringResource(R.string.subscribe_trial_copy, product.formattedPrice)
    } else {
        product.formattedPrice
    }
    val actionLabel = stringResource(
        if (trial) R.string.subscribe_buy_trial_button else R.string.subscribe_buy_button
    )
    val actionDescription = stringResource(
        if (trial) R.string.subscribe_buy_trial_desc else R.string.subscribe_buy_desc,
        product.title,
        priceText
    )

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(product.title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                text = priceText,
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onBuy,
                enabled = !purchasing,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = actionDescription }
                    .testTag(SubscribeTags.buy(product.productId))
            ) {
                if (purchasing) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(18.dp)
                            .progressSemantics(stringResource(R.string.subscribe_purchasing)),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(actionLabel)
            }
        }
    }
}

private fun previewProduct(
    id: String = "gatebridge_individual_monthly",
    title: String = "Gatebridge Individual Monthly",
    price: String = "$4.99",
    trial: Boolean = true
) = SubscriptionProduct(
    productId = id,
    title = title,
    formattedPrice = price,
    billingPeriod = "P1M",
    freeTrialPeriod = if (trial) ProductIds.TRIAL_PERIOD else null,
    offerToken = "preview"
)

private val noAction: () -> Unit = {}
private val noBuy: (String) -> Unit = {}

@Preview(name = "Loading", showBackground = true, widthDp = 411, heightDp = 800, locale = "en")
@Composable
private fun SubscribePreviewLoading() {
    FidoBridgeTheme {
        SubscribeScreenContent(
            uiState = SubscribeUiState(loading = true),
            inviteDialogVisible = false,
            inviteCodeInput = "",
            inviteError = null,
            inviteSubmitting = false,
            onRetry = noAction,
            onBuy = noBuy,
            onRestore = noAction,
            onShowInvite = noAction,
            onInviteCodeChange = noBuy,
            onSubmitInvite = noAction,
            onHideInvite = noAction
        )
    }
}

@Preview(name = "Products", showBackground = true, widthDp = 411, heightDp = 800, locale = "en")
@Composable
private fun SubscribePreviewProducts() {
    FidoBridgeTheme {
        SubscribeScreenContent(
            uiState = SubscribeUiState(
                loading = false,
                products = listOf(
                    previewProduct(
                        trial = true,
                        title = "Gatebridge Individual Monthly",
                        price = "$4.99"
                    ),
                    previewProduct(
                        id = "gatebridge_individual_yearly",
                        title = "Gatebridge Individual Yearly",
                        price = "$49.99",
                        trial = false
                    )
                )
            ),
            inviteDialogVisible = false,
            inviteCodeInput = "",
            inviteError = null,
            inviteSubmitting = false,
            onRetry = noAction,
            onBuy = noBuy,
            onRestore = noAction,
            onShowInvite = noAction,
            onInviteCodeChange = noBuy,
            onSubmitInvite = noAction,
            onHideInvite = noAction
        )
    }
}

@Preview(name = "Billing unavailable", showBackground = true, widthDp = 411, heightDp = 800, locale = "en")
@Composable
private fun SubscribePreviewBillingUnavailable() {
    FidoBridgeTheme {
        SubscribeScreenContent(
            uiState = SubscribeUiState(loading = false, billingUnavailable = true),
            inviteDialogVisible = false,
            inviteCodeInput = "",
            inviteError = null,
            inviteSubmitting = false,
            onRetry = noAction,
            onBuy = noBuy,
            onRestore = noAction,
            onShowInvite = noAction,
            onInviteCodeChange = noBuy,
            onSubmitInvite = noAction,
            onHideInvite = noAction
        )
    }
}

@Preview(name = "Invite dialog", showBackground = true, widthDp = 411, heightDp = 800, locale = "en")
@Composable
private fun SubscribePreviewInviteDialog() {
    FidoBridgeTheme {
        SubscribeScreenContent(
            uiState = SubscribeUiState(
                loading = false,
                products = listOf(previewProduct())
            ),
            inviteDialogVisible = true,
            inviteCodeInput = "",
            inviteError = InviteError.INVALID,
            inviteSubmitting = false,
            onRetry = noAction,
            onBuy = noBuy,
            onRestore = noAction,
            onShowInvite = noAction,
            onInviteCodeChange = noBuy,
            onSubmitInvite = noAction,
            onHideInvite = noAction
        )
    }
}