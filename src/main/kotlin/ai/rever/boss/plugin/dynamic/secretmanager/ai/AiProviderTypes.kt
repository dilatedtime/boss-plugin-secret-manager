package ai.rever.boss.plugin.dynamic.secretmanager.ai

/**
 * Request/response shape an endpoint speaks.
 *
 * Deliberately a plugin-local enum rather than the api's `LlmApiFormat`, even though
 * the two mirror each other. Referencing the api enum here would put a new-api symbol
 * on a code path that runs whenever this panel loads, so a host shipping an older api
 * jar would fail to link the whole feature. Keeping it local confines every new-api
 * reference to [LlmProviderSettingsApiImpl], which is registered inside a
 * `LinkageError` guard — the same containment editor-tab uses.
 */
enum class WireFormat {
    /** Anthropic Messages API. */
    ANTHROPIC_MESSAGES,

    /** OpenAI-compatible Chat Completions. */
    OPENAI_CHAT,

    /** Google Gemini generative language API. */
    GOOGLE_GENERATIVE,

    /**
     * OpenAI Responses API. Not interchangeable with [OPENAI_CHAT]: same credential
     * style, different request and reply shapes, and a Chat Completions body posted
     * to `/v1/responses` is rejected. This is what Codex speaks.
     */
    OPENAI_RESPONSES,
}

/**
 * How a provider pages its model list.
 *
 * A single unpaged GET silently truncates: Anthropic's `/v1/models` defaults to 20 items
 * and Google's `ListModels` to 50, both with a cursor. A quietly clipped list is the same
 * failure mode as the hardcoded lists this feature replaced, so the cursor is followed.
 */
enum class PagingStyle {
    /** One request. The provider documents no pagination for this endpoint. */
    NONE,

    /** `limit` + `after_id` query params; `has_more` / `last_id` in the body (Anthropic). */
    ANTHROPIC_CURSOR,

    /** `pageSize` + `pageToken` query params; `nextPageToken` in the body (Google). */
    GOOGLE_PAGE_TOKEN,
}

/**
 * How a provider expects its credential to travel on a request. The six supported
 * providers use three distinct shapes, so this cannot be folded into [WireFormat]
 * (Google speaks a different wire format *and* a different credential transport;
 * Anthropic shares neither with OpenAI-compatible providers).
 */
enum class CredentialTransport {
    /** `Authorization: Bearer <key>` — OpenAI, xAI, Moonshot, Together. */
    BEARER_HEADER,

    /** `x-api-key: <key>` plus an `anthropic-version` header — Anthropic. */
    X_API_KEY_HEADER,

    /** `?key=<key>` query parameter — Google generative language API. */
    QUERY_KEY_PARAM,
}

/** Trust-neutral source ordering for competing shared default recommendations. */
enum class SharedProviderProvenance {
    OWNED,
    ORGANISATION,
    DIRECT_SHARE,
}

/**
 * Static description of one AI provider: where its endpoints are, how it wants the
 * credential, and where a user goes to obtain one.
 *
 * There is deliberately no OAuth/sign-in field. Of the providers listed here only
 * Google has a documented third-party OAuth flow, and it runs through Vertex AI
 * (different base URL, requires a GCP project and region) — that is tracked as
 * separate work. Anthropic prohibits third-party OAuth outright, and the xAI and
 * Kimi flows belong to their own coding CLIs rather than to a documented
 * third-party program. See AGENTS.md before adding one.
 */
