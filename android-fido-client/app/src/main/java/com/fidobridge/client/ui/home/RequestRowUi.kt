package com.fidobridge.client.ui.home

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Help
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.ui.graphics.vector.ImageVector
import com.fidobridge.client.ui.model.RequestOutcome
import com.fidobridge.client.ui.model.RequestType

fun requestTypeLabel(type: RequestType): String = when (type) {
    RequestType.SIGN_IN -> "Sign-in"
    RequestType.REGISTER -> "Register"
    RequestType.BROWSER_CHECK -> "Browser check"
}

fun requestTypeIcon(type: RequestType): ImageVector = when (type) {
    RequestType.SIGN_IN -> Icons.AutoMirrored.Outlined.Login
    RequestType.REGISTER -> Icons.Outlined.PersonAdd
    RequestType.BROWSER_CHECK -> Icons.AutoMirrored.Outlined.Help
}

fun requestOutcomeLabel(outcome: RequestOutcome): String = when (outcome) {
    RequestOutcome.ACCEPTED -> "Accepted"
    RequestOutcome.REJECTED -> "Rejected"
    RequestOutcome.PENDING -> "Pending"
}

fun requestOutcomeIcon(outcome: RequestOutcome): ImageVector = when (outcome) {
    RequestOutcome.ACCEPTED -> Icons.Outlined.Check
    RequestOutcome.REJECTED -> Icons.Outlined.Close
    RequestOutcome.PENDING -> Icons.Outlined.HourglassEmpty
}

fun formatRelativeTime(timestamp: Long, now: Long = System.currentTimeMillis()): String {
    val diff = now - timestamp
    if (diff < 60_000) return "just now"
    val minutes = diff / 60_000
    if (minutes < 60) return "$minutes min ago"
    val hours = minutes / 60
    if (hours < 24) return "$hours h ago"
    val days = hours / 24
    return "$days d ago"
}