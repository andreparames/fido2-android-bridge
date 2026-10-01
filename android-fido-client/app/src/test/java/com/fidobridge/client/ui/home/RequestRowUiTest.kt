package com.fidobridge.client.ui.home

import com.fidobridge.client.ui.model.RequestOutcome
import com.fidobridge.client.ui.model.RequestType
import org.junit.Assert.assertEquals
import org.junit.Test

class RequestRowUiTest {

    @Test
    fun `request type labels`() {
        assertEquals("Sign-in", requestTypeLabel(RequestType.SIGN_IN))
        assertEquals("Register", requestTypeLabel(RequestType.REGISTER))
        assertEquals("Browser check", requestTypeLabel(RequestType.BROWSER_CHECK))
    }

    @Test
    fun `outcome labels`() {
        assertEquals("Accepted", requestOutcomeLabel(RequestOutcome.ACCEPTED))
        assertEquals("Rejected", requestOutcomeLabel(RequestOutcome.REJECTED))
        assertEquals("Pending", requestOutcomeLabel(RequestOutcome.PENDING))
    }

    @Test
    fun `relative time formats bounds and buckets`() {
        val now = 1_000_000L

        assertEquals("just now", formatRelativeTime(now - 5_000, now))
        assertEquals("just now", formatRelativeTime(now - 59_999, now))
        assertEquals("1 min ago", formatRelativeTime(now - 60_000, now))
        assertEquals("59 min ago", formatRelativeTime(now - 59 * 60_000, now))
        assertEquals("1 h ago", formatRelativeTime(now - 60 * 60_000, now))
        assertEquals("23 h ago", formatRelativeTime(now - 23 * 3_600_000, now))
        assertEquals("1 d ago", formatRelativeTime(now - 24 * 3_600_000, now))
        assertEquals("3 d ago", formatRelativeTime(now - 3 * 24 * 3_600_000, now))
    }
}