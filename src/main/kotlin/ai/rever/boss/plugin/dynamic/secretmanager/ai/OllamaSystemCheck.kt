package ai.rever.boss.plugin.dynamic.secretmanager.ai

import java.awt.Desktop
import java.io.File
import java.lang.management.ManagementFactory
import java.net.URI

/**
 * What this machine can tell us about running Ollama locally: whether the binary is here at
 * all, and how much RAM there is to run something with.
 *
 * A plain snapshot rather than a live probe read on every access — [OllamaSystemCheck.current]
 * is the one place that actually touches the filesystem and `ManagementFactory`, so everything
 * downstream (the panel, its tests) works from a value instead of re-probing.
 */
data class OllamaSystemInfo(
    val binaryFound: Boolean,
    /** Total system RAM in GB, or null when this JVM could not report it. */
    val totalRamGb: Double?,
) {
    /**
     * Whether this machine can usefully run *any* model through Ollama.
     *
     * Unknown RAM reads as "yes", not "no": a bean lookup failing for a reason that has
     * nothing to do with the hardware (a non-HotSpot JVM, a locked-down module system) must
     * not silently take the whole provider away from someone whose machine is fine.
     */
    val meetsMinimum: Boolean
        get() = totalRamGb?.let { it >= OllamaSystemCheck.MIN_USABLE_RAM_GB } ?: true

    /** A short, curated shortlist for this machine's RAM — the single best-fitting tier, not a catalog. */
    val suggestedModels: List<SuggestedOllamaModel>
        get() = totalRamGb?.let { OllamaSystemCheck.suggestedModelsFor(it) }.orEmpty()
}

/** One model worth suggesting, and why. [tag] is what `ollama pull` and the model field both take. */
data class SuggestedOllamaModel(val tag: String, val note: String)

/**
 * Reads this machine's Ollama-relevant facts, and offers the one action this plugin can take
 * about the result: sending the user to the official installer.
 *
 * Every dependency is a constructor parameter with a real default — the same shape as
 * [GatewayPresence] next to it — so a test can hand this a fake machine without touching the
 * real filesystem, environment or `Desktop`.
 */
