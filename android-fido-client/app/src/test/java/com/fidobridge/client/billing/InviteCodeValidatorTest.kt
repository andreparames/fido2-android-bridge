package com.fidobridge.client.billing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InviteCodeValidatorTest {

    @Test
    fun `accepts exactly eight digits`() {
        assertTrue(InviteCodeValidator.isValid("00000000"))
        assertTrue(InviteCodeValidator.isValid("12345678"))
        assertTrue(InviteCodeValidator.isValid("99999999"))
    }

    @Test
    fun `rejects wrong length or non-digits`() {
        assertFalse(InviteCodeValidator.isValid(""))
        assertFalse(InviteCodeValidator.isValid("1234567"))
        assertFalse(InviteCodeValidator.isValid("123456789"))
        assertFalse(InviteCodeValidator.isValid("1234567a"))
        assertFalse(InviteCodeValidator.isValid(" 12345678"))
        assertFalse(InviteCodeValidator.isValid("12345678 "))
    }
}
