package com.example.stickerpackbuilder.reverse

/** Read readiness quickly; throttle repeated input, not observation of a new screen. */
internal class ImportTimingPolicy {
    private val inputs = mutableMapOf<String,Long>()
    fun allow(action: String, now: Long, retryMs: Long = 650): Boolean {
        if (now - (inputs[action] ?: (Long.MIN_VALUE / 2)) < retryMs) return false
        inputs[action] = now; return true
    }
    fun reset() { inputs.clear() }
    companion object {
        const val OBSERVE_MS = 60L
        const val POLL_MS = 120L
        fun fileReady(now: Long, stagedAt: Long, animated: Boolean) =
            stagedAt > 0 && now - stagedAt >= if (animated) 450 else 160
    }
}
