package com.fidobridge.client.ui

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.fidobridge.client.networking.FidoBridgeService
import com.fidobridge.client.ui.home.HomeScreen
import com.fidobridge.client.ui.pairing.PairingScreen
import com.fidobridge.client.ui.theme.FidoBridgeTheme

object Routes {
    const val HOME = "home"
    const val PAIRING = "pairing"
}

@Composable
fun FidoBridgeApp() {
    FidoBridgeTheme {
        val appViewModel: AppViewModel = hiltViewModel()
        val navController = rememberNavController()
        val startDestination = if (appViewModel.isPaired) Routes.HOME else Routes.PAIRING

        NavHost(navController = navController, startDestination = startDestination) {
            composable(Routes.PAIRING) {
                val context = LocalContext.current
                PairingScreen(
                    onPaired = {
                        context.startForegroundService(Intent(context, FidoBridgeService::class.java))
                        navController.navigate(Routes.HOME) {
                            popUpTo(Routes.PAIRING) { inclusive = true }
                        }
                    }
                )
            }
            composable(Routes.HOME) {
                val context = LocalContext.current
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
}
