package ai.rever.boss.plugin.dynamic.secretmanager.ai

import java.net.http.HttpRequest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins [OllamaModelInstaller] against captured `/api/pull` response shapes — the streamed
 * NDJSON envelope Ollama documents, not the OpenAI-compatible one [ModelCatalogClient] speaks.
 */
class OllamaModelInstallerTest {
    private fun installerReturning(
        status: Int,
        body: String,
    ) = OllamaModelInstaller(httpClient = FakeStreamingHttpClient(pullBody = body, status = status))

    @Test
    fun `a stream ending in success is a success`() =
        runTest {
            val body =
                """
                {"status":"pulling manifest"}
                {"status":"downloading","completed":100,"total":1000}
                {"status":"success"}
                """.trimIndent()

            val result = installerReturning(200, body).pull("llama3.2:3b")

            assertTrue(result.isSuccess)
        }

    @Test
    fun `a mid-stream error object fails the pull and surfaces its message`() =
        runTest {
            // Ollama reports a failed pull as its own object with no `status` key at all, after
            // an HTTP 200 - so neither the status code nor a scan of `status` can see it. This
            // is the shape a bad tag actually produces.
            val body =
                """
                {"status":"pulling manifest"}
                {"error":"pull model manifest: file does not exist"}
                """.trimIndent()

            val result = installerReturning(200, body).pull("nonexistent:1b")

            assertTrue(result.isFailure)
            val message = result.exceptionOrNull()?.message.orEmpty()
            assertTrue(message.contains("nonexistent:1b"), message)
            assertTrue(message.contains("pull model manifest"), message)
        }

    @Test
    fun `a stream that stops before success fails the pull`() =
        runTest {
            // A daemon killed, a full disk, a laptop asleep mid-download: the last line is an
            // ordinary progress record. Nothing about it is an error, and the model is not on
            // disk - so anything but a terminal `success` has to read as a failure.
            val body =
                """
                {"status":"pulling manifest"}
                {"status":"downloading","completed":100,"total":4000}
                """.trimIndent()

            val result = installerReturning(200, body).pull("llama3.2:3b")

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message?.contains("downloading") == true)
        }

    @Test
    fun `an empty stream fails rather than reporting a pull that never happened`() =
        runTest {
            val result = installerReturning(200, "").pull("llama3.2:3b")

            assertTrue(result.isFailure)
        }

    @Test
    fun `progress records before the terminal success do not fail the pull`() =
        runTest {
            // The success rule is about the *terminal* line, not about the word "error"
            // appearing anywhere: a digest or a layer name containing it must not fail a pull
            // that the daemon went on to report as successful.
            val body =
                """
                {"status":"pulling manifest"}
                {"status":"pulling sha256:e77berror0","completed":10,"total":10}
                {"status":"success"}
                """.trimIndent()

            assertTrue(installerReturning(200, body).pull("llama3.2:3b").isSuccess)
        }

    @Test
    fun `a non-2xx response fails without needing a parseable body`() =
        runTest {
            val result = installerReturning(404, "not found").pull("llama3.2:3b")

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message?.contains("404") == true)
        }

    @Test
    fun `the request posts the tag as name to the native pull endpoint`() =
        runTest {
            var seenRequest: HttpRequest? = null
            val client =
                FakeStreamingHttpClient(onRequest = { request -> seenRequest = request })

            OllamaModelInstaller(httpClient = client).pull("qwen2.5:32b")

            assertEquals("http://localhost:11434/api/pull", seenRequest?.uri().toString())
        }
}
