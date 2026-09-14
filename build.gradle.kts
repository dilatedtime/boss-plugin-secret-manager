import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.serialization") version "2.3.0"
    id("org.jetbrains.compose") version "1.10.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.0"
}

group = "ai.rever.boss.plugin.dynamic"
version = "1.2.26"

// Keep the ordinary JAR away from the plugin artifact and the release's build/libs/*.jar glob.
tasks.jar { destinationDirectory.set(layout.buildDirectory.dir("intermediates/jar")) }

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

composeCompiler {
    // Resolves ai.rever.boss.plugin.logging stability at compile time so no runtime
    // `$stable` read is emitted for it anywhere in this module. See compose-stability.conf
    // for why that is load-bearing rather than an optimisation.
    stabilityConfigurationFiles.add(layout.projectDirectory.file("compose-stability.conf"))
}

// Auto-detect CI environment
val useLocalDependencies = System.getenv("CI") != "true"
val bossPluginApiPath = "../boss-plugin-api"

/**
 * The most recently built api jar in the sibling checkout, whatever its version.
 *
 * Local development only — CI uses the downloaded jar. Deliberately not a hardcoded file
 * name: that goes stale on every api release and surfaces as "Unresolved reference" on a
 * symbol that plainly exists, which is a genuinely confusing hour. Newest-by-mtime rather
 * than by version string, because 1.0.9 sorts above 1.0.71 lexicographically and the jar
 * you just built is the one you meant.
 */
val localBossPluginApiJar: File? =
    file("$bossPluginApiPath/build/libs")
        .listFiles { f: File -> f.name.startsWith("boss-plugin-api-") && f.name.endsWith(".jar") }
        ?.filterNot { it.name.contains("-sources") || it.name.contains("-thin") }
        ?.maxByOrNull { it.lastModified() }

repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    if (useLocalDependencies) {
        // Local development: use boss-plugin-api JAR from sibling repo.
        // plugin.json declares apiVersion and minApiVersion 1.0.89, the release that added
        // AiProviderModels/AiAvailableModel. Those types occur in an override signature, so
        // a construction-time LinkageError guard cannot make an older host safe: method types
        // may resolve later and the host can scan constant-pool references before registration.
        // See AGENTS.md "Linkage containment".
        compileOnly(
            files(
                localBossPluginApiJar
                    ?: error(
                        "No boss-plugin-api jar in $bossPluginApiPath/build/libs — " +
                            "run ./gradlew jar in the sibling boss-plugin-api checkout first.",
                    ),
            ),
        )
    } else {
        // CI: use downloaded JAR
        compileOnly(files("build/downloaded-deps/boss-plugin-api.jar"))
    }

    // Compose dependencies
    implementation(compose.desktop.currentOs)
    implementation(compose.runtime)
    implementation(compose.ui)
    implementation(compose.foundation)
    implementation(compose.material)
    implementation(compose.materialIconsExtended)

    // Compose Icons (FeatherIcons)
    implementation("br.com.devsrsouza.compose.icons:feather:1.1.1")

    // Decompose for ComponentContext
    implementation("com.arkivanov.decompose:decompose:3.3.0")
    implementation("com.arkivanov.essenty:lifecycle:2.5.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    // Serialization (for JSON parsing of SupabaseDataProvider responses)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Tests. The model-list parsers are hand-written from provider docs and had no
    // coverage; these run without a host or a live credential.
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    // BossLogger binds slf4j at class-init, so a backend is required or every class
    // holding a logger fails with NoClassDefFoundError in tests. The host provides one
    // at runtime; tests have to supply their own.
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.17")
    // The api is compileOnly (the host supplies it at runtime), so it is absent from the
    // test runtime by default — BossLogger lives there and would fail with
    // NoClassDefFoundError. Tests need it on the classpath explicitly.
    if (useLocalDependencies) {
        testImplementation(files(localBossPluginApiJar ?: error("No boss-plugin-api jar; see above.")))
    } else {
        testImplementation(files("build/downloaded-deps/boss-plugin-api.jar"))
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // An *independent* source of truth for PluginVersionTest. Asserting the reported version
    // against the bundled plugin.json is circular — both read the same file, so any value in
    // it passes, including a stale one. This comes from Gradle instead.
    systemProperty("boss.plugin.expectedVersion", version.toString())
}

// Packaged-jar assertions, shared by buildPluginJar and shadowJar so the two cannot drift.
// A lambda rather than a top-level fun: it needs the script receiver for zipTree().
val verifyPackagedJar: (File, String) -> Unit = { jar, expectedVersion ->
    // 1. No runtime Compose `$stable` read against ai.rever.boss.plugin.logging.
    //
    // boss-plugin-api ships that package and IS a Compose project, so its ComponentLogger has the
    // field; the host bundles its own copy WITHOUT it and shadows the api's parent-first inside
    // plugin classloaders. An emitted read links at build time and is missing at load time, so
    // BinaryCompatibilityValidator rejects the WHOLE plugin — that is what made 1.2.6 and 1.2.7
    // unloadable. compose-stability.conf should prevent it module-wide; this proves it did.
    //
    // javap, not constant-pool string matching: a raw scan flags every class that merely has its
    // own $stable AND mentions the package, which is most of them. The fieldref is what matters.
    // ai/rever/** rather than a hardcoded package: catches a package move inside our own
    // namespace, while excluding vendored dependency classes — shadowJar bundles the whole
    // runtime classpath and scanning kotlin-stdlib for our logging refs is pointless.
    val classNames = mutableListOf<String>()
    zipTree(jar).matching { include("ai/rever/**/*.class") }.visit {
        if (!isDirectory) {
            classNames += relativePath.pathString.removeSuffix(".class").replace('/', '.')
        }
    }

    if (classNames.isNotEmpty()) {
        val javapExe = File(System.getProperty("java.home"), "bin/javap").absolutePath

        // Chunked, because javap does NOT support @argfile (it reads it as a class name) and one
        // splatted list blows ARG_MAX on the fat jar — "Argument list too long". 150 keeps every
        // chunk well under Windows' 8 KB command limit too.
        val disassembly =
            classNames.chunked(150).joinToString("\n") { chunk ->
                val process =
                    ProcessBuilder(
                        listOf(javapExe, "-p", "-c", "-cp", jar.absolutePath) + chunk,
                    ).redirectErrorStream(true)
                        .start()
                val chunkOutput = process.inputStream.bufferedReader().use { it.readText() }
                val exit = process.waitFor()
                // Without this the guard passes for the WRONG reason: a javap that errored, was
                // missing from a JRE-shaped java.home, or got a truncated arg list simply produces
                // output that does not contain the pattern.
                require(exit == 0) {
                    "javap exited $exit while scanning ${jar.name}; the bytecode guard did not " +
                        "run:\n" + chunkOutput.take(1000)
                }
                chunkOutput
            }

        // Positive control: prove the guard actually saw every class it claims to have checked.
        val disassembled = disassembly.lineSequence().count { it.startsWith("Compiled from") }
        require(disassembled == classNames.size) {
            "javap disassembled $disassembled of ${classNames.size} packaged classes in " +
                "${jar.name} — the guard did not see everything"
        }

        val bad =
            disassembly
                .lineSequence()
                .filter { line ->
                    line.contains("boss/plugin/logging/") && line.contains("\u0024stable")
                }.toList()
        require(bad.isEmpty()) {
            "Bytecode references a Compose \$stable field on ai.rever.boss.plugin.logging, which the " +
                "host's bundled plugin-logging jar does not have — the host would disable this plugin " +
                "as binary incompatible (as it did for 1.2.6 and 1.2.7). Check that " +
                "compose-stability.conf still lists that package.\n" +
                bad.joinToString("\n").take(1000)
        }
    }

    // 2. The packaged plugin.json declares the version being built.
    //
    // The committed copy says 0.0.0-unstamped; processResources stamps the one that reaches the
    // jar. Asserting the CONTENT, not the entry count: duplicatesStrategy = EXCLUDE means exactly
    // one is ever packaged, so counting can never fail (verified — that was tried first).
    val entry = zipTree(jar).matching { include("META-INF/boss-plugin/plugin.json") }.singleFile
    val declared =
        Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(entry.readText())?.groupValues?.get(1)
    require(declared == expectedVersion) {
        "plugin.json in ${jar.name} declares version '$declared' but the build is " +
            "'$expectedVersion' — processResources did not stamp the copy that reached the jar."
    }
}

/**
 * The two panel files must not grow a loose `fontSize` again.
 *
 * They carried **118** of them across nine sizes with no rule about which meant what; they are
 * now at zero, and every size comes from `SecretPanelType`. Nothing renders in the test suite, so
 * no test can see this erode - a contributor adding one `fontSize = 13.sp` gets a green build and
 * the drift is invisible until it is 118 again. Same reasoning as the `javap` `$stable` guard and
 * the plugin.json stamp assertion: the invariant nothing else can reach is the one worth pinning
 * at build time.
 *
 * Scoped to the two files that were converted. `ai/AiProvidersPanel.kt` is in the same sweep now,
 * so if it ever needs to be exempted again, add it here rather than dropping the check.
 */
fun verifyNoLooseFontSizes() {
    val guarded =
        listOf(
            "src/main/kotlin/ai/rever/boss/plugin/dynamic/secretmanager/SecretManagerContent.kt",
            "src/main/kotlin/ai/rever/boss/plugin/dynamic/secretmanager/SharedSecretsSection.kt",
            "src/main/kotlin/ai/rever/boss/plugin/dynamic/secretmanager/ai/AiProvidersPanel.kt"
        ).map { file(it) }

    // A missing file must fail, not silently pass: a rename would otherwise retire the guard.
    val missing = guarded.filterNot { it.isFile }
    require(missing.isEmpty()) {
        "The fontSize guard names files that do not exist: " +
            missing.joinToString { it.path } +
            " — update verifyNoLooseFontSizes() after a rename rather than leaving it vacuous."
    }

    val offenders =
        guarded.flatMap { f ->
            f.readLines().withIndex()
                .filter { (_, line) -> line.contains("fontSize") && !line.trimStart().startsWith("*") }
                .map { (i, line) -> "${f.name}:${i + 1}: ${line.trim()}" }
        }
    require(offenders.isEmpty()) {
        "Loose font sizes found. Use a SecretPanelType role instead - the panel is on the host's " +
            "type scale and a literal here is how it drifts back off it.\n" +
            offenders.joinToString("\n")
    }
}

// Task to build plugin JAR with compiled classes only
tasks.register<Jar>("buildPluginJar") {
    archiveFileName.set("boss-plugin-secret-manager-${version}.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes(
            "Implementation-Title" to "BOSS Secret Manager Plugin",
            "Implementation-Version" to version,
            "Main-Class" to "ai.rever.boss.plugin.dynamic.secretmanager.SecretManagerDynamicPlugin"
        )
    }

    // Compiled classes AND processed resources — sourceSets output already contains the
    // plugin.json that processResources stamped with the Gradle version.
    //
    // Deliberately does NOT also copy src/main/resources. That added a second, *unstamped*
    // plugin.json (the committed one says 1.0.9) and with duplicatesStrategy = EXCLUDE the
    // winner was decided purely by `from` order — so reordering these lines would have made
    // the plugin report 1.0.9 to the host and the store, silently.
    from(sourceSets.main.get().output)

    // Structural guard, not a comment. The hazard is that an *unstamped* plugin.json reaches
    // the jar and duplicatesStrategy picks the winner by `from` order — which is how this
    // plugin could have shipped reporting 1.0.9 (the version committed to src/main/resources)
    // with every test green.
    //
    // Note what is NOT checked: the number of plugin.json entries. duplicatesStrategy =
    // EXCLUDE means the jar always contains exactly one, so counting can never fail —
    // verified. Asserting the *content* is what actually catches the reorder.
    doLast {
        verifyPackagedJar(archiveFile.get().asFile, version.toString())
        verifyNoLooseFontSizes()
    }
}

// Sync version from build.gradle.kts into plugin.json (single source of truth)
tasks.processResources {
    inputs.property("pluginVersion", version)
    filesMatching("**/plugin.json") {
        filter { line ->
            line.replace(Regex(""""version"\s*:\s*"[^"]*""""), """"version": "\$version"""")
        }
    }
}

tasks.build {
    dependsOn("buildPluginJar")
}

// Fat JAR for out-of-process plugin execution
tasks.register<Jar>("shadowJar") {
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes(
            "Main-Class" to "ai.rever.boss.plugin.runtime.PluginProcessMainKt"
        )
    }
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    // Same reasoning as buildPluginJar: no raw src/main/resources copy, or an unstamped
    // plugin.json can win on `from` order.
    from(sourceSets.main.get().output)

    // Same assertions as buildPluginJar — this fat jar is a real shipping surface
    // (plugin.json declares isolationMode: out-of-process).
    doLast {
        verifyPackagedJar(archiveFile.get().asFile, version.toString())
        verifyNoLooseFontSizes()
    }
}
