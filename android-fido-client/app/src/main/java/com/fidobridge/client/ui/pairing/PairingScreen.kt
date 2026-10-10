package com.fidobridge.client.ui.pairing

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NoPhotography
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fidobridge.client.R
import com.fidobridge.client.pairing.PairingUiState
import com.fidobridge.client.pairing.PairingViewModel
import com.fidobridge.client.ui.components.ScreenScaffold
import com.fidobridge.client.ui.components.StatusMessage
import com.fidobridge.client.ui.components.heading
import com.fidobridge.client.ui.theme.FidoBridgeTheme
import com.fidobridge.client.util.findActivity

object PairingTags {
    const val SCAN_AREA = "pairing_scan_area"
    const val RATIONALE = "pairing_camera_rationale"
    const val DENIED = "pairing_camera_denied"
    const val ALLOW_CAMERA = "pairing_allow_camera"
    const val MANUAL_FIELD = "pairing_manual_field"
    const val PAIR_BUTTON = "pairing_pair_button"
    const val ERROR = "pairing_error"
}

/**
 * Shows QR and manual URI pairing controls within the system bars, including pairing errors.
 * Calls [onPaired] when the observed state is [PairingUiState.Paired].
 */
@Composable
fun PairingScreen(
    onPaired: () -> Unit,
    viewModel: PairingViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state) {
        if (state is PairingUiState.Paired) {
            onPaired()
        }
    }

    PairingContent(
        state = state,
        validateUri = viewModel::validateUri,
        onQrResult = viewModel::onQrResult,
        onManualChange = viewModel::clearError,
        onManualSubmit = viewModel::onManualSubmit
    )
}

@Composable
internal fun PairingContent(
    state: PairingUiState,
    validateUri: (String) -> Boolean,
    onQrResult: (String) -> Unit,
    onManualChange: () -> Unit,
    onManualSubmit: (String) -> Unit
) {
    var manualUri by remember { mutableStateOf("") }
    val pairing = state is PairingUiState.Pairing

    ScreenScaffold(horizontalAlignment = Alignment.Start) {
        Text(
            text = stringResource(R.string.pairing_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.heading()
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.pairing_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(16.dp))

        CameraPermissionSection(
            onQrResult = onQrResult,
            scanLabel = stringResource(
                if (pairing) R.string.pairing_pairing else R.string.pairing_scanning_label
            ),
            scanningEnabled = !pairing,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
        )

        Spacer(Modifier.height(16.dp))

        ManualPairingField(
            value = manualUri,
            isInvalid = manualUri.isNotBlank() && !validateUri(manualUri.trim()),
            pairing = pairing,
            onValueChange = {
                manualUri = it
                onManualChange()
            },
            onSubmit = { onManualSubmit(manualUri.trim()) }
        )

        if (state is PairingUiState.Error) {
            Spacer(Modifier.height(12.dp))
            StatusMessage(text = state.message, testTag = PairingTags.ERROR)
        }
    }
}

@Composable
private fun CameraPermissionSection(
    onQrResult: (String) -> Unit,
    scanLabel: String,
    scanningEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var requestedBefore by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        requestedBefore = true
    }
    val canRequestAgain =
        context.findActivity()?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) == true

    when {
        hasPermission -> QrScanner(
            onResult = onQrResult,
            modifier = modifier.testTag(PairingTags.SCAN_AREA),
            scanLabel = scanLabel,
            enabled = scanningEnabled
        )

        !requestedBefore -> CameraRationale(
            modifier = modifier,
            onAllowCamera = { permissionLauncher.launch(Manifest.permission.CAMERA) }
        )

        canRequestAgain -> CameraDenied(
            modifier = modifier,
            onRequestAgain = { permissionLauncher.launch(Manifest.permission.CAMERA) }
        )

        else -> CameraPermanentlyDenied(
            modifier = modifier,
            onOpenSettings = { openAppSettings(context) }
        )
    }
}

@Composable
private fun CameraRationale(modifier: Modifier, onAllowCamera: () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(PairingTags.RATIONALE),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Outlined.QrCodeScanner,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.pairing_allow_camera_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.pairing_allow_camera_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onAllowCamera,
            modifier = Modifier.testTag(PairingTags.ALLOW_CAMERA)
        ) {
            Text(stringResource(R.string.pairing_allow_camera))
        }
    }
}

@Composable
private fun CameraDenied(modifier: Modifier, onRequestAgain: () -> Unit) {
    CameraMessage(
        modifier = modifier,
        title = stringResource(R.string.pairing_camera_denied_title),
        body = stringResource(R.string.pairing_camera_denied_body),
        actionLabel = stringResource(R.string.pairing_request_again),
        onAction = onRequestAgain,
        tag = PairingTags.DENIED
    )
}

@Composable
private fun CameraPermanentlyDenied(modifier: Modifier, onOpenSettings: () -> Unit) {
    CameraMessage(
        modifier = modifier,
        title = stringResource(R.string.pairing_camera_denied_title),
        body = stringResource(R.string.pairing_camera_denied_body),
        actionLabel = stringResource(R.string.pairing_open_settings),
        onAction = onOpenSettings,
        tag = PairingTags.DENIED
    )
}

@Composable
private fun CameraMessage(
    modifier: Modifier,
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
    tag: String
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(tag),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Outlined.NoPhotography,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(4.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onAction) {
            Text(actionLabel)
        }
    }
}

private fun openAppSettings(context: Context) {
    try {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")
            )
        )
    } catch (_: ActivityNotFoundException) {
        // Some launchers have no app-settings activity; the manual URI field is the fallback.
    }
}

@Composable
private fun ManualPairingField(
    value: String,
    isInvalid: Boolean,
    pairing: Boolean,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(R.string.pairing_field_label)) },
        modifier = Modifier
            .fillMaxWidth()
            .testTag(PairingTags.MANUAL_FIELD),
        singleLine = true,
        isError = isInvalid,
        supportingText = if (isInvalid) {
            { Text(stringResource(R.string.pairing_invalid_uri)) }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Done
        ),
        keyboardActions = KeyboardActions(onDone = { onSubmit() })
    )

    Spacer(Modifier.height(8.dp))

    Button(
        onClick = onSubmit,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(PairingTags.PAIR_BUTTON),
        enabled = value.isNotBlank() && !pairing
    ) {
        Text(
            if (pairing) {
                stringResource(R.string.pairing_pairing)
            } else {
                stringResource(R.string.pairing_pair)
            }
        )
    }
}

private val noPairAction: (String) -> Unit = {}
private val noChange: () -> Unit = {}

@Preview(name = "Pairing intro", showBackground = true, widthDp = 411, heightDp = 800, locale = "en")
@Composable
private fun PairingPreviewIntro() {
    FidoBridgeTheme {
        PairingContent(
            state = PairingUiState.Scanning,
            validateUri = { false },
            onQrResult = noPairAction,
            onManualChange = noChange,
            onManualSubmit = noPairAction
        )
    }
}

@Preview(name = "Pairing error", showBackground = true, widthDp = 411, heightDp = 800, locale = "en")
@Composable
private fun PairingPreviewError() {
    FidoBridgeTheme {
        PairingContent(
            state = PairingUiState.Error("Invalid pairing URI"),
            validateUri = { it.startsWith("fidobridge://pair") },
            onQrResult = noPairAction,
            onManualChange = noChange,
            onManualSubmit = noPairAction
        )
    }
}