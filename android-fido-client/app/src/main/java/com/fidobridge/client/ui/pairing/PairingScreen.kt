package com.fidobridge.client.ui.pairing

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.NoPhotography
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fidobridge.client.pairing.PairingUiState
import com.fidobridge.client.pairing.PairingViewModel

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "Connect your phone",
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Scan the pairing code from your computer to connect.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(16.dp))

        CameraPermissionSection(
            onQrResult = viewModel::onQrResult,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )

        Spacer(Modifier.height(16.dp))

        ManualPairingField(state = state, viewModel = viewModel)

        if (state is PairingUiState.Error) {
            Spacer(Modifier.height(12.dp))
            ErrorBanner((state as PairingUiState.Error).message)
        }
    }
}

@Composable
private fun CameraPermissionSection(
    onQrResult: (String) -> Unit,
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

    when {
        hasPermission -> QrScanner(onResult = onQrResult, modifier = modifier)

        !requestedBefore -> CameraRationale(
            modifier = modifier,
            onAllowCamera = { permissionLauncher.launch(Manifest.permission.CAMERA) }
        )

        else -> CameraDenied(
            modifier = modifier,
            onRequestAgain = { permissionLauncher.launch(Manifest.permission.CAMERA) }
        )
    }
}

@Composable
private fun CameraRationale(modifier: Modifier, onAllowCamera: () -> Unit) {
    Column(
        modifier = modifier.fillMaxWidth(),
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
            "Allow camera access to scan the pairing code",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Or paste the pairing URI below.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onAllowCamera) {
            Text("Allow camera")
        }
    }
}

@Composable
private fun CameraDenied(modifier: Modifier, onRequestAgain: () -> Unit) {
    Column(
        modifier = modifier.fillMaxWidth(),
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
            "Camera access is needed to scan the pairing code",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "You can still pair by pasting the URI below.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRequestAgain) {
            Text("Request again")
        }
    }
}

@Composable
private fun ManualPairingField(state: PairingUiState, viewModel: PairingViewModel) {
    var manualUri by remember { mutableStateOf("") }
    val isInvalid = manualUri.startsWith("fidobridge://pair") && !viewModel.validateUri(manualUri)

    OutlinedTextField(
        value = manualUri,
        onValueChange = {
            manualUri = it
            viewModel.clearError()
        },
        label = { Text("Or paste pairing URI") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        isError = isInvalid,
        supportingText = if (isInvalid) {
            { Text("Invalid pairing URI") }
        } else {
            null
        }
    )

    Spacer(Modifier.height(8.dp))

    Button(
        onClick = { viewModel.onManualSubmit(manualUri) },
        modifier = Modifier.fillMaxWidth(),
        enabled = manualUri.isNotBlank() && state != PairingUiState.Pairing
    ) {
        Text(if (state is PairingUiState.Pairing) "Pairing…" else "Pair")
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}