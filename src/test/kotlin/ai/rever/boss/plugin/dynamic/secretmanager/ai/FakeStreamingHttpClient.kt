package ai.rever.boss.plugin.dynamic.secretmanager.ai

import java.io.ByteArrayInputStream
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture

/**
 * Minimal `HttpClient` stub for the local-Ollama paths, answering by request path.
 *
 * Shared rather than nested in one suite because two different callers reach the same fake
 * daemon with two different body handlers: [OllamaModelInstaller] streams `/api/pull` with
 * `BodyHandlers.ofInputStream()`, while [ModelCatalogClient] reads `/v1/models` with
 * `ofString()`. A stub that answers only one of them cannot stand in for a ViewModel test
 * that exercises a pull *and* the catalog refresh it triggers.
 */
internal class FakeStreamingHttpClient(
    /** NDJSON returned for `/api/pull`, as an `InputStream`. */
    private val pullBody: String = """{"status":"success"}""",
    /** JSON returned for anything else, as a `String`. */
    private val catalogBody: String = """{"data":[]}""",
    private val status: Int = 200,
    private val onRequest: (HttpRequest) -> Unit = {},
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

    private fun bodyFor(request: HttpRequest): Any =
        if (request.uri().path.startsWith("/api/pull")) {
            ByteArrayInputStream(pullBody.toByteArray())
        } else {
            catalogBody
        }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any?> send(
        request: HttpRequest,
        responseBodyHandler: HttpResponse.BodyHandler<T>,
    ): HttpResponse<T> {
        onRequest(request)
        return FakeResponse(status, bodyFor(request), request) as HttpResponse<T>
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any?> sendAsync(
        request: HttpRequest,
        responseBodyHandler: HttpResponse.BodyHandler<T>,
    ): CompletableFuture<HttpResponse<T>> {
        onRequest(request)
        return CompletableFuture.completedFuture(FakeResponse(status, bodyFor(request), request) as HttpResponse<T>)
    }

    override fun <T : Any?> sendAsync(
        request: HttpRequest,
        responseBodyHandler: HttpResponse.BodyHandler<T>,
        pushPromiseHandler: HttpResponse.PushPromiseHandler<T>?,
    ): CompletableFuture<HttpResponse<T>> = sendAsync(request, responseBodyHandler)

    private class FakeResponse(
        private val status: Int,
        private val body: Any,
        private val request: HttpRequest,
    ) : HttpResponse<Any> {
        override fun statusCode() = status

        override fun request() = request

        override fun previousResponse() = java.util.Optional.empty<HttpResponse<Any>>()

        override fun headers() = java.net.http.HttpHeaders.of(emptyMap()) { _, _ -> true }

        override fun body(): Any = body

        override fun sslSession() = java.util.Optional.empty<javax.net.ssl.SSLSession>()

        override fun uri() = request.uri()

        override fun version() = HttpClient.Version.HTTP_1_1
    }
}
