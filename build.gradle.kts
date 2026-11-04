plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    // Feature pipeline (architecture D75): `make coverage` merges both modules into one Kover XML,
    // `make lint` runs detekt; neither runs in `make test` or `make check`.
    alias(libs.plugins.kover)
    alias(libs.plugins.detekt) apply false
}

dependencies {
    kover(project(":shared"))
    kover(project(":androidApp"))
}

// One variant, "pipeline": shared's jvm tests and androidApp's debug unit tests (the release unit tests
// can't see the debug-only catalog). `koverXmlReportPipeline` merges both modules.
kover { currentProject { createVariant("pipeline") {} } }

// `make test T=Name` filters both modules at once: a module with no class matching the filter
// passes instead of failing the run.
subprojects {
    tasks.withType<Test>().configureEach { filter.isFailOnNoMatchingTests = false }

    apply(plugin = "org.jetbrains.kotlinx.kover")
    plugins.withId("com.android.application") {
        extensions.configure<kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension> {
            currentProject { createVariant("pipeline") { add("debug") } }
        }
    }
    plugins.withId("org.jetbrains.kotlin.multiplatform") {
        extensions.configure<kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension> {
            currentProject { createVariant("pipeline") { add("jvm") } }
        }
    }
    apply(plugin = "dev.detekt")
    // Default rules, never failing the build: scripts/pipeline/dead.sh keeps only the dead-code rules
    // (unused import, private member, parameter, variable; unreachable code) from the XML report.
    extensions.configure<dev.detekt.gradle.extensions.DetektExtension> { ignoreFailures = true }
}
