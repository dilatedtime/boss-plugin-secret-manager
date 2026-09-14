package ai.rever.boss.plugin.dynamic.secretmanager.ai

import ai.rever.boss.plugin.api.CreateSecretRequestData
import ai.rever.boss.plugin.api.SecretDataProvider
import ai.rever.boss.plugin.api.SecretEntryData
import ai.rever.boss.plugin.api.UpdateSecretRequestData
import ai.rever.boss.plugin.dynamic.secretmanager.SecretAccess
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Every provider's effective credential, plus whether the secret store could be read.
 *
 * Not a `Result`: environment-supplied keys work even when the store is unreachable, so
 * the connections are always usable and the failure is a separate fact rather than a
 * reason to throw the map away.
 */
data class ConnectionsSnapshot(
    val connections: Map<String, ProviderConnection>,
    val storeReadFailed: Boolean,
    val descriptors: List<ProviderDescriptor> = ProviderRegistry.all,
    val sharedDiscoveryWarning: String? = null,
    val sharedDiscoveryComplete: Boolean = true,
    val invalidatedDuringLoad: Boolean = false,
)

/**
 * Stores AI provider credentials and per-provider settings as ordinary secrets, so
 * they inherit the encryption, row-level security and audit trail the secret store
 * already provides instead of sitting in a plaintext file.
 *
 * Mapping onto the secret schema:
 * - `website`  → the provider id (`ANTHROPIC`, `OPENAI`, …)
 * - `username` → the provider's standard key name (`TOGETHER_API_KEY`, …), which is the
 *                secret list's "Key Name" column — the same name as the environment
 *                variable that would supply this key, so both paths read alike
 * - `password` → the API key
 * - `tags`     → [TAG_AI_PROVIDER] (provider configuration, for this plugin),
 *                [TAG_API_KEY] (renders as an API key card, not a password), and the
 *                provider id
 * - `notes`    → JSON holding the selected model, custom endpoint and generation
 *                defaults. The schema has no free-form JSON column, and adding one
 *                would mean a database migration for settings that are only ever
 *                read back by this plugin.
 *
 * Resolution precedence is environment → stored secret → unconfigured. A key found in
 * the environment is never written here.
 *
 * Mutations are serialised through [mutex]: each one is a read-modify-write over the
 * whole store, and the settings panel and the Secret Manager dialog drive this from
 * separate ViewModels with separate busy flags. Two interleaved saves for one provider
 * would otherwise both observe "no existing secret", both create one, and leave a
 * duplicate whose winner is decided arbitrarily on the next read.
 */
