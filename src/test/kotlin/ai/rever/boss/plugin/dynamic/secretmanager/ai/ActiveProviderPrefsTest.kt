package ai.rever.boss.plugin.dynamic.secretmanager.ai

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the preference file's read-modify-write behaviour.
 *
 * One file holds the active provider *and* every per-provider model selection, so a write
 * that clobbers the other field, or a truncated write, loses configuration the user set by
 * hand. There is no second copy to recover from — for providers keyed by an environment
 * variable this file is the only record of the model choice.
 */
class ActiveProviderPrefsTest {
    private fun prefsIn(dir: File = Files.createTempDirectory("ai-prefs").toFile()): Pair<ActiveProviderPrefs, File> =
        ActiveProviderPrefs(bossRootDir = dir) to dir

    @Test
    fun `writing a model preserves the active provider and vice versa`() =
        runTest {
            // The two fields are written through the same transform, so a copy() that
            // dropped one would silently reset it on the next unrelated write.
            val (prefs, _) = prefsIn()
            prefs.write(ProviderRegistry.OPENAI)
            prefs.writeModel(ProviderRegistry.ANTHROPIC, "claude-opus-5")

            assertEquals(ProviderRegistry.OPENAI, prefs.read())
            assertEquals(mapOf(ProviderRegistry.ANTHROPIC to "claude-opus-5"), prefs.readModels())

            prefs.write(ProviderRegistry.TOGETHER)
            assertEquals(mapOf(ProviderRegistry.ANTHROPIC to "claude-opus-5"), prefs.readModels())
        }

    @Test
    fun `concurrent writers do not drop each other's entry`() =
        runTest {
            // Mutex + read-modify-write: without the lock, interleaved writers each read the
            // same base and the last one wins, losing the others' model selections.
            val (prefs, _) = prefsIn()
            val ids = ProviderRegistry.all.map { it.id }

            coroutineScope {
                ids.map { id -> async { prefs.writeModel(id, "model-for-$id") } }.awaitAll()
            }

            assertEquals(ids.size, prefs.readModels().size)
            ids.forEach { assertEquals("model-for-$it", prefs.readModels()[it]) }
        }

    @Test
    fun `fifty concurrent multi-instance rounds preserve every entry`() =
        runTest {
            // The UI and provider discovery paths construct their own preference objects.
            // A lock owned by one instance cannot protect their shared file.
            val root = Files.createTempDirectory("ai-prefs-stress").toFile()
            val ids = ProviderRegistry.all.map { it.id }

            repeat(50) { round ->
                val dir = File(root, "round-$round").apply { mkdirs() }
                coroutineScope {
                    ids.map { id ->
                        async {
                            ActiveProviderPrefs(dir).writeModel(id, "model-for-$id")
                        }
                    }.awaitAll()
                }

                val persisted = ActiveProviderPrefs(dir).readModels()
                assertEquals(ids.size, persisted.size, "round $round lost an entry")
                ids.forEach { assertEquals("model-for-$it", persisted[it], "round $round lost $it") }
            }
        }

    @Test
    fun `the temp file is not left behind after a write`() =
        runTest {
            // Temp-then-move is only safe if its unique sibling is consumed; a stale temp
            // beside the real file is how a half-written state survives to the next read.
            val (prefs, dir) = prefsIn()
            prefs.write(ProviderRegistry.XAI)

            assertFalse(
                dir.listFiles().orEmpty().any {
                    it.name.startsWith(".ai_provider_prefs.json-") && it.name.endsWith(".tmp")
                },
                "temp file survived the write",
            )
            assertEquals(ProviderRegistry.XAI, prefs.read())
        }

    @Test
    fun `independent field writers merge one complete preference record`() =
        runTest {
            val (_, dir) = prefsIn()

            coroutineScope {
                awaitAll(
                    async { ActiveProviderPrefs(dir).write(ProviderRegistry.OPENAI) },
                    async {
                        ActiveProviderPrefs(dir).writeModel(
                            ProviderRegistry.ANTHROPIC,
                            "claude-opus-5",
                        )
                    },
                    async {
                        ActiveProviderPrefs(dir).writeCustomEndpoint(
                            ProviderRegistry.CUSTOM,
                            "http://localhost:11434/v1",
                        )
                    },
                )
            }

            val persisted = ActiveProviderPrefs(dir)
            assertEquals(ProviderRegistry.OPENAI, persisted.read())
            assertEquals("claude-opus-5", persisted.readModels()[ProviderRegistry.ANTHROPIC])
            assertEquals(
                "http://localhost:11434/v1",
                persisted.readCustomEndpoints()[ProviderRegistry.CUSTOM],
            )
        }

    @Test
    fun `readers only observe complete records while the file is replaced`() =
        runTest {
            // Large values widen a truncate-and-copy implementation's partial-read window.
            // Every observed value must still be one complete committed generation.
            val (writer, dir) = prefsIn()
            val first = "a".repeat(256 * 1024)
            val second = "b".repeat(256 * 1024)
            writer.writeModel(ProviderRegistry.OPENAI, first)
            val start = CompletableDeferred<Unit>()

            coroutineScope {
                val writes =
                    async(Dispatchers.IO) {
                        start.await()
                        repeat(20) { generation ->
                            writer.writeModel(
                                ProviderRegistry.OPENAI,
                                if (generation % 2 == 0) second else first,
                            )
                        }
                    }
                val reads =
                    async(Dispatchers.IO) {
                        start.complete(Unit)
                        repeat(100) {
                            val observed = ActiveProviderPrefs(dir).readModels()[ProviderRegistry.OPENAI]
                            assertTrue(
                                observed == first || observed == second,
                                "reader observed a missing or partial preference record",
                            )
                        }
                    }

                awaitAll(writes, reads)
            }
        }

    @Test
    fun `a corrupt file degrades to defaults instead of throwing`() =
        runTest {
            // Hand-editable plain file: a parse failure must not take out provider
            // resolution, which awaits this read during registration.
            val (prefs, dir) = prefsIn()
            File(dir, "ai_provider_prefs.json").writeText("{not json")

            assertNull(prefs.read())
            assertEquals(emptyMap(), prefs.readModels())

            // ...and a later write repairs it rather than failing forever.
            prefs.write(ProviderRegistry.GOOGLE)
            assertEquals(ProviderRegistry.GOOGLE, prefs.read())
        }

    @Test
    fun `a provider id that no longer exists is ignored`() =
        runTest {
            // Ids are persisted, so a removed or renamed provider must not resurface as an
            // active selection that nothing can resolve.
            val (prefs, dir) = prefsIn()
            File(dir, "ai_provider_prefs.json").writeText(
                """{"activeProviderId":"RETIRED","modelByProvider":{"RETIRED":"m"}}""",
            )

            assertNull(prefs.read())
            assertEquals(emptyMap(), prefs.readModels())
        }
}
