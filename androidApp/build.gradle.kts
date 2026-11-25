import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

dependencies {
    implementation(projects.shared)
    implementation(projects.core.model)
    implementation(projects.core.designsystem)
    implementation(projects.core.security)
    implementation(projects.core.database)
    implementation(projects.core.ssh)
    implementation(projects.core.terminal)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(libs.vico.compose.glance)
    implementation(libs.koin.android)
    implementation(libs.koin.compose)
    implementation(libs.kermit)

    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)
}

android {
    namespace = "com.example.glassrack"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    val resolvedVersionName = (providers.gradleProperty("versionName").orNull
        ?: System.getenv("GLASSRACK_VERSION_NAME")
        ?: "0.0.1").removePrefix("v")
    val resolvedVersionCode = (providers.gradleProperty("versionCode").orNull
        ?: System.getenv("GLASSRACK_VERSION_CODE"))
        ?.toIntOrNull()
        ?: 1

    val keystorePath = providers.gradleProperty("signing.keystoreFile").orNull
        ?: System.getenv("ANDROID_KEYSTORE_PATH")
    val keystorePassword = providers.gradleProperty("signing.keystorePassword").orNull
        ?: System.getenv("ANDROID_KEYSTORE_PASSWORD")
    val releaseKeyAlias = providers.gradleProperty("signing.keyAlias").orNull
        ?: System.getenv("ANDROID_KEY_ALIAS")
    val releaseKeyPassword = providers.gradleProperty("signing.keyPassword").orNull
        ?: System.getenv("ANDROID_KEY_PASSWORD")

    defaultConfig {
        applicationId = "com.example.glassrack"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = resolvedVersionCode
        versionName = resolvedVersionName
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }
    signingConfigs {
        if (!keystorePath.isNullOrBlank() &&
            !keystorePassword.isNullOrBlank() &&
            !releaseKeyAlias.isNullOrBlank() &&
            !releaseKeyPassword.isNullOrBlank()
        ) {
            val keystoreFile = file(keystorePath)
            if (keystoreFile.exists()) {
                create("release") {
                    storeFile = keystoreFile
                    storePassword = keystorePassword
                    keyAlias = releaseKeyAlias
                    keyPassword = releaseKeyPassword
                }
            }
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}