data class ProviderDescriptor(
    /** Stable identifier, persisted in secrets and exposed as `LlmConfig.providerId`. */
    val id: String,
    /** Human-readable name for the UI. */
    val displayName: String,
    /** Wire format of [chatEndpoint], for plugins building requests. */
    val wireFormat: WireFormat,
    /** How the credential is attached to requests. */
    val credentialTransport: CredentialTransport,
    /**
     * Endpoint a caller POSTs completions to. For Google this is a prefix — the
     * selected model and `:generateContent` are appended per request, which
     * [chatEndpointFor] handles.
     */
    val chatEndpoint: String,
    /**
     * Endpoint listing the models this credential can reach, or null when the
     * provider has no such endpoint (a user-supplied custom endpoint).
     */
    val modelsEndpoint: String?,
    /**
     * Secondary models endpoint tried when [modelsEndpoint] fails — xAI exposes a
     * richer `/v1/language-models` alongside the minimal `/v1/models`.
     */
    val modelsEndpointFallback: String? = null,
    /** Environment variables consulted for a key, in priority order. */
    val envVarNames: List<String> = emptyList(),
    /** Where the user creates an API key, opened by the "Get API key" action. */
    val consoleUrl: String? = null,
    /** Shape hint shown in the empty key field, e.g. `sk-ant-...`. */
    val keyPlaceholder: String = "",
    /** How [modelsEndpoint] pages, so a long list isn't silently clipped. */
    val pagingStyle: PagingStyle = PagingStyle.NONE,
    /**
     * Whether this provider needs a credential at all before it can be used.
     *
     * True for every hosted provider. False only for a local daemon (Ollama) that
     * takes no key on the wire: [ProviderConnection.isConfigured] does not wait on
     * one, and [ModelCatalog] fetches its model list with a blank key rather than
     * reporting [CatalogState.NotConfigured] forever.
     */
    val requiresApiKey: Boolean = true,
    /**
     * Broker that mints this provider's credential from the signed-in BOSS session,
     * or null for a provider the user supplies a key for.
     *
     * A provider with a broker has no key field: there is nothing for the user to
     * paste, and [envVarNames] stays empty so nothing writes a minted credential to
     * disk. Reaching the broker needs a host that implements the relay, so such a
     * provider reports unconfigured on a host that does not.
     */
    val brokerId: String? = null,
    /** Default recommendation supplied by managed provider metadata. */
    val sharedDefault: Boolean = false,
    /** Untrusted provenance label shown for a vault-delivered definition. */
    val sharedSourceLabel: String? = null,
    /** Typed provenance used for deterministic default ordering, never as authorization. */
    val sharedProvenance: SharedProviderProvenance? = null,
) {
    /** Whether a model id is embedded into [chatEndpoint] rather than sent in the body. */
    val needsModelInEndpoint: Boolean
        get() = wireFormat == WireFormat.GOOGLE_GENERATIVE

    /**
     * Canonical name for this provider's key, used as the stored secret's name so an
     * entry reads as `TOGETHER_API_KEY` rather than a prose label.
     *
     * This is the provider's own environment variable, which means the name in the secret
     * store matches the variable someone would export to supply the same key — the two
     * paths are visibly the same credential.
     */
    val standardKeyName: String
        get() = envVarNames.firstOrNull() ?: "${id}_API_KEY"

    /**
     * Full completions URL for [modelId]. Only Google needs the model in the path;
     * every other provider takes it in the request body and ignores the argument.
     */
    fun chatEndpointFor(modelId: String): String =
        if (needsModelInEndpoint) "$chatEndpoint/$modelId:generateContent" else chatEndpoint
}

/**
 * Whether [descriptor] belongs in the compact provider list shared by the panel and API.
 *
 * Keyed providers need a credential. A keyless local daemon instead needs a catalog that
 * proves it is reachable. Without that distinction every user would see Ollama from first
 * launch whether or not they had ever run it.
 *
 * [wasAddedByUser] deliberately overrides the daemon rule for the current panel session. A
 * user who adds Ollama before installing it must keep the row and its install controls after
 * closing the editor; the catalog rule takes over again on the next launch.
 */
