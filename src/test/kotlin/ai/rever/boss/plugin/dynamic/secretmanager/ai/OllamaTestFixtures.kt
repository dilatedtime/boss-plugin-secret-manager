package ai.rever.boss.plugin.dynamic.secretmanager.ai

/**
 * A machine with no Ollama on it, built entirely from injected answers.
 *
 * Every `AiProvidersViewModel` a test constructs needs one. `init` calls
 * `refreshOllamaSystemInfo()`, so the default `OllamaSystemCheck()` would read the real
 * `PATH`, the real `user.home` and the real JMX bean — which is how a suite whose whole
 * point is "every source of variables is injected" quietly stops being hermetic, and
 * whose result then depends on whether the machine running it happens to have Ollama.
 */
internal fun noOllamaOnThisMachine(): OllamaSystemCheck =
    OllamaSystemCheck(
        path = "",
        home = "",
        isWindows = false,
        isExecutable = { false },
        physicalMemoryBytes = { null },
        browse = { false },
    )
