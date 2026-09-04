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
        maven("https://convokitapp.github.io/ConvoKit-Android-Maven/")
    }
}

rootProject.name = "convokit-android-example"
include(":app")
