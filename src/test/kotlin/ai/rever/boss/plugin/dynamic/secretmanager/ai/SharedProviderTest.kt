package ai.rever.boss.plugin.dynamic.secretmanager.ai

import ai.rever.boss.plugin.api.PaginatedSecretsWithSharingData
import ai.rever.boss.plugin.api.SecretDataProvider
import ai.rever.boss.plugin.api.SecretEntryWithSharingData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedProviderTest {
    private val base = "https://api.example/v1"
    private val notes = """{"schema":"boss-managed-provider-v1","name":"Shared AI","brokerId":"managed","baseUrl":"$base","defaultForNewUsers":true}"""

    private fun entry(id: String = "secret-id", note: String = notes, sharedBy: String? = null) = SecretEntryWithSharingData(
        id = id, website = "Shared AI", username = "BOSS sign-in", password = "",
        notes = note, tags = listOf(SharedProviderDefinition.TAG), createdAt = "", updatedAt = "",
        isOwner = false, accessLevel = "read",
        sharedByEmail = sharedBy,
    )

    private class SharedStore(val delegate: FakeSecretDataProvider, var entries: List<SecretEntryWithSharingData>) : SecretDataProvider by delegate {
        val offsets = mutableListOf<Int>()
        override suspend fun getUserSecretsWithSharingInfo(limit: Int, offset: Int): Result<PaginatedSecretsWithSharingData> {
            offsets += offset
            return Result.success(PaginatedSecretsWithSharingData(entries.drop(offset).take(limit), offset + limit < entries.size))
        }
    }

    private val broker = object : BrokeredKeySource {
        override val supportsSharedProviders = true
        override fun permitsEndpoint(brokerId: String, endpoint: String) = brokerId == "managed" && SharedProviderDefinition.withinScope(endpoint, base)
        override suspend fun fetch(brokerId: String) = if (brokerId == "managed") Result.success(BrokeredKey("session-derived", 600)) else Result.failure(IllegalStateException("Unknown broker"))
    }

    @Test fun `canonical BOSS AI request is discoverable inert metadata`() {
        val request = bossAiDefinitionRequest()
        assertEquals(SharedProviderDefinition.BOSS_AI_WEBSITE, request.website)
        assertEquals(SharedProviderDefinition.BOSS_AI_USERNAME, request.username)
        assertEquals(SharedProviderDefinition.INERT_PASSWORD, request.password)
        assertEquals(listOf(SharedProviderDefinition.TAG), request.tags)
        assertEquals(SharedProviderDefinition.bossAi(), SharedProviderDefinition.parse(request.notes))
    }

    private fun env(root: java.io.File) = EnvResolver(root, processEnv = { null }, systemProperty = { null }, useLaunchctl = false)

    @Test fun `shared notes cannot redirect credentials outside the host scope`() {
        for (url in listOf("https://api.example.evil/v1", "$base.evil/models", "$base/../other", "$base/%2e%2e/other", "https://user@api.example/v1", "http://api.example/v1")) {
            assertFalse(SharedProviderDefinition.withinScope(url, base), url)
        }
        assertTrue(SharedProviderDefinition.withinScope("$base/models", base))
        assertNull(SharedProviderDefinition.parse(notes.replace("boss-managed-provider-v1", "unknown-v2")))
        assertEquals(
            "OfficialBOSS",
            SharedProviderDefinition.parse(notes.replace("Shared AI", "Official\nBOSS"))?.name,
        )
        assertNull(SharedProviderDefinition.parse(notes.replace("Shared AI", "x".repeat(101))))
        assertNull(SharedProviderDefinition.parse(notes.replace("managed", "")))
        assertNull(SharedProviderDefinition.parse(notes.replace(base, "https://api.example/" + "x".repeat(2048))))
    }

    @Test fun `shared providers are paged discovered minted and removed without writes`() = runBlocking {
        val root = Files.createTempDirectory("shared-provider-test").toFile()
        val fake = FakeSecretDataProvider(emptyList())
        val ownedOpenAi = SecretEntryWithSharingData(
            id = "own", website = ProviderRegistry.OPENAI, username = "OPENAI_API_KEY",
            password = "personal", notes = null,
            tags = listOf(ProviderCredentialStore.TAG_AI_PROVIDER, ProviderRegistry.OPENAI),
            createdAt = "", updatedAt = "", isOwner = true, accessLevel = "owner",
        )
        val entries = (0..160).map { entry("$it", sharedBy = "admin@example.com") }
        val vault = SharedStore(fake, entries + entry("000-malicious", notes.replace(base, "https://attacker.example/v1")))
        vault.entries = vault.entries + ownedOpenAi
        val store = ProviderCredentialStore(vault, env(root)).also { it.brokeredKeys = broker }
        try {
            val snapshot = store.loadAll()
            assertTrue(vault.offsets.size > 1)
            assertTrue(fake.pageRequests.isEmpty(), "Cold shared discovery must use one vault scan")
            assertEquals(32, snapshot.descriptors.count { SharedProviderDefinition.isShared(it.id) })
            assertNull(snapshot.connections["shared:000-malicious"])
            assertEquals("session-derived", snapshot.connections["shared:10"]?.apiKey)
            assertEquals(CredentialSource.BROKERED, snapshot.connections["shared:10"]?.source)
            assertEquals("Shared by admin@example.com", snapshot.descriptors.first { it.id == "shared:10" }.sharedSourceLabel)
            assertEquals("personal", snapshot.connections[ProviderRegistry.OPENAI]?.apiKey)
            vault.entries = emptyList()
            store.invalidate()
            assertFalse(store.loadAll().connections.containsKey("shared:10"))
            assertTrue(fake.created.isEmpty())
            assertTrue(fake.updated.isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun `first API consumer discovers shared default without opening settings`() = runBlocking {
        val root = Files.createTempDirectory("shared-provider-default").toFile()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val fake = FakeSecretDataProvider(emptyList())
        val store = ProviderCredentialStore(SharedStore(fake, listOf(entry())), env(root)).also { it.brokeredKeys = broker }
        val catalog = ModelCatalog(client = ModelCatalogClient(QueuedHttpClient(listOf(200 to
            """{"data":[{"id":"a","name":"A"},{"id":"z","name":"Z","is_default":true,"max_output_tokens":100,"context_length":2048}]}"""
        ))))
        try {
            val vm = AiProvidersViewModel(store = store, catalog = catalog, prefs = ActiveProviderPrefs(root),
                legacyImport = null, splitViewOperations = null, scope = scope, envResolver = env(root),
                ollamaSystemCheck = noOllamaOnThisMachine())
            val api = LlmProviderSettingsApiImpl(vm)
            api.activeConfig()
            withTimeout(10_000) { vm.catalogsLoaded.first { it } }
            val config = api.activeConfig()
            assertNotNull(config)
            assertEquals("shared:secret-id", config.providerId)
            assertEquals("z", config.modelId)
            assertEquals(100, config.maxTokens)
            vm.selectModel(config.providerId, "a")
            withTimeout(5_000) { while (ActiveProviderPrefs(root).readModels()[config.providerId] != "a") delay(10) }
            assertEquals("a", api.activeConfig()?.modelId)
            assertEquals(ProviderConnection.DEFAULT_MAX_TOKENS, api.activeConfig()?.maxTokens)
            vm.selectModel(config.providerId, "z")
            withTimeout(5_000) { while (ActiveProviderPrefs(root).readModels()[config.providerId] != "z") delay(10) }
            assertEquals(100, api.activeConfig()?.maxTokens)
            vm.selectModel(config.providerId, "a")
            withTimeout(5_000) { while (ActiveProviderPrefs(root).readModels()[config.providerId] != "a") delay(10) }
            assertEquals(ProviderConnection.DEFAULT_MAX_TOKENS, api.activeConfig()?.maxTokens)
            vm.selectModel(config.providerId, "no-longer-published")
            assertNull(api.activeConfig(), "An unavailable explicit selection must not switch models silently")
            assertTrue(vm.state.value.unavailableSharedModel)
            withTimeout(5_000) { while (ActiveProviderPrefs(root).readModels()[config.providerId] != "no-longer-published") delay(10) }
            assertTrue(fake.created.isEmpty())
            assertTrue(fake.updated.isEmpty())
        } finally { scope.cancel(); root.deleteRecursively() }
    }
}
