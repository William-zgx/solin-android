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

rootProject.name = "PocketMind"
include(":app")
include(":modelpackE2b")
include(":modelpackE2bExtra")
include(":modelpackE4b")
include(":modelpackE4bExtra")
