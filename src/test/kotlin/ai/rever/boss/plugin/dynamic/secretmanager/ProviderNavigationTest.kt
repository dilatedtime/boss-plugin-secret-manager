package ai.rever.boss.plugin.dynamic.secretmanager

import ai.rever.boss.plugin.api.CustomPluginEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ProviderNavigationTest {
    @Test fun `requests survive panel creation and are scoped to the requested window`() {
        val navigation = ProviderNavigation()
        navigation.accept(CustomPluginEvent("fluck", "secret-manager.open-ai", mapOf("windowId" to "one")))
        assertEquals(mapOf("one" to 1L), navigation.state.value)
        navigation.accept(CustomPluginEvent("fluck", "secret-manager.open-ai", mapOf("windowId" to "two")))
        navigation.accept(CustomPluginEvent("fluck", "secret-manager.open-ai", mapOf("windowId" to "one")))
        assertEquals(mapOf("one" to 3L, "two" to 2L), navigation.state.value)
    }

    @Test fun `unrelated and untargeted events do not navigate`() {
        val navigation = ProviderNavigation()
        navigation.accept(CustomPluginEvent("fluck", "other", mapOf("windowId" to "one")))
        navigation.accept(CustomPluginEvent("fluck", "secret-manager.open-ai"))
        navigation.accept(CustomPluginEvent("fluck", "secret-manager.open-ai", mapOf("windowId" to " ")))
        navigation.accept(CustomPluginEvent("fluck", "secret-manager.open-ai", mapOf("windowId" to 42)))
        assertTrue(navigation.state.value.isEmpty())
    }

    @Test fun `a recreated or second panel cannot consume an already handled request`() {
        val navigation = ProviderNavigation()
        navigation.accept(request("one"))
        val token = navigation.state.value.getValue("one")
        assertFalse(navigation.consume("two", token))
        assertFalse(navigation.consume(null, token))
        assertTrue(navigation.consume("one", token))
        assertFalse(navigation.consume("one", token))
        assertTrue(navigation.state.value.isEmpty())

        navigation.accept(request("one"))
        assertFalse(navigation.consume("one", token))
        assertTrue(navigation.consume("one", navigation.state.value.getValue("one")))
    }

    @Test fun `consuming a request leaves other windows pending`() {
        val navigation = ProviderNavigation()
        navigation.accept(request("one"))
        navigation.accept(request("two"))
        assertTrue(navigation.consume("one", navigation.state.value.getValue("one")))
        assertEquals(setOf("two"), navigation.state.value.keys)
    }

    @Test fun `an unavailable AI section cannot be selected and drops the request`() {
        val navigation = ProviderNavigation()
        navigation.accept(request("one"))
        val token = navigation.state.value.getValue("one")
        assertFalse(navigation.consume("one", token, aiAvailable = false))
        assertTrue(navigation.state.value.isEmpty())
        assertFalse(navigation.consume("one", token, aiAvailable = true))
    }

    @Test fun `an expired request cannot surprise a panel opened later`() {
        var now = 1_000L
        val navigation = ProviderNavigation(monotonicMillis = { now }, requestTtlMs = 100)
        navigation.accept(request("one"))
        val token = navigation.state.value.getValue("one")
        now += 101
        assertFalse(navigation.consume("one", token))
        assertTrue(navigation.state.value.isEmpty())
    }

    @Test fun `accepting a request prunes expired windows`() {
        var now = 1_000L
        val navigation = ProviderNavigation(monotonicMillis = { now }, requestTtlMs = 100)
        navigation.accept(request("one"))
        now += 101
        navigation.accept(request("two"))
        assertEquals(setOf("two"), navigation.state.value.keys)
    }

    @Test fun `event flow failure is reported without cancelling shared plugin scope`() = runBlocking {
        val parent = Job()
        val scope = CoroutineScope(coroutineContext + parent)
        val failure = IllegalStateException("event bus unavailable")
        var reported: Throwable? = null
        val navigation = ProviderNavigation()
        scope.launch {
            navigation.collect({ flow { emit(request("one")); throw failure } }) { reported = it }
        }.join()
        assertSame(failure, reported)
        assertTrue(parent.isActive)
        assertTrue(navigation.state.value.containsKey("one"))
        parent.cancel()
    }

    @Test fun `event subscription failure is contained too`() = runBlocking {
        val failure = IllegalStateException("cannot subscribe")
        var reported: Throwable? = null
        ProviderNavigation().collect({ throw failure }) { reported = it }
        assertSame(failure, reported)
    }

    @Test fun `linkage failure cannot cancel the shared plugin scope`() = runBlocking {
        val parent = Job()
        val scope = CoroutineScope(coroutineContext + parent)
        val failure = NoSuchMethodError("older event bus")
        var reported: Throwable? = null
        scope.launch {
            ProviderNavigation().collect({ throw failure }) { reported = it }
        }.join()
        assertSame(failure, reported)
        assertTrue(parent.isActive)
        parent.cancel()
    }

    @Test fun `navigation collection preserves cancellation`() = runBlocking {
        val cancellation = CancellationException("plugin unloaded")
        var observed: CancellationException? = null
        try {
            ProviderNavigation().collect({ flow { throw cancellation } }) {
                error("Cancellation must not be reported as an event bus failure")
            }
        } catch (failure: CancellationException) {
            observed = failure
        }
        assertSame(cancellation, observed)
    }

    private fun request(window: String) = CustomPluginEvent(
        "fluck", ProviderNavigation.OPEN_AI_EVENT, mapOf(ProviderNavigation.WINDOW_ID_KEY to window),
    )
}
