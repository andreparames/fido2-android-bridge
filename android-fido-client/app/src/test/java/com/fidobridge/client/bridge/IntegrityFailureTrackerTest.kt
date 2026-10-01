package com.fidobridge.client.bridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntegrityFailureTrackerTest {

    @Test
    fun `alerts only after threshold failures within the window`() {
        var t = 0L
        val tracker = IntegrityFailureTracker(threshold = 3, windowMs = 1_000, now = { t })

        assertFalse(tracker.record())
        assertFalse(tracker.record())
        assertTrue(tracker.record())
    }

    @Test
    fun `failures outside the window are pruned`() {
        var t = 0L
        val tracker = IntegrityFailureTracker(threshold = 3, windowMs = 1_000, now = { t })

        tracker.record()
        tracker.record()
        t = 2_000

        assertFalse(tracker.record())
    }

    @Test
    fun `reset clears recorded failures`() {
        var t = 0L
        val tracker = IntegrityFailureTracker(threshold = 3, windowMs = 1_000, now = { t })
        tracker.record()
        tracker.record()

        tracker.reset()

        assertFalse(tracker.record())
    }
}