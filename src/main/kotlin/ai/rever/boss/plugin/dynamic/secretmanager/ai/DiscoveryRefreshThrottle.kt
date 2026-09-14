package ai.rever.boss.plugin.dynamic.secretmanager.ai

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Serialize discovery attempts and space them even when invalidation prevents caching. */
internal class DiscoveryRefreshThrottle(
    private val intervalMs: Long,
    private val nowNanos: () -> Long,
) {
    private val inFlight = AtomicBoolean(false)
    private val hasFinished = AtomicBoolean(false)
    private val finishedAt = AtomicLong(0)

    fun begin(): Boolean {
        if (!inFlight.compareAndSet(false, true)) return false
        if (hasFinished.get() && nowNanos() - finishedAt.get() < intervalMs * 1_000_000L) {
            inFlight.set(false)
            return false
        }
        return true
    }

    fun finish() {
        finishedAt.set(nowNanos())
        hasFinished.set(true)
        inFlight.set(false)
    }
}
