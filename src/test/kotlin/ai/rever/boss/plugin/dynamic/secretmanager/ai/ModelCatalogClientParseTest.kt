package ai.rever.boss.plugin.dynamic.secretmanager.ai

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the model-list parsers against captured response shapes.
 *
 * These are the highest-risk code in the feature: each was written from a provider's
 * published reference rather than from an observed response, and two of the six
 * (xAI, Together) do not document their envelope at all. A parser that quietly returns
 * nothing is the same failure the hardcoded lists were — an empty or wrong picker with
 * no error — so the shapes are locked down here rather than discovered in the app.
 */
class ModelCatalogClientParseTest {
    @Test
    fun `shared catalog parses bigint allowances and rejects non-string capabilities`() = runTest {
        val shared = SharedProviderDefinition(
            "boss-managed-provider-v1", "Shared", "managed", "https://api.example/v1",
        ).descriptor("entry")
        val body = """{"data":[{"id":"published","name":"Published","is_default":true,
            "context_length":2048,"max_output_tokens":100,"capabilities":["text","tools",true,42],
            "allowance":{
              "day":{"remaining":3000000000,"limit":4000000000,"resets_at":"2026-09-12T00:00:00Z"},
              "week":{"remaining":"5000","limit":"6000","resets_at":"2026-09-14T00:00:00+00:00"},
              "month":{"remaining":true,"limit":9000,"resets_at":"invalid"}
            }}]}"""
        val model = clientReturning(body).fetch(shared, "minted").getOrThrow().single()
        assertEquals("published", model.id)
        assertEquals("Published", model.displayName)
        assertEquals(2048, model.contextLength)
        assertEquals(100, model.maxOutputTokens)
        assertTrue(model.isDefault)
        assertEquals(listOf("text", "tools"), model.capabilities)
        assertEquals(
            "day: 3000000000 / 4000000000 tokens remaining (resets 12 Sep 00:00 UTC)\n" +
                "week: 5000 / 6000 tokens remaining (resets 14 Sep 00:00 UTC)",
            model.allowanceSummary,
        )
    }

    /** Returns [body] for every request, so only parsing is under test. */
    private fun clientReturning(
        body: String,
        status: Int = 200,
    ): ModelCatalogClient = ModelCatalogClient(FakeHttpClient(status, body))

    private fun descriptor(id: String): ProviderDescriptor =
        ProviderRegistry.find(id) ?: error("unknown provider $id")

    // ==================== Anthropic ====================

    @Test
    fun `anthropic exposes display name, limits and supported capabilities`() =
        runTest {
            val body = """
                {"data":[{"type":"model","id":"claude-opus-5","display_name":"Claude Opus 5",
                "max_input_tokens":1000000,"max_tokens":128000,
                "capabilities":{"image_input":{"supported":true},
                "structured_outputs":{"supported":false},
                "thinking":{"supported":true,"types":{"adaptive":{"supported":true}}}}}],
                "has_more":false,"last_id":"claude-opus-5"}
            """.trimIndent()

            val models = clientReturning(body).fetch(descriptor(ProviderRegistry.ANTHROPIC), "k").getOrThrow()

            assertEquals(1, models.size)
            val model = models.single()
            assertEquals("claude-opus-5", model.id)
            assertEquals("Claude Opus 5", model.displayName)
            assertEquals(1_000_000, model.contextLength)
            assertEquals(128_000, model.maxOutputTokens)
            // Only capabilities reporting supported=true, and only the top level — the
            // nested thinking.types tree must not be flattened into the badge list.
            assertEquals(listOf("image_input", "thinking"), model.capabilities)
        }

    // ==================== Google ====================

    @Test
    fun `google strips the models prefix and drops non-chat models`() =
        runTest {
            val body = """
                {"models":[
                  {"name":"models/gemini-3-pro","displayName":"Gemini 3 Pro",
                   "inputTokenLimit":1048576,"outputTokenLimit":65536,
                   "supportedGenerationMethods":["generateContent"],"thinking":true},
                  {"name":"models/text-embedding-004","displayName":"Embedding 004",
                   "supportedGenerationMethods":["embedContent"]}
                ]}
            """.trimIndent()

            val models = clientReturning(body).fetch(descriptor(ProviderRegistry.GOOGLE), "k").getOrThrow()

            // The embedding model has no generateContent, so it has no place in a chat picker.
            assertEquals(1, models.size)
            val model = models.single()
            // Requests take the bare id; the resource name would 404.
            assertEquals("gemini-3-pro", model.id)
            assertEquals("Gemini 3 Pro", model.displayName)
            assertEquals(1_048_576, model.contextLength)
            assertEquals(65_536, model.maxOutputTokens)
            assertEquals(listOf("thinking"), model.capabilities)
        }

    // ==================== Together ====================

