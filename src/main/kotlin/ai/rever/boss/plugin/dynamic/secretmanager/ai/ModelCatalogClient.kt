package ai.rever.boss.plugin.dynamic.secretmanager.ai

import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Asks each provider what models the supplied credential can actually reach.
 *
 * This is the whole point of the feature: model lists are never hardcoded, so they
 * cannot go stale. A provider is only ever described by what it just reported.
 *
 * Uses the JDK HTTP client rather than Ktor deliberately — the host excludes the
 * ktor server stack to keep plugin classloaders clean, and bundling a second Ktor
 * copy into a plugin invites the same loader-constraint trouble. The JDK client
 * needs no dependency at all.
 */
class ModelCatalogClient(
    private val httpClient: HttpClient = defaultHttpClient(),
) {
    private val logger = BossLogger.forComponent("AiModelCatalogClient")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Fetch [descriptor]'s model list using [apiKey].
     *
     * Returns a failure with a human-readable message rather than throwing, because
     * every caller renders the message in the settings panel. Providers with no
     * models endpoint (a custom endpoint) fail with an explanatory message.
     */
    suspend fun fetch(
        descriptor: ProviderDescriptor,
        apiKey: String,
    ): Result<List<AiModel>> {
        val primary = descriptor.modelsEndpoint
            ?: return Result.failure(
                IllegalStateException("${descriptor.displayName} has no model list endpoint — enter a model id manually."),
            )

        val first = fetchAllPages(descriptor, primary, apiKey)
        val fallback = descriptor.modelsEndpointFallback ?: return first

        // An explicit empty array is valid for an authoritative endpoint such as a fresh
        // Ollama daemon. A provider with a fallback is different: its primary route is a
        // best-effort guess, so an empty success is also a reason to ask the known fallback.
        if (first.isSuccess && first.getOrThrow().isNotEmpty()) return first

        // xAI exposes a richer endpoint plus a minimal one; fall back rather than reporting
        // failure when only the richer route is unavailable.
        //
        // Gated on "this endpoint is wrong" rather than on any failure: retrying a 401 is
        // guaranteed to 401 again and only the second message would reach the user, and
        // retrying a 429 hits a provider that just asked us to slow down — twice per
        // refresh. An unrecognised envelope stays in scope because that is the other way a
        // wrong endpoint presents (a 200 that parses to nothing).
        if (first.isFailure && !worthRetryingOnFallback(first.exceptionOrNull())) return first
        val second = fetchAllPages(descriptor, fallback, apiKey)
        // The primary still gave a valid explicit answer. A broken enrichment fallback must
        // not turn "this provider currently has no models" into a discovery failure.
        if (first.isSuccess && second.isFailure) return first
        return second
    }

    /**
     * Follow the provider's cursor to the end of the list.
     *
     * A single GET truncates on Anthropic (20 by default) and Google (50), both of
     * which report a cursor — and a quietly clipped list is the same failure mode as
     * the stale hardcoded lists this replaced. Bounded by [MAX_PAGES]; stopping early
     * is logged rather than passed off as a complete list.
     */
    private suspend fun fetchAllPages(
        descriptor: ProviderDescriptor,
        endpoint: String,
        apiKey: String,
    ): Result<List<AiModel>> {
        val collected = mutableListOf<AiModel>()
        var cursor: String? = null
        var pages = 0
        var explicitlyEmpty = false
        var undecodedEntries = false

        while (pages < MAX_PAGES) {
            val page = requestPage(descriptor, endpoint, apiKey, cursor)
            val body = page.getOrElse { return Result.failure(it) }

            collected += body.models
            explicitlyEmpty = explicitlyEmpty || body.explicitlyEmpty
            undecodedEntries = undecodedEntries || (body.models.isEmpty() && !body.explicitlyEmpty)
            pages++

            // Assign before breaking: leaving the previous page's cursor in place made a
            // complete list that ended exactly on the last allowed page report itself as
            // truncated.
            cursor = body.nextCursor
            if (cursor == null) break
        }

        if (pages >= MAX_PAGES && cursor != null) {
            logger.warn(
                LogCategory.NETWORK,
                "Stopped paging AI model list at page cap — list may be incomplete",
                mapOf("provider" to descriptor.id, "pages" to pages, "models" to collected.size),
            )
        }

        // An explicit empty model array is a valid answer (e.g. a fresh local daemon).
        // An unknown shape or entries we cannot decode still must not masquerade as one.
        if (collected.isEmpty() && (!explicitlyEmpty || undecodedEntries)) {
            return Result.failure(
                UnrecognisedEnvelope(
                    "${descriptor.displayName} returned no recognisable models — response format not recognised.",
                ),
            )
        }
        return Result.success(collected.distinctBy { it.id }.sortedBy { it.displayName.lowercase() })
    }

    private data class Page(val models: List<AiModel>, val nextCursor: String?, val explicitlyEmpty: Boolean)

    private suspend fun requestPage(
        descriptor: ProviderDescriptor,
        endpoint: String,
        apiKey: String,
        cursor: String?,
    ): Result<Page> =
        withContext(Dispatchers.IO) {
            // Build the request in its own guard. URI.create and header validation embed
            // the offending value in their exception message, and on the Google path the
            // credential is in the query string — a key pasted with a space would
            // otherwise reach the error banner and the log verbatim. Only messages
            // authored here escape.
            val request =
                runCatching { buildRequest(descriptor, endpoint, apiKey, cursor) }
                    .getOrElse {
                        return@withContext Result.failure(
                            IllegalStateException(
                                "Could not build the ${descriptor.displayName} model list request — check the API key for stray characters.",
                            ),
                        )
                    }

            runCatching {
                val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() !in 200..299) {
                    throw HttpStatusFailure(response.statusCode(), describeFailure(descriptor, response.statusCode()))
                }
                parsePage(descriptor, response.body())
            }.recoverCatching { cause ->
                // Anything from send/parse that isn't already one of our messages
                // (timeouts, TLS, malformed JSON) is reported without its text, since
                // that text is not ours and the request carried a credential.
                if (cause is IllegalStateException && cause.message?.isNotBlank() == true) {
                    throw cause
                }
                throw IllegalStateException("Could not reach ${descriptor.displayName} (${cause::class.simpleName}).")
            }
        }

    private fun buildRequest(
        descriptor: ProviderDescriptor,
        endpoint: String,
        apiKey: String,
        cursor: String?,
    ): HttpRequest {
        val key = apiKey.trim()
        val params = mutableListOf<Pair<String, String>>()

        when (descriptor.pagingStyle) {
            PagingStyle.ANTHROPIC_CURSOR -> {
                params += "limit" to PAGE_LIMIT.toString()
                cursor?.let { params += "after_id" to it }
            }

            PagingStyle.GOOGLE_PAGE_TOKEN -> {
                params += "pageSize" to PAGE_LIMIT.toString()
                cursor?.let { params += "pageToken" to it }
            }

            PagingStyle.NONE -> Unit
        }

        if (descriptor.credentialTransport == CredentialTransport.QUERY_KEY_PARAM) {
            params += "key" to key
        }

        val query =
            params.joinToString("&") { (name, value) ->
                "${enc(name)}=${enc(value)}"
            }
        val separator = if (endpoint.contains('?')) "&" else "?"
        val url = if (query.isEmpty()) endpoint else "$endpoint$separator$query"

        val builder =
            HttpRequest
                .newBuilder(URI.create(url))
                .GET()
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")

        when (descriptor.credentialTransport) {
            CredentialTransport.BEARER_HEADER -> builder.header("Authorization", "Bearer $key")

            CredentialTransport.X_API_KEY_HEADER -> {
                builder.header("x-api-key", key)
                builder.header("anthropic-version", ProviderRegistry.ANTHROPIC_VERSION)
            }

            CredentialTransport.QUERY_KEY_PARAM -> Unit // already in the URL
        }

        return builder.build()
    }

    /** Percent-encode a query component; a raw key can contain `&`, `=`, spaces or quotes. */
    private fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    /**
     * Turn a status code into something a user can act on. The body is deliberately
     * not included: provider error bodies can echo the request, and this one carries
     * a credential.
     */
    /**
     * Whether a primary-endpoint failure suggests the *endpoint* was wrong, rather than the
     * credential, the rate limit or the provider being down.
     *
     * Only those cases are worth a second request: a 401 would reject the fallback too and
     * only its message would survive, and a 429 means the provider already asked us to back
     * off. 404/405 and an unparseable-but-2xx body are the two ways a guessed endpoint shows
     * up — and xAI's is a guess, which is why a fallback exists at all.
     */
    private fun worthRetryingOnFallback(cause: Throwable?): Boolean =
        when (cause) {
            is UnrecognisedEnvelope -> true
            is HttpStatusFailure -> cause.status == 404 || cause.status == 405
            // Transport failures (timeout, TLS, DNS) carry no status; a different path on
            // the same host would fail the same way.
            else -> false
        }

    /** Carries the status so [worthRetryingOnFallback] doesn't have to parse a message. */
    internal class HttpStatusFailure(
        val status: Int,
        message: String,
    ) : IllegalStateException(message)

    /** A 2xx whose body held nothing we recognised — the other shape of a wrong endpoint. */
    internal class UnrecognisedEnvelope(
        message: String,
    ) : IllegalStateException(message)

    private fun describeFailure(
        descriptor: ProviderDescriptor,
        status: Int,
    ): String =
        when (status) {
            401, 403 -> "${descriptor.displayName} rejected the API key (HTTP $status). Check the key and try again."
            404 -> "${descriptor.displayName} has no model list at that endpoint (HTTP 404)."
            429 -> "${descriptor.displayName} rate-limited the model list request (HTTP 429). Try again shortly."
            in 500..599 -> "${descriptor.displayName} returned a server error (HTTP $status). Try again shortly."
            else -> "${descriptor.displayName} model list failed (HTTP $status)."
        }

    private fun parsePage(
        descriptor: ProviderDescriptor,
        body: String,
    ): Page {
        val root = json.parseToJsonElement(body)
        val entries = modelArray(root) ?: throw UnrecognisedEnvelope(
            "${descriptor.displayName} model list response format was not recognised.",
        )
        val cursor = nextCursor(descriptor, root)

        val models = entries
            .filterIsInstance<JsonObject>()
            .mapNotNull { obj ->
                when {
                    descriptor.wireFormat == WireFormat.GOOGLE_GENERATIVE -> googleModel(obj)
                    descriptor.id == ProviderRegistry.ANTHROPIC -> anthropicModel(obj)
                    descriptor.id == ProviderRegistry.MOONSHOT -> moonshotModel(obj)
                    descriptor.id == ProviderRegistry.TOGETHER -> togetherModel(obj)
                    descriptor.id == ProviderRegistry.XAI -> xaiModel(obj)
                    descriptor.id == ProviderRegistry.OPENROUTER -> openRouterModel(obj)
                    isManagedProvider(descriptor.id) -> bossAiModel(obj)
                    else -> openAiModel(obj)
                }
            }
        return Page(models = models, nextCursor = cursor, explicitlyEmpty = entries.isEmpty())
    }

    /**
     * The cursor for the next page, or null when this was the last one.
     *
     * Anthropic reports `has_more` plus `last_id`; Google reports a non-empty
     * `nextPageToken`. Anything else pages once.
     */
    private fun nextCursor(
        descriptor: ProviderDescriptor,
        root: JsonElement,
    ): String? {
        val obj = root as? JsonObject ?: return null
        return when (descriptor.pagingStyle) {
            PagingStyle.ANTHROPIC_CURSOR ->
                if (obj.bool("has_more") == true) {
                    // has_more with a missing or blank last_id would otherwise stop paging
                    // and report the truncated list as complete — the silent clipping this
                    // whole feature exists to remove. Say so instead.
                    obj.str("last_id") ?: run {
                        logger.warn(
                            LogCategory.NETWORK,
                            "Provider reported more models but gave no cursor; list may be incomplete",
                            mapOf("provider" to descriptor.id),
                        )
                        null
                    }
                } else {
                    null
                }

            PagingStyle.GOOGLE_PAGE_TOKEN -> obj.str("nextPageToken")

            PagingStyle.NONE -> null
        }
    }

    /**
     * Pull the model array out of whatever envelope the provider used.
     *
     * Anthropic, OpenAI and Moonshot document `data`; Google documents `models`.
     * xAI's and Together's envelope shape is not stated in their published model-list
     * references, so all three forms are accepted rather than betting on one.
     */
    private fun modelArray(root: JsonElement): List<JsonElement>? =
        when {
            root is JsonArray -> root
            root is JsonObject && root["data"] is JsonArray -> (root["data"] as JsonArray)
            root is JsonObject && root["models"] is JsonArray -> (root["models"] as JsonArray)
            else -> null
        }

    private fun anthropicModel(obj: JsonObject): AiModel? {
        val id = obj.str("id") ?: return null
        return AiModel(
            id = id,
            displayName = obj.str("display_name") ?: id,
            contextLength = obj.int("max_input_tokens"),
            maxOutputTokens = obj.int("max_tokens"),
            capabilities = anthropicCapabilities(obj["capabilities"] as? JsonObject),
        )
    }

    /**
     * Anthropic reports capabilities as a tree with `{"supported": bool}` leaves.
     * Only the top level is surfaced — enough for a picker badge, without flattening
     * nested sub-trees like `thinking.types.*` into noise.
     */
    private fun anthropicCapabilities(capabilities: JsonObject?): List<String> =
        capabilities
            ?.mapNotNull { (key, value) ->
                key.takeIf { (value as? JsonObject)?.bool("supported") == true }
            }?.sorted()
            .orEmpty()

    private fun openAiModel(obj: JsonObject): AiModel? {
        val id = obj.str("id") ?: return null
        return AiModel(id = id, displayName = id, ownedBy = obj.str("owned_by"))
    }

    private fun bossAiModel(obj: JsonObject): AiModel? {
        val id = obj.str("id") ?: return null
        val allowance = obj["allowance"] as? JsonObject
        val summary = listOf("day", "week", "month").mapNotNull { period ->
            val window = allowance?.get(period) as? JsonObject ?: return@mapNotNull null
            val remaining = (window["remaining"] as? JsonPrimitive)?.longOrNull
                ?.takeIf { it >= 0 } ?: return@mapNotNull null
            val limit = (window["limit"] as? JsonPrimitive)?.longOrNull
                ?.takeIf { it >= 0 } ?: return@mapNotNull null
            val reset = window.str("resets_at") ?: return@mapNotNull null
            val resetLabel = runCatching {
                OffsetDateTime.parse(reset).withOffsetSameInstant(ZoneOffset.UTC)
                    .format(DateTimeFormatter.ofPattern("dd MMM HH:mm 'UTC'", Locale.ENGLISH))
            }.getOrNull() ?: return@mapNotNull null
            "$period: $remaining / $limit tokens remaining (resets $resetLabel)"
        }.joinToString("\n").takeIf { it.isNotEmpty() }
        return AiModel(
            id = id,
            displayName = obj.str("name") ?: id,
            contextLength = obj.int("context_length"),
            maxOutputTokens = obj.int("max_output_tokens"),
            capabilities = obj.strList("capabilities"),
            isDefault = obj.bool("is_default") == true,
            allowanceSummary = summary,
        )
    }

    private fun moonshotModel(obj: JsonObject): AiModel? {
        val id = obj.str("id") ?: return null
        val capabilities =
            buildList {
                if (obj.bool("supports_image_in") == true) add("image-in")
                if (obj.bool("supports_video_in") == true) add("video-in")
                if (obj.bool("supports_reasoning") == true) add("reasoning")
            }
        return AiModel(
            id = id,
            displayName = id,
            contextLength = obj.int("context_length"),
            capabilities = capabilities,
            ownedBy = obj.str("owned_by"),
        )
    }

    private fun togetherModel(obj: JsonObject): AiModel? {
        val id = obj.str("id") ?: return null
        // Together lists embedding, rerank and moderation models alongside chat
        // models; only conversational ones belong in a chat model picker.
        val type = obj.str("type")
        if (type != null && type !in TOGETHER_CHAT_TYPES) return null
        return AiModel(
            id = id,
            displayName = obj.str("display_name") ?: id,
            contextLength = obj.int("context_length"),
            ownedBy = obj.str("organization"),
        )
    }

    private fun xaiModel(obj: JsonObject): AiModel? {
        val id = obj.str("id") ?: return null
        val inputs = obj.strList("input_modalities")
        val outputs = obj.strList("output_modalities")
        val capabilities =
            buildList {
                if (inputs.any { it.equals("image", ignoreCase = true) }) add("image-in")
                if (outputs.any { it.equals("image", ignoreCase = true) }) add("image-out")
            }
        return AiModel(
            id = id,
            displayName = id,
            capabilities = capabilities,
            ownedBy = obj.str("owned_by"),
        )
    }

    /**
     * OpenRouter documents `data[].context_length` directly on each model, unlike
     * OpenAI's bare `id` — worth its own parser rather than the generic
     * [openAiModel] so the picker can show a context length and a readable name.
     */
    private fun openRouterModel(obj: JsonObject): AiModel? {
        val id = obj.str("id") ?: return null
        return AiModel(
            id = id,
            displayName = obj.str("name") ?: id,
            contextLength = obj.int("context_length"),
        )
    }

    private fun googleModel(obj: JsonObject): AiModel? {
        // Google returns resource names ("models/gemini-x"); requests take the bare id.
        val rawName = obj.str("name") ?: return null
        val id = rawName.removePrefix("models/")

        // Skip embedding and other non-chat models — the picker is for completions.
        val methods = obj.strList("supportedGenerationMethods")
        if (methods.isNotEmpty() && methods.none { it == "generateContent" }) return null

        val capabilities = buildList { if (obj.bool("thinking") == true) add("thinking") }
        return AiModel(
            id = id,
            displayName = obj.str("displayName") ?: id,
            contextLength = obj.int("inputTokenLimit"),
            maxOutputTokens = obj.int("outputTokenLimit"),
            capabilities = capabilities,
        )
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.strList(key: String): List<String> =
        (this[key] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            .orEmpty()

    companion object {
        private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(20)
        private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)

        private val TOGETHER_CHAT_TYPES = setOf("chat", "language", "code")

        /** Page size requested where the provider supports one. */
        private const val PAGE_LIMIT = 1000

        /** Bound on cursor-following, so a misbehaving cursor can't loop forever. */
        private const val MAX_PAGES = 20

        fun defaultHttpClient(): HttpClient =
            HttpClient
                .newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                // NEVER, not NORMAL: every endpoint here is fixed and first-party, so
                // following a redirect buys nothing — while Google's key travels in the
                // query string and OpenAI/Anthropic send an auth header, so a redirect
                // would hand the credential to whatever host answered.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()
    }
}
