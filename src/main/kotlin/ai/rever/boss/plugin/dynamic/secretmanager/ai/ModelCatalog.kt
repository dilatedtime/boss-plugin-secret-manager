package ai.rever.boss.plugin.dynamic.secretmanager.ai

import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns per-provider model lists: fetches them from the providers, caches them on
 * disk, and exposes the result as observable state.
 *
 * The cache exists to avoid re-fetching on every panel open, not to paper over a
 * missing fetch — entries carry the time they were retrieved, the UI shows that age,
 * and anything older than [CACHE_TTL_MS] is refreshed on next open. A provider with
 * no credential is reported as [CatalogState.NotConfigured]; it never falls back to
 * a built-in list.
 */
class ModelCatalog(
    private val client: ModelCatalogClient = ModelCatalogClient(),
    private val cacheDir: File? = null,
) {
    private val logger = BossLogger.forComponent("AiModelCatalog")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    /**
     * Serialises cache writes. [writeCacheEntry] is a read-modify-write over one file
     * shared by all providers, and refreshes can overlap (a manual Refresh or Test
     * connection while the open-panel sweep is still running), so an unguarded writer
     * can drop another provider's entry.
     */
    private val cacheMutex = Mutex()

    private val _states = MutableStateFlow<Map<String, CatalogState>>(emptyMap())
    private val generations = ConcurrentHashMap<String, AtomicLong>()

    /** Per-provider catalog state, keyed by [ProviderDescriptor.id]. */
    val states: StateFlow<Map<String, CatalogState>> = _states.asStateFlow()

    private val cacheFile: File? get() = cacheDir?.let { File(it, CACHE_FILE_NAME) }

    /** Current state for one provider, defaulting to not-configured. */
    fun stateOf(providerId: String): CatalogState =
        _states.value[providerId] ?: CatalogState.NotConfigured

    /**
     * Seed state from the on-disk cache so the panel has something to show
     * immediately. Safe to call more than once; never overwrites a live result.
     */
    suspend fun seedFromCache() {
        val cached = readCache() ?: return
        _states.update { current ->
            val seeded = current.toMutableMap()
            cached.providers.forEach { (providerId, entry) ->
                if (isManagedProvider(providerId)) return@forEach
                // Seeding fills an *absence* — any state already present wins, whatever it is.
                //
                // Enumerating states to skip kept leaving holes. Skipping only Loaded let a
                // 401 be papered over: load() seeds on every entry into the section, so Failed
                // was replaced by a within-TTL cached list, isStale then said "fresh", no
                // refetch happened, and the panel showed a working picker for a credential
                // that does not authenticate. Adding Failed still left NotConfigured — which
                // clearKey sets precisely to drop a stale list — and Loading, contradicting
                // this method's own promise never to overwrite a live result.
                if (seeded.containsKey(providerId)) return@forEach
                if (entry.models.isEmpty()) return@forEach
                seeded[providerId] =
                    CatalogState.Loaded(
                        models = entry.models.map { it.toModel() },
                        fetchedAtEpochMs = entry.fetchedAtEpochMs,
                        fromCache = true,
                    )
            }
            seeded
        }
    }

    /** Record that a provider has no usable credential, clearing any stale list. */
    fun markNotConfigured(providerId: String) {
        // Routine sweeps look at every unconfigured provider. Do not turn that bookkeeping into
        // a new credential generation or make it race an unrelated explicit refresh.
        if (_states.value[providerId] is CatalogState.NotConfigured) return
        generations.computeIfAbsent(providerId) { AtomicLong() }.incrementAndGet()
        _states.update { it + (providerId to CatalogState.NotConfigured) }
    }

    /**
     * True when [providerId] has no result, or its result is older than the TTL.
     * Callers use this to refresh on panel open without hammering the providers.
     */
    fun isStale(providerId: String, nowEpochMs: Long): Boolean =
        when (val state = _states.value[providerId]) {
            is CatalogState.Loaded -> nowEpochMs - state.fetchedAtEpochMs >
                if (isManagedProvider(providerId)) SHARED_CACHE_TTL_MS else CACHE_TTL_MS
            // A permanent failure (rejected key) is not stale: nothing changes until the
            // credential does, and the panel re-enters this on every open. Saving a new key
            // calls refresh(force = true), so recovery does not depend on staleness.
            is CatalogState.Failed ->
                !state.permanent && nowEpochMs - state.failedAtEpochMs >= TRANSIENT_FAILURE_RETRY_MS
            null, is CatalogState.NotConfigured -> true
            is CatalogState.Loading -> false
        }

    /**
     * Fetch [descriptor]'s models with [apiKey].
     *
     * When [force] is false a result that is still within the TTL is kept as-is, so
     * opening the panel repeatedly costs nothing. On failure the previous good list
     * is preserved inside [CatalogState.Failed] so the picker keeps working while the
     * UI shows that it is not current.
     */
    suspend fun refresh(
        descriptor: ProviderDescriptor,
        apiKey: String,
        force: Boolean = false,
        nowEpochMs: Long = System.currentTimeMillis(),
    ) {
        val generation = generations.computeIfAbsent(descriptor.id) { AtomicLong() }
        val startedAt = generation.get()
        // A provider that declares it needs no key (a local Ollama daemon) is fetched
        // with a blank one rather than reported as unconfigured forever.
        if (descriptor.requiresApiKey && apiKey.isBlank()) {
            markNotConfigured(descriptor.id)
            return
        }

        if (!force && !isStale(descriptor.id, nowEpochMs)) return

        // A provider that serves a fixed set has nothing to ask. This is not a return
        // to the hardcoded catalogue this class replaced: it covers only the case where
        // the provider itself offers one model to one scoped key, so there is no
        // endpoint and no drift for a fetch to correct. Marked fresh at `nowEpochMs` so
        // the TTL does not make it look stale and retry forever.
        val fixed = ProviderRegistry.fixedModels[descriptor.id]
        if (fixed != null) {
            seatIfCurrent(
                descriptor.id,
                generation,
                startedAt,
                CatalogState.Loaded(models = fixed, fetchedAtEpochMs = nowEpochMs),
            )
            return
        }

        // Carry the previous good list through a *chain* of failures, not just the first.
        // Before Failed survived seeding, re-entry converted it back to Loaded so this cast
        // happened to succeed; now that it survives, `as? Loaded` would yield null on the
        // second attempt — clearing the picker for an offline user who merely opened the
        // section twice, even with a within-TTL list still on disk.
        val lastKnown =
            when (val current = _states.value[descriptor.id]) {
                is CatalogState.Loaded -> current
                is CatalogState.Failed -> current.lastKnown
                else -> null
            }
        if (lastKnown == null) {
            // Check inside StateFlow's CAS update. If invalidation wins before this write,
            // the lambda retries against its NotConfigured state and sees the new generation;
            // a separate check followed by a write still left a race one instruction wide.
            if (!seatIfCurrent(descriptor.id, generation, startedAt, CatalogState.Loading)) return
        }

        val result = client.fetch(descriptor, apiKey)

        result
            .onSuccess { models ->
                val loaded =
                    CatalogState.Loaded(
                        models = models,
                        fetchedAtEpochMs = nowEpochMs,
                        fromCache = false,
                    )
                if (!seatIfCurrent(descriptor.id, generation, startedAt, loaded)) return@onSuccess
                writeCacheEntry(descriptor.id, loaded)
                logger.info(
                    LogCategory.NETWORK,
                    "Fetched AI model list",
                    mapOf("provider" to descriptor.id, "models" to models.size),
                )
            }.onFailure { error ->
                val message = error.message ?: "Could not reach ${descriptor.displayName}."
                val status = (error as? ModelCatalogClient.HttpStatusFailure)?.status
                val permanent = status == 401 || status == 403
                val failed = CatalogState.Failed(message, lastKnown, permanent, nowEpochMs)
                if (!seatIfCurrent(descriptor.id, generation, startedAt, failed)) return@onFailure
                // The message is provider-supplied and status-only by construction —
                // ModelCatalogClient never puts response bodies or keys into it.
                logger.warn(
                    LogCategory.NETWORK,
                    "AI model list fetch failed",
                    mapOf("provider" to descriptor.id, "reason" to message),
                )
            }
    }

    /** Seat a refresh result only while it still belongs to the current credential. */
    private fun seatIfCurrent(
        providerId: String,
        generation: AtomicLong,
        startedAt: Long,
        next: CatalogState,
    ): Boolean {
        var seated = false
        _states.update { current ->
            seated = generation.get() == startedAt
            if (seated) current + (providerId to next) else current
        }
        return seated
    }

    // ==================== disk cache ====================

    private suspend fun readCache(): CachedCatalog? =
        withContext(Dispatchers.IO) {
            val file = cacheFile ?: return@withContext null
            runCatching {
                if (!file.exists()) return@runCatching null
                val parsed = json.decodeFromString<CachedCatalog>(file.readText())
                // Written but previously never checked. A future format change would
                // otherwise be read as if it were the current one.
                if (parsed.version != CACHE_FORMAT_VERSION) null else parsed
            }.onFailure {
                // A corrupt or older-format cache is not worth reporting: it is
                // rebuilt on the next fetch. Deliberately no throwable in the log —
                // decode errors quote surrounding JSON, and this file sits next to
                // credential-adjacent data.
                logger.debug(
                    LogCategory.SYSTEM,
                    "Discarding unreadable AI model cache",
                    mapOf("exception" to (it::class.simpleName ?: "Exception")),
                )
            }.getOrNull()
        }

    private suspend fun writeCacheEntry(
        providerId: String,
        loaded: CatalogState.Loaded,
    ) = cacheMutex.withLock {
        // This catalog carries account-specific permissions and usage. Never persist it.
        if (isManagedProvider(providerId)) return@withLock
        withContext(Dispatchers.IO) {
            val file = cacheFile ?: return@withContext
            runCatching {
                val existing = readCacheBlocking(file)
                val merged =
                    CachedCatalog(
                        providers =
                            existing.providers +
                                (
                                    providerId to
                                        CachedProvider(
                                            models = loaded.models.map { CachedModel.from(it) },
                                            fetchedAtEpochMs = loaded.fetchedAtEpochMs,
                                        )
                                ),
                    )
                file.parentFile?.mkdirs()
                // Write-then-rename: writeText truncates in place, so an interrupted write
                // leaves a partial file, and readCache discards *every* provider's list on
                // a parse failure rather than just the entry being written.
                val temp = File(file.parentFile, "${file.name}.tmp")
                temp.writeText(json.encodeToString(CachedCatalog.serializer(), merged))
                if (!temp.renameTo(file)) {
                    temp.copyTo(file, overwrite = true)
                    temp.delete()
                }
            }.onFailure {
                logger.debug(
                    LogCategory.SYSTEM,
                    "Could not persist AI model cache",
                    mapOf("exception" to (it::class.simpleName ?: "Exception")),
                )
            }
            Unit
        }
    }

    /**
     * The write path's read, and it applies the same version test as [readCache] on purpose.
     *
     * Without it, a format bump laundered the old file back in: the read path correctly
     * discarded a v1 cache, then the first successful fetch merged the new provider into
     * that same parsed v1 map and wrote the result stamped with the current version — so
     * every entry just rejected was accepted on the next open.
     */
    private fun readCacheBlocking(file: File): CachedCatalog =
        runCatching {
            if (!file.exists()) return@runCatching null
            json.decodeFromString<CachedCatalog>(file.readText())
                .takeIf { it.version == CACHE_FORMAT_VERSION }
        }.getOrNull() ?: CachedCatalog(emptyMap())

    // Model lists only — never credentials. Keys live in the secret store.
    @Serializable
    private data class CachedCatalog(
        val providers: Map<String, CachedProvider>,
        val version: Int = CACHE_FORMAT_VERSION,
    )

    @Serializable
    private data class CachedProvider(
        val models: List<CachedModel>,
        val fetchedAtEpochMs: Long,
    )

    @Serializable
    private data class CachedModel(
        val id: String,
        val displayName: String,
        val contextLength: Int? = null,
        val maxOutputTokens: Int? = null,
        val capabilities: List<String> = emptyList(),
        val ownedBy: String? = null,
    ) {
        fun toModel(): AiModel =
            AiModel(
                id = id,
                displayName = displayName,
                contextLength = contextLength,
                maxOutputTokens = maxOutputTokens,
                capabilities = capabilities,
                ownedBy = ownedBy,
            )

        companion object {
            fun from(model: AiModel): CachedModel =
                CachedModel(
                    id = model.id,
                    displayName = model.displayName,
                    contextLength = model.contextLength,
                    maxOutputTokens = model.maxOutputTokens,
                    capabilities = model.capabilities,
                    ownedBy = model.ownedBy,
                )
        }
    }

    companion object {
        /** Model lists are refreshed when older than this. */
        const val CACHE_TTL_MS: Long = 6 * 60 * 60 * 1000L
        /** Account allowances change during use, including when the settings panel is closed. */
        const val SHARED_CACHE_TTL_MS: Long = 60_000L

        /** Transient provider failures back off instead of retrying every consumer poll. */
        const val TRANSIENT_FAILURE_RETRY_MS: Long = 5 * 60 * 1000L

        private const val CACHE_FILE_NAME = "ai-model-catalog.json"
        private const val CACHE_FORMAT_VERSION = 1
    }
}