    @Test
    fun `together keeps chat-shaped types and drops the rest`() =
        runTest {
            val body = """
                [
                  {"id":"meta-llama/Llama-4","object":"model","type":"chat",
                   "display_name":"Llama 4","context_length":1000000,"organization":"Meta"},
                  {"id":"BAAI/bge-large","object":"model","type":"embedding"},
                  {"id":"some/reranker","object":"model","type":"rerank"}
                ]
            """.trimIndent()

            val models = clientReturning(body).fetch(descriptor(ProviderRegistry.TOGETHER), "k").getOrThrow()

            assertEquals(listOf("meta-llama/Llama-4"), models.map { it.id })
            assertEquals("Llama 4", models.single().displayName)
            assertEquals(1_000_000, models.single().contextLength)
            assertEquals("Meta", models.single().ownedBy)
        }

    @Test
    fun `together is accepted as a bare array or wrapped in data`() =
        runTest {
            // Together's model-list envelope isn't stated in its reference, so both are
            // accepted rather than betting on one.
            val bare = """[{"id":"m","object":"model","type":"chat"}]"""
            val wrapped = """{"data":[{"id":"m","object":"model","type":"chat"}]}"""
            val descriptor = descriptor(ProviderRegistry.TOGETHER)

            assertEquals(
                listOf("m"),
                clientReturning(bare).fetch(descriptor, "k").getOrThrow().map { it.id },
            )
            assertEquals(
                listOf("m"),
                clientReturning(wrapped).fetch(descriptor, "k").getOrThrow().map { it.id },
            )
        }

    // ==================== Moonshot ====================

    @Test
    fun `moonshot maps its boolean capability flags`() =
        runTest {
            val body = """
                {"object":"list","data":[{"id":"kimi-k3","object":"model","created":1,
                "owned_by":"moonshot","context_length":262144,
                "supports_image_in":true,"supports_video_in":false,"supports_reasoning":true}]}
            """.trimIndent()

            val model =
                clientReturning(body).fetch(descriptor(ProviderRegistry.MOONSHOT), "k").getOrThrow().single()

            assertEquals("kimi-k3", model.id)
            assertEquals(262_144, model.contextLength)
            assertEquals(listOf("image-in", "reasoning"), model.capabilities)
            assertEquals("moonshot", model.ownedBy)
        }

    // ==================== xAI ====================

    @Test
    fun `xai derives capabilities from modalities`() =
        runTest {
            val body = """
                {"models":[{"id":"grok-5","owned_by":"xai","version":"1",
                "input_modalities":["text","image"],"output_modalities":["text"]}]}
            """.trimIndent()

            val model =
                clientReturning(body).fetch(descriptor(ProviderRegistry.XAI), "k").getOrThrow().single()

            assertEquals("grok-5", model.id)
            assertEquals(listOf("image-in"), model.capabilities)
        }

    // ==================== OpenAI ====================

    @Test
    fun `openai reports little beyond the id`() =
        runTest {
            val body = """{"data":[{"id":"gpt-5","object":"model","created":1,"owned_by":"openai"}]}"""

            val model =
                clientReturning(body).fetch(descriptor(ProviderRegistry.OPENAI), "k").getOrThrow().single()

            assertEquals("gpt-5", model.id)
            assertEquals("gpt-5", model.displayName)
            assertEquals("openai", model.ownedBy)
            assertNull(model.contextLength)
        }

    // ==================== OpenRouter ====================

    @Test
    fun `openrouter reports its readable name and context length`() =
        runTest {
            val body = """
                {"data":[{"id":"anthropic/claude-opus-5","name":"Anthropic: Claude Opus 5",
                "context_length":200000,"pricing":{"prompt":"0.000015","completion":"0.000075"}}]}
            """.trimIndent()

            val model =
                clientReturning(body).fetch(descriptor(ProviderRegistry.OPENROUTER), "k").getOrThrow().single()

            assertEquals("anthropic/claude-opus-5", model.id)
            assertEquals("Anthropic: Claude Opus 5", model.displayName)
            assertEquals(200_000, model.contextLength)
        }

    // ==================== Ollama ====================

    @Test
    fun `ollama's openai-compatible list needs no special parser`() =
        runTest {
            // Ollama's /v1/models mirrors OpenAI's own envelope, so it falls through to
            // openAiModel — this pins that assumption rather than the OpenAI test alone,
            // since a future Ollama release changing that shape would not otherwise be
            // caught here.
            val body = """{"object":"list","data":[{"id":"llama3.2:latest","object":"model"}]}"""

            val model =
                clientReturning(body).fetch(descriptor(ProviderRegistry.OLLAMA), "").getOrThrow().single()

            assertEquals("llama3.2:latest", model.id)
        }

    @Test
    fun `a provider that requires no key is still fetched with a blank one`() =
        runTest {
            // ModelCatalog.refresh short-circuits to NotConfigured on a blank key for
            // every ordinary provider (see ModelCatalogStateTest) — Ollama is the one
            // exception, gated on ProviderDescriptor.requiresApiKey rather than on the
            // key itself, and only an end-to-end ModelCatalog.refresh call exercises
            // that branch; ModelCatalogClient.fetch alone never sees the short-circuit.
            val body = """{"data":[{"id":"llama3.2:latest","object":"model"}]}"""
            val catalog = ModelCatalog(client = clientReturning(body), cacheDir = null)

            catalog.refresh(descriptor(ProviderRegistry.OLLAMA), apiKey = "", force = true)

            val state = catalog.stateOf(ProviderRegistry.OLLAMA)
            assertTrue(state is CatalogState.Loaded, "expected Loaded, was $state")
            assertEquals(listOf("llama3.2:latest"), (state as CatalogState.Loaded).models.map { it.id })
        }

