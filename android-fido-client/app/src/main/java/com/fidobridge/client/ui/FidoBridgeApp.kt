package com.fidobridge.client.ui

import android.content.Intent
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.fidobridge.client.networking.FidoBridgeService
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
                HomeScreen()
            }
        }
    }
}

@Composable
private fun HomeScreen() {
    Scaffold { innerPadding ->
        Text(
            text = "FIDO Bridge",
            modifier = Modifier.padding(innerPadding)
        )
    }
}
