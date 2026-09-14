package ai.rever.boss.plugin.dynamic.secretmanager.ai

import ai.rever.boss.plugin.api.PaginatedSecretsWithSharingData
import ai.rever.boss.plugin.api.SecretDataProvider
import ai.rever.boss.plugin.api.SecretEntryWithSharingData
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedProviderReviewTest {
    private val definition = SharedProviderDefinition(
        "boss-managed-provider-v1", "Shared AI", "managed", "https://api.example/v1", true,
    )
    private val descriptor = definition.descriptor("entry")
    private val connection = ProviderConnection(descriptor.id, "minted", CredentialSource.BROKERED)
    private val broker = object : BrokeredKeySource {
        override val supportsSharedProviders = true
        override fun permitsEndpoint(brokerId: String, endpoint: String) =
            brokerId == "managed" && SharedProviderDefinition.withinScope(endpoint, definition.baseUrl)
        override suspend fun fetch(brokerId: String) = if (brokerId == "managed") {
            Result.success(BrokeredKey("minted", 600))
        } else Result.failure(IllegalArgumentException("Unknown broker"))
    }

    private fun entry(id: String = "entry") = SecretEntryWithSharingData(
        id = id, website = "Shared AI", username = "", password = "", createdAt = "", updatedAt = "",
        tags = listOf(SharedProviderDefinition.TAG), isOwner = false, accessLevel = "read",
        notes = """{"schema":"boss-managed-provider-v1","name":"Shared AI","brokerId":"managed",
            "baseUrl":"https://api.example/v1","defaultForNewUsers":true}""",
    )

    private class Vault : SecretDataProvider by FakeSecretDataProvider(emptyList()) {
        var entries = emptyList<SecretEntryWithSharingData>()
        var reads = 0
        var fail = false
        @Volatile var onRead: (() -> Unit)? = null
        var gate: CompletableDeferred<Unit>? = null
        val entered = CompletableDeferred<Unit>()
        override suspend fun getUserSecretsWithSharingInfo(limit: Int, offset: Int)
            : Result<PaginatedSecretsWithSharingData> {
            reads++
            onRead?.invoke()
            val page = entries.drop(offset).take(limit)
            entered.complete(Unit)
            gate?.await()
            if (fail) return Result.failure(IllegalStateException("Unavailable"))
            return Result.success(PaginatedSecretsWithSharingData(page, offset + page.size < entries.size))
        }
    }

    private fun env(root: File) = EnvResolver(
        root, processEnv = { null }, systemProperty = { null }, useLaunchctl = false,
    )

    @Test fun `both machine lists require a configured shared connection and loaded catalog`() {
        val loaded = CatalogState.Loaded(listOf(AiModel("model", "Model")), 0)
        assertTrue(isProviderListed(descriptor, connection, loaded))
        assertFalse(isProviderListed(descriptor, connection.copy(apiKey = ""), loaded))
        assertFalse(isProviderListed(descriptor, connection, CatalogState.NotConfigured))
        assertFalse(isProviderListed(descriptor, connection, CatalogState.Loading))
    }

    @Test fun `unavailable shared model banner uses the same transient fallback as configuration`() {
        val lastKnown = CatalogState.Loaded(listOf(AiModel("other", "Other")), 1)
        val state = AiProvidersUiState(
            activeProviderId = descriptor.id,
            connections = mapOf(descriptor.id to connection.copy(selectedModelId = "removed")),
            catalogs = mapOf(
                descriptor.id to CatalogState.Failed("temporary outage", lastKnown, permanent = false),
            ),
        )
        assertTrue(state.unavailableSharedModel)
        assertNull(state.connectionOf(descriptor.id).selectedModelId)
    }

    @Test fun `stale ids cannot wedge startup and shared defaults do not preempt configured keys`() {
        val openai = ProviderRegistry.find(ProviderRegistry.OPENAI)!!
        val own = ProviderConnection(openai.id, "own-key", CredentialSource.STORED, selectedModelId = "own")
        val descriptors = ProviderRegistry.all + descriptor
        val connections = mapOf(descriptor.id to connection, openai.id to own)
        assertEquals(openai.id, initialProviderId("shared:revoked", descriptors, connections))
        assertEquals(openai.id, initialProviderId(null, descriptors, connections))
        assertEquals(descriptor.id, initialProviderId(descriptor.id, descriptors, connections))
        assertEquals(descriptor.id, initialProviderId(null, descriptors, mapOf(descriptor.id to connection)))
        val direct = descriptor.copy(id = "shared:direct", sharedProvenance = SharedProviderProvenance.DIRECT_SHARE)
        val organisation = descriptor.copy(id = "shared:org", sharedProvenance = SharedProviderProvenance.ORGANISATION)
        assertEquals(
            organisation.id,
            initialProviderId(
                null,
                listOf(direct, organisation),
                mapOf(direct.id to connection.copy(providerId = direct.id), organisation.id to connection.copy(providerId = organisation.id)),
            ),
        )
    }

    @Test fun `revoked saved share warns before startup falls back`() = runBlocking<Unit> {
        val root = Files.createTempDirectory("shared-startup-revoked").toFile()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val prefs = ActiveProviderPrefs(root)
        prefs.write("shared:revoked")
        val store = ProviderCredentialStore(Vault(), env(root)).also { it.brokeredKeys = broker }
        try {
            val vm = AiProvidersViewModel(
                store = store, catalog = ModelCatalog(ModelCatalogClient(QueuedHttpClient(emptyList()))),
                prefs = prefs, legacyImport = null, splitViewOperations = null, scope = scope,
                envResolver = env(root), ollamaSystemCheck = noOllamaOnThisMachine(),
            )
            vm.ensureConnectionsLoaded()
            withTimeout(10_000) { vm.connectionsLoaded.first { it } }
            assertFalse(vm.state.value.activeProviderId == "shared:revoked")
            assertNotNull(vm.state.value.providerSelectionWarning)
        } finally { scope.cancel(); root.deleteRecursively() }
    }

    @Test fun `shared discovery caches success and empty results until expiry or invalidation`() = runBlocking {
        val root = Files.createTempDirectory("shared-cache").toFile()
        var now = 0L
        val vault = Vault().also { it.entries = listOf(entry()) }
        val store = ProviderCredentialStore(vault, env(root), monotonicNanos = { now })
            .also { it.brokeredKeys = broker }
        try {
            assertNotNull(store.loadAll().connections[descriptor.id])
            repeat(4) { store.loadAll() }
            assertEquals(1, vault.reads)
            vault.entries = emptyList()
            now = 5 * 60 * 1_000_000_000L
            assertNull(store.loadAll().connections[descriptor.id])
            store.loadAll()
            assertEquals(2, vault.reads)
            vault.entries = listOf(entry())
            store.invalidate()
            assertNotNull(store.loadAll().connections[descriptor.id])
            assertEquals(3, vault.reads)
        } finally { root.deleteRecursively() }
    }

    @Test fun `hosts without shared broker support never scan the vault`() = runBlocking {
        val root = Files.createTempDirectory("shared-unsupported").toFile()
        val vault = Vault()
        val store = ProviderCredentialStore(vault, env(root))
        try {
            store.loadAll()
            store.brokeredKeys = BrokeredKeySource { Result.success(BrokeredKey("token", 600)) }
            store.loadAll()
            assertEquals(0, vault.reads)
        } finally { root.deleteRecursively() }
    }

    @Test fun `scan cap retains discovered providers and read errors do not accuse credential storage`() = runBlocking {
        val root = Files.createTempDirectory("shared-cap").toFile()
        val vault = Vault().also { it.entries = (0..2000).map { index -> entry("$index") } }
        val store = ProviderCredentialStore(vault, env(root)).also { it.brokeredKeys = broker }
        try {
            val capped = store.loadAll()
            assertEquals(32, capped.descriptors.count { SharedProviderDefinition.isShared(it.id) })
            assertFalse(capped.sharedDiscoveryComplete)
            assertEquals(20, vault.reads)
            assertNotNull(capped.sharedDiscoveryWarning)
            assertFalse(capped.storeReadFailed)
            vault.fail = true
            store.invalidate()
            val failed = store.loadAll()
            assertNotNull(failed.sharedDiscoveryWarning)
            assertFalse(failed.storeReadFailed)
            store.loadAll()
            assertEquals(21, vault.reads, "A failed scan is also backed off")
        } finally { root.deleteRecursively() }
    }

    @Test fun `an invalidated discovery cannot reseat the old share cache`() = runBlocking {
        val root = Files.createTempDirectory("shared-race").toFile()
        val gate = CompletableDeferred<Unit>()
        val vault = Vault().also { it.entries = listOf(entry()); it.gate = gate }
        val store = ProviderCredentialStore(vault, env(root)).also { it.brokeredKeys = broker }
        try {
            val old = async { store.loadAll() }
            vault.entered.await()
            store.invalidate()
            vault.entries = emptyList()
            gate.complete(Unit)
            val invalidated = old.await()
            assertNull(invalidated.connections[descriptor.id])
            assertTrue(invalidated.invalidatedDuringLoad)
            assertFalse(invalidated.storeReadFailed)
            assertNull(store.loadAll().connections[descriptor.id])
            assertEquals(2, vault.reads)
        } finally { gate.complete(Unit); root.deleteRecursively() }
    }

    @Test fun `shared catalog is never written to disk`() = runBlocking {
        val root = Files.createTempDirectory("shared-no-write").toFile()
        try {
            val catalog = ModelCatalog(ModelCatalogClient(QueuedHttpClient(listOf(
                200 to """{"data":[{"id":"model"}]}""",
            ))), root)
            catalog.refresh(descriptor, "minted", nowEpochMs = 1000)
            assertTrue(catalog.stateOf(descriptor.id) is CatalogState.Loaded)
            assertFalse(File(root, "ai-model-catalog.json").exists())
            assertFalse(catalog.isStale(descriptor.id, 61_000))
            assertTrue(catalog.isStale(descriptor.id, 61_001))
        } finally { root.deleteRecursively() }
    }

    @Test fun `shared catalog is never seeded from disk`() = runBlocking {
        val root = Files.createTempDirectory("shared-no-seed").toFile()
        try {
            File(root, "ai-model-catalog.json").writeText("""{"version":1,"providers":{
                "${descriptor.id}":{"models":[{"id":"private","displayName":"Private"}],"fetchedAtEpochMs":1000}}}""")
            val catalog = ModelCatalog(cacheDir = root)
            catalog.seedFromCache()
            assertEquals(CatalogState.NotConfigured, catalog.stateOf(descriptor.id))
        } finally { root.deleteRecursively() }
    }

    @Test fun `removing an active share clears its catalog and stops the active request path`() = runBlocking {
        val root = Files.createTempDirectory("shared-removal").toFile()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val vault = Vault().also { it.entries = listOf(entry()) }
        val store = ProviderCredentialStore(vault, env(root)).also { it.brokeredKeys = broker }
        val catalog = ModelCatalog(ModelCatalogClient(QueuedHttpClient(listOf(
            200 to """{"data":[{"id":"model","is_default":true}]}""",
        ))))
        try {
            val vm = AiProvidersViewModel(
                store = store, catalog = catalog, prefs = ActiveProviderPrefs(root), legacyImport = null,
                splitViewOperations = null, scope = scope, envResolver = env(root),
                ollamaSystemCheck = noOllamaOnThisMachine(),
            )
            val api = LlmProviderSettingsApiImpl(vm)
            api.activeConfig()
            withTimeout(10_000) { vm.catalogsLoaded.first { it } }
            assertNotNull(api.activeConfig())
            vault.entries = emptyList()
            vm.refreshConnections()
            withTimeout(10_000) { vm.state.first { state -> state.providers.none { it.id == descriptor.id } } }
            assertNull(vm.state.value.activeProviderId)
            assertNull(api.activeConfig())
            assertEquals(CatalogState.NotConfigured, catalog.stateOf(descriptor.id))
            assertTrue(api.configuredProviders().none { it.providerId == descriptor.id })
            assertTrue(api.availableModels().none { it.providerId == descriptor.id })
        } finally { scope.cancel(); root.deleteRecursively() }
    }

    @Test fun `broker scope lookup recovers after host broker discovery changes`() {
        var advertised = emptyList<ai.rever.boss.plugin.api.BrokerInfo>()
        var reads = 0
        val source = BrokeredCredentialBridge.from(object : ai.rever.boss.plugin.api.BrokeredCredentialProvider {
            override fun availableBrokers(): List<ai.rever.boss.plugin.api.BrokerInfo> {
                reads++
                return advertised
            }
            override suspend fun exchange(brokerId: String) =
                Result.success(ai.rever.boss.plugin.api.BrokeredCredential("minted", 600))
        })
        assertFalse(source.permitsEndpoint("managed", descriptor.chatEndpoint))
        assertFalse(source.canDiscoverSharedProviders())
        advertised = listOf(ai.rever.boss.plugin.api.BrokerInfo("managed", "Managed", scopedTo = definition.baseUrl))
        assertTrue(source.canDiscoverSharedProviders())
        assertTrue(source.permitsEndpoint("managed", descriptor.chatEndpoint))
        reads = 0
        assertTrue(source.permitsEndpoints("managed", listOf(descriptor.chatEndpoint, descriptor.modelsEndpoint!!)))
        assertEquals(1, reads, "One definition must use one broker-registry snapshot")
        advertised = emptyList()
        assertFalse(source.permitsEndpoint("managed", descriptor.chatEndpoint))
    }

    @Test fun `provider admission cap warns without making a complete vault scan ambiguous`() = runBlocking<Unit> {
        val root = Files.createTempDirectory("shared-provider-cap").toFile()
        val vault = Vault().also { it.entries = (0 until 33).map { index -> entry("%02d".format(index)) } }
        val store = ProviderCredentialStore(vault, env(root)).also { it.brokeredKeys = broker }
        try {
            val snapshot = store.loadAll()
            assertEquals(32, snapshot.descriptors.count { SharedProviderDefinition.isShared(it.id) })
            assertTrue(snapshot.sharedDiscoveryComplete)
            assertNotNull(snapshot.sharedDiscoveryWarning)
        } finally { root.deleteRecursively() }
    }

    @Test fun `manual discovery refresh reuses minted credentials`() = runBlocking {
        val root = Files.createTempDirectory("shared-narrow-refresh").toFile()
        var mints = 0
        val vault = Vault().also { it.entries = listOf(entry()) }
        val store = ProviderCredentialStore(vault, env(root)).also {
            it.brokeredKeys = object : BrokeredKeySource by broker {
                override suspend fun fetch(brokerId: String): Result<BrokeredKey> {
                    if (brokerId == "managed") mints++
                    return broker.fetch(brokerId)
                }
            }
        }
        try {
            store.loadAll()
            val generation = store.invalidations.value
            store.expireSharedDefinitions()
            store.loadAll()
            assertEquals(2, vault.reads)
            assertEquals(1, mints)
            assertEquals(generation, store.invalidations.value)
        } finally { root.deleteRecursively() }
    }

    @Test fun `transient discovery failure retains selection and consumer polling restores it`() = runBlocking<Unit> {
        val root = Files.createTempDirectory("shared-transient").toFile()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        var now = 0L
        val vault = Vault().also { it.entries = listOf(entry()) }
        val store = ProviderCredentialStore(vault, env(root), monotonicNanos = { now })
            .also { it.brokeredKeys = broker }
        val catalog = ModelCatalog(ModelCatalogClient(QueuedHttpClient(listOf(
            200 to """{"data":[{"id":"model","is_default":true}]}""",
        ))))
        try {
            val vm = AiProvidersViewModel(
                store = store, catalog = catalog, prefs = ActiveProviderPrefs(root), legacyImport = null,
                splitViewOperations = null, scope = scope, envResolver = env(root),
                ollamaSystemCheck = noOllamaOnThisMachine(),
            )
            val api = LlmProviderSettingsApiImpl(vm)
            api.activeConfig()
            withTimeout(10_000) { vm.catalogsLoaded.first { it } }
            assertNotNull(api.activeConfig())
            vm.selectProvider(descriptor.id)
            vault.fail = true
            vm.refreshConnections()
            withTimeout(10_000) { vm.state.first { it.sharedDiscoveryWarning != null } }
            assertEquals(descriptor.id, vm.state.value.activeProviderId)
            assertEquals(descriptor.id, vm.state.value.selectedProviderId)
            assertTrue(vm.state.value.providers.any { it.id == descriptor.id })
            assertNull(api.activeConfig(), "Missing fresh credentials fail closed")
            vault.fail = false
            now = 16 * 1_000_000_000L
            api.activeConfig()
            withTimeout(10_000) { vm.state.first { it.sharedDiscoveryWarning == null } }
            withTimeout(10_000) { catalog.states.first { it[descriptor.id] is CatalogState.Loaded } }
            assertEquals(descriptor.id, api.activeConfig()?.providerId)
            vault.fail = true
            vm.refreshConnections()
            withTimeout(10_000) { vm.state.first { it.sharedDiscoveryWarning != null } }
            vault.fail = false
            vault.entries = emptyList()
            vm.refreshConnections()
            withTimeout(10_000) { vm.state.first { it.sharedDiscoveryWarning == null } }
            assertNull(vm.state.value.activeProviderId, "A complete scan after failure confirms revocation")
            assertNotNull(vm.state.value.providerSelectionWarning)
        } finally { scope.cancel(); root.deleteRecursively() }
    }

    @Test fun `shared token rotation preserves catalog but account invalidation discards it`() = runBlocking<Unit> {
        val root = Files.createTempDirectory("shared-token-rotation").toFile()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val mint = java.util.concurrent.atomic.AtomicInteger()
        val vault = Vault().also { it.entries = listOf(entry()) }
        val store = ProviderCredentialStore(vault, env(root)).also {
            it.brokeredKeys = object : BrokeredKeySource by broker {
                override suspend fun fetch(brokerId: String) = if (brokerId == "managed") {
                    Result.success(BrokeredKey("token-${mint.incrementAndGet()}", 600))
                } else broker.fetch(brokerId)
            }
        }
        val catalog = ModelCatalog(ModelCatalogClient(QueuedHttpClient(listOf(
            200 to """{"data":[{"id":"model","is_default":true}]}""", 503 to "unavailable",
        ))))
        try {
            val vm = AiProvidersViewModel(
                store = store, catalog = catalog, prefs = ActiveProviderPrefs(root), legacyImport = null,
                splitViewOperations = null, scope = scope, envResolver = env(root),
                ollamaSystemCheck = noOllamaOnThisMachine(),
            )
            val api = LlmProviderSettingsApiImpl(vm)
            api.activeConfig()
            withTimeout(10_000) { vm.catalogsLoaded.first { it } }
            assertEquals("token-1", api.activeConfig()?.apiKey)
            store.expireBrokeredCache()
            withTimeout(10_000) { vm.refreshConnections().join() }
            assertEquals("token-2", api.activeConfig()?.apiKey)
            assertEquals("model", api.activeConfig()?.modelId)
            assertTrue(catalog.states.value[descriptor.id] is CatalogState.Loaded)
            store.invalidate()
            withTimeout(10_000) { catalog.states.first { it[descriptor.id] is CatalogState.Failed } }
            assertNull(api.activeConfig(), "Never reuse previous-account metadata after invalidation")
        } finally { scope.cancel(); root.deleteRecursively() }
    }

    @Test fun `discovery retries require both completion and an elapsed rate floor`() {
        var now = 0L
        val throttle = DiscoveryRefreshThrottle(5000) { now }
        assertTrue(throttle.begin())
        now = 10_000_000_000L
        assertFalse(throttle.begin(), "An old in-flight request still owns the slot")
        throttle.finish()
        assertFalse(throttle.begin(), "Invalidated cache must not allow back-to-back attempts")
        now += 4_999_000_000L
        assertFalse(throttle.begin())
        now += 1_000_000L
        assertTrue(throttle.begin())
    }

    @Test fun `transient catalog failures keep known shared models but auth failures do not`() = runBlocking<Unit> {
        val root = Files.createTempDirectory("shared-catalog-outage").toFile()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val vault = Vault().also { it.entries = listOf(entry()) }
        val store = ProviderCredentialStore(vault, env(root)).also { it.brokeredKeys = broker }
        val catalog = ModelCatalog(ModelCatalogClient(QueuedHttpClient(listOf(
            200 to """{"data":[{"id":"model","is_default":true}]}""", 503 to "unavailable", 401 to "expired",
        ))))
        try {
            val vm = AiProvidersViewModel(
                store = store, catalog = catalog, prefs = ActiveProviderPrefs(root), legacyImport = null,
                splitViewOperations = null, scope = scope, envResolver = env(root),
                ollamaSystemCheck = noOllamaOnThisMachine(),
            )
            val api = LlmProviderSettingsApiImpl(vm)
            api.activeConfig()
            withTimeout(10_000) { vm.catalogsLoaded.first { it } }
            catalog.refresh(descriptor, "minted", force = true)
            assertTrue(catalog.states.value[descriptor.id] is CatalogState.Failed)
            assertEquals("model", api.activeConfig()?.modelId)
            assertTrue(api.configuredProviders().any { it.providerId == descriptor.id })
            assertTrue(api.availableModels().any { it.providerId == descriptor.id })
            catalog.refresh(descriptor, "minted", force = true)
            assertNull(api.activeConfig())
            assertTrue(api.configuredProviders().none { it.providerId == descriptor.id })
            assertTrue(api.availableModels().none { it.providerId == descriptor.id })
        } finally { scope.cancel(); root.deleteRecursively() }
    }

    @Test fun `missing broker scope retains selection without credentials and recovers`() = runBlocking<Unit> {
        val root = Files.createTempDirectory("shared-broker-outage").toFile()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val advertised = java.util.concurrent.atomic.AtomicBoolean(true)
        val vault = Vault().also { it.entries = listOf(entry()) }
        val store = ProviderCredentialStore(vault, env(root)).also {
            it.brokeredKeys = object : BrokeredKeySource by broker {
                override fun permitsEndpoint(brokerId: String, endpoint: String) =
                    advertised.get() && broker.permitsEndpoint(brokerId, endpoint)
                override fun permitsEndpoints(brokerId: String, endpoints: List<String>) =
                    advertised.get() && broker.permitsEndpoints(brokerId, endpoints)
            }
        }
        val catalog = ModelCatalog(ModelCatalogClient(QueuedHttpClient(listOf(
            200 to """{"data":[{"id":"model","is_default":true}]}""",
        ))))
        try {
            val vm = AiProvidersViewModel(
                store = store, catalog = catalog, prefs = ActiveProviderPrefs(root), legacyImport = null,
                splitViewOperations = null, scope = scope, envResolver = env(root),
                ollamaSystemCheck = noOllamaOnThisMachine(),
            )
            val api = LlmProviderSettingsApiImpl(vm)
            api.activeConfig()
            withTimeout(10_000) { vm.catalogsLoaded.first { it } }
            advertised.set(false)
            vm.refreshConnections()
            withTimeout(10_000) { vm.state.first { it.sharedDiscoveryWarning != null } }
            assertEquals(descriptor.id, vm.state.value.activeProviderId)
            assertNull(api.activeConfig())
            advertised.set(true)
            vm.refreshConnections()
            withTimeout(10_000) { vm.state.first { it.sharedDiscoveryWarning == null } }
            withTimeout(10_000) { catalog.states.first { it[descriptor.id] is CatalogState.Loaded } }
            assertEquals(descriptor.id, api.activeConfig()?.providerId)
        } finally { scope.cancel(); root.deleteRecursively() }
    }

    @Test fun `cancelled discovery propagates and does not cache a transient failure`() = runBlocking {
        val root = Files.createTempDirectory("shared-cancelled").toFile()
        val vault = Vault().also { it.entries = listOf(entry()) }
        val store = ProviderCredentialStore(vault, env(root)).also { it.brokeredKeys = broker }
        try {
            vault.onRead = { throw CancellationException("cancelled") }
            assertFailsWith<CancellationException> { store.loadAll() }
            vault.onRead = null
            assertNotNull(store.loadAll().connections[descriptor.id])
            assertEquals(2, vault.reads)
        } finally { root.deleteRecursively() }
    }

    @Test fun `a throwing host broker fails closed without escaping the provider load`() = runBlocking {
        val root = Files.createTempDirectory("shared-broker-throw").toFile()
        val vault = Vault().also { it.entries = listOf(entry()) }
        val store = ProviderCredentialStore(vault, env(root)).also {
            it.brokeredKeys = object : BrokeredKeySource by broker {
                override suspend fun fetch(brokerId: String): Result<BrokeredKey> =
                    throw IllegalStateException("host bridge failed")
            }
        }
        try {
            val snapshot = store.loadAll()
            assertEquals(CredentialSource.NONE, snapshot.connections[descriptor.id]?.source)
            assertEquals("", snapshot.connections[descriptor.id]?.apiKey)
        } finally { root.deleteRecursively() }
    }

    @Test fun `exhausted startup retries do not publish readiness and Refresh recovers`() = runBlocking {
        val root = Files.createTempDirectory("shared-exhausted").toFile()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val vault = Vault().also { it.entries = listOf(entry()) }
        val store = ProviderCredentialStore(vault, env(root)).also { it.brokeredKeys = broker }
        vault.onRead = store::invalidate
        try {
            val vm = AiProvidersViewModel(
                store = store, catalog = ModelCatalog(ModelCatalogClient(QueuedHttpClient(listOf(
                    200 to """{"data":[{"id":"model","is_default":true}]}""",
                )))), prefs = ActiveProviderPrefs(root), legacyImport = null,
                splitViewOperations = null, scope = scope, envResolver = env(root),
                ollamaSystemCheck = noOllamaOnThisMachine(),
            )
            vm.load()
            withTimeout(10_000) { vm.state.first { !it.isLoading && it.error != null } }
            assertFalse(vm.connectionsLoaded.value)
            assertFalse(vm.catalogsLoaded.value)
            assertTrue(vault.reads >= 3)
            vault.onRead = null
            vm.refreshConnections()
            withTimeout(10_000) { vm.catalogsLoaded.first { it } }
            assertTrue(vm.connectionsLoaded.value)
            assertNull(vm.state.value.error)
            assertNotNull(LlmProviderSettingsApiImpl(vm).activeConfig())
            Unit
        } finally { scope.cancel(); root.deleteRecursively() }
    }

    @Test fun `first load retries an invalidated scan without a false storage failure`() = runBlocking {
        val root = Files.createTempDirectory("shared-first-race").toFile()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val gate = CompletableDeferred<Unit>()
        val vault = Vault().also { it.entries = listOf(entry()); it.gate = gate }
        val store = ProviderCredentialStore(vault, env(root)).also { it.brokeredKeys = broker }
        try {
            val vm = AiProvidersViewModel(
                store = store, catalog = ModelCatalog(), prefs = ActiveProviderPrefs(root), legacyImport = null,
                splitViewOperations = null, scope = scope, envResolver = env(root),
                ollamaSystemCheck = noOllamaOnThisMachine(),
            )
            vm.ensureConnectionsLoaded()
            withTimeout(10_000) { vault.entered.await() }
            store.invalidate()
            gate.complete(Unit)
            withTimeout(10_000) { vm.connectionsLoaded.first { it } }
            assertTrue(vm.state.value.storeAvailable)
            assertNotNull(vm.state.value.connections[descriptor.id])
            assertEquals(descriptor.id, vm.state.value.activeProviderId)
        } finally { gate.complete(Unit); scope.cancel(); root.deleteRecursively() }
    }
}
