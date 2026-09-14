package ai.rever.boss.plugin.dynamic.secretmanager.ai

import ai.rever.boss.plugin.api.LlmConfig
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The read path a consumer actually takes, which is not the one the store tests cover.
 *
 * `LlmProviderSettingsApiImpl.activeConfig` reads a snapshot that `ensureConnectionsLoaded`
 * populates exactly once - it is `compareAndSet(false, true)` guarded and never runs again - so
 * between a panel visit and a secret edit nothing calls `loadAll`, and the expiry cap inside
 * `ProviderCredentialStore` never gets a chance to notice. That is how a dead brokered key was
 * re-sent for eleven minutes: the store's cache was not what served it.
 *
 * These drive `activeConfig()` rather than `loadAll()` on purpose. A store-level test passes
 * whether or not the read path refreshes, which is exactly the gap that let the first version of
 * this fix look complete.
 *
 * `runBlocking` with a real scope, not `runTest`: the load parks on `Dispatchers.IO` inside the
 * store, so `advanceUntilIdle()` returns without waiting for it and every assertion here read an
 * empty snapshot. Progress is awaited on observable signals instead of slept for.
 */
class BrokeredReadPathTest {
    private val risa = ProviderRegistry.find(ProviderRegistry.RISA_GLM)!!
    private val scopes = mutableListOf<CoroutineScope>()

    /**
     * Counts mints atomically.
     *
     * Not `@Volatile var` plus `+= 1`: that is safe only because the per-broker mint lock
     * serialises mints, and the lock is one of the things these tests are about.
     */
    private class CountingSource(
        private val credential: (Int) -> Result<BrokeredKey>,
    ) : BrokeredKeySource {
        private val counter = java.util.concurrent.atomic.AtomicInteger(0)
        val calls: Int get() = counter.get()

        override suspend fun fetch(brokerId: String): Result<BrokeredKey> = credential(counter.incrementAndGet())
    }

    private class Harness(
        val api: LlmProviderSettingsApiImpl,
        val viewModel: AiProvidersViewModel,
        val store: ProviderCredentialStore,
    )

    private fun tempDir(prefix: String): File = Files.createTempDirectory(prefix).toFile()

    /** Hermetic: every source of variables is injected, so no exported key can leak in. */
    private fun envIn(dir: File): EnvResolver =
        EnvResolver(
            bossRootDir = dir.also { File(it, "env_vars").writeText("") },
            processEnv = { null },
            systemProperty = { null },
            useLaunchctl = false,
        )

