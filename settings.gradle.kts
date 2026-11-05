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

rootProject.name = "Solin"
include(":app")
include(":releaseSmoke")
// Model-pack asset modules live under modelpacks/; their Gradle paths stay :modelpackX so the
// split APK names (split_modelpackX.apk) do not change.
listOf("modelpackE2b", "modelpackE2bExtra", "modelpackE4b", "modelpackE4bExtra").forEach { module ->
    include(":$module")
    project(":$module").projectDir = file("modelpacks/$module")
}