class ProviderCredentialStore(
    private val secrets: SecretDataProvider,
    private val envResolver: EnvResolver = EnvResolver(),
    /**
     * How long after a failed mint the read path may try again.
     *
     * A parameter rather than a constant so the retry is testable: hard-coded, any test of it had
     * to outwait it, which is how an untested guard ends up wrong.
     */
    private val mintRetryBackoffMs: Long = DEFAULT_MINT_RETRY_BACKOFF_MS,
    private val monotonicNanos: () -> Long = System::nanoTime,
    private val bossAiDiscovery: BossAiDiscovery = BossAiDiscovery(),
) {
    private val logger = BossLogger.forComponent("AiCredentialStore")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val mutex = Mutex()

    /**
     * Supplies credentials for providers that have a broker instead of a key.
     *
     * Set after construction because the broker is an api type reachable only inside
     * the plugin's `LinkageError` guard, while this store is built outside it. Null
     * means brokered providers resolve as unconfigured, which is also the right answer
     * on a host with no broker.
     */
    @Volatile
    var brokeredKeys: BrokeredKeySource? = null

    /**
     * Live brokered credentials, in memory only.
     *
     * Never written to the secret store. A minted credential expires within hours and
     * is cheap to re-obtain, so persisting it would trade a credential that self-heals
     * for one that leaks - the same reasoning that keeps environment-supplied keys off
     * disk, and the defect this feature's predecessor shipped.
     */
    private val brokeredCache = java.util.concurrent.ConcurrentHashMap<String, CachedBrokeredKey>()

    private class CachedBrokeredKey(
        val token: String,
        val reuseUntilMs: Long,
    )

    /**
     * When a mint last failed, per broker.
     *
     * A failed mint is deliberately **not** cached as a credential - a user who signs in must be
     * able to retry without anything clearing the cache first - but removing the entry and
     * calling that "not lapsed" made failure terminal on the read path: nothing calls `loadAll`
     * again there, so a single network blip left the provider unconfigured until the panel was
     * opened. A recorded failure time gives bounded retry instead.
     *
     * Note this cannot be expressed as a blank-token cache entry: `resolveBrokered` would serve
     * it while the deadline was in the future.
     */
    private val lastMintFailureNanos = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Brokers whose reuse window is currently being shortened by the expiry, so the log line
     * fires on the transition rather than on every mint.
     *
     * In the case that motivated this cap the condition is *constant* and the read path can mint
     * every few seconds, which would be hundreds of identical INFO lines an hour.
     */
    private val cappedBrokers = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Per-broker mint lock, created on first use. */
    private val brokeredMintLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    private fun brokeredMintLock(brokerId: String): Mutex =
        brokeredMintLocks.computeIfAbsent(brokerId) { Mutex() }

    /**
     * The model id to report for [descriptor], honouring a fixed list where one exists.
     *
     * A provider with no fixed list keeps whatever was stored, including null.
     */
    private fun resolveModelId(
        descriptor: ProviderDescriptor,
        stored: String?,
    ): String? {
        val fixed = ProviderRegistry.fixedModels[descriptor.id] ?: return stored
        return stored?.takeIf { candidate -> fixed.any { it.id == candidate } } ?: fixed.firstOrNull()?.id
    }

    /**
     * Last known provider secrets, so a save doesn't re-page the whole store three
     * times. Cleared by every mutation and by [invalidate].
     */
    @Volatile
    private var cached: Map<String, StoredProvider>? = null

    private data class SharedDefinitions(
        val descriptors: List<ProviderDescriptor>,
        val warning: String? = null,
        val stored: Map<String, StoredProvider> = emptyMap(),
        val removalAuthoritative: Boolean = true,
        val usedSharingSnapshot: Boolean = false,
    )

    private data class CachedSharedDefinitions(
        val result: Result<SharedDefinitions>,
        val generation: Long,
        val fetchedAtNanos: Long,
    )

    private val sharedMutex = Mutex()
    @Volatile private var cachedShared: CachedSharedDefinitions? = null

    private data class CachedBossAi(
        val result: Result<ProviderDescriptor>,
        val scope: String?,
        val generation: Long,
        val fetchedAtNanos: Long,
    )
    private val bossAiMutex = Mutex()
    @Volatile private var cachedBossAi: CachedBossAi? = null

    private suspend fun loadBossAi(): Result<ProviderDescriptor>? = bossAiMutex.withLock {
        val broker = brokeredKeys?.takeIf { it.discoversBossAi } ?: return@withLock null
        val startedAt = generation.get()
        val scope = broker.bossAiScope()
        cachedBossAi?.takeIf {
            it.scope == scope && it.generation == startedAt && monotonicNanos() - it.fetchedAtNanos <
                (if (it.result.isSuccess) SHARED_DISCOVERY_TTL_NANOS else SHARED_RETRY_NANOS)
        }?.let { return@withLock it.result }
        val result = when {
            scope == null -> Result.failure(IllegalStateException("BOSS AI authentication is unavailable."))
            !SharedProviderDefinition.withinScope(scope, scope) ->
                Result.failure(IllegalStateException("BOSS AI is unavailable within its trusted API scope."))
            else -> {
                val token = resolveBrokered(BossAiDiscovery.BROKER_ID)
                if (token.isBlank()) Result.failure(IllegalStateException("Sign in to BOSS and retry BOSS AI using Refresh."))
                else bossAiDiscovery.fetch(scope, token)
            }
        }
        if (generation.get() == startedAt) cachedBossAi = CachedBossAi(result, scope, startedAt, monotonicNanos())
        result
    }

    /** A manual discovery refresh is not a sign-out and must not discard minted keys. */
    suspend fun expireSharedDefinitions() {
        sharedMutex.withLock { cachedShared = null }
        bossAiMutex.withLock { cachedBossAi = null }
    }

    fun sharedDefinitionsStale(): Boolean {
        if (brokeredKeys?.discoversBossAi == true) {
            val cache = cachedBossAi
            if (cache == null || cache.generation != generation.get() ||
                monotonicNanos() - cache.fetchedAtNanos >=
                (if (cache.result.isSuccess) SHARED_DISCOVERY_TTL_NANOS else SHARED_RETRY_NANOS)) return true
        }
        if (brokeredKeys?.canDiscoverSharedProviders() != true) return false
        val cache = cachedShared ?: return true
        val ttl = if (cache.result.isSuccess) SHARED_DISCOVERY_TTL_NANOS else SHARED_RETRY_NANOS
        return cache.generation != generation.get() || monotonicNanos() - cache.fetchedAtNanos >= ttl
    }

    /**
     * Bumped by every [invalidate]; a load only seats its result if the generation it
     * started in is still current.
     */
    private val generation = java.util.concurrent.atomic.AtomicLong(0)

    /**
     * Bumped on every [invalidate] so holders of a derived snapshot can re-read.
     *
     * Clearing this class's own cache is necessary but not sufficient: `AiProvidersViewModel`
     * keeps its own `connections` map and `activeConfig()` answers other plugins from that,
     * so without a signal a secret deleted from the list kept being served for the session
     * unless the user happened to open Settings → AI Providers.
     */
    val invalidations: StateFlow<Long> get() = _invalidations.asStateFlow()

    private val _invalidations = MutableStateFlow(0L)

    /** Drop the cached provider map, e.g. after an external edit in the secret list. */
    fun invalidate() {
        // Order matters: bump first, so a load that is already paging cannot seat a map it
        // read before the deletion. Clearing alone left a window where an in-flight
        // loadStoredSecrets re-seated the pre-delete map and resurrected a deleted
        // provider for the rest of the session — the exact thing invalidate() prevents.
        generation.incrementAndGet()
        cached = null
        cachedShared = null
        cachedBossAi = null
        // Brokered credentials go too. Sign-out is one of the things that invalidates,
        // and a credential minted for the previous session must not outlive it.
        brokeredCache.clear()
        lastMintFailureNanos.clear()
        cappedBrokers.clear()
        _invalidations.value = _invalidations.value + 1
    }

    /** Read every provider's effective connection. */
    suspend fun loadAll(): ConnectionsSnapshot {
        val startedAt = generation.get()
        val bossAi = loadBossAi()
        val shared = loadSharedDefinitions()
        // The sharing RPC is a superset of the owned-secret list. A successful shared
        // scan therefore supplies both views and avoids a second full-vault traversal.
        // On failure, fall back to the narrower API so personal credentials still work.
        val storedResult = if (shared.getOrNull()?.usedSharingSnapshot == true) {
            Result.success(shared.getOrThrow().stored)
        } else {
            loadStoredSecrets()
        }
        val stored = storedResult.getOrElse { emptyMap() }
        val automatic = if (bossAi != null) listOf(bossAi.getOrElse { BossAiDiscovery.unavailableDescriptor() })
            else emptyList()
        // A legacy shared BOSS definition must not duplicate the automatically discovered provider.
        val sharedDescriptors = shared.getOrNull()?.descriptors.orEmpty().filterNot {
            bossAi != null && it.brokerId == BossAiDiscovery.BROKER_ID
        }
        val descriptors = automatic + ProviderRegistry.all + sharedDescriptors

        val connections =
            descriptors.associate { descriptor ->
                descriptor.id to if (descriptor.id == BossAiDiscovery.PROVIDER_ID && bossAi?.isSuccess != true) {
                    ProviderConnection(descriptor.id, "", CredentialSource.NONE)
                } else resolveConnection(descriptor, stored[descriptor.id])
            }

        // Preserve the pre-existing direct-caller contract for static providers. The
        // ViewModel's reload path additionally guards the whole snapshot against invalidation.
        if (startedAt != generation.get()) return ConnectionsSnapshot(
            connections = connections.filterKeys { !isManagedProvider(it) },
            storeReadFailed = storedResult.isFailure,
            invalidatedDuringLoad = true,
            sharedDiscoveryComplete = false,
        )

        return ConnectionsSnapshot(
            connections = connections,
            storeReadFailed = storedResult.isFailure,
            descriptors = descriptors,
            sharedDiscoveryComplete = shared.isSuccess && shared.getOrNull()?.removalAuthoritative == true,
            sharedDiscoveryWarning = listOfNotNull(
                bossAi?.exceptionOrNull()?.message,
                if (shared.isFailure) "Shared AI providers could not be refreshed. Retry using Refresh."
                else shared.getOrNull()?.warning,
            ).takeIf { it.isNotEmpty() }?.joinToString(" "),
        )
    }

    private suspend fun loadSharedDefinitions(): Result<SharedDefinitions> = sharedMutex.withLock {
        val broker = brokeredKeys
        if (broker?.canDiscoverSharedProviders() != true) {
            return@withLock Result.success(SharedDefinitions(emptyList()))
        }
        val startedAt = generation.get()
        cachedShared?.takeIf {
            it.generation == startedAt && monotonicNanos() - it.fetchedAtNanos <
                (if (it.result.isSuccess) SHARED_DISCOVERY_TTL_NANOS else SHARED_RETRY_NANOS)
        }?.let { return@withLock it.result }
        val result = try {
            Result.success(scanSharedDefinitions(broker))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
        if (generation.get() == startedAt) {
            cachedShared = CachedSharedDefinitions(result, startedAt, monotonicNanos())
            result.getOrNull()?.let { cached = it.stored }
        }
        result
    }

    private suspend fun scanSharedDefinitions(broker: BrokeredKeySource): SharedDefinitions {
        val found = mutableMapOf<String, ProviderDescriptor>()
        val stored = mutableMapOf<String, StoredProvider>()
        var offset = 0
        var warning: String? = null
        var removalAuthoritative = true
        while (true) {
            val page = secrets.getUserSecretsWithSharingInfo(limit = PAGE_SIZE, offset = offset).getOrThrow()
            for (entry in page.data) {
                if ((entry.accessLevel.equals(SecretAccess.OWNER, ignoreCase = true) ||
                        entry.accessLevel.equals(SecretAccess.ORG, ignoreCase = true)) &&
                    entry.tags.contains(TAG_AI_PROVIDER)) {
                    val providerId = providerIdOf(entry.website, entry.tags) ?: continue
                    stored.putIfAbsent(
                        providerId,
                        toStored(providerId, entry.id, entry.password, entry.username, entry.notes),
                    )
                }
                if (!entry.tags.contains(SharedProviderDefinition.TAG)) continue
                val definition = SharedProviderDefinition.parse(entry.notes) ?: continue
                val sharedBy = entry.sharedByEmail?.filterNot(Char::isISOControl)?.trim()?.take(100)
                val provenance = when {
                    entry.accessLevel.equals(SecretAccess.OWNER, ignoreCase = true) -> SharedProviderProvenance.OWNED
                    entry.accessLevel.equals(SecretAccess.ORG, ignoreCase = true) -> SharedProviderProvenance.ORGANISATION
                    else -> SharedProviderProvenance.DIRECT_SHARE
                }
                val source = when (provenance) {
                    SharedProviderProvenance.OWNED -> "Managed from your vault"
                    SharedProviderProvenance.ORGANISATION -> "Managed by your organisation"
                    SharedProviderProvenance.DIRECT_SHARE ->
                        if (sharedBy.isNullOrBlank()) "Shared with you" else "Shared by $sharedBy"
                }
                val descriptor = definition.descriptor(entry.id, source, provenance)
                val modelsEndpoint = descriptor.modelsEndpoint ?: continue
                if (!broker.permitsEndpoints(
                        definition.brokerId,
                        listOf(descriptor.chatEndpoint, modelsEndpoint),
                    )) {
                    // Scope refusal can mean an unavailable host broker, not vault revocation.
                    // Omit credentials, but do not clear the user's selected id based on this scan.
                    warning = "Some shared AI definitions are unavailable within this host's broker scopes."
                    removalAuthoritative = false
                    continue
                }
                found.putIfAbsent(descriptor.id, descriptor)
            }
            if (!page.hasMore || page.data.isEmpty()) break
            offset += page.data.size
            if (offset >= MAX_SCANNED) {
                warning = "Shared AI discovery reached its scan limit; some providers may not be listed."
                removalAuthoritative = false
                logger.warn(LogCategory.NETWORK, warning)
                break
            }
        }
        if (found.size > MAX_SHARED_PROVIDERS) {
            warning = "Shared AI discovery reached its provider limit; some providers may not be listed."
        }
        return SharedDefinitions(
            descriptors = found.values.sortedBy { it.id }.take(MAX_SHARED_PROVIDERS),
            warning = warning,
            stored = stored.toMap(),
            // A provider over the admission cap is unavailable by policy, not unknown.
            removalAuthoritative = removalAuthoritative,
            usedSharingSnapshot = true,
        )
    }

    /**
     * Environment first, then the stored secret. Settings (selected model, endpoint,
     * generation defaults) always come from the stored entry when there is one, even
     * if the key itself came from the environment — the user still gets to pick a
     * model for a provider they authenticate through an env var.
     */
    private suspend fun resolveConnection(
        descriptor: ProviderDescriptor,
        stored: StoredProvider?,
    ): ProviderConnection {
        val settings = stored?.settings ?: ProviderSettings()
        val envKey = envResolver.resolve(descriptor.envVarNames)
        val brokerId = descriptor.brokerId

        val (key, source) =
            when {
                // A broker takes precedence over everything for a provider that has
                // one: there is no key to store or export, so nothing can shadow it.
                brokerId != null -> resolveBrokered(brokerId) to CredentialSource.BROKERED
                !envKey.isNullOrBlank() -> envKey to CredentialSource.ENVIRONMENT
                !stored?.apiKey.isNullOrBlank() -> stored.apiKey to CredentialSource.STORED
                else -> "" to CredentialSource.NONE
            }

        return ProviderConnection(
            providerId = descriptor.id,
            // A broker that could not mint is unconfigured, not brokered-with-no-key:
            // `isConfigured` reads the key, and a BROKERED source with a blank one
            // would offer the provider as usable and fail on the first request.
            apiKey = key,
            source = if (source == CredentialSource.BROKERED && key.isBlank()) CredentialSource.NONE else source,
            // A provider serving a fixed set has its selection constrained to that set.
            // Not just defaulted: a stored id that is not in the list can only have come
            // from a stale prefs entry or a hand-typed value, and letting it win durably
            // replaces the one model the provider actually serves with no picker to
            // correct it from. Leaving it null instead makes activeConfig() return null
            // forever, so the first fixed model is the fallback.
            selectedModelId = resolveModelId(descriptor, settings.selectedModelId),
            customEndpoint = settings.customEndpoint,
            temperature = settings.temperature,
            maxTokens = settings.maxTokens,
            label =
                when (source) {
                    CredentialSource.ENVIRONMENT -> envResolver.resolveSourceName(descriptor.envVarNames)
                    CredentialSource.STORED -> stored?.label
                    CredentialSource.BROKERED -> if (key.isBlank()) null else "Signed in to BOSS"
                    CredentialSource.NONE -> null
                },
        )
    }

    /**
     * A live brokered credential, minted only when the cached one has aged out.
     *
     * Returns blank rather than failing when there is no broker on this host or the
     * exchange fails: the caller's contract is "unconfigured provider", which the panel
     * already knows how to present. The reason is logged, because "RISA Codex GLM says
     * not configured" is otherwise unexplainable.
     */
    private suspend fun resolveBrokered(brokerId: String): String {
        val source = brokeredKeys ?: return ""
        brokeredCache[brokerId]?.takeIf { it.reuseUntilMs > System.currentTimeMillis() }?.let {
            return it.token
        }

        // One mint per broker at a time. Two concurrent loadAll() calls both miss the
        // cache and both mint otherwise, which is exactly what "Check access" does: it
        // invalidates and reloads while the invalidations collector reloads too. The
        // second caller re-checks the cache inside the lock and finds the first one's
        // result.
        return brokeredMintLock(brokerId).withLock {
            brokeredCache[brokerId]?.takeIf { it.reuseUntilMs > System.currentTimeMillis() }?.let {
                return@withLock it.token
            }
            mintBrokered(source, brokerId)
        }
    }

    /**
     * When the soonest-expiring cached brokered credential stops being reusable, or null when
     * none is cached.
     *
     * Exists so a renewal can be *scheduled* rather than waited for. Everything else on this path
     * is reactive: the credential is replaced only once a read notices it is already dead, which
     * costs the user a failed request every time one expires while the app is running.
     */
    /**
     * Drop cached brokered credentials so the next resolve mints a fresh one.
     *
     * Needed because a renewal that only calls [loadAll] renews nothing: [resolveBrokered] returns
     * the cached token while its reuse window is open, so a scheduled reload before the deadline
     * hands back the *same* credential and the whole point - replacing it before anything is
     * rejected - is lost. Narrower than [invalidate], which bumps the generation to disown a
     * signed-out session's secrets; this only says "the key is old", not "the session changed".
     */
    fun expireBrokeredCache() {
        brokeredCache.clear()
    }

    fun nextBrokeredReuseDeadline(): Long? = brokeredCache.values.minOfOrNull { it.reuseUntilMs }

    /**
     * Whether a brokered credential is cached but no longer reusable.
     *
     * The cap in [reuseUntil] only bites when something calls [loadAll], and on the path that
     * matters nothing does: `LlmProviderSettingsApiImpl.activeConfig` reads a snapshot the
     * view model loaded once, so between a panel visit and a secret edit a consumer keeps
     * being handed whatever token that snapshot captured. This lets the read path notice.
     *
     * False when nothing is cached: there is no stale credential to replace, and the load
     * path already mints on demand. Answering true there would turn every read before the
     * first successful mint into another reload.
     */
    fun brokeredCredentialLapsed(brokerId: String): Boolean {
        val now = System.currentTimeMillis()
        brokeredCache[brokerId]?.let { return it.reuseUntilMs <= now }
        // Nothing cached. True only when a mint failed long enough ago to be worth retrying:
        // answering true unconditionally would hammer the broker before the first success, and
        // answering false always made a failure terminal on this path.
        // nanoTime, not the wall clock: this measures an elapsed duration, and a clock that
        // steps backwards (VM resume, first NTP sync, a manual change) would make the difference
        // negative and hold the retry off for the length of the step - re-creating this exact
        // wedge from a different cause. The expiry comparison above genuinely needs wall time,
        // because it is checked against an absolute timestamp from the broker.
        val failedAt = lastMintFailureNanos[brokerId] ?: return false
        return System.nanoTime() - failedAt >= mintRetryBackoffMs * NANOS_PER_MILLI
    }

    /**
     * When a freshly minted credential stops being reusable.
     *
     * The broker's own reuse window, **capped by the credential's expiry**. Trusting the
     * window alone wedges every request for its duration when a broker reports a window
     * that outlives its key: that is not hypothetical - RISA's gateway did exactly that,
     * and LLM RPA failed with `401 Expired Key` on a cached token for eleven minutes,
     * re-sending the same dead key every time because nothing re-minted until the window
     * lapsed.
     *
     * A margin comes off the expiry because a credential that dies mid-flight is a failed
     * request either way, and re-minting is cheap next to that - the per-broker mint lock
     * already stops a stampede. A window that computes to zero or less simply means "mint
     * every time", which is correct rather than degraded.
     *
     * An unparseable or absent expiry falls back to the window as reported, which is the
     * behaviour before this cap existed.
     */
    private fun reuseUntil(
        brokerId: String,
        credential: BrokeredKey,
    ): Long {
        val now = System.currentTimeMillis()
        // coerceIn, not coerceAtLeast: an absurd reported window overflows the multiply and
        // wraps the sum negative, which would read as "already lapsed" forever.
        val window =
            now + credential.refreshAfterSeconds.coerceIn(0, MAX_REUSE_SECONDS) * MILLIS_PER_SECOND
        val expiry = credential.expiresAt?.let(::expiryMillis) ?: return window
        val capped = minOf(window, expiry - EXPIRY_SAFETY_MARGIN_MS).coerceAtLeast(now)
        if (capped < window) {
            if (cappedBrokers.add(brokerId)) {
            // Without this, a gateway minting keys shorter than the safety margin - or a local
            // clock running ahead of the broker's - silently turns every read into a network
            // mint, with nothing in the log connecting the slowness to expiry handling. That
            // is the same un-diagnosable shape as the bug this cap fixes.
                logger.info(
                    LogCategory.SYSTEM,
                    "Brokered credential expiry shortened its reuse window",
                    mapOf(
                        "broker" to brokerId,
                        // Not a secret, and the first thing wanted when the question is which
                        // gateway is misreporting and what it actually sent.
                        "expiresAt" to (credential.expiresAt ?: "absent"),
                        "reportedWindowSeconds" to credential.refreshAfterSeconds,
                        // Floors to 0 for a sub-second window, which reads as "mint every time".
                        "effectiveWindowSeconds" to (capped - now) / MILLIS_PER_SECOND,
                    ),
                )
            }
        } else {
            cappedBrokers.remove(brokerId)
        }
        return capped
    }

    /**
     * An RFC 3339 instant in epoch millis, or null when it cannot be read.
     *
     * More than one shape is accepted on purpose. The api documents RFC 3339, but the value
     * originates in LiteLLM and has been seen space-separated rather than `T`-separated, and
     * offset-less. A parser that only accepted the documented form would silently return null
     * for the real value and disable the cap it exists to enforce - so the tolerant reader is
     * the point, not laziness. An offset-less timestamp is read as UTC, which is what LiteLLM
     * stores.
     */
    private fun expiryMillis(raw: String): Long? {
        // replaceFirst, and a trailing zone word dropped: `2026-08-09 17:27:08 +00:00` and
        // `... UTC` are both shapes these values carry, and a transform too eager or too
        // narrow silently disables the cap - the same failure the tolerant parser exists to
        // avoid, one layer up.
        val text =
            raw
                .trim()
                .removeSuffix(" UTC")
                .trim()
                // Only when there is no separator already: `replaceFirst` on
                // `2026-08-09T17:27:08 +00:00` would insert a *second* `T` before the offset and
                // fail both parsers - silently disabling the cap, which is the exact failure this
                // parser exists to avoid. The producer is not ours to assume.
                .let { if (it.contains('T')) it else it.replaceFirst(' ', 'T') }
                .replace(" ", "")
                // `+0000` parses as neither an offset nor a local time, so without this the cap
                // silently switches off - the failure this parser exists to avoid. Deliberately
                // no "parse the leading local part and call it UTC" fallback: for a value that
                // really carries an offset that reads the instant as later than truth, which
                // lengthens the cap instead of tightening it.
                .replace(Regex("([+-]\\d{2})(\\d{2})$"), "$1:$2")
        return runCatching { java.time.OffsetDateTime.parse(text).toInstant().toEpochMilli() }
            .recoverCatching {
                java.time.LocalDateTime
                    .parse(text)
                    .toInstant(java.time.ZoneOffset.UTC)
                    .toEpochMilli()
            }.getOrNull()
    }

    private suspend fun mintBrokered(
        source: BrokeredKeySource,
        brokerId: String,
    ): String {
        // Captured before the exchange, mirroring loadStoredSecrets: if invalidate() lands
        // while this is in flight, the token belongs to the session that has just gone and
        // must not be seated. It is still returned to this caller, which asked before the
        // invalidation.
        val startedAt = generation.get()

        val fetched = try {
            source.fetch(brokerId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
        return fetched
            .fold(
                onSuccess = { credential ->
                    lastMintFailureNanos.remove(brokerId)
                    if (generation.get() == startedAt) {
                        brokeredCache[brokerId] =
                            CachedBrokeredKey(
                                token = credential.token,
                                reuseUntilMs = reuseUntil(brokerId, credential),
                            )
                    }
                    credential.token
                },
                onFailure = { error ->
                    brokeredCache.remove(brokerId)
                    lastMintFailureNanos[brokerId] = System.nanoTime()
                    logger.info(
                        LogCategory.SYSTEM,
                        "Brokered credential unavailable",
                        mapOf("broker" to brokerId, "reason" to (error.message ?: "unknown")),
                    )
                    ""
                },
            )
    }

    /**
     * Persist an API key for [providerId], creating or updating its secret.
     *
     * Rejects a blank key (use [clearKey]) and refuses to write when the key came
     * from the environment — that write is the defect this replaced.
     */
    suspend fun saveKey(
        providerId: String,
        apiKey: String,
        label: String? = null,
    ): Result<Unit> {
        if (apiKey.isBlank()) {
            return Result.failure(IllegalArgumentException("Refusing to store a blank API key."))
        }
        val descriptor =
            ProviderRegistry.find(providerId)
                ?: return Result.failure(IllegalArgumentException("Unknown provider: $providerId"))

        if (!envResolver.resolve(descriptor.envVarNames).isNullOrBlank()) {
            return Result.failure(
                IllegalStateException(
                    "${descriptor.displayName} is configured by environment variable; " +
                        "unset it before storing a key here.",
                ),
            )
        }

        return mutex.withLock {
            val existing = loadStoredSecrets().getOrElse { emptyMap() }[providerId]
            upsert(
                descriptor = descriptor,
                // Default to the provider's standard key name (TOGETHER_API_KEY, …) so the
                // entry is identifiable at a glance in the secret list.
                label = label ?: existing?.label ?: descriptor.standardKeyName,
                apiKey = apiKey.trim(),
                settings = existing?.settings ?: ProviderSettings(),
                existingSecretId = existing?.secretId,
            ).also { invalidate() }
        }
    }

    /**
     * Update the non-secret settings on [providerId]'s existing secret.
     *
     * Returns `false` when there is no secret to attach them to, which is the normal
     * case for a provider whose key comes from the environment. It deliberately does
     * **not** create a key-less entry: the store may reject a blank password, and an
     * AI-provider card carrying no credential reads as broken in the secret list. The
     * caller persists the choice elsewhere in that case — see `ActiveProviderPrefs`.
     */
    suspend fun saveSettings(
        providerId: String,
        settings: ProviderSettings,
    ): Result<Boolean> {
        val descriptor =
            ProviderRegistry.find(providerId)
                ?: return Result.failure(IllegalArgumentException("Unknown provider: $providerId"))

        return mutex.withLock {
            val existing =
                loadStoredSecrets().getOrElse { emptyMap() }[providerId]
                    ?: return@withLock Result.success(false)

            // UpdateSecretRequestData has no partial-update form, so changing a model id
            // rewrites the whole record — including the password, which came from
            // getUserSecrets. That is sound only while list responses carry the full
            // decrypted secret (they do: the host's SecretDataProviderImpl.toPluginData
            // copies it verbatim, and decryption happens server-side). If a host ever
            // redacts passwords in list payloads, picking a model would silently destroy
            // the credential — so refuse the write instead of performing it blind.
            if (existing.apiKey.isBlank()) {
                logger.warn(
                    LogCategory.SYSTEM,
                    "Refusing to save settings: the stored entry read back with no secret",
                    mapOf("provider" to providerId),
                )
                return@withLock Result.failure(
                    IllegalStateException(
                        "Could not save settings for ${descriptor.displayName}: its stored key " +
                            "read back empty, and saving would have overwritten it.",
                    ),
                )
            }

            upsert(
                descriptor = descriptor,
                apiKey = existing.apiKey,
                label = existing.label ?: descriptor.standardKeyName,
                settings = settings,
                existingSecretId = existing.secretId,
            ).also { invalidate() }.map { true }
        }
    }

    /** Delete the stored secret for [providerId]. Environment keys are unaffected. */
    suspend fun clearKey(providerId: String): Result<Unit> =
        mutex.withLock {
            val existing =
                loadStoredSecrets().getOrElse { emptyMap() }[providerId]
                    ?: return@withLock Result.success(Unit)
            secrets.deleteSecret(existing.secretId).also { invalidate() }
        }

    private suspend fun upsert(
        descriptor: ProviderDescriptor,
        apiKey: String,
        label: String,
        settings: ProviderSettings,
        existingSecretId: String?,
    ): Result<Unit> {
        val notes = json.encodeToString(ProviderSettings.serializer(), settings)
        // TAG_API_KEY makes the Secret Manager card render as an API key (key icon,
        // "Key Name" label) rather than a website/password pair, which is what these are.
        // TAG_AI_PROVIDER is what marks it as provider configuration for this plugin.
        val tags = listOf(TAG_AI_PROVIDER, TAG_API_KEY, descriptor.id)

        return if (existingSecretId == null) {
            secrets.createSecret(
                CreateSecretRequestData(
                    website = descriptor.id,
                    username = label,
                    password = apiKey,
                    notes = notes,
                    tags = tags,
                ),
            )
        } else {
            secrets.updateSecret(
                UpdateSecretRequestData(
                    secretId = existingSecretId,
                    website = descriptor.id,
                    username = label,
                    password = apiKey,
                    notes = notes,
                    tags = tags,
                ),
            )
        }
    }

    /**
     * Every AI-provider secret, keyed by provider id.
     *
     * Pages through the store rather than taking the first page: a user with many
     * password entries would otherwise appear to have no providers configured.
     */
    private suspend fun loadStoredSecrets(): Result<Map<String, StoredProvider>> {
        cached?.let { return Result.success(it) }

        // Captured before the first page: if invalidate() lands while we are paging, this
        // result is already stale and must not be seated.
        val startedAt = generation.get()

        return runCatching {
            val found = mutableMapOf<String, StoredProvider>()
            var offset = 0

            while (true) {
                val page =
                    secrets
                        .getUserSecrets(limit = PAGE_SIZE, offset = offset)
                        .getOrThrow()

                page.data
                    .filter { it.tags.contains(TAG_AI_PROVIDER) }
                    .forEach { entry ->
                        val providerId = providerIdOf(entry) ?: return@forEach
                        // First entry wins: providers are single-connection here, and a
                        // duplicate would otherwise flip between runs.
                        found.putIfAbsent(providerId, toStored(providerId, entry))
                    }

                if (!page.hasMore || page.data.isEmpty()) break
                offset += PAGE_SIZE
                if (offset >= MAX_SCANNED) {
                    logger.warn(
                        LogCategory.SYSTEM,
                        "Stopped scanning secrets for AI providers at cap",
                        mapOf("scanned" to offset),
                    )
                    break
                }
            }
            found.toMap()
        }.onSuccess { loaded ->
            if (generation.get() == startedAt) cached = loaded
        }
            .onFailure {
                logger.warn(
                    LogCategory.SYSTEM,
                    "Could not read AI provider credentials",
                    mapOf("exception" to (it::class.simpleName ?: "Exception")),
                )
            }
    }

    /**
     * Provider id from a secret. Prefers `website`, falling back to whichever tag
     * names a known provider, so an entry whose website was edited by hand still
     * resolves.
     */
    private fun providerIdOf(entry: SecretEntryData): String? = providerIdOf(entry.website, entry.tags)

    private fun providerIdOf(website: String, tags: List<String>): String? =
        ProviderRegistry.find(website)?.id
            ?: tags.firstNotNullOfOrNull { tag -> ProviderRegistry.find(tag)?.id }

    private fun toStored(
        providerId: String,
        entry: SecretEntryData,
    ): StoredProvider = toStored(providerId, entry.id, entry.password, entry.username, entry.notes)

    private fun toStored(
        providerId: String,
        secretId: String,
        password: String,
        username: String,
        notes: String?,
    ): StoredProvider =
        StoredProvider(
            secretId = secretId,
            providerId = providerId,
            apiKey = password,
            label = username.takeIf { it.isNotBlank() },
            settings = parseSettings(notes),
        )

    /**
     * Notes are this plugin's own JSON. A user could edit them by hand, so a parse
     * failure falls back to defaults rather than losing the credential. The throwable
     * is deliberately not logged: notes sit on the same record as the key.
     */
    private fun parseSettings(notes: String?): ProviderSettings {
        if (notes.isNullOrBlank()) return ProviderSettings()
        return runCatching { json.decodeFromString(ProviderSettings.serializer(), notes) }
            .onFailure {
                logger.debug(
                    LogCategory.SYSTEM,
                    "Ignoring unreadable AI provider settings note",
                    mapOf("exception" to (it::class.simpleName ?: "Exception")),
                )
            }.getOrDefault(ProviderSettings())
    }

    private data class StoredProvider(
        val secretId: String,
        val providerId: String,
        val apiKey: String,
        val label: String?,
        val settings: ProviderSettings,
    )

    companion object {
        /** Marks a secret as AI provider configuration rather than a password entry. */
        const val TAG_AI_PROVIDER: String = "ai-provider"

        /**
         * The Secret Manager's existing "this is an API key" tag. Reused rather than
         * invented so these entries pick up the same card treatment as any other API key.
         */
        const val TAG_API_KEY: String = "api_key"

        private const val PAGE_SIZE = 100
        private const val MAX_SCANNED = 2000
        internal const val MAX_SHARED_PROVIDERS = 32
        private const val SHARED_DISCOVERY_TTL_NANOS = 5 * 60 * 1_000_000_000L
        private const val SHARED_RETRY_NANOS = 15 * 1_000_000_000L
        private const val MILLIS_PER_SECOND = 1000L
        private const val NANOS_PER_MILLI = 1_000_000L

        /**
         * Taken off a credential's expiry before caching it.
         *
         * Covers a request already in flight and clock skew between this machine and the
         * broker. Deliberately generous relative to a mint, which is one fast HTTP call.
         */
        private const val EXPIRY_SAFETY_MARGIN_MS = 30_000L

        /** Caps the reported window so the millis multiply cannot overflow. Ten years. */
        private const val MAX_REUSE_SECONDS = 315_360_000L

        /** How long to wait after a failed mint before the read path tries again. */
        private const val DEFAULT_MINT_RETRY_BACKOFF_MS = 15_000L
    }
}

/**
 * Non-secret, per-provider settings, serialised into a secret's notes field.
 *
 * Every field is defaulted so an entry written by an older build still reads back.
 */
@Serializable
data class ProviderSettings(
    val selectedModelId: String? = null,
    val customEndpoint: String? = null,
    val temperature: Float = ProviderConnection.DEFAULT_TEMPERATURE,
    val maxTokens: Int = ProviderConnection.DEFAULT_MAX_TOKENS,
)
