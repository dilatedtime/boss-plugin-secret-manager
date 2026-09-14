package ai.rever.boss.plugin.dynamic.secretmanager.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Pulls a model into a local Ollama daemon by calling its own API — not by printing a
 * terminal command and asking the user to run it themselves. `/api/pull` is Ollama's native
 * endpoint, not the OpenAI-compatible one [WireFormats] speaks: pulling a model is a
 * management operation, not a completion request, and has no OpenAI equivalent.
 *
 * Same shape as [ModelCatalogClient]: the JDK `HttpClient` is a constructor parameter so a
 * test can hand this a fake one instead of reaching an actual daemon.
 */
class OllamaModelInstaller(
    private val httpClient: HttpClient = defaultHttpClient(),
    private val baseUrl: String = NATIVE_API_BASE,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Pull [tag], returning once the daemon reports the pull finished — successfully or not.
     *
     * The response is streamed newline-delimited JSON progress (`{"status":"downloading",
     * "completed":.., "total":..}`, ..., finally `{"status":"success"}`); this drains it
     * rather than surfacing live progress, which is more than this picker currently shows
     * and easy to add later without changing this method's contract.
     *
     * **Success is the positive rule: the stream must actually end in `{"status":"success"}`.**
     * Scanning for an error status instead lets two real failures through as successes, and
     * both end with the caller persisting a model selection for a model that is not on disk:
     *
     * - a **mid-stream failure**, which Ollama reports as its own object with no `status` key
     *   at all (`{"error":"pull model manifest: file does not exist"}`). The HTTP status is 200
     *   by then, so the check above cannot catch it either — that `error` field is where the
     *   message the user can act on lives, so it is what gets surfaced;
     * - a **truncated stream** — daemon killed, disk full, the laptop sleeping mid-download —
     *   whose last line is an ordinary `downloading` progress record.
     */
    suspend fun pull(tag: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val request =
                    HttpRequest
                        .newBuilder(URI("$baseUrl/api/pull"))
                        .timeout(PULL_TIMEOUT)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(buildJsonObject { put("name", tag) }.toString()))
                        .build()

                val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
                // `use` on both paths, not just the streaming one: with `ofInputStream()` the
                // body *is* the connection, and an unread error body left open never releases it.
                response.body().use { body ->
                    if (response.statusCode() !in 200..299) {
                        throw IllegalStateException("Ollama rejected the pull request (HTTP ${response.statusCode()}).")
                    }

                    var lastStatus: String? = null
                    var lastError: String? = null
                    body.bufferedReader().forEachLine { line ->
                        val obj = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return@forEachLine
                        obj.text(ERROR_FIELD)?.let { lastError = it }
                        obj.text(STATUS_FIELD)?.let { lastStatus = it }
                    }

                    if (lastStatus != SUCCESS_STATUS) {
                        val detail =
                            lastError
                                ?: lastStatus?.let { "the pull stopped at \"$it\"" }
                                ?: "the daemon closed the stream without reporting a result"
                        throw IllegalStateException("Ollama could not pull $tag: $detail")
                    }
                }
            }
        }

    /** One non-blank string field, or null — `as?` because a non-primitive here must not throw. */
    private fun JsonObject.text(field: String): String? =
        (this[field] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    companion object {
        /**
         * Ollama's own API, distinct from [ProviderRegistry.ollama]'s OpenAI-compatible
         * `chatEndpoint` — same host and port, different path prefix. Fixed rather than
         * derived from the descriptor for the same reason the descriptor's own endpoint is
         * fixed: this provider is the default local install, not a user-editable one.
         */
        const val NATIVE_API_BASE = "http://localhost:11434"

        /** The one terminal status that means the model is on disk. */
        private const val SUCCESS_STATUS = "success"

        private const val STATUS_FIELD = "status"

        /** Ollama's own field for a mid-stream failure; carries the actionable message. */
        private const val ERROR_FIELD = "error"

        /**
         * A model pull can be several gigabytes on a slow connection; the default 20 s used
         * for a models-list GET would abort a real pull in progress.
         */
        private val PULL_TIMEOUT: Duration = Duration.ofMinutes(30)

        fun defaultHttpClient(): HttpClient =
            HttpClient
                .newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()
    }
}
