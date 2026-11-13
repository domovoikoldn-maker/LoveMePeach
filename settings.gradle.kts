// Откуда Gradle берёт плагины (Android Gradle Plugin и т.д.)
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// Откуда берутся библиотеки (ARCore и AndroidX лежат в репозитории google())
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ArTamagotchi"
include(":app")
