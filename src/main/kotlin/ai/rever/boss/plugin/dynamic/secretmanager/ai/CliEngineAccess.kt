package ai.rever.boss.plugin.dynamic.secretmanager.ai

/**
 * The local agent CLIs, as this panel needs to see them.
 *
 * A plugin-local mirror of the AI Gateway's `AiCliSessionAPI`, and the types below mirror its
 * types. Keeping every host reference inside one adapter makes the panel independently testable
 * and limits an incoherent optional-gateway installation to the Local CLI section.
 *
 * It exists at all because a CLI engine cannot be a [ProviderDescriptor]: there is no
 * endpoint, no credential, and nothing for the user to paste. What it offers is the auth they
 * already have - a `claude` or `codex` login from a terminal - which is why it is a section of
 * its own rather than a row in the provider list.
 */
interface CliEngineAccess {
    /** Engines the gateway can drive, in display order. Cheap; says nothing about installs. */
    fun engines(): List<CliEngineInfo>

    /** Whether [engineId] is installed and runnable. Spawns a process, so not per composition. */
    suspend fun health(engineId: String): CliEngineHealth

    /** The engine currently serving AI requests, or null when an HTTP provider is. */
    fun selectedEngineId(): String?

    /** Choose an engine, or null to hand AI requests back to the HTTP provider. */
    fun selectEngine(engineId: String?): Boolean
}

/** One engine, for a settings row. */
data class CliEngineInfo(
    val id: String,
    val displayName: String,
    val description: String = "",
    val installHint: String = "",
)

/**
 * Whether an engine can run.
 *
 * [Unknown] has no counterpart in the api and is this panel's own: a probe that has not
 * finished yet is a real state for a row that renders before it answers, and showing "not
 * installed" in the meantime would be a claim rather than a wait.
 */
sealed interface CliEngineHealth {
    data object Unknown : CliEngineHealth

    /** Runnable, and [version] as the CLI reported it. Note this does NOT mean signed in. */
    data class Ready(val version: String) : CliEngineHealth

    data class NotInstalled(val hint: String) : CliEngineHealth

    data class Failed(val message: String) : CliEngineHealth
}