internal fun isProviderListed(
    descriptor: ProviderDescriptor,
    connection: ProviderConnection,
    catalog: CatalogState,
    wasAddedByUser: Boolean = false,
): Boolean =
    if (isManagedProvider(descriptor.id)) {
        connection.isConfigured && usableSharedCatalog(catalog) != null
    } else if (descriptor.requiresApiKey) {
        connection.isConfigured
    } else {
        catalog is CatalogState.Loaded || wasAddedByUser
    }

/** Pure connection-shape check for credential-free discovery responses. */
internal fun hasUsableProviderConnection(
    descriptor: ProviderDescriptor,
    connection: ProviderConnection,
    requireModel: Boolean,
): Boolean {
    if (!connection.isConfigured) return false
    val modelId = connection.selectedModelId.orEmpty().trim()
    if (requireModel && modelId.isEmpty()) return false
    if (descriptor.needsModelInEndpoint && modelId.isEmpty()) return false
    if (ProviderRegistry.needsManualModel(descriptor) && modelId.isEmpty()) return false
    if (descriptor.id == ProviderRegistry.CUSTOM && connection.customEndpoint.isNullOrBlank()) return false
    return true
}

/**
 * One model offered by a provider, as reported by that provider's models endpoint.
 * Every field beyond [id] is optional because coverage varies — Together reports
 * context length and pricing, OpenAI reports almost nothing.
 */
data class AiModel(
    /** Model id to send in a request, e.g. `claude-opus-5`. */
    val id: String,
    /** Display name from the provider, falling back to [id]. */
    val displayName: String,
    /** Maximum input tokens, when the provider reports it. */
    val contextLength: Int? = null,
    /** Maximum output tokens, when the provider reports it. */
    val maxOutputTokens: Int? = null,
    /** Free-form capability labels, e.g. `vision`, `reasoning`. */
    val capabilities: List<String> = emptyList(),
    /** Owning organisation, when reported. */
    val ownedBy: String? = null,
    /** Published by BOSS AI; never inferred from display order. */
    val isDefault: Boolean = false,
    /** Server-reported remaining allowance and UTC reset times. */
    val allowanceSummary: String? = null,
)

/** Where a provider's credential came from, which decides whether it is editable. */
enum class CredentialSource {
    /** Resolved from the environment. Read-only: never written back to storage. */
    ENVIRONMENT,

    /** Stored as a secret by this plugin. Editable. */
    STORED,

    /**
     * Minted by a broker from the signed-in BOSS session. Read-only, and **never
     * written to storage**: it expires within hours and is cheap to re-obtain, so
     * persisting it would turn a credential that self-heals into one that leaks.
     * Same rule as [ENVIRONMENT], for a different reason.
     */
    BROKERED,

    /** No credential available. */
    NONE,
}

/**
 * A short-lived credential a broker minted, as this plugin sees it.
 *
 * A plugin-local mirror of the api's `BrokeredCredential`. The api type cannot
 * appear here: this file is on the always-taken load path, and the whole point of
 * [LlmProviderSettingsApiImpl] being the only api-touching file is that a host with
 * an older api jar loses the AI panel rather than the whole plugin. See AGENTS.md.
 */
data class BrokeredKey(
    val token: String,
    /** Seconds this credential may be reused before asking the broker again. */
    val refreshAfterSeconds: Long,
    /**
     * When the credential stops working, RFC 3339, or null when the broker did not say.
     *
     * Carried because [refreshAfterSeconds] alone cannot be trusted to end before the
     * credential does: a broker that reports a reuse window outliving its own key wedges
     * every request for the rest of that window. See `ProviderCredentialStore.reuseUntil`.
     */
    val expiresAt: String? = null,
)

/**
 * Where [ProviderCredentialStore] gets a brokered credential from.
 *
 * A seam rather than a direct call, because the store is constructed outside the
 * `LinkageError` guard while the broker is an api type only reachable inside it.
 * The store is handed an implementation after the fact, and resolves brokered
 * providers as unconfigured until then - which is also the correct behaviour on a
 * host that has no broker at all.
 */
