package com.fidobridge.client.ui.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
            .padding(16.dp),
        verticalArrangement = Arrangement.Center
    ) {
        when (state) {
            is PairingUiState.Scanning -> ScanningContent(
                viewModel = viewModel
            )
            is PairingUiState.Error -> {
                ScanningContent(viewModel = viewModel)
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = (state as PairingUiState.Error).message,
                    color = MaterialTheme.colorScheme.error
                )
            }
            is PairingUiState.Paired -> Unit
        }
    }
}

@Composable
private fun ColumnScope.ScanningContent(viewModel: PairingViewModel) {
    var manualUri by remember { mutableStateOf("") }

    QrScanner(
        onResult = viewModel::onQrResult,
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
    )

    Spacer(modifier = Modifier.height(16.dp))

    OutlinedTextField(
        value = manualUri,
        onValueChange = { manualUri = it },
        label = { Text("Or paste pairing URI") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )

    Spacer(modifier = Modifier.height(8.dp))

    Button(
        onClick = { viewModel.onManualSubmit(manualUri) },
        modifier = Modifier.fillMaxWidth(),
        enabled = manualUri.isNotBlank()
    ) {
        Text("Pair")
    }
}
