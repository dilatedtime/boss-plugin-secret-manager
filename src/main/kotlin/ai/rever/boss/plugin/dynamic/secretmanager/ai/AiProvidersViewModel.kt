package ai.rever.boss.plugin.dynamic.secretmanager.ai

import ai.rever.boss.plugin.api.SplitViewOperations
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Everything the AI providers panel renders. */
data class AiProvidersUiState(
    val providers: List<ProviderDescriptor> = ProviderRegistry.all,
    /** Provider whose detail is expanded in the panel. */
    val selectedProviderId: String = ProviderRegistry.default.id,
    /**
     * Whether the provider editor card is open at all.
     *
     * Separate from [selectedProviderId] on purpose: that field is remembered across a
     * reload so reopening the section returns to the same provider, but the *card* should
     * not reopen uninvited just because a previous visit left one expanded. Always false on
     * a fresh load; set true only by picking a row or one of the two Add actions.
     */
    val isEditorOpen: Boolean = false,
    /** Provider served to other plugins as the active config. */
    val activeProviderId: String? = null,
    /**
     * Local CLI engines the gateway can drive, or empty when it serves none.
     *
     * Empty covers three cases: the gateway is not installed, it predates `AiCliSessionAPI`, or
     * this host's api jar does not link the symbol. The panel used to treat all three alike on
     * the grounds that none gave the user anything to do - which was wrong about the first one,
     * and is what [gatewayNotice] now separates out. The other two still leave nothing to do.
     */
    val cliEngines: List<CliEngineInfo> = emptyList(),
    /** Per-engine readiness, filled in as the probes answer. */
    val cliHealth: Map<String, CliEngineHealth> = emptyMap(),
    /**
     * The CLI engine serving AI requests, or null when an HTTP provider is.
     *
     * **Mutually exclusive with [activeProviderId] in this panel**, which is the whole reason
     * both setters clear the other: two stores each holding "which provider is active" can
     * disagree, and one writer is what stops them. The gateway resolves a disagreement in the
     * engine's favour, so a stale HTTP preference is harmless to a request - but a panel
     * showing two things as active is not harmless to a user.
     */
    val activeCliEngineId: String? = null,
    /**
     * What to say about the AI Gateway, or [GatewayNotice.NONE] when there is nothing to say.
     *
     * Starts at NONE rather than at a "checking" state on purpose: a section that flashes
     * "install the gateway" for one frame on every open, for the many users who have it, is a
     * worse lie than a notice that appears a beat late for the few who do not.
     */
    val gatewayNotice: GatewayNotice = GatewayNotice.NONE,
    /** True while the Toolbox is being asked, so the button cannot be pressed twice. */
    val isAskingForGateway: Boolean = false,
    /**
     * What this machine can tell us about running Ollama, or **null until the probe answers**.
     *
     * Null rather than a `binaryFound = false` default for the same reason
     * [CliEngineHealth.Unknown] exists and [gatewayNotice] starts at NONE: the probe is
     * asynchronous, and a card that renders "Ollama doesn't appear to be installed on this
     * machine" in the frame before it lands is making a claim rather than waiting — to a user
     * who does have it installed. Everything reading this treats null as "don't say yet", and
     * unknown RAM still reads as meeting the minimum once it *has* answered (see
     * [OllamaSystemInfo.meetsMinimum]).
     */
    val ollamaSystemInfo: OllamaSystemInfo? = null,
    /** The tag currently being pulled into Ollama, or null when no pull is in flight. */
    val installingOllamaModelTag: String? = null,
    /**
     * Providers the user picked from "Add provider" in this session.
     *
     * Only a keyless provider needs this. An ordinary one earns its row by having a credential;
     * a keyless one earns it by having a reachable daemon (`isProviderListed`), which is exactly
     * what the user who just added Ollama does *not* have yet — so without this the row they
     * added vanishes the moment they close the card, with nothing said, for precisely the user
     * the whole install flow exists for.
     *
     * Session-scoped rather than persisted on purpose: on the next launch the rule that decides
     * the row is "is the daemon answering", which is the honest one. This only stops an add from
     * being undone while the user is still standing in front of it.
     */
    val addedProviderIds: Set<String> = emptySet(),
    val connections: Map<String, ProviderConnection> = emptyMap(),
    val catalogs: Map<String, CatalogState> = emptyMap(),
    /** In-progress key edits, keyed by provider id. Never persisted until saved. */
    val keyDrafts: Map<String, String> = emptyMap(),
    val busyProviderIds: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    /** False when the secret store is unavailable — env keys still work. */
    val storeAvailable: Boolean = true,
    val sharedDiscoveryWarning: String? = null,
    val providerSelectionWarning: String? = null,
    val error: String? = null,
    val notice: String? = null,
    val legacyOffer: LegacyImportOffer? = null,
) {
    fun rawConnectionOf(providerId: String): ProviderConnection = connections[providerId]
        ?: ProviderConnection(providerId = providerId, apiKey = "", source = CredentialSource.NONE)

    fun connectionOf(providerId: String): ProviderConnection =
        effectiveSharedConnection(
            rawConnectionOf(providerId),
            catalogOf(providerId),
        )

    val unavailableSharedModel: Boolean get() {
        val id = activeProviderId ?: return false
        if (!isManagedProvider(id)) return false
        val selected = connections[id]?.selectedModelId?.takeIf { it.isNotBlank() } ?: return false
        val loaded = usableSharedCatalog(catalogOf(id)) ?: return false
        return loaded.models.none { it.id == selected }
    }

    fun catalogOf(providerId: String): CatalogState = catalogs[providerId] ?: CatalogState.NotConfigured

    fun cliHealthOf(engineId: String): CliEngineHealth = cliHealth[engineId] ?: CliEngineHealth.Unknown
}

/**
 * Drives the AI providers panel: resolves credentials, keeps model lists current, and
 * persists changes.
 *
 * Model lists are fetched from the providers themselves — on first load for anything
 * stale, and on demand from the Refresh action. Nothing here falls back to a built-in
 * list, which is what let the previous implementation drift years out of date.
 */