class OllamaSystemCheck(
    private val path: String = System.getenv("PATH").orEmpty(),
    private val home: String = System.getProperty("user.home").orEmpty(),
    private val isWindows: Boolean =
        System.getProperty("os.name")?.startsWith("Windows", ignoreCase = true) == true,
    private val isExecutable: (File) -> Boolean = ::isRunnableBinary,
    /** Total physical RAM in bytes, or null when it could not be read. */
    private val physicalMemoryBytes: () -> Long? = ::readTotalPhysicalMemoryBytes,
    /** Hands a URL to the platform's browser. Injected so the route is testable. */
    private val browse: (URI) -> Boolean = { uri ->
        runCatching {
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                return@runCatching false
            }
            Desktop.getDesktop().browse(uri)
            true
        }.getOrDefault(false)
    },
) {
    fun current(): OllamaSystemInfo =
        OllamaSystemInfo(
            binaryFound = findOllamaBinary() != null,
            totalRamGb = physicalMemoryBytes()?.let { it / BYTES_PER_GB },
        )

    /**
     * `ollama` (or `ollama.exe`) on `PATH`, or in the handful of directories it commonly
     * installs to when a Finder/Explorer-launched app inherits a `PATH` that never saw a
     * shell profile — the same lesson `commandPathIn` in the AI Gateway plugin documents for
     * Claude Code and Codex.
     */
    private fun findOllamaBinary(): File? {
        val command = if (isWindows) "ollama.exe" else "ollama"
        return (path.split(File.pathSeparatorChar) + commonInstallDirs())
            .filter { it.isNotBlank() }
            .map { dir -> File(expandHome(dir), command) }
            .firstOrNull(isExecutable)
    }

    private fun commonInstallDirs(): List<String> =
        if (isWindows) {
            listOf("~/AppData/Local/Programs/Ollama")
        } else {
            listOf("/usr/local/bin", "/opt/homebrew/bin", "~/.local/bin", "~/.ollama/bin")
        }

    private fun expandHome(dir: String): String = if (dir.startsWith("~/")) home + dir.removePrefix("~") else dir

    /** Sends the user to Ollama's own installer. False when there is no browser to hand it to. */
    fun openInstallPage(): Boolean = browse(URI(INSTALL_URL))

    companion object {
        const val INSTALL_URL = "https://ollama.com/download"

        /**
         * Whether a candidate path is the runnable binary — the default [isExecutable].
         *
         * `isFile` as well as `canExecute`: `canExecute` is true for a **directory** carrying
         * the x bit, so on its own it reports the binary found on a machine that merely has an
         * `~/.ollama/bin/ollama/` directory. Named rather than a lambda so the rule itself is
         * testable; a test that went through the constructor would have to inject over it, and
         * would then be asserting on its own fake.
         */
        fun isRunnableBinary(file: File): Boolean =
            runCatching { file.isFile && file.canExecute() }.getOrDefault(false)

        /**
         * Ollama's own published floor for running its smallest models at all (see its
         * README: "You should have at least 8 GB of RAM available to run the 7B models").
         * Below this, no model is worth offering.
         */
        const val MIN_USABLE_RAM_GB = 8.0

        private const val BYTES_PER_GB = 1024.0 * 1024.0 * 1024.0

        /**
         * Total physical RAM as this JVM's `com.sun.management.OperatingSystemMXBean` reports
         * it, or null when that bean is unavailable. `getTotalMemorySize()` has been the
         * non-deprecated name since JDK 14; this plugin's floor is 17.
         */
        private fun readTotalPhysicalMemoryBytes(): Long? =
            runCatching {
                (ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean)
                    ?.totalMemorySize
            }.getOrNull()

        /**
         * A short, curated shortlist for [totalRamGb] — the single tier that best fits, largest
         * first within it, not an exhaustive catalog. Thresholds are Ollama's own published
         * minimums for running a model of that scale at all (7B/13B/33B need 8/16/32 GB), with
         * a 64 GB tier added for 70B by the same doubling the published figures already follow.
         */
        fun suggestedModelsFor(totalRamGb: Double): List<SuggestedOllamaModel> =
            RAM_TIERS.firstOrNull { totalRamGb >= it.first }?.second.orEmpty()

        /**
         * The one hardcoded model list in this plugin, and a deliberate exception to the rule
         * `ModelCatalogClient` exists to enforce ("the host implementation this replaced shipped
         * hardcoded models that drifted years out of date"). These are *suggestions for a machine
         * that has pulled nothing yet* — there is no endpoint to ask, because the honest answer
         * from an empty daemon is an empty list. The live picker stays the only authority on what
         * is actually installed.
         *
         * The tags will drift. When `llama3.1:70b` stops being the right suggestion for a 64 GB
         * machine, this list is the only place to change — nothing keys off these strings, and a
         * tag that no longer exists surfaces as a failed pull rather than a silent wrong model.
         */
        private val RAM_TIERS: List<Pair<Double, List<SuggestedOllamaModel>>> =
            listOf(
                64.0 to
                    listOf(
                        SuggestedOllamaModel("llama3.1:70b", "70B — the most capable of this shortlist"),
                        SuggestedOllamaModel("qwen2.5:72b", "72B"),
                    ),
                32.0 to
                    listOf(
                        SuggestedOllamaModel("qwen2.5:32b", "32B"),
                        SuggestedOllamaModel("codellama:34b", "34B, code-focused"),
                    ),
                16.0 to
                    listOf(
                        SuggestedOllamaModel("llama3.1:8b", "8B — a solid general default"),
                        SuggestedOllamaModel("mistral:7b", "7B"),
                        SuggestedOllamaModel("gemma2:9b", "9B"),
                    ),
                MIN_USABLE_RAM_GB to
                    listOf(
                        SuggestedOllamaModel("llama3.2:3b", "3B — fits comfortably"),
                        SuggestedOllamaModel("phi3:mini", "3.8B"),
                    ),
            )
    }
}
