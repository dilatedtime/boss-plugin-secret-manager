package ai.rever.boss.plugin.dynamic.secretmanager.ai

import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Remembers which provider is the active one — what `LlmProvider.activeConfig()`
 * resolves to for other plugins.
 *
 * This is a preference, not a credential, so it lives in a small plain file rather
 * than the secret store: it must be readable before (and without) a signed-in
 * session, otherwise a restart would lose the choice whenever the store is
 * unreachable. Nothing sensitive is written here — only a provider id.
 */
class ActiveProviderPrefs(
    private val bossRootDir: File = EnvResolver.defaultBossRootDir(),
) {
    private val logger = BossLogger.forComponent("AiProviderPrefs")
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    private val file: File get() = File(bossRootDir, FILE_NAME)

    /** The stored active provider id, or null when none has been chosen yet. */
    suspend fun read(): String? =
        readPrefs().activeProviderId?.takeIf {
            ProviderRegistry.find(it) != null || isManagedProvider(it)
        }

    /**
     * Model selections held here rather than on a secret.
     *
     * A provider whose key comes from the environment has no secret to attach settings
     * to, and creating a key-less one to hold a model id would mean writing a secret
     * with a blank password (which the store may reject) and showing a credential-less
     * AI-provider card in the secret list. The choice lives here instead.
     */
    suspend fun readModels(): Map<String, String> =
        readPrefs().modelByProvider.filterKeys {
            ProviderRegistry.find(it) != null || isManagedProvider(it)
        }

    /** Persist [providerId] as the active provider. */
    suspend fun write(providerId: String) {
        update { it.copy(activeProviderId = providerId) }
    }

    /**
     * Endpoints for providers with no secret to attach settings to.
     *
     * A keyless local runtime (Ollama, vLLM) is configured by its endpoint alone, so that
     * endpoint is configuration that must survive a restart even though no credential
     * exists to carry it. Same reasoning as [readModels].
     */
    suspend fun readCustomEndpoints(): Map<String, String> =
        readPrefs().endpointByProvider.filterKeys { ProviderRegistry.find(it) != null }

    /** Persist [endpoint] as [providerId]'s endpoint, or forget it when blank. */
    suspend fun writeCustomEndpoint(
        providerId: String,
        endpoint: String,
    ) {
        update {
            val trimmed = endpoint.trim()
            it.copy(
                endpointByProvider =
                    if (trimmed.isEmpty()) {
                        it.endpointByProvider - providerId
                    } else {
                        it.endpointByProvider + (providerId to trimmed)
                    },
            )
        }
    }

    /** Persist [modelId] as [providerId]'s selected model. */
    suspend fun writeModel(
        providerId: String,
        modelId: String,
    ) {
        update { it.copy(modelByProvider = it.modelByProvider + (providerId to modelId)) }
    }

    private suspend fun readPrefs(): Prefs =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!file.exists()) return@runCatching null
                json.decodeFromString(Prefs.serializer(), file.readText())
            }.getOrNull() ?: Prefs()
        }

    /**
     * Read-modify-write under [mutex]: the active provider and the model map live in one
     * file, so concurrent writers would otherwise drop each other's field.
     */
    private suspend fun update(transform: (Prefs) -> Prefs) {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                runCatching {
                    val current =
                        if (file.exists()) {
                            runCatching { json.decodeFromString(Prefs.serializer(), file.readText()) }
                                .getOrDefault(Prefs())
                        } else {
                            Prefs()
                        }
                    file.parentFile?.mkdirs()
                    // Temp-then-rename, as with the model cache: writeText truncates in
                    // place, so an interrupted write loses both the active provider and
                    // every model selection at once.
                    val temp = File(file.parentFile, "${file.name}.tmp")
                    temp.writeText(json.encodeToString(Prefs.serializer(), transform(current)))
                    if (!temp.renameTo(file)) {
                        temp.copyTo(file, overwrite = true)
                        temp.delete()
                    }
                }.onFailure {
                    logger.warn(
                        LogCategory.SYSTEM,
                        "Could not persist AI provider preferences",
                        mapOf("exception" to (it::class.simpleName ?: "Exception")),
                    )
                }
                Unit
            }
        }
    }

    @Serializable
    private data class Prefs(
        val activeProviderId: String? = null,
        val modelByProvider: Map<String, String> = emptyMap(),
        val endpointByProvider: Map<String, String> = emptyMap(),
    )

    companion object {
        private const val FILE_NAME = "ai_provider_prefs.json"
    }
}