fun interface BrokeredKeySource {
    suspend fun fetch(brokerId: String): Result<BrokeredKey>
    /** The plugin credential source opts into the BOSS provider metadata protocol. */
    val discoversBossAi: Boolean get() = false
    fun bossAiScope(): String? = null
    val supportsSharedProviders: Boolean get() = false
    /** Whether this host currently exposes at least one scoped broker. */
    fun canDiscoverSharedProviders(): Boolean = supportsSharedProviders
    /** Host-owned destination check, mandatory for descriptors read from shared notes. */
    fun permitsEndpoint(brokerId: String, endpoint: String): Boolean = false
    /** Check one definition against a single snapshot of the host broker registry. */
    fun permitsEndpoints(brokerId: String, endpoints: List<String>): Boolean =
        endpoints.all { permitsEndpoint(brokerId, it) }
}

/**
 * A provider's resolved credential plus the generation settings chosen for it.
 * [apiKey] is blank exactly when [source] is [CredentialSource.NONE].
 */
data class ProviderConnection(
    val providerId: String,
    val apiKey: String,
    val source: CredentialSource,
    val selectedModelId: String? = null,
    val customEndpoint: String? = null,
    val temperature: Float = DEFAULT_TEMPERATURE,
    val maxTokens: Int = DEFAULT_MAX_TOKENS,
    /** Label shown alongside the credential, e.g. an account or key name. */
    val label: String? = null,
) {
    /**
     * A local runtime needs no credential. For [ProviderRegistry.CUSTOM] an endpoint
     * alone counts — requiring a key there forced users to invent a dummy one to make
     * `activeConfig()` resolve at all. For any provider whose descriptor declares
     * [ProviderDescriptor.requiresApiKey] false (Ollama, at a fixed endpoint it never
     * needs to be told), no further input is needed at all.
     */
    val isConfigured: Boolean
        get() =
            apiKey.isNotBlank() ||
                (providerId == ProviderRegistry.CUSTOM && !customEndpoint.isNullOrBlank()) ||
                ProviderRegistry.find(providerId)?.requiresApiKey == false

    companion object {
        const val DEFAULT_TEMPERATURE: Float = 0.7f
        const val DEFAULT_MAX_TOKENS: Int = 2000
    }
}

/**
 * State of one provider's model list.
 *
 * There is no "bundled defaults" case on purpose: model lists are only ever what a
 * provider just told us. A provider with no credential reports
 * [NotConfigured] rather than a hardcoded guess, because a stale hardcoded list is
 * what this replaced.
 */
sealed interface CatalogState {
    /** No credential, so the provider cannot be asked. */
    data object NotConfigured : CatalogState

    /** A fetch is in flight and nothing usable is cached. */
    data object Loading : CatalogState

    /** Models as reported by the provider at [fetchedAtEpochMs]. */
    data class Loaded(
        val models: List<AiModel>,
        val fetchedAtEpochMs: Long,
        /** True when served from disk cache rather than a fetch in this session. */
        val fromCache: Boolean = false,
    ) : CatalogState

    /**
     * The fetch failed. [lastKnown] carries the previous good result when there is
     * one, so the picker keeps working while surfacing that it is not current.
     */
    data class Failed(
        val message: String,
        val lastKnown: Loaded? = null,
        /**
         * True when retrying cannot help until the credential changes (a 401/403).
         *
         * `load()` runs from a LaunchedEffect on every entry into the section and `isStale`
         * treats a failure as stale, so without this a user with a revoked key sent an
         * auth-failure request to the provider on every visit — which some providers notice.
         * Transient failures (offline, 5xx, timeouts) stay retryable, because there the next
         * attempt genuinely might succeed.
         */
        val permanent: Boolean = false,
        /** When the failed request finished, used to bound retries of transient failures. */
        val failedAtEpochMs: Long = 0,
    ) : CatalogState
}
