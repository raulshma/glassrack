import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    iosArm64()
    iosSimulatorArm64()
    android {
        namespace = "com.raulshma.grack.core.security"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions { jvmTarget = JvmTarget.JVM_11 }
        withHostTest {}
    }
    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.model)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kermit)
        }
        androidMain.dependencies {
            implementation(libs.tink.android)
            implementation(libs.androidx.biometric)
            implementation(libs.androidx.core.ktx)
            implementation(libs.bcprov.jdk18on)
            implementation(libs.jsch)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
