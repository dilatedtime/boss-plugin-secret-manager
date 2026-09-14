package ai.rever.boss.plugin.dynamic.secretmanager.ai

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the two things the no-migration story rests on: that [ProviderSettings] survives a
 * JSON round-trip through a secret's notes field, and that garbage in that field degrades
 * to defaults instead of losing the credential it sits next to.
 *
 * Settings ride in `notes` precisely to avoid a database migration, so the format's
 * tolerance is the whole guarantee — and a user can edit that field by hand in the secret
 * list.
 */
class ProviderSettingsSerializationTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `round-trips every field`() {
        val original =
            ProviderSettings(
                selectedModelId = "claude-opus-5",
                customEndpoint = "http://localhost:11434/v1/chat/completions",
                temperature = 0.25f,
                maxTokens = 8192,
            )

        val decoded =
            json.decodeFromString(
                ProviderSettings.serializer(),
                json.encodeToString(ProviderSettings.serializer(), original),
            )

        assertEquals(original, decoded)
    }

    @Test
    fun `an entry written by an older build still reads`() {
        // Every field is defaulted so a note saved before a field existed still decodes;
        // without that, adding a field would orphan existing provider entries.
        val decoded = json.decodeFromString(ProviderSettings.serializer(), """{"selectedModelId":"gpt-5"}""")

        assertEquals("gpt-5", decoded.selectedModelId)
        assertEquals(ProviderConnection.DEFAULT_TEMPERATURE, decoded.temperature)
        assertEquals(ProviderConnection.DEFAULT_MAX_TOKENS, decoded.maxTokens)
    }

    @Test
    fun `unknown fields are ignored rather than failing`() {
        val decoded =
            json.decodeFromString(
                ProviderSettings.serializer(),
                """{"selectedModelId":"gpt-5","fieldFromTheFuture":true}""",
            )
        assertEquals("gpt-5", decoded.selectedModelId)
    }
}

/**
 * Pins the TTL and cache-seeding rules.
 *
 * `nowEpochMs` is injectable, so staleness is testable without waiting six hours — and the
 * freshness label in the panel is only meaningful if these hold.
 */
class ModelCatalogStateTest {
    private fun catalog(): ModelCatalog =
        ModelCatalog(cacheDir = Files.createTempDirectory("catalog").toFile())

    private val descriptor = ProviderRegistry.find(ProviderRegistry.OPENAI)!!

    @Test
    fun `an unknown provider is stale`() {
        assertTrue(catalog().isStale(ProviderRegistry.OPENAI, nowEpochMs = 0))
    }

    /**
     * Seeds a Loaded state from a cache file with a known fetch time.
     *
     * Going through the cache is the only way to establish a Loaded state without a
     * network call, and it exercises the seeding path at the same time.
     */
    private fun catalogSeededAt(fetchedAtEpochMs: Long): Pair<ModelCatalog, File> {
        val dir = Files.createTempDirectory("catalog-seed").toFile()
        File(dir, "ai-model-catalog.json").writeText(
            """
            {"providers":{"${descriptor.id}":{"models":[{"id":"gpt-5","displayName":"GPT-5"}],
            "fetchedAtEpochMs":$fetchedAtEpochMs}},"version":1}
            """.trimIndent(),
        )
        return ModelCatalog(cacheDir = dir) to dir
    }

    @Test
    fun `a result within the TTL is fresh and one past it is stale`() =
        runTest {
            val fetchedAt = 1_000_000L
            val (catalog, _) = catalogSeededAt(fetchedAt)
            catalog.seedFromCache()

            assertTrue(catalog.stateOf(descriptor.id) is CatalogState.Loaded)
            assertFalse(catalog.isStale(descriptor.id, fetchedAt))
            assertFalse(catalog.isStale(descriptor.id, fetchedAt + ModelCatalog.CACHE_TTL_MS))
            assertTrue(catalog.isStale(descriptor.id, fetchedAt + ModelCatalog.CACHE_TTL_MS + 1))
        }

    @Test
    fun `a seeded list is marked as coming from the cache`() =
        runTest {
            // The panel distinguishes "live" from "cached" in its freshness line, so a
            // seeded entry must not claim to be a fresh fetch.
            val (catalog, _) = catalogSeededAt(1_000_000L)
            catalog.seedFromCache()

            val loaded = catalog.stateOf(descriptor.id) as CatalogState.Loaded
            assertTrue(loaded.fromCache)
            assertEquals(listOf("gpt-5"), loaded.models.map { it.id })
        }

