package ai.rever.boss.plugin.dynamic.secretmanager.ai

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The editor card's own state machine, and the Ollama install flow that writes to it.
 *
 * This ViewModel is the plugin's **single instance**, shared by the sidebar AI tab and the
 * host's `Settings → AI Providers`. That is what makes "which card is open" worth a test: a
 * per-panel ViewModel would be reconstructed on every entry, so the flag would reset for free;
 * this one carries whatever the last visit left behind, to both surfaces.
 */
class AiProvidersPanelStateTest {
    private val scopes = mutableListOf<CoroutineScope>()

    @AfterTest
    fun stopScopes() = scopes.forEach { it.cancel() }

    private fun tempDir(prefix: String): File = Files.createTempDirectory(prefix).toFile()

    /** Hermetic: every source of variables is injected, so no exported key can leak in. */
    private fun envIn(dir: File): EnvResolver =
        EnvResolver(
            bossRootDir = dir.also { File(it, "env_vars").writeText("") },
            processEnv = { null },
            systemProperty = { null },
            useLaunchctl = false,
        )

    private fun viewModel(
        pullBody: String = """{"status":"success"}""",
        catalogBody: String = """{"data":[{"id":"llama3.2:3b"}]}""",
        ollamaInstalled: Boolean = true,
        onRequest: (java.net.http.HttpRequest) -> Unit = {},
        root: File = tempDir("panel-state"),
        initialEnv: String = "",
        legacyFile: File? = null,
        secrets: FakeSecretDataProvider = FakeSecretDataProvider(emptyList()),
    ): AiProvidersViewModel {
        val env = envIn(root)
        File(root, "env_vars").writeText(initialEnv)
        val store = ProviderCredentialStore(secrets, env)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob()).also { scopes.add(it) }
        val http = FakeStreamingHttpClient(pullBody = pullBody, catalogBody = catalogBody, onRequest = onRequest)
        return AiProvidersViewModel(
            store = store,
            catalog = ModelCatalog(client = ModelCatalogClient(http), cacheDir = tempDir("panel-state-catalog")),
            prefs = ActiveProviderPrefs(bossRootDir = root),
            legacyImport = legacyFile?.let { file ->
                LegacySettingsImport(store, env, root, sources = listOf(
                    LegacySettingsImport.LegacySource(file) { mapOf("OPENAI" to "legacy-test-key") },
                ))
            },
            splitViewOperations = null,
            scope = scope,
            envResolver = env,
            ollamaSystemCheck =
                OllamaSystemCheck(
                    path = "",
                    home = "",
                    isWindows = false,
                    isExecutable = { ollamaInstalled },
                    physicalMemoryBytes = { SIXTEEN_GB },
                    browse = { false },
                ),
            ollamaModelInstaller = OllamaModelInstaller(httpClient = http),
        )
    }

    /** `load()` launches; this waits for the pass that clears `isLoading` to have finished. */
    private suspend fun AiProvidersViewModel.loaded() {
        withTimeout(TIMEOUT_MS) { enterSection().join() }
    }

    @Test
    fun enteringTheSectionDoesNotReopenAnEditorLeftOpenOnAPreviousVisit() = runBlocking {
        val vm = viewModel()
        vm.loaded()

        vm.selectProvider(ProviderRegistry.ANTHROPIC)
        assertTrue(vm.state.value.isEditorOpen)

        // Leaving and re-entering the section is exactly this: the panel's LaunchedEffect
        // calls enterSection() again on the same instance.
        vm.loaded()

        assertFalse(vm.state.value.isEditorOpen, "a reload must not carry a previous visit's open card")
        // The *selection* is deliberately kept - reopening returns to the same provider.
        assertEquals(ProviderRegistry.ANTHROPIC, vm.state.value.selectedProviderId)
    }

    @Test
    fun firstSectionEntryPreservesTheProviderOpenedFromASecretCard() = runBlocking {
        val vm = viewModel()
        assertTrue(vm.requestProviderOnEntry(ProviderRegistry.ANTHROPIC))
        vm.loaded()

        assertTrue(vm.state.value.isEditorOpen)
        assertEquals(ProviderRegistry.ANTHROPIC, vm.state.value.selectedProviderId)
        vm.loaded()
        assertFalse(vm.state.value.isEditorOpen, "the navigation command is consumed once")
    }

    @Test
    fun unknownProviderLinksDoNotOpenAnotherProvider() = runBlocking {
        val vm = viewModel()
        assertFalse(vm.requestProviderOnEntry("removed-provider"))
        vm.loaded()
        assertFalse(vm.state.value.isEditorOpen)
        vm.selectProvider("removed-provider")
        assertFalse(vm.state.value.isEditorOpen)
        assertNotNull(vm.state.value.error)
    }

    @Test
    fun clickingAnExpandedProviderClosesItAndDropsItsDraft() = runBlocking {
        val vm = viewModel()
        vm.loaded()
        vm.toggleProvider(ProviderRegistry.ANTHROPIC)
        assertTrue(vm.state.value.isEditorOpen)
        vm.updateKeyDraft(ProviderRegistry.ANTHROPIC, "unsaved-test-key")
        vm.toggleProvider(ProviderRegistry.ANTHROPIC)
        assertFalse(vm.state.value.isEditorOpen)
        assertNull(vm.state.value.keyDrafts[ProviderRegistry.ANTHROPIC])
    }

    @Test
    fun sectionEntryReloadsEnvironmentAndOffersLegacyImport() = runBlocking {
        val root = tempDir("entry-env")
        val legacy = File(root, "legacy.json").also { it.writeText("legacy fixture") }
        val vm = viewModel(root = root, initialEnv = "OPENAI_API_KEY=env-test-key", legacyFile = legacy)
        vm.loaded()
        assertEquals(CredentialSource.ENVIRONMENT, vm.state.value.connectionOf(ProviderRegistry.OPENAI).source)
        File(root, "env_vars").writeText("")
        vm.loaded()
        assertEquals(CredentialSource.NONE, vm.state.value.connectionOf(ProviderRegistry.OPENAI).source)
        assertNotNull(vm.state.value.legacyOffer)
    }

    @Test
    fun sectionEntryChecksLocalOllamaEvenWhenVaultReadsFail() = runBlocking {
        val vm = viewModel(secrets = FakeSecretDataProvider(emptyList(), failReads = true))
        vm.loaded()
        assertFalse(vm.state.value.storeAvailable)
        assertNotNull(vm.state.value.ollamaSystemInfo)
    }

    @Test
    fun cancellingTheEditorDropsTheUnsavedKeyDraft() = runBlocking {
        val vm = viewModel()
        vm.loaded()

        vm.selectProvider(ProviderRegistry.ANTHROPIC)
        vm.updateKeyDraft(ProviderRegistry.ANTHROPIC, "sk-ant-not-saved")
        vm.closeEditor()

        assertNull(
            vm.state.value.keyDrafts[ProviderRegistry.ANTHROPIC],
            "an unsaved key must not outlive the card - this instance is shared with Settings",
        )
    }

    @Test
    fun addingAKeylessProviderIsRememberedSoItsRowSurvivesClosingTheCard() = runBlocking {
        val vm = viewModel(ollamaInstalled = false)
        vm.loaded()

        vm.selectProvider(ProviderRegistry.OLLAMA)
        vm.closeEditor()

        assertTrue(ProviderRegistry.OLLAMA in vm.state.value.addedProviderIds)
    }

    @Test
    fun aSuccessfulPullSelectsTheModelAndClearsTheBusyFlag() = runBlocking {
        val vm = viewModel()
        vm.loaded()

        vm.installOllamaModel("llama3.2:3b")
        // The success notice is published immediately before the finally block releases the
        // pull guard and clears the busy tag. Await the complete terminal state rather than
        // racing that cleanup on a faster CI dispatcher.
        withTimeout(TIMEOUT_MS) {
            vm.state.first {
                it.notice == "Pulled llama3.2:3b." &&
                    it.installingOllamaModelTag == null &&
                    it.connectionOf(ProviderRegistry.OLLAMA).selectedModelId == "llama3.2:3b"
            }
        }

        val state = vm.state.value
        assertEquals("Pulled llama3.2:3b.", state.notice)
        assertNull(state.installingOllamaModelTag)
        assertEquals("llama3.2:3b", state.connectionOf(ProviderRegistry.OLLAMA).selectedModelId)
    }

    @Test
    fun aPullThatFailsMidStreamDoesNotSelectAModelThatIsNotThere() = runBlocking {
        // The whole reason the installer's success rule is positive: this body is an HTTP 200
        // whose last line carries no `status` at all, so a scan for an error status reads it as
        // a success - and the selection persisted here is what other plugins are then handed as
        // LlmConfig.modelId.
        val vm =
            viewModel(pullBody = """{"status":"pulling manifest"}
{"error":"pull model manifest: file does not exist"}""")
        vm.loaded()

        vm.installOllamaModel("nonexistent:1b")
        withTimeout(TIMEOUT_MS) { vm.state.first { it.error != null } }

        val state = vm.state.value
        assertNotNull(state.error)
        assertTrue(state.error!!.contains("pull model manifest"), state.error!!)
        assertNull(state.notice)
        assertNull(state.installingOllamaModelTag)
        assertNull(state.connectionOf(ProviderRegistry.OLLAMA).selectedModelId)
    }

    @Test
    fun aSecondPullIsRefusedWhileTheFirstIsStillRunning() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pulls = AtomicInteger()
        val vm = viewModel(onRequest = { request ->
            if (request.uri().path == "/api/pull") {
                pulls.incrementAndGet()
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        })
        vm.loaded()

        try {
            vm.installOllamaModel("llama3.2:3b")
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            // Hold the first request in flight: a fast fake could otherwise finish
            // before the second click, making a second pull correct behavior.
            vm.installOllamaModel("mistral:7b")
        } finally {
            release.countDown()
        }

        withTimeout(TIMEOUT_MS) {
            vm.state.first { it.notice != null && it.installingOllamaModelTag == null }
        }
        assertEquals(1, pulls.get())
        assertEquals("Pulled llama3.2:3b.", vm.state.value.notice, "the second press must not have started a pull")
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val SIXTEEN_GB = 16L * 1024 * 1024 * 1024
    }
}
