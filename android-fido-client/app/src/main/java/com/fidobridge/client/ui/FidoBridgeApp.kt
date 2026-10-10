package com.fidobridge.client.ui

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.fidobridge.client.BuildConfig
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
        val snackbarHostState = remember { SnackbarHostState() }

        LaunchedEffect(Unit) { appViewModel.refreshEntitlement() }

        // Transient operation results (export/reset) surface as a snackbar, not a modal.
        LaunchedEffect(userMessage) {
            userMessage?.let { message ->
                snackbarHostState.showSnackbar(message = message, withDismissAction = true)
                appViewModel.dismissUserMessage(message)
            }
        }

        // The entitlement gate decides which surface is shown; the NavHost below
        // keeps a static start destination so navigation is deterministic.
        Box(modifier = Modifier.fillMaxSize()) {
            when (surfaceFor(BuildConfig.PLAY_BILLING_REQUIRED, entitlement.status)) {
                AppSurface.LOADING -> LoadingScreen()
                AppSurface.MAIN -> MainNavHost(appViewModel)
                AppSurface.SUBSCRIBE -> SubscribeScreen(onEntitled = { appViewModel.refreshEntitlement() })
            }
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
            )
        }
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