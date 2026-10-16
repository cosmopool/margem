// The SQL mutation operator of `make mutation` (architecture D48): an included build, built only when
// :shared:mutation runs, never by `./gradlew test` or the app builds.
dependencyResolutionManagement {
    repositories { mavenCentral() }
    versionCatalogs { create("libs") { from(files("../../gradle/libs.versions.toml")) } }
}

rootProject.name = "pit-sql"
