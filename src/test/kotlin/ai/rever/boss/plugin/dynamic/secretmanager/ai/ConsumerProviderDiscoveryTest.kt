package ai.rever.boss.plugin.dynamic.secretmanager.ai

import ai.rever.boss.plugin.api.SecretEntryData
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConsumerProviderDiscoveryTest {
    private class Harness(
        keys: Map<String, String> = mapOf("OPENROUTER_API_KEY" to "test-only-key"),
        response: Pair<Int, String> = 200 to """{"data":[{"id":"consumer/model","name":"Consumer model","context_length":131072}]}""",
        responses: List<Pair<Int, String>> = emptyList(),
        probe: OllamaSystemCheck = noOllamaOnThisMachine(),
        beforeResponse: () -> Unit = {},
        val nowNanos: AtomicLong = AtomicLong(0),
    ) : AutoCloseable {
        val root = Files.createTempDirectory("consumer-provider").toFile()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val env = EnvResolver(root, processEnv = keys::get, systemProperty = { null }, useLaunchctl = false)
        val prefs = ActiveProviderPrefs(bossRootDir = root)
        val http = QueuedHttpClient(responses, always = response, beforeResponse = { beforeResponse() })
        val catalog = ModelCatalog(ModelCatalogClient(http))
        val secrets = FakeSecretDataProvider(emptyList())
        val store = ProviderCredentialStore(secrets, env)
        val vm =
            AiProvidersViewModel(
                store = store,
                catalog = catalog,
                prefs = prefs,
                legacyImport = null,
                splitViewOperations = null,
                scope = scope,
                envResolver = env,
                ollamaSystemCheck = probe,
                monotonicNanos = nowNanos::get,
            )
        val api = LlmProviderSettingsApiImpl(vm)

        suspend fun load() {
            api.availableModels()
            withTimeout(5000) { vm.catalogsLoaded.first { it } }
        }

        override fun close() {
            scope.cancel()
            root.deleteRecursively()
        }
    }

    @Test fun `OpenRouter model metadata needs no default or settings visit`() = runBlocking {
        Harness().use { h ->
            h.load()
            val router = h.api.configuredProviders().single { it.providerId == ProviderRegistry.OPENROUTER }
            assertEquals("", router.modelId)
            assertNull(h.api.activeConfig(), "apps without their own model selection need a default")
            val model = h.api.availableModels().single { it.providerId == ProviderRegistry.OPENROUTER }.models.single()
            assertEquals("consumer/model", model.id)
            assertEquals(131072, model.contextLength)
            assertEquals(1, h.http.requests.size)
            assertFalse(h.api.configuredProviders().any { it.providerId == ProviderRegistry.OLLAMA })
        }
    }

    @Test fun `consumer catalog waits for Ollama probe before deciding to fetch`() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val probe = OllamaSystemCheck(path = "", home = "", isWindows = false, isExecutable = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            true
        }, physicalMemoryBytes = { null }, browse = { false })
        Harness(keys = emptyMap(), probe = probe).use { h ->
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                h.api.availableModels()
                withTimeout(5000) { h.vm.connectionsLoaded.first { it } }
                assertNull(withTimeoutOrNull(100) { h.vm.catalogsLoaded.first { it } },
                    "catalog sweep must remain suspended while the probe is unresolved")
                assertFalse(h.vm.catalogsLoaded.value)
                assertTrue(h.http.requests.isEmpty())
            } finally {
                release.countDown()
            }
            withTimeout(5000) { h.vm.catalogsLoaded.first { it } }
            assertEquals(1, h.http.requests.size, "the installed daemon is discovered after the probe answers")
            assertEquals(ProviderRegistry.OLLAMA, h.api.configuredProviders().single().providerId)
        }
    }

    @Test fun `an overlapping panel load joins the consumer catalog sweep`() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        Harness(beforeResponse = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }).use { h ->
            try {
                h.api.availableModels()
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                h.vm.load()
            } finally {
                release.countDown()
            }
            withTimeout(5000) { h.vm.catalogsLoaded.first { it } }
            withTimeout(5000) { h.vm.state.first { !it.isLoading } }
            assertEquals(1, h.http.requests.size, "panel load duplicated the in-flight consumer sweep")
        }
    }

    @Test fun `a provider without a fetched catalog is omitted rather than empty`() = runBlocking {
        Harness(response = 503 to "unavailable").use { h ->
            h.load()
            assertTrue(h.api.availableModels().isEmpty())
            h.nowNanos.set(31_000_000_000L)
            h.api.availableModels()
            delay(100)
            assertEquals(1, h.http.requests.size, "polling must not retry a failure continuously")
        }
    }

    @Test fun `manual provider contributes its typed model without catalog fetch`() = runBlocking {
        Harness(keys = emptyMap()).use { h ->
            h.prefs.writeModel(ProviderRegistry.CUSTOM, "private-model")
            h.prefs.writeCustomEndpoint(ProviderRegistry.CUSTOM, "http://localhost:9999/v1/chat/completions")
            h.load()
            assertEquals("private-model", h.api.availableModels().single().models.single().id)
            assertTrue(h.http.requests.isEmpty())
        }
    }

    @Test fun `Google without a selected model never exposes a malformed endpoint`() = runBlocking {
        Harness(
            keys = mapOf("GOOGLE_API_KEY" to "test-only-key"),
            response = 200 to """{"models":[{"name":"models/gemini-3"}]}""",
        ).use { h ->
            h.load()
            assertFalse(h.api.configuredProviders().any { it.providerId == ProviderRegistry.GOOGLE })
            assertTrue(h.api.availableModels().any { it.providerId == ProviderRegistry.GOOGLE })
        }
    }

    @Test fun `a failed refresh keeps the last known models available to consumers`() = runBlocking {
        Harness(
            responses = listOf(
                200 to """{"data":[{"id":"known-model"}]}""",
                503 to """{"error":"offline"}""",
            ),
        ).use { h ->
            h.load()
            val descriptor = ProviderRegistry.find(ProviderRegistry.OPENROUTER)!!
            h.catalog.refresh(descriptor, "test-only-key", force = true)
            assertTrue(h.catalog.stateOf(descriptor.id) is CatalogState.Failed)
            // The API reads the ViewModel snapshot, whose catalog collector advances on a
            // separate coroutine. Await that observable handoff instead of racing it.
            withTimeout(5000) {
                h.vm.state.first { it.catalogOf(descriptor.id) is CatalogState.Failed }
            }
            assertEquals("known-model", h.api.availableModels().single().models.single().id)
        }
    }

    @Test fun `adding an unreachable local provider stays a panel-only affordance`() = runBlocking {
        Harness(keys = emptyMap()).use { h ->
            h.vm.selectProvider(ProviderRegistry.OLLAMA)
            h.load()
            assertTrue(ProviderRegistry.OLLAMA in h.vm.state.value.addedProviderIds)
            assertTrue(h.api.configuredProviders().isEmpty())
            assertTrue(h.api.availableModels().isEmpty())
        }
    }

    @Test fun `a key added after discovery loads models without opening settings`() = runBlocking {
        Harness(keys = emptyMap()).use { h ->
            h.load()
            assertTrue(h.api.availableModels().isEmpty())
            // The shared fake records create requests but intentionally does not persist
            // them. Model a Secrets-section edit by changing its readable rows first,
            // then emitting the same invalidation that production CRUD emits.
            h.secrets.entries = listOf(SecretEntryData(
                id = "new-provider-secret", website = ProviderRegistry.OPENROUTER,
                username = "test-account", password = "new-test-key", notes = null,
                tags = listOf(ProviderCredentialStore.TAG_AI_PROVIDER, ProviderRegistry.OPENROUTER),
                createdAt = "2026-01-01", updatedAt = "2026-01-01",
            ))
            h.store.invalidate()
            withTimeout(5000) {
                h.vm.state.first { it.catalogOf(ProviderRegistry.OPENROUTER) is CatalogState.Loaded }
            }
            assertTrue(h.api.availableModels().any { it.providerId == ProviderRegistry.OPENROUTER })
        }
    }

    @Test fun `a panel load keeps catalog refresh paired with later credential invalidation`() = runBlocking {
        Harness(keys = emptyMap()).use { h ->
            h.vm.load()
            withTimeout(5000) { h.vm.catalogsLoaded.first { it } }
            h.secrets.entries = listOf(SecretEntryData(
                id = "panel-added-secret", website = ProviderRegistry.OPENROUTER,
                username = "test-account", password = "new-test-key", notes = null,
                tags = listOf(ProviderCredentialStore.TAG_AI_PROVIDER, ProviderRegistry.OPENROUTER),
                createdAt = "2026-01-01", updatedAt = "2026-01-01",
            ))
            h.store.invalidate()
            withTimeout(5000) {
                h.vm.state.first { it.catalogOf(ProviderRegistry.OPENROUTER) is CatalogState.Loaded }
            }
            assertEquals(1, h.http.requests.size)
        }
    }

    @Test fun `an unchanged credential reload preserves the seated catalog`() = runBlocking {
        Harness().use { h ->
            h.load()
            assertTrue(h.catalog.stateOf(ProviderRegistry.OPENROUTER) is CatalogState.Loaded)
            val sawUnloaded = AtomicBoolean(false)
            val sawCatalogInvalidation = AtomicBoolean(false)
            val loadedMonitor = launch {
                h.vm.catalogsLoaded.drop(1).collect { loaded ->
                    if (!loaded) sawUnloaded.set(true)
                }
            }
            val catalogMonitor = launch {
                h.catalog.states.drop(1).collect { states ->
                    if (states[ProviderRegistry.OPENROUTER] is CatalogState.NotConfigured) {
                        sawCatalogInvalidation.set(true)
                    }
                }
            }

            val pageCount = h.secrets.pageRequests.size
            h.store.invalidate()
            withTimeout(5000) {
                while (h.secrets.pageRequests.size <= pageCount) delay(10)
            }
            // The page request is recorded at the start of reload; let its state write settle.
            delay(100)
            loadedMonitor.cancelAndJoin()
            catalogMonitor.cancelAndJoin()

            assertFalse(sawUnloaded.get(), "an unchanged reload reset catalogsLoaded")
            assertFalse(sawCatalogInvalidation.get(), "an unchanged reload cleared a seated catalog")
            assertTrue(h.catalog.stateOf(ProviderRegistry.OPENROUTER) is CatalogState.Loaded)
            assertEquals(1, h.http.requests.size, "an unchanged reload repeated model discovery")
        }
    }

    @Test fun `consumer reads recheck catalog TTL after the bounded refresh interval`() = runBlocking {
        Harness().use { h ->
            h.load()
            val descriptor = ProviderRegistry.find(ProviderRegistry.OPENROUTER)!!
            h.catalog.refresh(descriptor, "test-only-key", force = true,
                nowEpochMs = System.currentTimeMillis() - ModelCatalog.CACHE_TTL_MS - 1000)
            assertEquals(2, h.http.requests.size)
            h.nowNanos.set(31_000_000_000L)
            h.api.availableModels()
            withTimeout(5000) {
                h.catalog.states.first {
                    (it[descriptor.id] as? CatalogState.Loaded)?.fetchedAtEpochMs?.let { fetched ->
                        System.currentTimeMillis() - fetched < 5000
                    } == true
                }
            }
            assertEquals(3, h.http.requests.size)
        }
    }

    @Test fun `a fetched empty catalog is distinguishable from no fetched catalog`() = runBlocking {
        Harness(response = 200 to """{"data":[]}""").use { h ->
            h.load()
            assertEquals(ProviderRegistry.OPENROUTER, h.api.availableModels().single().providerId)
            assertTrue(h.api.availableModels().single().models.isEmpty())
        }
    }

    @Test fun `manual provider without a typed model is omitted`() = runBlocking {
        Harness(keys = emptyMap()).use { h ->
            h.prefs.writeCustomEndpoint(ProviderRegistry.CUSTOM, "http://localhost:9999/v1/chat/completions")
            h.load()
            assertTrue(h.api.availableModels().isEmpty())
            assertTrue(h.api.configuredProviders().isEmpty())
        }
    }
}
