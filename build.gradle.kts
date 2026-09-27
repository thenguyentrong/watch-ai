plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    // Declared here so every module (and AGP's built-in Kotlin) uses the same Kotlin version.
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.spotless)
    alias(libs.plugins.cyclonedx)
}

// Only source folders: walking build/ races with compilation running in parallel.
val kotlinSources =
    listOf(
        "app",
        "core/brain",
        "core/brain-chatgpt",
        "core/brain-ondevice",
        "core/security",
        "core/testing",
        "core/voice",
        "core/watchlink",
        "wear",
    )

spotless {
    kotlin {
        target(kotlinSources.map { "$it/src/**/*.kt" })
        ktlint(libs.versions.ktlint.get())
            .editorConfigOverride(mapOf("ktlint_function_naming_ignore_when_annotated_with" to "Composable"))
    }
    kotlinGradle {
        target(listOf("*.gradle.kts") + kotlinSources.map { "$it/*.gradle.kts" })
        ktlint(libs.versions.ktlint.get())
    }
}

// Test data must be synthetic: anything shaped like a real token needs the SYNTHETIC marker on its line.
tasks.register("checkSyntheticFixtures") {
    group = "verification"
    description = "Fails if test sources contain token-shaped strings without the SYNTHETIC marker."
    val testSources =
        fileTree(rootDir) {
            include("**/src/test/**", "**/src/androidTest/**")
            exclude("**/build/**")
        }
    inputs.files(testSources)
    doLast {
        val tokenShapes = listOf(Regex("""eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}"""), Regex("""sk-[A-Za-z0-9]{16,}"""))
        val hits =
            testSources.files.flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    val suspicious = tokenShapes.any { it.containsMatchIn(line) } && !line.contains("SYNTHETIC")
                    if (suspicious) "${file.path}:${index + 1}" else null
                }
            }
        if (hits.isNotEmpty()) throw GradleException("Token-shaped test data without SYNTHETIC marker:\n" + hits.joinToString("\n"))
    }
}
