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
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE

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
        preferenceMutex.withLock {
            withContext(Dispatchers.IO) {
                runCatching {
                    if (!file.exists()) return@runCatching null
                    json.decodeFromString(Prefs.serializer(), file.readText())
                }.getOrNull() ?: Prefs()
            }
        }

    /**
     * Read-modify-write under the process-wide [preferenceMutex]: separate UI and
     * discovery objects share this file, so an instance-owned lock still loses updates.
     * Reads take the same lock because Windows denies replacement while another accessor
     * has the target open.
     */
    private suspend fun update(transform: (Prefs) -> Prefs) {
        preferenceMutex.withLock {
            withContext(Dispatchers.IO) {
                runCatching {
                    val current =
                        if (file.exists()) {
                            runCatching { json.decodeFromString(Prefs.serializer(), file.readText()) }
                                .getOrDefault(Prefs())
                        } else {
                            Prefs()
                        }
                    replaceAtomically(
                        json.encodeToString(Prefs.serializer(), transform(current)),
                    )
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

    /**
     * Flush a complete record before replacing the live file. A unique sibling avoids
     * fixed-temp collisions, while the non-atomic fallback covers file systems that do
     * not implement [ATOMIC_MOVE] without returning to truncate-in-place writes.
     */
    private fun replaceAtomically(contents: String) {
        val target = file.toPath()
        val parent = target.parent ?: error("Preference file has no parent")
        Files.createDirectories(parent)
        val temp = Files.createTempFile(parent, ".$FILE_NAME-", ".tmp")

        try {
            FileChannel.open(temp, WRITE, TRUNCATE_EXISTING).use { channel ->
                val bytes = ByteBuffer.wrap(contents.toByteArray(Charsets.UTF_8))
                while (bytes.hasRemaining()) channel.write(bytes)
                channel.force(true)
            }
            try {
                Files.move(temp, target, ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, target, REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
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

        // There is one preference record per BOSS process. Keep its transaction lock at
        // the same lifetime so independently constructed accessors cannot race.
        private val preferenceMutex = Mutex()
    }
}
