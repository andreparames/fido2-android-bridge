package com.fidobridge.client.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Semantic status colors used across the UI for connection state and request
 * outcomes. Always paired with an icon/label — never conveyed by color alone.
 */
data class SemanticColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val neutral: Color,
    val onNeutral: Color,
    val neutralContainer: Color,
    val danger: Color,
    val onDanger: Color,
    val dangerContainer: Color
)

private val LightSemanticColors = SemanticColors(
    success = Color(0xFF2E7D32),
    onSuccess = Color(0xFFFFFFFF),
    successContainer = Color(0xFFB7EFB0),
    warning = Color(0xFFB26A00),
    onWarning = Color(0xFFFFFFFF),
    warningContainer = Color(0xFFFFDDB4),
    neutral = Color(0xFF616161),
    onNeutral = Color(0xFFFFFFFF),
    neutralContainer = Color(0xFFE0E0E0),
    danger = Color(0xFFB3261E),
    onDanger = Color(0xFFFFFFFF),
    dangerContainer = Color(0xFFF9DEDC)
)

private val DarkSemanticColors = SemanticColors(
    success = Color(0xFF81C784),
    onSuccess = Color(0xFF00391A),
    successContainer = Color(0xFF005C2E),
    warning = Color(0xFFFFB95C),
    onWarning = Color(0xFF4A2C00),
    warningContainer = Color(0xFF6A4300),
    neutral = Color(0xFFBDBDBD),
    onNeutral = Color(0xFF212121),
    neutralContainer = Color(0xFF424242),
    danger = Color(0xFFF2B8B5),
    onDanger = Color(0xFF601410),
    dangerContainer = Color(0xFF8C1D18)
)

@Composable
fun semanticColors(): SemanticColors =
    if (isSystemInDarkTheme()) DarkSemanticColors else LightSemanticColors