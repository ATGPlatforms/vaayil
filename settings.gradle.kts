pluginManagement {
    repositories {
        google { content { includeGroupByRegex("com\\.android.*"); includeGroupByRegex("com\\.google.*"); includeGroupByRegex("androidx.*") } }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "vaayil"

include(":app")
include(":core:model", ":core:config", ":core:tunnel-api", ":core:data", ":core:tunnel-wg", ":core:dns", ":core:automation", ":core:ui")
include(":feature:profiles", ":feature:tunnel", ":feature:settings")
include(":third-party:wireguard-tunnel")
