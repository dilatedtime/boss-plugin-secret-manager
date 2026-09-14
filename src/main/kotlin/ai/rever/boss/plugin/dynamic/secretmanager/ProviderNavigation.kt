package ai.rever.boss.plugin.dynamic.secretmanager

import ai.rever.boss.plugin.api.CustomPluginEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Retained briefly so a management click can reach a panel constructed just afterward. */
internal class ProviderNavigation(
    private val monotonicMillis: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
    private val requestTtlMs: Long = DEFAULT_REQUEST_TTL_MS,
) {
    companion object {
        const val OPEN_AI_EVENT = "secret-manager.open-ai"
        const val WINDOW_ID_KEY = "windowId"
        const val DEFAULT_REQUEST_TTL_MS = 30_000L
        private const val NANOS_PER_MILLI = 1_000_000L
    }

    private var nextRequest = 0L
    private val requests = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val expiresAt = mutableMapOf<String, Long>()
    val state: StateFlow<Map<String, Long>> get() = requests

    @Synchronized
    fun accept(event: CustomPluginEvent) {
        if (event.eventName != OPEN_AI_EVENT) return
        val window = (event.payload[WINDOW_ID_KEY] as? String)?.takeIf { it.isNotBlank() } ?: return
        pruneExpired()
        val request = ++nextRequest
        expiresAt[window] = monotonicMillis() + requestTtlMs
        requests.update { it + (window to request) }
    }

    /** A retained request is owned by the window and consumed even when AI is unavailable. */
    @Synchronized
    fun consume(window: String?, request: Long, aiAvailable: Boolean = true): Boolean {
        if (window == null || requests.value[window] != request) return false
        val isFresh = monotonicMillis() <= (expiresAt.remove(window) ?: Long.MIN_VALUE)
        requests.update { it - window }
        return aiAvailable && isFresh
    }

    private fun pruneExpired() {
        val now = monotonicMillis()
        val expired = expiresAt.filterValues { it < now }.keys
        if (expired.isEmpty()) return
        expired.forEach(expiresAt::remove)
        requests.update { it - expired }
    }

    suspend fun collect(events: () -> Flow<CustomPluginEvent>, onFailure: (Throwable) -> Unit) {
        try {
            events().collect { accept(it) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            // An optional navigation channel must not cancel the shared plugin scope.
            onFailure(failure)
        }
    }
}
