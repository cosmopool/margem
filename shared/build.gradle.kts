import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

kotlin {
    androidLibrary {
        namespace = "com.kaiodelphino.margem.shared"
        compileSdk = 36
        minSdk = 29
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.datetime)
            implementation(libs.kotlinx.io.core)
            api(libs.androidx.sqlite.bundled)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// `make mutation` (architecture D48): PIT over the jvm target with the commonTest suite, PIT's default
// mutators for the Kotlin bytecode plus the SQL operator of tools/pit-sql for the SQL in string constants.
// Only run by this task: `./gradlew test` and the app builds never touch PIT.
val pitest: Configuration by configurations.creating
dependencies {
    pitest(libs.pitest.command.line)
    pitest("com.kaiodelphino.margem:pit-sql")
}

tasks.register<JavaExec>("mutation") {
    group = "verification"
    description = "Mutation testing of shared on the JVM (PIT, Kotlin and SQL mutants)"
    val main = kotlin.jvm().compilations.getByName("main")
    val test = kotlin.jvm().compilations.getByName("test")
    val testClasspath = files(test.output.allOutputs, main.output.allOutputs, test.runtimeDependencyFiles)
    val reports = layout.buildDirectory.dir("reports/pitest")
    inputs.files(testClasspath)
    outputs.dir(reports)
    outputs.upToDateWhen { false }
    // A mutant can crash a test JVM: its hs_err_pid*.log lands here, not in the sources.
    val work = layout.buildDirectory.dir("pitest-work")
    workingDir(work)
    doFirst { work.get().asFile.mkdirs() }
    classpath = pitest
    mainClass = "org.pitest.mutationtest.commandline.MutationCoverageReport"
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "--classPath", testClasspath.joinToString(",") { it.absolutePath },
            "--mutableCodePaths", main.output.classesDirs.joinToString(",") { it.absolutePath },
            "--sourceDirs", file("src/commonMain/kotlin").absolutePath,
            "--targetClasses", "com.kaiodelphino.margem.*",
            "--targetTests", "com.kaiodelphino.margem.*Test",
            "--mutators", "DEFAULTS,SQL",
            // Half the cores: the Db tests run threads of their own.
            "--threads", (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1).toString(),
            // Small units spread Db's hundreds of mutants over every thread (one unit runs in one JVM).
            "--mutationUnitSize", "8",
            // A test runs slower under load than in the coverage run: a mutant that only slows it down
            // must not pass for an infinite loop (TIMED_OUT counts as killed) and move the score.
            "--timeoutFactor", "3",
            "--timeoutConst", "30000",
            // The score reached when this was set up (85.99%: Kotlin 87.04%, SQL 72.83%), rounded down (D48).
            // Fails `make check` when new code brings surviving mutants: kill them, or argue them equivalent.
            "--mutationThreshold", "85",
            "--outputFormats", "HTML,XML",
            "--timestampedReports", "false",
            "--reportDir", reports.get().asFile.absolutePath,
        )
    })
}