    // ==================== failure shapes ====================

    @Test
    fun `an explicit empty catalog succeeds rather than being mistaken for an unknown envelope`() = runTest {
        for (body in listOf("""{"data":[]}""", """{"models":[]}""", "[]")) {
            val result = clientReturning(body).fetch(descriptor(ProviderRegistry.OPENAI), "k")
            assertTrue(result.isSuccess, body)
            assertTrue(result.getOrThrow().isEmpty(), body)
        }
    }

    @Test
    fun `unrecognisable entries do not masquerade as an explicitly empty catalog`() = runTest {
        for (body in listOf("""{"data":[{"unexpected":"value"}]}""", """{"data":"not-an-array"}""")) {
            assertTrue(clientReturning(body).fetch(descriptor(ProviderRegistry.OPENAI), "k").isFailure, body)
        }
    }

    @Test
    fun `an unrecognised envelope fails instead of reporting an empty list`() =
        runTest {
            // A 200 that parses to nothing means the shape wasn't what we expected, not
            // that the provider has no models. Reporting success here is what would put
            // an empty dropdown under a "live · updated just now" label.
            val result = clientReturning("""{"unexpected":{}}""").fetch(descriptor(ProviderRegistry.OPENAI), "k")

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message?.contains("not recognised") == true)
        }

    @Test
    fun `a rejected key is reported without echoing the key`() =
        runTest {
            val result =
                clientReturning("""{"error":"bad key sk-secret-value"}""", status = 401)
                    .fetch(descriptor(ProviderRegistry.OPENAI), "sk-secret-value")

            assertTrue(result.isFailure)
            val message = result.exceptionOrNull()?.message.orEmpty()
            assertTrue(message.contains("401"))
            // Neither the response body nor the credential may reach a message that is
            // rendered in the panel and written to the log.
            assertTrue(!message.contains("sk-secret-value"), "message leaked the key: $message")
        }

    @Test
    fun `a provider with no models endpoint fails with an actionable message`() =
        runTest {
            val result = clientReturning("[]").fetch(descriptor(ProviderRegistry.CUSTOM), "k")

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message?.contains("model id") == true)
        }

    @Test
    fun `models are de-duplicated and ordered by display name`() =
        runTest {
            val body = """
                {"data":[{"id":"zeta","object":"model","type":"chat"},
                         {"id":"alpha","object":"model","type":"chat"},
                         {"id":"alpha","object":"model","type":"chat"}]}
            """.trimIndent()

            val models = clientReturning(body).fetch(descriptor(ProviderRegistry.TOGETHER), "k").getOrThrow()

            assertEquals(listOf("alpha", "zeta"), models.map { it.id })
        }

    /** Minimal HttpClient stub; only send/sendAsync are reachable from the client. */
    private class FakeHttpClient(
        private val status: Int,
        private val body: String,
    ) : HttpClient() {
        override fun cookieHandler() = java.util.Optional.empty<java.net.CookieHandler>()

        override fun connectTimeout() = java.util.Optional.empty<java.time.Duration>()

        override fun followRedirects() = Redirect.NEVER

        override fun proxy() = java.util.Optional.empty<java.net.ProxySelector>()

        override fun sslContext(): javax.net.ssl.SSLContext = javax.net.ssl.SSLContext.getDefault()

        override fun sslParameters(): javax.net.ssl.SSLParameters = javax.net.ssl.SSLParameters()

        override fun authenticator() = java.util.Optional.empty<java.net.Authenticator>()

        override fun version() = Version.HTTP_1_1

        override fun executor() = java.util.Optional.empty<java.util.concurrent.Executor>()

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any?> send(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>,
        ): HttpResponse<T> = FakeResponse(status, body, request) as HttpResponse<T>

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any?> sendAsync(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>,
        ): CompletableFuture<HttpResponse<T>> =
            CompletableFuture.completedFuture(FakeResponse(status, body, request) as HttpResponse<T>)

        override fun <T : Any?> sendAsync(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>,
            pushPromiseHandler: HttpResponse.PushPromiseHandler<T>?,
        ): CompletableFuture<HttpResponse<T>> = sendAsync(request, responseBodyHandler)
    }

    private class FakeResponse(
        private val status: Int,
        private val body: String,
        private val request: HttpRequest,
    ) : HttpResponse<String> {
        override fun statusCode() = status

        override fun request() = request

        override fun previousResponse() = java.util.Optional.empty<HttpResponse<String>>()

        override fun headers() = java.net.http.HttpHeaders.of(emptyMap()) { _, _ -> true }

        override fun body() = body

        override fun sslSession() = java.util.Optional.empty<javax.net.ssl.SSLSession>()

        override fun uri() = request.uri()

        override fun version() = HttpClient.Version.HTTP_1_1
    }
}