    @Test
    fun `a cache written by a different format version is discarded`() =
        runTest {
            val dir = Files.createTempDirectory("catalog-version").toFile()
            File(dir, "ai-model-catalog.json").writeText(
                """{"providers":{"${descriptor.id}":{"models":[{"id":"x","displayName":"X"}],
                "fetchedAtEpochMs":1}},"version":999}""".trimIndent(),
            )
            val catalog = ModelCatalog(cacheDir = dir)
            catalog.seedFromCache()

            assertEquals(CatalogState.NotConfigured, catalog.stateOf(descriptor.id))
        }

    @Test
    fun `a provider with no credential is reported not-configured`() {
        val catalog = catalog()
        catalog.markNotConfigured(descriptor.id)

        assertEquals(CatalogState.NotConfigured, catalog.stateOf(descriptor.id))
        // Not-configured counts as stale so adding a key triggers a fetch immediately.
        assertTrue(catalog.isStale(descriptor.id, nowEpochMs = 0))
    }

    @Test
    fun `refresh with a blank key marks not-configured instead of calling out`() =
        runTest {
            val catalog = catalog()
            catalog.refresh(descriptor, apiKey = "  ", force = true)

            assertEquals(CatalogState.NotConfigured, catalog.stateOf(descriptor.id))
        }

    @Test
    fun `seeding an empty cache leaves state untouched`() =
        runTest {
            val catalog = catalog()
            catalog.seedFromCache()

            assertEquals(CatalogState.NotConfigured, catalog.stateOf(descriptor.id))
        }

    @Test
    fun `a rejected cache version is not laundered back in by the write path`() =
        runTest {
            // The read path discarded a wrong-version file correctly, but the *write* path
            // read that same file without checking, merged the new provider into it, and
            // wrote the result stamped with the current version — so everything just
            // rejected came back on the next open.
            // The stale entry must belong to a *different* provider than the one refreshed:
            // refreshing the same provider replaces its entry on merge, so the laundering
            // would be masked. Here Anthropic's rejected entry is the bystander that used to
            // be rescued by an unrelated OpenAI fetch.
            val bystander = ProviderRegistry.find(ProviderRegistry.ANTHROPIC)!!
            val dir = Files.createTempDirectory("catalog-launder").toFile()
            val cache = File(dir, "ai-model-catalog.json")
            cache.writeText(
                """{"providers":{"${bystander.id}":{"models":[{"id":"stale","displayName":"Stale"}],
                "fetchedAtEpochMs":1}},"version":999}""".trimIndent(),
            )

            // A genuinely successful fetch is required: writeCacheEntry is the read-modify-
            // write that used to launder the rejected file, and only a real result reaches it.
            val fake = QueuedHttpClient(listOf(200 to """{"data":[{"id":"fresh","object":"model"}]}"""))
            val catalog = ModelCatalog(client = ModelCatalogClient(fake), cacheDir = dir)

            catalog.seedFromCache()
            assertEquals(CatalogState.NotConfigured, catalog.stateOf(bystander.id))

            catalog.refresh(descriptor, apiKey = "a-key", force = true)
            assertTrue(catalog.stateOf(descriptor.id) is CatalogState.Loaded, "the fetch did not land")

            val rewritten = cache.readText()
            assertFalse(
                rewritten.contains("stale"),
                "a rejected-version entry was laundered into the current-version cache",
            )
            assertTrue(rewritten.contains("fresh"), "the new result was not cached")
        }

    @Test
    fun `a failed fetch is not papered over by seeding a cached list`() =
        runTest {
            // load() calls seedFromCache on every entry into the section. A Failed state used
            // to be overwritten by a within-TTL cached list, isStale then reported "fresh" so
            // no refetch happened, and the panel showed a working model picker for a
            // credential that does not authenticate — the 401 message simply gone.
            val dir = Files.createTempDirectory("catalog-failed").toFile()
            File(dir, "ai-model-catalog.json").writeText(
                """{"providers":{"${descriptor.id}":{"models":[{"id":"cached","displayName":"Cached"}],
                "fetchedAtEpochMs":1000000}},"version":1}""".trimIndent(),
            )

            val fake = QueuedHttpClient(listOf(401 to """{"error":"bad key"}"""))
            val catalog = ModelCatalog(client = ModelCatalogClient(fake), cacheDir = dir)

            catalog.refresh(descriptor, apiKey = "a-rejected-key", force = true)
            assertTrue(catalog.stateOf(descriptor.id) is CatalogState.Failed, "the 401 did not land")

            // Re-entering the section must not erase the failure.
            catalog.seedFromCache()

            val state = catalog.stateOf(descriptor.id)
            assertTrue(
                state is CatalogState.Failed,
                "seeding replaced a rejected-key failure with a cached list: $state",
            )
            // The message survives, which is the point — the user sees why, instead of a
            // working-looking picker. Staleness is deliberately NOT asserted here: a 401 is
            // classified permanent so it does not re-request on every open. That split is
            // covered by `a rejected key does not re-request on every section open` and
            // `a transient failure stays retryable`.
            assertTrue((state as CatalogState.Failed).message.contains("401"))
        }

