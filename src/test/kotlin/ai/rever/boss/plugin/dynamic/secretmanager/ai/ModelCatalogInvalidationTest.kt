package ai.rever.boss.plugin.dynamic.secretmanager.ai

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelCatalogInvalidationTest {
    @Test
    fun `a response from a replaced credential cannot repopulate its invalidated catalog`() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = AtomicBoolean(true)
        val http = QueuedHttpClient(
            responses = listOf(
                200 to """{"data":[{"id":"old-model"}]}""",
                200 to """{"data":[{"id":"new-model"}]}""",
            ),
            beforeResponse = {
                if (first.compareAndSet(true, false)) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
            },
        )
        val catalog = ModelCatalog(ModelCatalogClient(http))
        val provider = ProviderRegistry.find(ProviderRegistry.OPENAI)!!
        val oldFetch = async(Dispatchers.IO) { catalog.refresh(provider, "old-test-key") }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            catalog.markNotConfigured(provider.id)
        } finally {
            release.countDown()
        }
        oldFetch.await()
        assertEquals(CatalogState.NotConfigured, catalog.stateOf(provider.id),
            "the old in-flight response must not be seated after credential replacement")
        catalog.refresh(provider, "new-test-key")
        assertEquals("new-model", (catalog.stateOf(provider.id) as CatalogState.Loaded).models.single().id)
    }
}
