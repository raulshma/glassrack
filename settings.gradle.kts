rootProject.name = "Glassrack"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

include(":androidApp")
include(":shared")
include(":core:model")
include(":core:designsystem")
include(":core:security")
include(":core:database")
include(":core:ssh")
include(":core:terminal")
include(":feature:overview")
include(":feature:servers")
include(":feature:containers")
include(":feature:processes")
include(":feature:terminal")
include(":feature:audit")
include(":feature:settings")