    @Test
    fun `two consecutive failures keep the last good list`() =
        runTest {
            // Regression guard for the fix that made Failed survive seeding: `lastKnown` was
            // computed with `as? Loaded`, which had only worked because re-entry converted
            // Failed back to Loaded. Once Failed survived, the second failure yielded
            // lastKnown = null — emptying the picker for an offline user who just opened the
            // section twice. CatalogState.Failed exists precisely so the picker keeps working.
            val fake =
                QueuedHttpClient(
                    listOf(
                        200 to """{"data":[{"id":"gpt-5","object":"model"}]}""",
                        500 to """{"error":"down"}""",
                        500 to """{"error":"still down"}""",
                    ),
                )
            val catalog =
                ModelCatalog(
                    client = ModelCatalogClient(fake),
                    cacheDir = Files.createTempDirectory("catalog-chain").toFile(),
                )

            catalog.refresh(descriptor, apiKey = "k", force = true)
            val loaded = catalog.stateOf(descriptor.id) as CatalogState.Loaded
            assertEquals(listOf("gpt-5"), loaded.models.map { it.id })

            catalog.refresh(descriptor, apiKey = "k", force = true)
            val first = catalog.stateOf(descriptor.id) as CatalogState.Failed
            assertEquals(listOf("gpt-5"), first.lastKnown?.models?.map { it.id })

            catalog.refresh(descriptor, apiKey = "k", force = true)
            val second = catalog.stateOf(descriptor.id) as CatalogState.Failed
            assertEquals(
                listOf("gpt-5"),
                second.lastKnown?.models?.map { it.id },
                "the second consecutive failure dropped the last good list",
            )
        }

    @Test
    fun `seeding never replaces a state that is already present`() =
        runTest {
            // markNotConfigured is how clearKey drops a stale list, so a cached list must not
            // come back over it — the panel would show models for a provider with no
            // credential until a paginated store read finished.
            val dir = Files.createTempDirectory("catalog-notconfigured").toFile()
            File(dir, "ai-model-catalog.json").writeText(
                """{"providers":{"${descriptor.id}":{"models":[{"id":"cached","displayName":"Cached"}],
                "fetchedAtEpochMs":1000000}},"version":1}""".trimIndent(),
            )

            val catalog = ModelCatalog(cacheDir = dir)
            catalog.markNotConfigured(descriptor.id)
            catalog.seedFromCache()

            assertEquals(
                CatalogState.NotConfigured,
                catalog.stateOf(descriptor.id),
                "seeding overwrote a deliberate NotConfigured",
            )
        }

    @Test
    fun `a rejected key does not re-request on every section open`() =
        runTest {
            // load() runs from a LaunchedEffect on every entry into the section and isStale
            // treats a failure as stale, so a revoked key would send an auth-failure request to
            // the provider every single visit — which some providers act on. A 401 cannot
            // succeed until the credential changes, and saving a new key calls refresh(force),
            // so recovery does not depend on staleness.
            val fake = QueuedHttpClient(listOf(401 to """{"error":"revoked"}"""))
            val catalog =
                ModelCatalog(
                    client = ModelCatalogClient(fake),
                    cacheDir = Files.createTempDirectory("catalog-permanent").toFile(),
                )

            catalog.refresh(descriptor, apiKey = "revoked-key", force = true)
            val failed = catalog.stateOf(descriptor.id) as CatalogState.Failed
            assertTrue(failed.permanent, "a 401 was not classified as permanent")
            assertFalse(catalog.isStale(descriptor.id, nowEpochMs = Long.MAX_VALUE))
        }

