package com.fidobridge.client.bridge

/**
 * Counts integrity failures within a sliding time window.
 *
 * A single blip is tolerated (weak connections cause transient corruption), so
 * [record] only reports once [threshold] failures have accumulated inside
 * [windowMs]. This distinguishes a flaky link from a sustained pattern.
 */
class IntegrityFailureTracker(
    private val threshold: Int = DEFAULT_THRESHOLD,
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val now: () -> Long = System::currentTimeMillis
) {

    private val failures = ArrayDeque<Long>()

    /** Records a failure and returns true when the windowed threshold is crossed. */
    @Synchronized
    fun record(): Boolean {
        val t = now()
        prune(t)
        failures.addLast(t)
        return failures.size >= threshold
    }

    @Synchronized
    fun reset() {
        failures.clear()
    }

    private fun prune(now: Long) {
        while (failures.isNotEmpty() && now - failures.first() > windowMs) {
            failures.removeFirst()
        }
    }

    companion object {
        const val DEFAULT_THRESHOLD = 3
        const val DEFAULT_WINDOW_MS = 60_000L
    }
}