package com.fidobridge.client.billing

/** Validates invite codes: exactly 8 decimal digits, no trim, no padding. */
object InviteCodeValidator {
    private val PATTERN = Regex("^[0-9]{8}$")

    fun isValid(code: String): Boolean = PATTERN.matches(code)
}