    @Test
    fun `a transient failure stays retryable`() =
        runTest {
            // The other half: offline and 5xx genuinely might succeed next time, so those must
            // keep refreshing on open.
            val fake = QueuedHttpClient(listOf(503 to """{"error":"maintenance"}"""))
            val catalog =
                ModelCatalog(
                    client = ModelCatalogClient(fake),
                    cacheDir = Files.createTempDirectory("catalog-transient").toFile(),
                )

            val failedAt = 10_000L
            catalog.refresh(descriptor, apiKey = "good-key", force = true, nowEpochMs = failedAt)
            val failed = catalog.stateOf(descriptor.id) as CatalogState.Failed
            assertFalse(failed.permanent, "a 503 was treated as permanent")
            assertFalse(catalog.isStale(descriptor.id, failedAt + ModelCatalog.TRANSIENT_FAILURE_RETRY_MS - 1))
            assertTrue(catalog.isStale(descriptor.id, failedAt + ModelCatalog.TRANSIENT_FAILURE_RETRY_MS))
        }

    @Test
    fun `the TTL boundary is six hours`() {
        // Documented as six hours and shown to the user as an age; pin it so a change is
        // deliberate.
        assertEquals(6 * 60 * 60 * 1000L, ModelCatalog.CACHE_TTL_MS)
    }
}

/**
 * Pins the provider registry's invariants.
 *
 * Ids are persisted in secrets and handed out as `LlmConfig.providerId`, so a rename is a
 * silent data break — and the default is derived from list order rather than named, which
 * only works if the list is non-empty and unique.
 */
class ProviderRegistryTest {
    @Test
    fun `ids are unique`() {
        val ids = ProviderRegistry.all.map { it.id }
        assertEquals(ids.size, ids.distinct().size, "duplicate provider ids: $ids")
    }

    @Test
    fun `the default is the first entry`() {
        assertEquals(ProviderRegistry.all.first().id, ProviderRegistry.default.id)
    }

    @Test
    fun `an unknown id falls back to the default rather than throwing`() {
        assertEquals(ProviderRegistry.default.id, ProviderRegistry.findOrDefault("NOT_A_PROVIDER").id)
        assertEquals(ProviderRegistry.default.id, ProviderRegistry.findOrDefault(null).id)
    }

    @Test
    fun `every provider names a standard key that looks like an env var`() {
        ProviderRegistry.all.forEach { descriptor ->
            val name = descriptor.standardKeyName
            assertTrue(
                name.matches(Regex("[A-Z0-9_]+")),
                "${descriptor.id} standard key name is not env-var shaped: $name",
            )
        }
    }

    @Test
    fun `a provider without a models endpoint has another way to name a model`() {
        // The whole feature rests on live lists, so what actually matters is that no
        // provider ends up with no models at all. Two ways out of needing an endpoint:
        // CUSTOM, where the user types the model id because nothing authoritative
        // exists to ask; and a provider serving a fixed set, where there is nothing to
        // ask because the answer cannot drift. Anything else silently has no models.
        val unexplained =
            ProviderRegistry.all
                .filter { it.modelsEndpoint == null }
                .filterNot { it.id == ProviderRegistry.CUSTOM }
                .filterNot { ProviderRegistry.fixedModels[it.id]?.isNotEmpty() == true }
                .map { it.id }

        assertEquals(emptyList(), unexplained)
    }

    @Test
    fun `a fixed model list belongs only to a provider with no endpoint to ask`() {
        // The reverse guard, and the one that stops this becoming the hardcoded
        // catalogue the live fetch replaced: pinning models for a provider that has an
        // endpoint would reintroduce exactly that drift.
        val withEndpoint =
            ProviderRegistry.fixedModels.keys
                .mapNotNull { ProviderRegistry.find(it) }
                .filter { it.modelsEndpoint != null }
                .map { it.id }

        assertEquals(emptyList(), withEndpoint)
    }

    @Test
    fun `a brokered provider stores no key and advertises no environment variable`() {
        // Both would put a minted, short-lived credential somewhere durable. envVarNames
        // also drives the secret entry's name, so a non-empty list here is the path by
        // which a brokered credential would end up written to the store.
        val brokered = ProviderRegistry.all.filter { it.brokerId != null }
        assertTrue(brokered.isNotEmpty(), "the RISA GLM provider should be registered")

        brokered.forEach { descriptor ->
            assertEquals(emptyList(), descriptor.envVarNames, "${descriptor.id} must not read an env var")
            assertEquals("", descriptor.keyPlaceholder, "${descriptor.id} must not prompt for a key")
            assertNull(descriptor.consoleUrl, "${descriptor.id} has no key page to send anyone to")
        }
    }

