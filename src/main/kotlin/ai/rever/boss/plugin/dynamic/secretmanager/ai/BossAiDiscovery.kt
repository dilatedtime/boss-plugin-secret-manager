package ai.rever.boss.plugin.dynamic.secretmanager.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Account-scoped catalogs and preferences include both automatic and legacy shared providers. */
internal fun isManagedProvider(id: String): Boolean =
    id == BossAiDiscovery.PROVIDER_ID || SharedProviderDefinition.isShared(id)

/**
 * The plugin credential source supplies the trusted bootstrap scope. The provider publishes its definition at
 * /provider, and both resulting API endpoints must remain within that scope.
 */
class BossAiDiscovery(
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build(),
) {
    internal suspend fun fetch(scope: String, token: String): Result<ProviderDescriptor> = withContext(Dispatchers.IO) {
        try {
            require(SharedProviderDefinition.withinScope(scope, scope))
            val request = HttpRequest.newBuilder(URI.create(scope.trimEnd('/') + "/provider"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .GET().build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                return@withContext Result.failure(IllegalStateException(
                    if (response.statusCode() == 404) "BOSS AI provider metadata is not deployed yet."
                    else "BOSS AI provider discovery failed (HTTP ${response.statusCode()}). Retry using Refresh.",
                ))
            }
            val definition = requireNotNull(SharedProviderDefinition.parse(response.body()))
            require(definition.brokerId == BROKER_ID)
            val descriptor = definition.descriptor("unused").copy(
                id = PROVIDER_ID,
                sharedSourceLabel = null,
                sharedProvenance = null,
            )
            require(listOfNotNull(descriptor.modelsEndpoint, descriptor.chatEndpoint).all {
                SharedProviderDefinition.withinScope(it, scope)
            })
            Result.success(descriptor)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Never surface HTTP/header exceptions that may contain the minted credential.
            Result.failure(IllegalStateException("Could not read trusted BOSS AI provider metadata. Retry using Refresh."))
        }
    }

    companion object {
        const val PROVIDER_ID = "managed:boss-ai"
        const val BROKER_ID = "boss-ai"

        /** Display-only until provider metadata is validated; it cannot supply inference config. */
        internal fun unavailableDescriptor() = ProviderDescriptor(
            id = PROVIDER_ID,
            displayName = "BOSS AI",
            wireFormat = WireFormat.OPENAI_CHAT,
            credentialTransport = CredentialTransport.BEARER_HEADER,
            chatEndpoint = "",
            modelsEndpoint = null,
            envVarNames = emptyList(),
            consoleUrl = null,
            brokerId = BROKER_ID,
        )
    }
}
