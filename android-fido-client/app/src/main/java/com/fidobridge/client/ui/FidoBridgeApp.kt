package com.fidobridge.client.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.fidobridge.client.R
import com.fidobridge.client.billing.EntitlementStatus
import com.fidobridge.client.networking.FidoBridgeService
import com.fidobridge.client.ui.home.HomeScreen
import com.fidobridge.client.ui.pairing.PairingScreen
import com.fidobridge.client.ui.subscribe.SubscribeScreen
import com.fidobridge.client.ui.theme.FidoBridgeTheme

object Routes {
    const val HOME = "home"
    const val PAIRING = "pairing"
}

@Composable
fun FidoBridgeApp() {
    FidoBridgeTheme {
        val appViewModel: AppViewModel = hiltViewModel()
        val userMessage by appViewModel.userMessage.collectAsStateWithLifecycle()
        val entitlement by appViewModel.entitlement.collectAsStateWithLifecycle()

        LaunchedEffect(Unit) { appViewModel.refreshEntitlement() }

        // The entitlement gate decides which surface is shown; the NavHost below
        // keeps a static start destination so navigation is deterministic.
        when (entitlement.status) {
            EntitlementStatus.LOADING -> LoadingScreen()
            EntitlementStatus.ENTITLED -> MainNavHost(appViewModel)
            else -> SubscribeScreen(onEntitled = { appViewModel.refreshEntitlement() })
        }

        userMessage?.let { message ->
            AlertDialog(
                onDismissRequest = { appViewModel.dismissUserMessage() },
                title = { Text(stringResource(R.string.app_name)) },
                text = { Text(message) },
                confirmButton = {
                    TextButton(onClick = { appViewModel.dismissUserMessage() }) {
                        Text("OK")
                    }
                }
            )
        }
    }
}

@Composable
private fun LoadingScreen() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.loading_entitlement),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MainNavHost(appViewModel: AppViewModel) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val startDestination = if (appViewModel.isPaired) Routes.HOME else Routes.PAIRING

    NavHost(navController = navController, startDestination = startDestination) {
        composable(Routes.PAIRING) {
            PairingScreen(
                onPaired = {
                    if (appViewModel.entitledForRelay()) {
                        context.startForegroundService(
                            Intent(context, FidoBridgeService::class.java)
                        )
                    }
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.PAIRING) { inclusive = true }
                    }
                }
            )
        }
        composable(Routes.HOME) {
            HomeScreen(
                viewModel = appViewModel,
                onResetConfirmed = {
                    context.stopService(Intent(context, FidoBridgeService::class.java))
                    navController.navigate(Routes.PAIRING) {
                        popUpTo(Routes.HOME) { inclusive = true }
                    }
                }
            )
        }
    }
}