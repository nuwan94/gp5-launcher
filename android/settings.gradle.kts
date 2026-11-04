pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "gp5-launcher"

include(":app")
include(":core:design", ":core:model", ":core:data")
include(":obd", ":media", ":car3d")
include(":feature:home", ":feature:vehicle", ":feature:trips", ":feature:apps", ":feature:settings")
