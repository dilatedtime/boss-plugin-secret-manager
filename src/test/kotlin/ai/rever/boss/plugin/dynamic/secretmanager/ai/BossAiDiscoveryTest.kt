package ai.rever.boss.plugin.dynamic.secretmanager.ai

import ai.rever.boss.plugin.api.QueryFilter
import ai.rever.boss.plugin.api.QueryRange
import ai.rever.boss.plugin.api.SupabaseDataProvider
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BossAiDiscoveryTest {
    private val id = BossAiDiscovery.PROVIDER_ID
    private val metadata = """{"schema":"boss-managed-provider-v1","name":"Included AI",
        "brokerId":"boss-ai","baseUrl":"${BossAiCredentialSource.API_SCOPE}","defaultForNewUsers":true}"""
    private val models = """{"data":[{"id":"a","name":"A"},{"id":"z","name":"Z",
        "is_default":true,"max_output_tokens":100,"context_length":2048,"capabilities":["text","tools"],
        "allowance":{"day":{"remaining":10000}}}]}"""
    private val tokenResponse get() = """{"access_token":"ai-only-token","refresh_after_seconds":180,
        "expires_at":"${Instant.now().plusSeconds(300)}"}"""

    private class Rpc : SupabaseDataProvider {
        var calls = 0
        var fail = false
        override suspend fun select(table: String, columns: String, filters: List<QueryFilter>, range: QueryRange?) =
            Result.failure<String>(AssertionError("No database SELECT is needed"))
        override suspend fun rpc(function: String, parameters: String): Result<String> {
            assertEquals("boss_ai_create_exchange_ticket", function)
            assertEquals("{}", parameters)
            calls++
            return if (fail) Result.failure(IllegalStateException("private-session-material"))
            else Result.success("""{"ticket":"${"a".repeat(64)}"}""")
        }
    }
    private fun env(root: File) = EnvResolver(root, processEnv = { null }, systemProperty = { null }, useLaunchctl = false)

    @Test fun `automatic provider is ready on first API read with no host broker or vault entries`() = runBlocking {
        val root = Files.createTempDirectory("boss-auto").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val vault = FakeSecretDataProvider(emptyList(), failReads = true)
        val rpc = Rpc()
        val exchange = QueuedHttpClient(listOf(200 to tokenResponse), beforeResponse = {
            assertEquals(BossAiCredentialSource.EXCHANGE_URL, it.uri().toString())
            assertTrue(it.headers().firstValue("Authorization").isEmpty)
        })
        val discovery = QueuedHttpClient(listOf(200 to metadata), beforeResponse = {
            assertEquals("Bearer ai-only-token", it.headers().firstValue("Authorization").orElse(null))
        })
        val store = ProviderCredentialStore(vault, env(root), bossAiDiscovery = BossAiDiscovery(discovery)).also {
            it.brokeredKeys = BossAiCredentialSource(rpc, httpClient = exchange)
        }
        val catalog = ModelCatalog(ModelCatalogClient(QueuedHttpClient(listOf(200 to models))), root)
        try {
            val prefs = ActiveProviderPrefs(root)
            val vm = AiProvidersViewModel(store = store, catalog = catalog, prefs = prefs,
                legacyImport = null, splitViewOperations = null, scope = scope, envResolver = env(root),
                ollamaSystemCheck = noOllamaOnThisMachine())
            val api = LlmProviderSettingsApiImpl(vm)
            api.activeConfig()
            withTimeout(10_000) { vm.catalogsLoaded.first { it } }
            val config = assertNotNull(api.activeConfig())
            assertEquals(id, config.providerId)
            assertEquals("z", config.modelId)
            assertEquals(100, config.maxTokens)
            assertEquals("Included AI", vm.state.value.providers.first { it.id == id }.displayName)
            assertFalse(vm.state.value.storeAvailable)
            vm.selectModel(id, "a")
            withTimeout(5_000) { while (prefs.readModels()[id] != "a") delay(10) }
            assertEquals("a", api.activeConfig()?.modelId)
            assertTrue(vault.created.isEmpty())
            assertTrue(vault.updated.isEmpty())
            assertEquals(1, rpc.calls)
            assertEquals(1, discovery.requests.size)
            assertFalse(File(root, "ai-model-catalog.json").readTextOrEmpty().contains(id))
            assertFalse(root.walkTopDown().filter { it.isFile }.any { it.readText().contains("ai-only-token") })
            rpc.fail = true
            store.invalidate()
            withTimeout(5_000) { vm.state.first { !it.connectionOf(id).isConfigured } }
            assertNull(api.activeConfig())
        } finally { scope.cancel(); root.deleteRecursively() }
    }

    @Test fun `sign-in recovery activates the automatic default without reopening settings`() = runBlocking {
        val root = Files.createTempDirectory("boss-sign-in").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val rpc = Rpc().also { it.fail = true }
        val store = ProviderCredentialStore(FakeSecretDataProvider(emptyList()), env(root),
            bossAiDiscovery = BossAiDiscovery(QueuedHttpClient(listOf(200 to metadata)))).also {
            it.brokeredKeys = BossAiCredentialSource(rpc, httpClient = QueuedHttpClient(listOf(200 to tokenResponse)))
        }
        try {
            val vm = AiProvidersViewModel(store = store,
                catalog = ModelCatalog(ModelCatalogClient(QueuedHttpClient(listOf(200 to models)))),
                prefs = ActiveProviderPrefs(root), legacyImport = null, splitViewOperations = null,
                scope = scope, envResolver = env(root), ollamaSystemCheck = noOllamaOnThisMachine())
            val api = LlmProviderSettingsApiImpl(vm)
            api.activeConfig()
            withTimeout(10_000) { vm.catalogsLoaded.first { it } }
            assertNull(api.activeConfig())
            rpc.fail = false
            vm.refreshBrokeredCredential(id)
            withTimeout(10_000) { while (api.activeConfig()?.modelId != "z") delay(10) }
            assertEquals(id, api.activeConfig()?.providerId)
        } finally { scope.cancel(); root.deleteRecursively() }
    }

    @Test fun `provider metadata cannot change broker or escape trusted scope`() = runBlocking {
        for (body in listOf(
            metadata.replace("\"boss-ai\"", "\"other-broker\""),
            metadata.replace(BossAiCredentialSource.API_SCOPE, "https://evil.example/v1"),
            metadata.replace(BossAiCredentialSource.API_SCOPE, BossAiCredentialSource.API_SCOPE + "-evil"),
            metadata.replace(BossAiCredentialSource.API_SCOPE, BossAiCredentialSource.API_SCOPE + "/../other"),
        )) {
            assertTrue(BossAiDiscovery(QueuedHttpClient(listOf(200 to body)))
                .fetch(BossAiCredentialSource.API_SCOPE, "token").isFailure)
        }
        assertTrue(BossAiDiscovery(QueuedHttpClient(listOf(302 to metadata)))
            .fetch(BossAiCredentialSource.API_SCOPE, "token").isFailure)
    }

    @Test fun `metadata failure is visible unconfigured and a refresh recovers without vault publishing`() = runBlocking {
        val root = Files.createTempDirectory("boss-retry").toFile()
        val http = QueuedHttpClient(listOf(404 to "{}", 200 to metadata))
        val store = ProviderCredentialStore(FakeSecretDataProvider(emptyList()), env(root),
            bossAiDiscovery = BossAiDiscovery(http)).also {
            it.brokeredKeys = BossAiCredentialSource(Rpc(), httpClient = QueuedHttpClient(listOf(200 to tokenResponse)))
        }
        try {
            val first = store.loadAll()
            assertFalse(first.connections.getValue(id).isConfigured)
            assertTrue(first.sharedDiscoveryWarning!!.contains("not deployed"))
            assertTrue(first.descriptors.any { it.id == id })
            store.loadAll()
            assertEquals(1, http.requests.size)
            store.expireSharedDefinitions()
            val recovered = store.loadAll()
            assertTrue(recovered.connections.getValue(id).isConfigured)
            assertNull(recovered.sharedDiscoveryWarning)
            store.invalidate()
            store.loadAll()
            assertEquals(3, http.requests.size)
        } finally { root.deleteRecursively() }
    }

    @Test fun `explicit choices win and server controls recommendation`() {
        val descriptor = SharedProviderDefinition.parse(metadata)!!.descriptor("unused").copy(id = id)
        val connection = ProviderConnection(id, "token", CredentialSource.BROKERED)
        val descriptors = ProviderRegistry.all + descriptor
        val own = ProviderConnection(ProviderRegistry.OPENAI, "own", CredentialSource.STORED, "chosen")
        val connections = mapOf(id to connection, own.providerId to own)
        assertEquals(own.providerId, initialProviderId(own.providerId, descriptors, connections))
        assertEquals(id, initialProviderId(null, descriptors, mapOf(id to connection)))
        assertNull(initialProviderId(null, listOf(descriptor.copy(sharedDefault = false)), mapOf(id to connection)))
    }

    @Test fun `failed ticket issuance never calls HTTP or leaks RPC errors`() = runBlocking {
        val http = QueuedHttpClient(emptyList())
        val source = BossAiCredentialSource(Rpc().also { it.fail = true }, httpClient = http)
        val result = source.fetch(BossAiDiscovery.BROKER_ID)
        assertTrue(result.isFailure)
        assertFalse(result.exceptionOrNull()!!.message!!.contains("private-session-material"))
        assertTrue(http.requests.isEmpty())
    }

    @Test fun `expired malformed and redirected exchanges cannot configure a provider`() = runBlocking {
        for (response in listOf(
            302 to tokenResponse,
            200 to tokenResponse.replace("ai-only-token", "bad token"),
            200 to """{"access_token":"token","refresh_after_seconds":180,"expires_at":"2000-01-01T00:00:00Z"}""",
            200 to "{}",
        )) {
            assertTrue(BossAiCredentialSource(Rpc(), httpClient = QueuedHttpClient(listOf(response)))
                .fetch(BossAiDiscovery.BROKER_ID).isFailure)
        }
    }

    private fun File.readTextOrEmpty() = if (exists()) readText() else ""
}
