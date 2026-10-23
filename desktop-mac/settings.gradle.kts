pluginManagement { repositories { mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { mavenCentral() } }
rootProject.name = "HongguoMac"
include(":core", ":desktop")
project(":core").projectDir = file("../kotlin-tv/core")
project(":desktop").projectDir = file("backend")