    @Test
    fun `a fixed-model provider reports known models and needs no manual entry`() {
        // The bug this pins: the ViewModel and the panel each tested `modelsEndpoint == null`
        // independently, so a provider serving a fixed set was treated as one nobody can ask.
        // Its catalogue was never populated and the panel offered an endpoint-and-model-id
        // form for a provider that has neither.
        val risa = ProviderRegistry.find(ProviderRegistry.RISA_GLM)!!

        assertTrue(ProviderRegistry.hasKnownModels(risa))
        assertFalse(ProviderRegistry.needsManualModel(risa))

        // CUSTOM is the one that genuinely needs manual entry.
        val custom = ProviderRegistry.find(ProviderRegistry.CUSTOM)!!
        assertTrue(ProviderRegistry.needsManualModel(custom))
    }

    @Test
    fun `every provider either has known models or needs manual entry, never neither`() {
        ProviderRegistry.all.forEach { descriptor ->
            assertTrue(
                ProviderRegistry.hasKnownModels(descriptor) || ProviderRegistry.needsManualModel(descriptor),
                "${descriptor.id} would have no way to name a model",
            )
        }
    }

    @Test
    fun `a fixed list seats as a loaded catalogue rather than staying not-configured`() =
        runTest {
            // The branch added for fixed models was unreachable, because both call sites
            // returned early first. This asserts the seating itself.
            val catalog = ModelCatalog(cacheDir = null)
            val risa = ProviderRegistry.find(ProviderRegistry.RISA_GLM)!!

            catalog.refresh(risa, apiKey = "sk-brokered", force = false)

            val state = catalog.stateOf(risa.id)
            assertTrue(state is CatalogState.Loaded, "expected Loaded, was $state")
            assertEquals(listOf("coreweave-glm-5-2"), (state as CatalogState.Loaded).models.map { it.id })
        }

    @Test
    fun `the default provider is not an organisation-only one`() {
        // `default` is all.first(), so ordering decides what a user who has never chosen
        // sees selected. A brokered provider can never resolve a credential outside the
        // organisation that runs its broker, so leading with one would ship every other
        // user a default that cannot work.
        assertNull(ProviderRegistry.default.brokerId)
    }

    @Test
    fun `google puts the model in the path and others do not`() {
        val google = ProviderRegistry.find(ProviderRegistry.GOOGLE)!!
        assertTrue(google.needsModelInEndpoint)
        assertTrue(google.chatEndpointFor("gemini-3-pro").endsWith("/models/gemini-3-pro:generateContent"))

        val openai = ProviderRegistry.find(ProviderRegistry.OPENAI)!!
        assertFalse(openai.needsModelInEndpoint)
        assertFalse(openai.chatEndpointFor("gpt-5").contains("gpt-5"))
    }

    @Test
    fun `openrouter and ollama speak the openai-compatible wire format`() {
        // Both ride the OPENAI_CHAT adapter the gateway plugin already implements —
        // adding a provider here is a registry entry, not a new wire format.
        val openRouter = ProviderRegistry.find(ProviderRegistry.OPENROUTER)!!
        val ollama = ProviderRegistry.find(ProviderRegistry.OLLAMA)!!

        assertEquals(WireFormat.OPENAI_CHAT, openRouter.wireFormat)
        assertEquals(WireFormat.OPENAI_CHAT, ollama.wireFormat)
        assertEquals(CredentialTransport.BEARER_HEADER, openRouter.credentialTransport)
        assertEquals(CredentialTransport.BEARER_HEADER, ollama.credentialTransport)
    }

    @Test
    fun `ollama is the only provider besides custom that needs no key`() {
        val keyless = ProviderRegistry.all.filterNot { it.requiresApiKey }.map { it.id }
        assertEquals(listOf(ProviderRegistry.OLLAMA), keyless)

        // OpenRouter is a real hosted API behind its own key, unlike the local daemon
        // next to it in the registry — pin that the two are not accidentally conflated.
        assertTrue(ProviderRegistry.find(ProviderRegistry.OPENROUTER)!!.requiresApiKey)
    }
}

/**
 * Pins [ProviderConnection.isConfigured] for the one provider that needs no credential
 * at all, alongside the ordinary key-bearing case it must not disturb.
 */
class ProviderConnectionConfiguredTest {
    @Test
    fun `a keyless provider is configured with a blank key`() {
        val connection = ProviderConnection(providerId = ProviderRegistry.OLLAMA, apiKey = "", source = CredentialSource.NONE)
        assertTrue(connection.isConfigured)
    }

    @Test
    fun `an ordinary provider still needs a key`() {
        val connection = ProviderConnection(providerId = ProviderRegistry.OPENROUTER, apiKey = "", source = CredentialSource.NONE)
        assertFalse(connection.isConfigured)

        val withKey = connection.copy(apiKey = "sk-or-v1-abc")
        assertTrue(withKey.isConfigured)
    }
}
