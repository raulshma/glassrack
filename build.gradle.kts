plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room3) apply false
}

// The verification gate: exactly what CI runs (.github/workflows/ci.yml) and what
// scripts/gate.sh wraps locally. The iOS compiles exist because a JVM-only API in
// commonMain once broke the iOS build for two phases before anyone noticed.
// Modules with Android host tests are picked up automatically, so a new test
// module needs no edit here.
tasks.register("gate") {
    group = "verification"
    description = "Android host tests, debug APK and iOS compiles (the CI gate)."
    dependsOn(
        ":androidApp:assembleDebug",
        ":core:ssh:compileKotlinIosSimulatorArm64",
        ":shared:compileKotlinIosSimulatorArm64",
    )
}

gradle.projectsEvaluated {
    tasks.named("gate") {
        dependsOn(subprojects.mapNotNull { it.tasks.findByName("testAndroidHostTest") })
    }
}