    private suspend fun harnessWith(
        source: BrokeredKeySource,
        minRefreshIntervalMs: Long = 0,
        mintRetryBackoffMs: Long = 50,
        renewalLeadMs: Long = 0,
        // Effectively off by default, so the existing read-path tests are unaffected by a timer.
        minRenewalDelayMs: Long = 600_000,
    ): Harness {
        val root = tempDir("brokered-readpath")
        val env = envIn(root)
        val store = ProviderCredentialStore(FakeSecretDataProvider(emptyList()), env, mintRetryBackoffMs)
        store.brokeredKeys = source

        val prefs = ActiveProviderPrefs(bossRootDir = root)
        prefs.write(risa.id)

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob()).also { scopes.add(it) }
        val viewModel =
            AiProvidersViewModel(
                store = store,
                catalog = ModelCatalog(cacheDir = tempDir("brokered-catalog")),
                prefs = prefs,
                legacyImport = null,
                splitViewOperations = null,
                scope = scope,
                envResolver = env,
                minBrokeredRefreshIntervalMs = minRefreshIntervalMs,
                brokeredRenewalLeadMs = renewalLeadMs,
                minBrokeredRenewalDelayMs = minRenewalDelayMs,
                ollamaSystemCheck = noOllamaOnThisMachine(),
            )
        return Harness(LlmProviderSettingsApiImpl(viewModel), viewModel, store)
    }

    /** The first read only kicks the async load; this waits for the snapshot to exist. */
    private suspend fun Harness.loadedConfig(): LlmConfig? {
        api.activeConfig()
        withTimeout(TIMEOUT_MS) { viewModel.connectionsLoaded.first { it } }
        return api.activeConfig()
    }

    /** Waits for the broker to be asked [target] times, so no interval is guessed at. */
    private suspend fun CountingSource.awaitCalls(target: Int) {
        withTimeout(TIMEOUT_MS) {
            while (calls < target) delay(POLL_MS)
        }
    }

    /**
     * Waits until the mint count stops moving, and returns it.
     *
     * [loadedConfig] reads `activeConfig()` twice, and with a collapsed window the second read
     * can itself kick a refresh. Capturing a baseline while that is in flight made this suite
     * flake: the in-flight guard then swallowed the explicit read under test, so the count never
     * reached the expected value. Settling first makes the baseline meaningful.
     */
    private suspend fun CountingSource.settled(): Int {
        withTimeout(TIMEOUT_MS) {
            var previous = -1
            while (previous != calls) {
                previous = calls
                delay(SETTLE_MS)
            }
        }
        return calls
    }

    @AfterTest
    fun tearDown() {
        scopes.forEach { it.cancel() }
    }

    @Test
    fun `a lapsed brokered credential is re-minted on the next read`() =
        runBlocking {
            val source =
                CountingSource { issued ->
                    Result.success(
                        BrokeredKey(
                            token = "sk-$issued",
                            // What the gateway actually reported: an hour of reuse on a key with
                            // seconds of life. The cap collapses the window; this proves the read
                            // path then acts on it.
                            refreshAfterSeconds = 3600,
                            expiresAt = secondsFromNow(2),
                        ),
                    )
                }
            val harness = harnessWith(source)

            assertNotNull(harness.loadedConfig(), "activeConfig stayed null after the load")
            val afterLoad = source.settled()

            harness.api.activeConfig()
            source.awaitCalls(afterLoad + 1)

            assertEquals(afterLoad + 1, source.calls, "a lapsed credential was not re-minted")
        }

    @Test
    fun `a live brokered credential is not re-minted on every read`() =
        runBlocking {
            val source =
                CountingSource {
                    Result.success(
                        BrokeredKey("sk-live", refreshAfterSeconds = 3600, expiresAt = secondsFromNow(7200)),
                    )
                }
            val harness = harnessWith(source)

            assertNotNull(harness.loadedConfig(), "activeConfig stayed null after the load")
            val afterLoad = source.settled()

            repeat(5) { harness.api.activeConfig() }
            delay(SETTLE_MS)

            // Refreshing on every read would put a network mint behind a non-suspending api and
            // hammer the broker. Note this asserts a negative after a wait, so it can only
            // false-*pass* under load (a wrongly-triggered refresh landing after the check) -
            // the weaker direction, but the failure it guards is a loop that would show up
            // immediately in the other tests' counts.
            assertEquals(afterLoad, source.calls, "re-minted a credential that was still good")
        }

    @Test
    fun `the refresh serves the new credential to the next reader`() =
        runBlocking {
            val source =
                CountingSource { issued ->
                    // The first credential is already dying; its replacement is healthy.
                    Result.success(
                        BrokeredKey(
                            token = "sk-$issued",
                            refreshAfterSeconds = 3600,
                            expiresAt = if (issued == 1) secondsFromNow(2) else secondsFromNow(7200),
                        ),
                    )
                }
            val harness = harnessWith(source)

            // Deliberately not asserting the snapshot still holds sk-1 first: `loadedConfig`'s
            // second read itself triggers the refresh, and with an in-memory source the launched
            // reload can land before the calling thread's next field read. That race is one field
            // read wide and would almost always win, which is the worst kind of flake.
            assertNotNull(harness.loadedConfig(), "activeConfig stayed null after the load")

            harness.api.activeConfig()
            source.awaitCalls(2)
            withTimeout(TIMEOUT_MS) {
                while (harness.api.activeConfig()?.apiKey == "sk-1") delay(POLL_MS)
            }

            // The whole point: a consumer reading again gets a working key, with nobody visiting
            // the panel or editing a secret.
            assertEquals("sk-2", harness.api.activeConfig()?.apiKey)
        }

    @Test
    fun `configuredProviders also notices a lapsed credential`() =
        runBlocking {
            // The same wedge one method over: `configuredProviders` hands out every configured
            // provider's key from the same once-loaded snapshot, so hooking only `activeConfig`
            // left this path serving the dead token. Both go through `configFor`, which is where
            // the hook now lives.
            val source =
                CountingSource { issued ->
                    Result.success(
                        BrokeredKey("sk-$issued", refreshAfterSeconds = 3600, expiresAt = secondsFromNow(2)),
                    )
                }
            val harness = harnessWith(source)

            assertNotNull(harness.loadedConfig(), "activeConfig stayed null after the load")
            val afterLoad = source.settled()

            harness.api.configuredProviders()
            source.awaitCalls(afterLoad + 1)

            assertEquals(afterLoad + 1, source.calls, "configuredProviders did not re-mint")
        }

    @Test
    fun `a failed re-mint is retried rather than left terminal`() =
        runBlocking {
            // A mint failure is deliberately not cached, and treating "nothing cached" as "not
            // lapsed" made a single network blip terminal on this path: nothing calls loadAll
            // again, so the provider stayed unconfigured until the panel was opened. A recorded
            // failure time gives bounded retry instead.
            val failFirst =
                CountingSource { issued ->
                    if (issued <= 2) {
                        Result.failure(IllegalStateException("broker unreachable"))
                    } else {
                        Result.success(
                            BrokeredKey("sk-$issued", refreshAfterSeconds = 3600, expiresAt = secondsFromNow(7200)),
                        )
                    }
                }
            val harness = harnessWith(failFirst)

            // The load itself fails to mint, so there is no config at all yet.
            harness.api.activeConfig()
            withTimeout(TIMEOUT_MS) { harness.viewModel.connectionsLoaded.first { it } }
            val afterFailure = failFirst.settled()

            // The harness injects a short backoff, but the retry still only becomes due after it
            // elapses, so this reads until it lands rather than asserting on a single call.
            withTimeout(TIMEOUT_MS) {
                while (failFirst.calls <= afterFailure) {
                    harness.api.activeConfig()
                    delay(RETRY_POLL_MS)
                }
            }

            assertTrue(failFirst.calls > afterFailure, "a failed mint was never retried")
        }

    @Test
    fun `configuredProviders retries a failed initial mint before filtering`() =
        runBlocking {
            val failFirst =
                CountingSource { issued ->
                    if (issued == 1) Result.failure(IllegalStateException("broker unavailable"))
                    else Result.success(BrokeredKey("sk-$issued", 3600, secondsFromNow(7200)))
                }
            val harness = harnessWith(failFirst)

            harness.api.configuredProviders()
            withTimeout(TIMEOUT_MS) { harness.viewModel.connectionsLoaded.first { it } }
            val afterFailure = failFirst.settled()
            withTimeout(TIMEOUT_MS) {
                while (failFirst.calls <= afterFailure) {
                    harness.api.configuredProviders()
                    delay(RETRY_POLL_MS)
                }
            }

            assertTrue(failFirst.calls > afterFailure, "the listing filter bypassed the retry hook")
        }

    @Test
    fun `the refresh interval floor bounds a permanently lapsed credential`() =
        runBlocking {
            // A window that collapses to zero makes the credential lapsed again immediately after
            // every mint, so without a floor a polling consumer drives a continuous loop of broker
            // round-trips. (Not secret-store re-pages: `loadStoredSecrets` returns from `cached`.)
            val source =
                CountingSource { issued ->
                    Result.success(
                        BrokeredKey("sk-$issued", refreshAfterSeconds = 0, expiresAt = secondsFromNow(1)),
                    )
                }
            val harness = harnessWith(source, minRefreshIntervalMs = 60_000)

            harness.api.activeConfig()
            withTimeout(TIMEOUT_MS) { harness.viewModel.connectionsLoaded.first { it } }
            val afterLoad = source.settled()

            // Let the first refresh happen and finish. `lastBrokeredRefreshMs` starts at zero, so
            // the floor never blocks the first one - that is deliberate, or a genuinely lapsed
            // credential would have to wait out the interval.
            harness.api.activeConfig()
            source.awaitCalls(afterLoad + 1)
            val afterFirstRefresh = source.settled()

            // Reads *spaced out*, each finding no refresh in flight. This is what distinguishes
            // the floor from the in-flight guard: an earlier version of this test fired twenty
            // reads in a tight loop, which the in-flight guard alone collapses into one mint, so
            // it passed with the floor removed.
            repeat(5) {
                harness.api.activeConfig()
                delay(SETTLE_MS)
            }

            assertEquals(afterFirstRefresh, source.calls, "the floor did not bound the refresh rate")
        }

    private fun secondsFromNow(seconds: Long): String =
        java.time.OffsetDateTime
            .now(java.time.ZoneOffset.UTC)
            .plusSeconds(seconds)
            .toString()

    private companion object {
        const val TIMEOUT_MS = 15_000L
        const val POLL_MS = 20L

        /** Long enough that a wrongly-triggered refresh would have landed and been counted. */
        const val SETTLE_MS = 300L

        /** How often to re-read while waiting for a backed-off retry to become due. */
        const val RETRY_POLL_MS = 200L
    }
    @Test
    fun `a brokered credential is renewed before it lapses, with no read at all`() =
        runBlocking {
            // The point of the whole change: nobody asks, and the credential is still replaced.
            // Reactively re-minting on the next read costs one rejected request every time a key
            // expires while the app is running, and that rejection reads as "go and fix this in
            // Settings" when there is nothing to fix - access is already granted.
            // A key with ten minutes left, so the *read* path has no reason to re-mint: with a
            // short-lived key a load-path read could satisfy this test and it would pass with the
            // renewal removed entirely. A large lead is what makes the timer fire immediately.
            val source =
                CountingSource { issued ->
                    Result.success(
                        BrokeredKey(
                            token = "sk-$issued",
                            refreshAfterSeconds = 3600,
                            expiresAt = secondsFromNow(600),
                        ),
                    )
                }
            val harness = harnessWith(source, renewalLeadMs = 600_000, minRenewalDelayMs = 150)

            assertNotNull(harness.loadedConfig(), "activeConfig stayed null after the load")
            // Deliberately NOT settled(): a renewal loop never goes quiet, which is the whole
            // point. The baseline is taken as-is and only an increase is asserted.
            val baseline = source.calls

            // No activeConfig() call between here and the assertion.
            source.awaitCalls(baseline + 1)

            assertTrue(source.calls > baseline, "the credential was never renewed on its own")
        }

    @Test
    fun `credential-free model discovery never mints a brokered token`() =
        runBlocking {
            val source =
                CountingSource { issued ->
                    Result.success(
                        BrokeredKey(
                            token = "sk-$issued",
                            refreshAfterSeconds = 3600,
                            expiresAt = secondsFromNow(7200),
                        ),
                    )
                }
            val harness = harnessWith(source)
            assertNotNull(harness.loadedConfig())
            source.settled()
            harness.store.expireBrokeredCache()
            val baseline = source.calls

            repeat(20) { harness.api.availableModels() }
            delay(SETTLE_MS)

            assertEquals(baseline, source.calls, "availableModels minted a credential")
        }

    @Test
    fun `routine broker renewal preserves its fixed model catalog`() =
        runBlocking {
            val source =
                CountingSource { issued ->
                    Result.success(
                        BrokeredKey(
                            token = "sk-$issued",
                            refreshAfterSeconds = 3600,
                            expiresAt = secondsFromNow(600),
                        ),
                    )
                }
            val harness = harnessWith(source, renewalLeadMs = 600_000, minRenewalDelayMs = 300)
            harness.api.availableModels()
            withTimeout(TIMEOUT_MS) { harness.viewModel.catalogsLoaded.first { it } }
            assertTrue(harness.viewModel.catalogStateOf(risa.id) is CatalogState.Loaded)
            val baseline = source.calls

            source.awaitCalls(baseline + 1)
            delay(POLL_MS)

            assertTrue(harness.viewModel.catalogsLoaded.value, "renewal reset catalogsLoaded")
            assertTrue(
                harness.viewModel.catalogStateOf(risa.id) is CatalogState.Loaded,
                "renewal cleared the fixed catalog",
            )
        }

    @Test
    fun `each renewal arms the next one`() =
        runBlocking {
            val source =
                CountingSource { issued ->
                    Result.success(
                        BrokeredKey(
                            token = "sk-$issued",
                            refreshAfterSeconds = 3600,
                            expiresAt = secondsFromNow(3),
                        ),
                    )
                }
            val harness = harnessWith(source, renewalLeadMs = 0, minRenewalDelayMs = 150)
            assertNotNull(harness.loadedConfig())
            val baseline = source.calls

            // Two, so this tests the reschedule rather than one armed timer.
            source.awaitCalls(baseline + 2)

            assertTrue(source.calls >= baseline + 2, "the renewal did not re-arm itself")
        }

    @Test
    fun `the armed delay is floored so a renewal loop cannot spin`() =
        runBlocking {
            // A deadline already in the past - what a key with seconds of life gives once the
            // reuse cap collapses its window. Without the floor this re-mints as fast as the
            // broker answers, which is hundreds of times in the second below.
            val source =
                CountingSource { issued ->
                    Result.success(
                        BrokeredKey(
                            token = "sk-$issued",
                            refreshAfterSeconds = 3600,
                            expiresAt = secondsFromNow(-60),
                        ),
                    )
                }
            val harness = harnessWith(source, renewalLeadMs = 0, minRenewalDelayMs = 300)
            assertNotNull(harness.loadedConfig())
            val baseline = source.calls

            delay(1_000)

            val renewals = source.calls - baseline
            assertTrue(renewals <= 5, "the floor did not hold: $renewals renewals in a second")
        }
}