class AiProvidersViewModel(
    private val store: ProviderCredentialStore?,
    private val catalog: ModelCatalog,
    private val prefs: ActiveProviderPrefs,
    private val legacyImport: LegacySettingsImport?,
    private val splitViewOperations: SplitViewOperations?,
    private val scope: CoroutineScope,
    private val envResolver: EnvResolver,
    /**
     * The AI Gateway's local CLI engines, or null when this host serves none.
     *
     * Injected rather than resolved here so the panel's behaviour is testable without a host,
     * and so every new-api reference stays inside the one adapter that implements it.
     */
    private val cliEngines: CliEngineAccess? = null,
    /**
     * Whether the AI Gateway is installed, and how to offer it. Null on a host that cannot be
     * asked, which reads the same as "installed": no notice.
     *
     * Injected for the same reason [cliEngines] is - so the decision is testable without a host.
     */
    private val gateway: GatewayPresence? = null,
    /**
     * Floor on how often a brokered refresh may run.
     *
     * A parameter rather than a constant so the floor itself is testable: with it hard-coded,
     * every test of the refresh had to either wait it out or be written around it, which is how
     * an untested guard ends up wrong.
     */
    private val minBrokeredRefreshIntervalMs: Long = DEFAULT_MIN_BROKERED_REFRESH_INTERVAL_MS,
    // Injectable for the same reason as the interval above: a test that has to wait two minutes to
    // observe a renewal is a test nobody runs.
    private val brokeredRenewalLeadMs: Long = BROKERED_RENEWAL_LEAD_MS,
    private val minBrokeredRenewalDelayMs: Long = MIN_BROKERED_RENEWAL_DELAY_MS,
    /**
     * How this machine's Ollama facts are read. Injected for the same reason [cliEngines] and
     * [gateway] are — so it's fakeable without touching the real filesystem or `Desktop`.
     */
    private val ollamaSystemCheck: OllamaSystemCheck = OllamaSystemCheck(),
    /** How a suggested model is actually pulled. Injected for the same reason as above. */
    private val ollamaModelInstaller: OllamaModelInstaller = OllamaModelInstaller(),
    private val catalogRefreshIntervalMs: Long = 30_000,
    private val monotonicNanos: () -> Long = System::nanoTime,
    private val catalogConnectionWaitTimeoutMs: Long = CATALOG_CONNECTION_WAIT_TIMEOUT_MS,
) {
    private val logger = BossLogger.forComponent("AiProvidersViewModel")

    private val _state = MutableStateFlow(AiProvidersUiState(storeAvailable = store != null))
    val state: StateFlow<AiProvidersUiState> = _state.asStateFlow()

    /** Guards [ensureSectionLoaded] so entering the section twice does not re-probe. */
    private val sectionLoadStarted = AtomicBoolean(false)
    private val providerOnNextEntry = AtomicReference<String?>(null)

    /** A one-shot navigation command, distinct from the editor left open on a prior visit. */
    fun requestProviderOnEntry(providerId: String): Boolean {
        if (descriptorOf(providerId) == null) return false
        providerOnNextEntry.set(providerId)
        return true
    }

    fun enterSection(): Job {
        ensureSectionLoaded()
        val providerToOpen = providerOnNextEntry.getAndSet(null)
        // Entry closes transient editors across the shared panel instances and drops drafts.
        // Data refresh is deliberately separate: it must never discard a key being typed.
        _state.update {
            it.copy(isEditorOpen = providerToOpen != null,
                selectedProviderId = providerToOpen ?: it.selectedProviderId, keyDrafts = emptyMap())
        }
        return load()
    }

    /** Guards [ensureConnectionsLoaded] so concurrent callers load credentials once. */
    private val connectionsLoadStarted = AtomicBoolean(false)
    private val catalogsLoadStarted = AtomicBoolean(false)
    private val catalogRefreshInFlight = AtomicBoolean(false)
    private val catalogRefreshMutex = Mutex()
    private val catalogRefreshGeneration = AtomicLong(0)
    private val hasCatalogRefreshed = AtomicBoolean(false)
    private val lastCatalogRefreshNanos = AtomicLong(0)
    private val lastCatalogRefreshGeneration = AtomicLong(-1)
    private val lastCatalogOllamaInfo = AtomicReference<OllamaSystemInfo?>(null)
    private val legacyOfferDismissed = AtomicBoolean(false)
    private val _catalogsLoaded = MutableStateFlow(false)
    /** False until the first catalog sweep finishes; an empty list before that is not definitive. */
    val catalogsLoaded: StateFlow<Boolean> = _catalogsLoaded.asStateFlow()

    /** The catalog source of truth; unlike the UI mirror, this cannot lag a completed sweep. */
    fun catalogStateOf(providerId: String): CatalogState = catalog.stateOf(providerId)

    fun descriptorOf(providerId: String): ProviderDescriptor? = state.value.providers.firstOrNull { it.id == providerId }

    /** The armed renewal, replaced on each reload rather than stacked. */
    private var brokeredRenewalJob: Job? = null

    /** Guards [refreshLapsedBrokeredCredential] so a burst of reads triggers one reload. */
    private val brokeredRefreshInFlight = AtomicBoolean(false)

    /** The pull in flight, so [installOllamaModel] admits exactly one at a time. */
    private val installingOllamaTag = AtomicReference<String?>(null)

    /**
     * When the last brokered refresh *finished*, as a floor on how often one may run.
     *
     * The in-flight guard serialises refreshes but does not space them. A window that collapses to
     * zero - a gateway minting keys shorter than the safety margin, or a local clock running ahead
     * of the broker's - makes `brokeredCredentialLapsed` true again immediately after every
     * successful mint, so a polling consumer would otherwise drive a continuous back-to-back loop
     * of broker round-trips. (Not a re-page of the secret store: `loadStoredSecrets` returns from
     * `cached` unless `invalidate()` has run.)
     *
     * `nanoTime`, because this measures an elapsed duration and a wall clock that steps backwards
     * would make the difference negative and disable the refresh for the length of the step -
     * which is the same symptom as the wedge this whole change fixes.
     *
     * Stamped on completion rather than at the start, so a reload slower than the interval does
     * not leave the next read immediately eligible.
     */
    private val lastBrokeredRefreshNanos = AtomicLong(Long.MIN_VALUE / 2)

    private val _connectionsLoaded = MutableStateFlow(false)
    private val connectionLoadMutex = Mutex()
    private val catalogFetchSlots = Semaphore(MAX_CONCURRENT_CATALOG_FETCHES)
    private val discoveryRefreshThrottle = DiscoveryRefreshThrottle(minBrokeredRefreshIntervalMs, monotonicNanos)
    private val lastConnectionLoadFailureNanos = AtomicLong(Long.MIN_VALUE / 2)
    /** Guarded by connectionLoadMutex; a token rotation is not an account invalidation. */
    private var connectionGeneration: Long? = null

    init {
        scope.launch { catalog.states.collect { states -> _state.update { it.copy(catalogs = states) } } }

        // Nothing that costs a process or a plugin-registry read happens here. See
        // [ensureSectionLoaded]: this object is built during `register()`, on every launch,
        // for a section most launches never open.

        // Re-read credentials whenever the store is invalidated — which is what the secret
        // list's own create/update/delete does. Clearing the store cache alone was not
        // enough: activeConfig() answers other plugins from the snapshot below, so a secret
        // deleted from the list kept being served for the session unless the user happened
        // to open Settings → AI Providers. drop(1) skips the initial value; only real
        // invalidations should trigger a read.
        store?.let { credentialStore ->
            scope.launch {
                credentialStore.invalidations.drop(1).collect {
                    if (connectionsLoadStarted.get()) {
                        try {
                            reloadConnections()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            logger.warn(LogCategory.NETWORK, "Could not reload AI provider connections")
                        }
                    }
                }
            }
        }
    }

    /**
     * Do the work that only the panel needs, once, when the section is first shown.
     *
     * **The CLI probes are the reason this exists.** `refreshCliEngines()` runs
     * `<engine> --version` for every engine the gateway serves, and it used to run from `init` -
     * which is `register()`, on every launch, for a section most launches never open. Two engines
     * is two processes spawned during startup to fill in a row nobody asked to see.
     *
     * `checkGateway()` moves for a second reason as well as cost: it asks the plugin loader about
     * its own registry, and during `register()` that loader is part-way through loading *this*
     * plugin.
     *
     * `ensureConnectionsLoaded()` deliberately does **not** move. Other
     * plugins read `PluginContext.llmProvider` without this panel ever being opened, so it stays
     * eager - the warm-up exists so the first AI action after a restart does not race the load.
     *
     * Idempotent: the section calls it on every entry, and the flag makes all but the first a
     * no-op. Refresh is the deliberate re-read.
     */
    fun ensureSectionLoaded() {
        if (!sectionLoadStarted.compareAndSet(false, true)) return
        refreshCliEngines()
        checkGateway()
    }

    /**
     * Load credentials only — no cache seeding, no model fetches, runs at most once.
     *
     * `activeConfig()` is answered from this state, and other plugins can ask for it
     * before the settings panel has ever been opened, so this warms it at registration.
     *
     * **It does not close the race.** This launches and returns; a caller on the very
     * next line still reads empty state, because the load does a paginated store read.
     * [connectionsLoaded] is the signal for callers that can wait — `activeConfig()`
     * cannot (it is a non-suspend api member), so a null from it may mean "not loaded
     * yet" rather than "nothing configured".
     */
    fun ensureConnectionsLoaded() {
        if (monotonicNanos() - lastConnectionLoadFailureNanos.get() < minBrokeredRefreshIntervalMs * NANOS_PER_MILLI) return
        if (!connectionsLoadStarted.compareAndSet(false, true)) return
        scope.launch { loadConnections() }
    }

    /** Consumers need model metadata even when the settings panel has never been opened. */
    fun ensureCatalogsLoaded() {
        ensureConnectionsLoaded()
        if (_connectionsLoaded.value && store?.sharedDefinitionsStale() == true &&
            discoveryRefreshThrottle.begin()) {
            scope.launch(Dispatchers.IO) { reloadConnectionsSafely() }
                .invokeOnCompletion { discoveryRefreshThrottle.finish() }
        }
        catalogsLoadStarted.set(true)
        // Consumer reads can be frequent. Re-check TTLs on demand, with a retry floor for
        // transient failures instead of a perpetual refresh coroutine or one-shot latch.
        val requestedAtNanos = monotonicNanos()
        if (hasCatalogRefreshed.get() &&
            requestedAtNanos - lastCatalogRefreshNanos.get() < catalogRefreshIntervalMs * NANOS_PER_MILLI
        ) return
        if (!catalogRefreshInFlight.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            try {
                val connectionsReady =
                    withTimeoutOrNull(catalogConnectionWaitTimeoutMs) {
                        connectionsLoaded.first { it }
                        true
                    } == true
                if (!connectionsReady) {
                    logger.warn(LogCategory.NETWORK, "Timed out waiting to load AI provider connections")
                    return@launch
                }
                val generation = catalogRefreshGeneration.get()
                refreshCatalogs(state.value.connections, requestedAtNanos, generation)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                logger.warn(LogCategory.NETWORK, "Could not refresh AI model catalogs")
            }
        }.invokeOnCompletion {
            catalogRefreshInFlight.set(false)
        }
    }

    /**
     * Run one catalog sweep at a time, regardless of whether a consumer, panel load, or
     * credential reload requested it.
     *
     * [requestedAtNanos] prevents two overlapping entry points from running back-to-back: if
     * the sweep that held the mutex finished after this caller asked, it already satisfied the
     * request. [generation] prevents that older sweep from publishing "loaded" after a changed
     * credential invalidated its result.
     */
    private suspend fun refreshCatalogs(
        connections: Map<String, ProviderConnection>,
        requestedAtNanos: Long,
        generation: Long,
    ) {
        catalogRefreshMutex.withLock {
            val catalogRequestSatisfied = hasCatalogRefreshed.get() &&
                lastCatalogRefreshGeneration.get() == generation &&
                lastCatalogRefreshNanos.get() >= requestedAtNanos
            // Entry and Refresh probe independently of credential availability. A sweep
            // under an older machine snapshot cannot satisfy a newly installed daemon.
            if (state.value.ollamaSystemInfo == null) readOllamaSystemInfo()
            val probeInfo = state.value.ollamaSystemInfo
            if (generation != catalogRefreshGeneration.get()) return
            if (catalogRequestSatisfied && lastCatalogOllamaInfo.get() == probeInfo) return
            if (!_catalogsLoaded.value) catalog.seedFromCache()
            refreshStale(connections)
            lastCatalogOllamaInfo.set(probeInfo)
            hasCatalogRefreshed.set(true)
            lastCatalogRefreshNanos.set(monotonicNanos())
            lastCatalogRefreshGeneration.set(generation)
            if (generation == catalogRefreshGeneration.get()) _catalogsLoaded.value = true
        }
    }

    /**
     * Whether credentials have been read at least once. Callers that can suspend should
     * await this before treating a null `activeConfig()` as "nothing configured".
     */
    val connectionsLoaded: StateFlow<Boolean> = _connectionsLoaded.asStateFlow()

    private suspend fun loadConnections(): Map<String, ProviderConnection>? = connectionLoadMutex.withLock {
        repeat(3) {
            val startedAt = store?.invalidations?.value
            val storedActive = prefs.read()
            val snapshot = store?.loadAll()
            val connections = withPreferredModels(snapshot?.connections ?: envOnlyConnections())
            if (snapshot?.invalidatedDuringLoad == true || startedAt != store?.invalidations?.value) {
                return@repeat
            }
            val descriptors = snapshot?.descriptors ?: ProviderRegistry.all
            if (connectionGeneration != null && connectionGeneration != startedAt) {
                _state.value.providers.filter { isManagedProvider(it.id) }
                    .forEach { catalog.markNotConfigured(it.id) }
            }
            connectionGeneration = startedAt
            // Consult the gateway selection without enumerating engines or probing health.
            val selectedCli = selectedCliEngineId()

            _state.update { current ->
                val preferred = current.activeProviderId ?: storedActive
                val savedShareRemoved = snapshot?.sharedDiscoveryComplete != false &&
                    preferred?.let(::isManagedProvider) == true &&
                    descriptors.none { it.id == preferred }
                current.copy(
                    connections = connections,
                    providers = descriptors,
                    activeProviderId = if (snapshot?.sharedDiscoveryComplete == false &&
                        preferred?.let(::isManagedProvider) == true) {
                        preferred
                    } else if (preferred == null && (selectedCli != null || current.activeCliEngineId != null)) {
                        null
                    } else initialProviderId(preferred, descriptors, connections),
                    storeAvailable = store != null && snapshot?.storeReadFailed != true,
                    sharedDiscoveryWarning = snapshot?.sharedDiscoveryWarning,
                    providerSelectionWarning = if (savedShareRemoved) {
                        "The previously selected shared AI provider is unavailable. " +
                            "Choose an available provider in AI settings."
                    } else current.providerSelectionWarning,
                    error = current.error.takeUnless { it == LOAD_RETRY_MESSAGE },
                )
            }
            _connectionsLoaded.value = true
            // Arm renewal on the first load too, even if no settings panel ever opens.
            scheduleBrokeredRenewal()
            return@withLock connections
        }
        // A busy invalidation stream must not wedge the one-shot consumer load latch.
        val hadLoaded = _connectionsLoaded.value
        lastConnectionLoadFailureNanos.set(monotonicNanos())
        connectionsLoadStarted.set(false)
        // connectionsLoaded means "loaded at least once". Do not make an already
        // satisfied consumer wait regress to false because a later refresh raced edits.
        if (!hadLoaded) {
            _connectionsLoaded.value = false
            _catalogsLoaded.value = false
        }
        logger.warn(LogCategory.NETWORK, "AI provider load invalidated repeatedly; retry required")
        _state.update { it.copy(error = LOAD_RETRY_MESSAGE) }
        null
    }

    /**
     * Overlay model selections held in prefs.
     *
     * A provider keyed by an environment variable has no secret to carry its settings,
     * so its chosen model lives in prefs; the stored value still wins where one exists.
     */
    private suspend fun withPreferredModels(
        connections: Map<String, ProviderConnection>,
    ): Map<String, ProviderConnection> {
        val preferred = prefs.readModels()
        val endpoints = prefs.readCustomEndpoints()
        if (preferred.isEmpty() && endpoints.isEmpty()) return connections
        return connections.mapValues { (providerId, connection) ->
            // A stored secret's settings win: prefs are the fallback for providers that
            // have no secret to attach settings to, not a second source of truth.
            var merged = connection
            if (merged.selectedModelId == null) {
                merged = preferred[providerId]?.let { merged.copy(selectedModelId = it) } ?: merged
            }
            if (merged.customEndpoint.isNullOrBlank()) {
                merged = endpoints[providerId]?.let { merged.copy(customEndpoint = it) } ?: merged
            }
            merged
        }
    }

    /** Load credentials, seed cached model lists, then refresh anything stale. */
    fun load(): Job {
        connectionsLoadStarted.set(true)
        catalogsLoadStarted.set(true)
        val catalogRequestedAtNanos = monotonicNanos()
        val catalogGeneration = catalogRefreshGeneration.get()
        // Marked in flight synchronously, before the launch rather than inside it: `isLoading`
        // is the panel's own "this entry is still settling" signal, and a caller that returns
        // from load() to a state still reading `isLoading = false` is being told the load
        // already finished. Nothing about the flag needs the coroutine.
        _state.update { it.copy(isLoading = true, error = null) }
        return scope.launch {
            // Re-read the environment on every entry into the section. The panel tells
            // users they can unset a variable to take key management over in BOSS, and the
            // resolver memoises misses as well as hits — so without this that instruction
            // was only true after an app restart.
            envResolver.invalidate()
            // Local setup and legacy migration must not depend on vault readiness.
            readOllamaSystemInfo()
            checkLegacyImport()
            val connections = loadConnections() ?: run {
                _state.update { it.copy(isLoading = false) }
                return@launch
            }

            _state.update { current ->
                current.copy(
                    isLoading = false,
                    // An entry command may have disappeared during discovery. Never
                    // render the fallback provider's editor for that stale id.
                    isEditorOpen = current.isEditorOpen && current.providers.any { it.id == current.selectedProviderId },
                    // Keep whichever provider the user had expanded. This runs from a
                    // LaunchedEffect on every entry into the section, so resetting the
                    // selection here discarded their place each time.
                    selectedProviderId =
                        current.selectedProviderId.takeIf { id -> current.providers.any { it.id == id } }
                            ?: current.activeProviderId
                            ?: firstConfigured(connections)
                            ?: ProviderRegistry.default.id,
                    error =
                        if (!current.storeAvailable && store != null) {
                            "Stored credentials are unavailable — environment variables still apply."
                        } else {
                            null
                        },
                )
            }

            // The shared serializer also performs the awaited Ollama probe before deciding
            // whether its local catalog is reachable. An overlapping consumer sweep can
            // satisfy this request without a second provider-wide sweep.
            refreshCatalogs(
                connections,
                catalogRequestedAtNanos,
                catalogGeneration,
            )
        }
    }

    /**
     * When the secret store cannot be read, providers configured by environment
     * variable must still work. Building the map directly from the resolver keeps the
     * panel useful in that state instead of showing everything as unconfigured.
     */
    private suspend fun envOnlyConnections(): Map<String, ProviderConnection> {
        // The injected resolver, so this degraded path reuses the memo cache instead of
        // re-spawning launchctl per provider exactly when latency is already bad.
        val resolver = envResolver
        return ProviderRegistry.all.associate { descriptor ->
            val key = resolver.resolve(descriptor.envVarNames)
            descriptor.id to
                ProviderConnection(
                    providerId = descriptor.id,
                    apiKey = key.orEmpty(),
                    source = if (key.isNullOrBlank()) CredentialSource.NONE else CredentialSource.ENVIRONMENT,
                    label = resolver.resolveSourceName(descriptor.envVarNames),
                )
        }
    }

    private fun firstConfigured(connections: Map<String, ProviderConnection>): String? =
        ProviderRegistry.all.firstOrNull { connections[it.id]?.isConfigured == true }?.id

    /**
     * Refresh every stale provider concurrently.
     *
     * Sequentially, at a 20 s per-request timeout, the last provider's list could appear
     * minutes after the panel opened; in parallel the sweep settles in roughly one
     * request's time.
     */
    private suspend fun refreshStale(connections: Map<String, ProviderConnection>) =
        coroutineScope {
            val localDaemonAbsent = _state.value.ollamaSystemInfo?.binaryFound == false
            state.value.providers.map { descriptor ->
                async {
                    val connection = connections[descriptor.id] ?: return@async
                    // A keyless provider is unconditionally `isConfigured`, so without this
                    // every user - overwhelmingly, users who will never run Ollama - fetched
                    // http://localhost:11434 on every panel entry and on every store
                    // invalidation, which the secrets list triggers on any create/update/delete.
                    // Nothing broke (a fast connection-refused, and the Failed state is hidden),
                    // but "the binary is not on this machine" is a free answer to the same
                    // question. Only skipped on a *probed* absence: null means not yet asked.
                    if (!descriptor.requiresApiKey && localDaemonAbsent) {
                        catalog.markNotConfigured(descriptor.id)
                        return@async
                    }
                    if (!connection.isConfigured) {
                        catalog.markNotConfigured(descriptor.id)
                        return@async
                    }
                    if (!ProviderRegistry.hasKnownModels(descriptor)) return@async
                    catalogFetchSlots.withPermit {
                        catalog.refresh(descriptor, connection.apiKey, force = false)
                    }
                }
            }.awaitAll()
            Unit
        }

    /**
     * Select [providerId] and open its editor card — picking a row or an Add action.
     *
     * Recording the id in [AiProvidersUiState.addedProviderIds] is what makes an add of a
     * keyless provider stick; for one that already has a row it is a no-op.
     */
    fun selectProvider(providerId: String) {
        if (descriptorOf(providerId) == null) {
            _state.update { it.copy(isEditorOpen = false, error = "This AI provider is no longer available.") }
            return
        }
        _state.update {
            it.copy(
                selectedProviderId = providerId,
                isEditorOpen = true,
                addedProviderIds = it.addedProviderIds + providerId,
                notice = null,
                error = null,
            )
        }
    }

    fun toggleProvider(providerId: String) {
        if (state.value.isEditorOpen && state.value.selectedProviderId == providerId) closeEditor()
        else selectProvider(providerId)
    }

    /**
     * Close the editor card without discarding anything already saved through it.
     *
     * Drops the unsaved key draft, which is what "Cancel" implies and what the rest of this
     * plugin's handling of plaintext requires: this ViewModel outlives the card by the life of
     * the process and is shared with the other surface, so a pasted-but-unsaved key would
     * otherwise sit in memory until shutdown *and* reappear in the Settings window's field.
     */
    fun closeEditor() {
        _state.update {
            it.copy(isEditorOpen = false, keyDrafts = it.keyDrafts - it.selectedProviderId)
        }
    }

    /**
     * Make [providerId] the provider other plugins get from `activeConfig()`.
     *
     * Also hands AI requests back from a local CLI engine, if one had them. This is the single
     * writer that keeps the two stores from disagreeing: without the `selectEngine(null)` the
     * gateway would go on serving the engine - it wins any tie by design - and the panel would
     * show a provider as active while requests went somewhere else.
     */
    fun setActiveProvider(providerId: String) {
        scope.launch {
            prefs.write(providerId)
            val released = runCatching { cliEngines?.selectEngine(null) }.getOrNull()
            _state.update {
                it.copy(
                    activeProviderId = providerId,
                    providerSelectionWarning = null,
                    // Only clear what we actually released. A gateway that refused leaves the
                    // engine serving requests, and showing it as inactive would be the exact
                    // disagreement this method exists to prevent.
                    activeCliEngineId = if (released == false) it.activeCliEngineId else null,
                    notice = null,
                )
            }
        }
    }

    /**
     * Make [engineId] serve AI requests through the user's own CLI login, or null to hand them
     * back to the selected HTTP provider.
     *
     * The mirror of [setActiveProvider], and deliberately explicit: nothing routes to a CLI
     * engine unless the user picked one here. A gateway that quietly spent someone's Claude
     * subscription because no API key happened to be configured would be a surprise bill, not
     * a helpful default.
     */
    fun setActiveCliEngine(engineId: String?) {
        val access = cliEngines
        if (access == null) {
            _state.update { it.copy(error = "The AI Gateway plugin is not available on this host.") }
            return
        }
        scope.launch {
            val applied = runCatching { access.selectEngine(engineId) }.getOrDefault(false)
            if (!applied) {
                // Reported rather than swallowed: the api returns false for an engine the
                // gateway does not have, and a row that springs back with no explanation is
                // worse than one that says why.
                _state.update {
                    it.copy(error = "The AI Gateway could not select that engine.", notice = null)
                }
                return@launch
            }
            _state.update { it.copy(activeCliEngineId = engineId, error = null, notice = null) }
        }
    }

    /**
     * Re-read whether the gateway is here.
     *
     * Never cached, for the same reason `GatewayCliEngineAccess` resolves the api per call: the
     * gateway can be installed, enabled or hot-reloaded while this panel is open, and that is
     * exactly the moment a settings page has to notice. Cheap - one list read, no process spawn.
     */
    fun checkGateway() {
        val presence = gateway ?: return
        scope.launch(Dispatchers.IO) {
            val notice = runCatching { presence.notice() }.getOrDefault(GatewayNotice.NONE)
            _state.update { it.copy(gatewayNotice = notice) }
        }
    }

    /**
     * Re-read whether Ollama is installed and how much RAM this machine has.
     *
     * On IO, not the caller's dispatcher: this touches the filesystem (a handful of `File`
     * stats) and a JMX bean, and `pluginScope` falls back to `Dispatchers.Main` — neither
     * should ever be a reason a panel entry blocks on disk access.
     */
    private suspend fun readOllamaSystemInfo() {
        val info =
            withContext(Dispatchers.IO) {
                runCatching { ollamaSystemCheck.current() }
                    .getOrElse { OllamaSystemInfo(binaryFound = false, totalRamGb = null) }
            }
        _state.update { it.copy(ollamaSystemInfo = info) }
    }

    /**
     * Send the user to Ollama's installer.
     *
     * Through the host's own tab, like the "Get API key" button next to it — an https page is
     * exactly what `openUrlInActivePanel` is for, and it keeps the user inside BOSS.
     * `GatewayPresence` reaches for `Desktop` only because it hands over a `boss://` deep link
     * that a BOSS tab cannot take; this URL has no such constraint. The `Desktop` route survives
     * as the fallback for a host that serves no split-view operations, and runs on IO because
     * `Desktop.browse` hands off to the platform (xdg-open, LSOpenURLs) and can block — the same
     * reason `askToolboxToInstall` is `suspend`.
     */
    fun openOllamaInstallPage() {
        val operations = splitViewOperations
        if (operations != null) {
            operations.openUrlInActivePanel(OllamaSystemCheck.INSTALL_URL, "Install Ollama", forceNewTab = true)
            return
        }
        scope.launch(Dispatchers.IO) {
            if (!ollamaSystemCheck.openInstallPage()) {
                _state.update { it.copy(notice = "Open ${OllamaSystemCheck.INSTALL_URL} to install Ollama.") }
            }
        }
    }

    /**
     * Pull [tag] into the local Ollama daemon, then select it once it lands.
     *
     * One pull at a time, held in an [AtomicReference] rather than read back off `_state`:
     * this is public API on a ViewModel two surfaces share, so a check-then-act on the state
     * flow is only safe for as long as every caller happens to be the UI thread. Losing the
     * `compareAndSet` means two concurrent pulls racing each other for the same disk write.
     *
     * Selecting the model on success — rather than leaving the picker empty for the user to
     * notice a new entry and choose it themselves — is what makes "install" feel like it
     * finished something, not just started a download. The busy flag clears *after* the
     * catalog refresh and the selection, the way `saveKey` sequences it: clearing first
     * re-enables Install while the refresh it depends on is still in flight.
     */
    fun installOllamaModel(tag: String) {
        if (!installingOllamaTag.compareAndSet(null, tag)) return
        _state.update { it.copy(installingOllamaModelTag = tag, error = null, notice = null) }
        scope.launch {
            try {
                ollamaModelInstaller
                    .pull(tag)
                    .onSuccess {
                        refreshOne(ProviderRegistry.OLLAMA, force = true)
                        selectModel(ProviderRegistry.OLLAMA, tag)
                        _state.update { it.copy(notice = "Pulled $tag.") }
                    }.onFailure { error ->
                        _state.update { it.copy(error = ollamaFailureMessage(tag, error)) }
                    }
            } finally {
                installingOllamaTag.set(null)
                _state.update { it.copy(installingOllamaModelTag = null) }
            }
        }
    }

    /**
     * A pull failure in words the user can act on.
     *
     * The raw cause is a `ConnectException` whose message is "Connection refused", which tells
     * a user nothing about what to do — and it is the *expected* failure for the two states this
     * panel already knows about: the binary is not here, or it is here and the daemon is not
     * running. Anything else keeps the underlying message, which for Ollama's own mid-stream
     * errors ("pull model manifest: file does not exist") is the useful one.
     */
    private fun ollamaFailureMessage(tag: String, error: Throwable): String =
        when {
            error is ConnectException || error is SocketTimeoutException ->
                if (_state.value.ollamaSystemInfo?.binaryFound == true) {
                    "Ollama isn't running — start it and try again."
                } else {
                    "Ollama isn't installed on this machine yet — install it first, then pull $tag."
                }
            else -> error.message ?: "Could not pull $tag."
        }

    /**
     * Ask the Toolbox to install the gateway, then re-check.
     *
     * The re-check is why this does not simply fire and forget: the Toolbox's dialog is modal and
     * the install happens after the user answers it, so the notice has to be re-read afterwards or
     * it sits there telling the user to install something they just installed. `refreshCliEngines`
     * follows, since a fresh gateway has engines this panel has never asked about.
     */
    fun requestGateway() {
        val presence = gateway ?: return
        if (_state.value.isAskingForGateway) return
        _state.update { it.copy(isAskingForGateway = true) }
        scope.launch {
            val asked =
                runCatching {
                    if (presence.canAskToolboxToInstall()) {
                        presence.askToolboxToInstall()
                    } else {
                        presence.openToolbox()
                    }
                }.getOrDefault(false)
            _state.update { it.copy(isAskingForGateway = false) }
            if (!asked) {
                _state.update { it.copy(error = "Could not open the Toolbox. Install AI Gateway from there.") }
                return@launch
            }
            checkGateway()
            refreshCliEngines()
        }
    }

    /**
     * Load the engine list and probe each one.
     *
     * The probes run one at a time and update the state as they answer, so a slow or missing
     * binary delays its own row rather than the section. Each spawns a process, which is why
     * this is called on first panel entry and from Refresh rather than per composition.
     */
    fun refreshCliEngines() {
        val access = cliEngines ?: return
        scope.launch(Dispatchers.IO) {
            val engines = runCatching { access.engines() }.getOrDefault(emptyList())
            val selected = runCatching { access.selectedEngineId() }.getOrNull()
            _state.update { it.copy(cliEngines = engines, activeCliEngineId = selected) }
            engines.forEach { engine ->
                val health = runCatching { access.health(engine.id) }.getOrDefault(CliEngineHealth.Unknown)
                _state.update { it.copy(cliHealth = it.cliHealth + (engine.id to health)) }
            }
        }
    }

    fun updateKeyDraft(providerId: String, value: String) {
        _state.update { it.copy(keyDrafts = it.keyDrafts + (providerId to value), error = null, notice = null) }
    }

    /** Persist the draft key for [providerId], then refresh its model list. */
    fun saveKey(providerId: String) {
        val draft = _state.value.keyDrafts[providerId]?.trim().orEmpty()
        val currentStore = store
        if (currentStore == null) {
            _state.update { it.copy(error = "Sign in to store credentials.") }
            return
        }
        if (draft.isBlank()) {
            _state.update { it.copy(error = "Enter an API key first.") }
            return
        }

        scope.launch {
            withBusy(providerId) {
                currentStore
                    .saveKey(providerId, draft)
                    .onSuccess {
                        // Drop the draft so the field stops holding key material.
                        _state.update {
                            it.copy(
                                keyDrafts = it.keyDrafts - providerId,
                                notice = "Saved.",
                            )
                        }
                        reloadConnections()
                        refreshOne(providerId, force = true)
                    }.onFailure { error ->
                        _state.update { it.copy(error = error.message ?: "Could not save the key.") }
                    }
            }
        }
    }

    /** Remove the stored key for [providerId]. */
    fun clearKey(providerId: String) {
        val currentStore = store ?: return
        scope.launch {
            withBusy(providerId) {
                currentStore
                    .clearKey(providerId)
                    .onSuccess {
                        _state.update {
                            it.copy(keyDrafts = it.keyDrafts - providerId, notice = "Removed.")
                        }
                        reloadConnections()
                        catalog.markNotConfigured(providerId)
                    }.onFailure { error ->
                        _state.update { it.copy(error = error.message ?: "Could not remove the key.") }
                    }
            }
        }
    }

    fun selectModel(providerId: String, modelId: String) {
        // Blanks are never a valid selection, and nothing downstream but a takeIf in
        // configFor stopped one reaching a caller. Refusing here kills the class.
        if (modelId.isBlank()) return

        val currentStore = store
        val existing = _state.value.rawConnectionOf(providerId)

        // Reflect immediately; persistence follows. The picker should not appear to
        // reject a choice while a round-trip to the store completes.
        _state.update {
            it.copy(
                connections = it.connections + (providerId to existing.copy(selectedModelId = modelId)),
                notice = null,
            )
        }

        scope.launch {
            if (isManagedProvider(providerId)) {
                prefs.writeModel(providerId, modelId)
                return@launch
            }
            val settings =
                ProviderSettings(
                    selectedModelId = modelId,
                    customEndpoint = existing.customEndpoint,
                    temperature = existing.temperature,
                    maxTokens = existing.maxTokens,
                )

            val result =
                currentStore?.saveSettings(providerId, settings)
                    ?.onFailure { error ->
                        // Revert rather than leaving the picker showing an unsaved choice.
                        _state.update {
                            it.copy(
                                connections = it.connections + (providerId to existing),
                                error = error.message ?: "Could not save the model choice.",
                            )
                        }
                    }

            // Three genuinely distinct outcomes, and the Result has to be kept to tell them
            // apart: null = no store at all (not signed in), success(false) = no secret to
            // attach settings to (an env-keyed provider) — both fall back to prefs, which is
            // the only place the choice could live. A *failure* must not: getOrNull()
            // collapsed it into null, so a save that failed still wrote prefs, and the
            // overlay in withPreferredModels then re-applied the choice the user was just
            // told wasn't saved.
            if (result == null || result.getOrNull() == false) prefs.writeModel(providerId, modelId)
        }
    }

    /** Persist a custom provider's endpoint, which has no models endpoint to discover. */
    fun setCustomEndpoint(providerId: String, endpoint: String) {
        if (isManagedProvider(providerId)) return
        val existing = _state.value.rawConnectionOf(providerId)
        val trimmed = endpoint.trim()
        _state.update {
            it.copy(connections = it.connections + (providerId to existing.copy(customEndpoint = trimmed)))
        }
        scope.launch {
            val settings =
                ProviderSettings(
                    selectedModelId = existing.selectedModelId,
                    customEndpoint = trimmed.takeIf { it.isNotBlank() },
                    temperature = existing.temperature,
                    maxTokens = existing.maxTokens,
                )
            val written =
                store?.saveSettings(providerId, settings)
                    ?.onFailure { error ->
                        _state.update { it.copy(error = error.message ?: "Could not save the endpoint.") }
                    }?.getOrNull()

            // Written unconditionally, not only when the secret write didn't happen — which
            // does mean a *failed* store write still takes effect via the prefs overlay.
            // Accepted deliberately here: an endpoint the user typed is worth keeping over
            // losing it to a transient store failure, and unlike a model id it can be
            // cleared, so a wrong value is recoverable.
            // withPreferredModels re-overlays prefs whenever the stored endpoint is blank,
            // so a write-only fallback let a cleared endpoint come back from prefs on the
            // next load: set A with no key (prefs = A), add a key, clear the endpoint
            // (stored = null, prefs still A), reload -> A returns. Keeping prefs in step
            // makes clearing stick; writeCustomEndpoint deletes the entry on blank.
            prefs.writeCustomEndpoint(providerId, trimmed)
        }
    }

    /**
     * Set a model id by hand, for a provider with no models endpoint to ask.
     *
     * Without this a custom endpoint could never be used: `activeConfig()` requires a
     * model id, and the picker is fed only from a fetched list.
     */
    fun setManualModelId(providerId: String, modelId: String) = selectModel(providerId, modelId.trim())

    /** Re-fetch [providerId]'s model list, bypassing the TTL. */
    fun refreshModels(providerId: String) {
        scope.launch { withBusy(providerId) { refreshOne(providerId, force = true) } }
    }

    /**
     * Re-ask a brokered provider's broker, and report what came back.
     *
     * The credential is cached for as long as the broker said it may be reused, so
     * without this there is no way to retry after signing in - the panel would keep
     * showing the failure it cached. Invalidating the store first is what forces the
     * next resolve to go back to the broker rather than serve the cached answer.
     */
    fun refreshBrokeredCredential(providerId: String) {
        val currentStore = store ?: return
        scope.launch {
            withBusy(providerId) {
                currentStore.invalidate()
                reloadConnections()
                val configured = _state.value.connectionOf(providerId).isConfigured
                _state.update {
                    if (configured) {
                        it.copy(notice = "Access confirmed.", error = null)
                    } else {
                        it.copy(
                            error = "No access yet. Sign in to BOSS with an account that has access.",
                            notice = null,
                        )
                    }
                }
            }
        }
    }

    /**
     * Probe the credential by asking the provider for its model list — the cheapest
     * authenticated call that proves the key works, with no completion tokens spent.
     */
    fun testConnection(providerId: String) {
        scope.launch {
            withBusy(providerId) {
                val descriptor = descriptorOf(providerId) ?: return@withBusy
                val connection = _state.value.connectionOf(providerId)
                if (!connection.isConfigured) {
                    _state.update { it.copy(error = "Add an API key first.") }
                    return@withBusy
                }
                refreshOne(providerId, force = true)
                val outcome = catalog.stateOf(providerId)
                _state.update {
                    when (outcome) {
                        is CatalogState.Loaded ->
                            it.copy(
                                notice = "${descriptor.displayName} responded — ${outcome.models.size} models available.",
                                error = null,
                            )
                        is CatalogState.Failed -> it.copy(error = outcome.message, notice = null)
                        else -> it
                    }
                }
            }
        }
    }

    /** Open the provider's console so the user can create a key. */
    fun openProviderConsole(providerId: String) {
        val descriptor = descriptorOf(providerId) ?: return
        val url = descriptor.consoleUrl
        if (url == null) {
            _state.update { it.copy(error = "${descriptor.displayName} has no key console.") }
            return
        }
        val operations = splitViewOperations
        if (operations == null) {
            _state.update { it.copy(notice = "Open $url to create a key.") }
            return
        }
        operations.openUrlInActivePanel(url, "${descriptor.displayName} API keys", forceNewTab = true)
    }

    fun importLegacyKeys() {
        val importer = legacyImport ?: return
        scope.launch {
            _state.update { it.copy(isLoading = true) }
            importer
                .import()
                .onSuccess { imported ->
                    _state.update {
                        it.copy(
                            legacyOffer = null,
                            isLoading = false,
                            notice =
                                if (imported.isEmpty()) {
                                    "Nothing to import."
                                } else {
                                    "Imported ${imported.size} key(s). Pick a model for each provider."
                                },
                        )
                    }
                    reloadConnections()
                    refreshStale(_state.value.connections)
                }.onFailure { error ->
                    _state.update {
                        it.copy(isLoading = false, error = error.message ?: "Import failed.")
                    }
                }
        }
    }

    fun dismissLegacyOffer() {
        legacyOfferDismissed.set(true)
        _state.update { it.copy(legacyOffer = null) }
    }

    fun clearMessages() {
        _state.update { it.copy(error = null, notice = null) }
    }

    private suspend fun checkLegacyImport() {
        if (legacyOfferDismissed.get()) return
        val offer = try {
            legacyImport?.inspectAndRetireIfEmpty()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            logger.warn(LogCategory.SYSTEM, "Could not inspect legacy AI settings")
            return
        }
        _state.update { if (legacyOfferDismissed.get()) it else it.copy(legacyOffer = offer) }
    }

    /** Gateway lookup crosses the host registry; keep it off Main and preserve cancellation. */
    private suspend fun selectedCliEngineId(): String? = withContext(Dispatchers.IO) {
        try {
            cliEngines?.selectedEngineId()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Re-mint [providerId]'s brokered credential if the cached one is past its reuse deadline.
     *
     * [ensureConnectionsLoaded] loads once and never again, so `state.connections` keeps
     * serving whatever token that load captured. The expiry cap in `ProviderCredentialStore`
     * cannot help on its own, because nothing calls `loadAll` between a panel visit and a
     * secret edit - which is exactly the path a consumer like LLM RPA takes, and exactly how a
     * dead key got re-sent for eleven minutes.
     *
     * Asynchronous because `activeConfig()` cannot suspend, so **this call still returns the
     * stale token** and the next one is fresh. That trades one failed request for a wedge
     * lasting the whole window, which is the right way round; making it synchronous would mean
     * blocking a non-suspending api on a network mint.
     *
     * A no-op for providers that are not brokered, and while a refresh is already running.
     */
    fun refreshLapsedBrokeredCredential(providerId: String) {
        val brokerId = descriptorOf(providerId)?.brokerId ?: return
        val credentials = store ?: return
        if (!credentials.brokeredCredentialLapsed(brokerId)) return
        if (System.nanoTime() - lastBrokeredRefreshNanos.get() < minBrokeredRefreshIntervalMs * NANOS_PER_MILLI) {
            return
        }
        if (!brokeredRefreshInFlight.compareAndSet(false, true)) return
        // A refresh costs a broker round-trip, not a secret-store re-page: `loadStoredSecrets`
        // returns from `cached` unless `invalidate()` has run. The floor is about the mint rate.
        //
        // IO because this can now fire from any consumer read, and `pluginScope` falls back to
        // Dispatchers.Main. Contain ordinary failures because a host `exchange`/`listSecrets` that throws rather
        // than returning a failed Result would escape and cancel the scope - and a plain
        // CoroutineScope(Main) is not a supervisor, so that would silently kill every later launch
        // in the plugin. invokeOnCompletion rather than finally: if the scope is already cancelled
        // the body never runs, and the flag would latch true forever - the same shape of latch as
        // the bug this PR fixes.
        scope
            .launch(Dispatchers.IO) { reloadConnectionsSafely() }
            .invokeOnCompletion {
                lastBrokeredRefreshNanos.set(System.nanoTime())
                brokeredRefreshInFlight.set(false)
            }
    }

    /**
     * Re-read the stored credentials on demand, for the panel's Refresh action.
     *
     * `ensureConnectionsLoaded` is not this: it is `compareAndSet(false, true)`, so it loads once
     * per ViewModel and a second call is a no-op. Refresh has to actually re-read - a key added or
     * revoked in the Secrets section next door is the case it exists for, and the invalidation
     * collector only covers changes made through this plugin's own store.
     *
     * On `Dispatchers.IO`, with ordinary failures contained, for the same reasons the brokered
     * refresh path documents: `pluginScope` falls back to `Dispatchers.Main`, and a host
     * `listSecrets` that throws instead of returning a failed `Result` would escape and cancel a
     * scope that is not a supervisor, silently killing every later launch in the plugin.
     */
    fun refreshConnections(): Job = scope.launch(Dispatchers.IO) {
        try {
            store?.expireSharedDefinitions()
            if (!_connectionsLoaded.value) {
                load().join()
            } else {
                envResolver.invalidate()
                readOllamaSystemInfo()
                checkLegacyImport()
                reloadConnectionsSafely()
                refreshCatalogs(state.value.connections, monotonicNanos(), catalogRefreshGeneration.get())
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            logger.warn(LogCategory.NETWORK, "Could not refresh AI provider setup")
        }
    }

    private suspend fun reloadConnectionsSafely() {
        try {
            reloadConnections()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            logger.warn(LogCategory.NETWORK, "Could not reload AI provider connections")
        }
    }

    private suspend fun reloadConnections() = connectionLoadMutex.withLock {
        val credentials = store ?: return@withLock
        // Mirrors the store's own generation guard, one layer up. The store refuses to seat a
        // `cached`/`brokeredCache` value from before an `invalidate()`, but the value consumers
        // actually read is `_state.connections`, and that write was unguarded: a refresh already
        // in flight when the user signs out or hits "Check access" could land last and re-seat the
        // previous session's brokered token. Pre-existing, and far more likely now that any
        // consumer read can start a reload.
        val startedAt = credentials.invalidations.value
        val reloaded = credentials.loadAll()
        if (reloaded.invalidatedDuringLoad || credentials.invalidations.value != startedAt) return@withLock
        val previous = _state.value.connections
        val previousDescriptors = _state.value.providers.associateBy { it.id }
        val sameGeneration = connectionGeneration == startedAt
        // Retain display metadata only within the same account generation. These rows
        // receive no connection and cannot authorize inference. Bound retained rows too.
        val retained = if (!reloaded.sharedDiscoveryComplete && sameGeneration) {
            previousDescriptors.values.filter { old ->
                isManagedProvider(old.id) && reloaded.descriptors.none { it.id == old.id }
            }.sortedBy { if (it.id == _state.value.activeProviderId || it.id == _state.value.selectedProviderId) 0 else 1 }
                .take(ProviderCredentialStore.MAX_SHARED_PROVIDERS)
        } else emptyList()
        val nextDescriptors = (reloaded.descriptors + retained).associateBy { it.id }
        val preferredConnections = withPreferredModels(reloaded.connections)
        if (credentials.invalidations.value != startedAt) return@withLock
        connectionGeneration = startedAt
        val removed = previous.keys - preferredConnections.keys
        val savedActive = prefs.read()
        val selectedCli = selectedCliEngineId()
        removed.forEach(catalog::markNotConfigured)
        _state.update { current ->
            val activeRemoved = reloaded.sharedDiscoveryComplete &&
                current.activeProviderId?.let(::isManagedProvider) == true &&
                current.activeProviderId !in preferredConnections
            current.copy(
                connections = preferredConnections,
                providers = nextDescriptors.values.toList(),
                activeProviderId = current.activeProviderId?.takeUnless { activeRemoved }
                    ?: if (!activeRemoved && savedActive == null && current.activeCliEngineId == null && selectedCli == null) {
                        nextDescriptors[BossAiDiscovery.PROVIDER_ID]?.takeIf {
                            it.sharedDefault && preferredConnections[it.id]?.isConfigured == true
                        }?.id
                    } else null,
                isEditorOpen = current.isEditorOpen && current.selectedProviderId in nextDescriptors,
                selectedProviderId = current.selectedProviderId.takeIf { it in nextDescriptors }
                    ?: ProviderRegistry.default.id,
                storeAvailable = !reloaded.storeReadFailed,
                sharedDiscoveryWarning = reloaded.sharedDiscoveryWarning,
                providerSelectionWarning = if (activeRemoved) {
                    "The selected shared AI provider is unavailable. Choose an available provider in AI settings."
                } else if (current.activeProviderId in preferredConnections) null else current.providerSelectionWarning,
            )
        }
        // Re-arm promptly; a catalog sweep can wait behind another provider's network timeout.
        scheduleBrokeredRenewal()
        val changed = _state.value.connections.filter { (id, connection) ->
            val before = previous[id] ?: return@filter isManagedProvider(id)
            val shared = isManagedProvider(id)
            if (shared && !sameGeneration) return@filter true
            if (shared && previousDescriptors[id] == nextDescriptors[id] &&
                before.customEndpoint == connection.customEndpoint &&
                before.apiKey.isNotBlank() && connection.apiKey.isNotBlank() &&
                before.source == CredentialSource.BROKERED && connection.source == CredentialSource.BROKERED &&
                usableSharedCatalog(catalog.stateOf(id)) != null) return@filter false
            previousDescriptors[id] != nextDescriptors[id] || catalogInputChanged(id, before, connection)
        }.keys
        if (changed.isNotEmpty()) {
            val generation = catalogRefreshGeneration.incrementAndGet()
            _catalogsLoaded.value = false
            changed.forEach(catalog::markNotConfigured)
            if (catalogsLoadStarted.get()) {
                val connections = _state.value.connections
                val requestedAtNanos = monotonicNanos()
                // Do not put provider network latency in front of the invalidation collector:
                // another secret edit must be able to refresh the credential snapshot promptly.
                scope.launch(Dispatchers.IO) {
                    try {
                        refreshCatalogs(connections, requestedAtNanos, generation)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        logger.warn(LogCategory.NETWORK, "Could not refresh AI model catalogs")
                    }
                }
            }
        }
    }

    /** Only inputs that can change a remotely discovered catalog warrant invalidating it. */
    private fun catalogInputChanged(
        providerId: String,
        before: ProviderConnection,
        after: ProviderConnection,
    ): Boolean {
        val descriptor = descriptorOf(providerId) ?: return false
        // Fixed catalogs (including the brokered GLM provider) do not depend on a minted token,
        // and manual providers have no catalog endpoint to invalidate.
        if (!ProviderRegistry.hasKnownModels(descriptor) || ProviderRegistry.fixedModels.containsKey(providerId)) {
            return false
        }
        return before.apiKey != after.apiKey || before.customEndpoint != after.customEndpoint
    }

    /**
     * Renew a brokered credential shortly *before* it stops being reusable.
     *
     * Everything else on this path is reactive: the credential is replaced only once a read
     * notices it is already dead, which costs one failed request every time one expires while the
     * app is running - and the user sees "The provider rejected the credential. Check Settings",
     * which reads as something they have to go and fix by hand. They do not: access is already
     * granted, the key is just short-lived, so renewing it is this plugin's job and it should
     * happen before anything asks.
     *
     * Rescheduled from [reloadConnections], so each successful renewal arms the next one. Cancelled
     * and replaced rather than stacked, because several reads can reload in quick succession.
     */
    private fun scheduleBrokeredRenewal() {
        val credentials = store ?: return
        val deadline = credentials.nextBrokeredReuseDeadline() ?: return
        val delayMs =
            (deadline - System.currentTimeMillis() - brokeredRenewalLeadMs)
                // Never zero: a mint that keeps failing, or a deadline already in the past, would
                // otherwise spin this loop as fast as the broker answers.
                .coerceAtLeast(minBrokeredRenewalDelayMs)
        brokeredRenewalJob?.cancel()
        brokeredRenewalJob =
            scope.launch(Dispatchers.IO) {
                delay(delayMs)
                // Drop the cache first, or this renews nothing: resolveBrokered serves the cached
                // token while its reuse window is open, so a reload before the deadline would hand
                // back the same credential. That made the first version of this a poller for
                // lapse rather than a renewal.
                credentials.expireBrokeredCache()
                // Contain failures for the same reason as refreshLapsedBrokeredCredential: a host
                // exchange that throws rather than returning a failed Result would escape and
                // cancel this scope, which is not a supervisor.
                reloadConnectionsSafely()
            }
    }

    private suspend fun refreshOne(providerId: String, force: Boolean) {
        val descriptor = descriptorOf(providerId) ?: return
        val connection = _state.value.connectionOf(providerId)
        if (!connection.isConfigured) {
            catalog.markNotConfigured(providerId)
            return
        }
        // Same skip refreshStale does. Without it, saving a Custom key fetched against a
        // nonexistent endpoint, logged a NETWORK warn, and parked a Failed state the panel
        // then hides — noise with no symptom. A provider with a FIXED list is not skipped:
        // it has models to seat, just no endpoint to ask.
        if (!ProviderRegistry.hasKnownModels(descriptor)) return
        catalogFetchSlots.withPermit {
            catalog.refresh(descriptor, connection.apiKey, force = force)
        }
    }

    private suspend fun withBusy(providerId: String, block: suspend () -> Unit) {
        _state.update { it.copy(busyProviderIds = it.busyProviderIds + providerId) }
        try {
            block()
        } finally {
            _state.update { it.copy(busyProviderIds = it.busyProviderIds - providerId) }
        }
    }

    private companion object {
        const val LOAD_RETRY_MESSAGE = "AI provider settings changed while loading. Retry using Refresh."
        const val MAX_CONCURRENT_CATALOG_FETCHES = 4
        /**
         * Floor on how often a brokered refresh may run.
         *
         * Bounds the worst case when a credential is always immediately lapsed, which is what a
         * collapsed reuse window means. Short enough that a genuine renewal is not delayed in any
         * way a user would notice.
         */
        const val DEFAULT_MIN_BROKERED_REFRESH_INTERVAL_MS = 5_000L
        const val NANOS_PER_MILLI = 1_000_000L
        const val CATALOG_CONNECTION_WAIT_TIMEOUT_MS = 30_000L

        /**
         * How far ahead of a brokered credential's reuse deadline to renew it.
         *
         * Long enough to cover a mint round-trip and a slow network, short enough that a laptop
         * waking from sleep is usually still inside the window.
         */
        const val BROKERED_RENEWAL_LEAD_MS = 120_000L

        /** Floor on the armed delay, so a failing mint cannot spin the renewal loop. */
        const val MIN_BROKERED_RENEWAL_DELAY_MS = 60_000L
    }
}
