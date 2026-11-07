plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Cross-repository software check; normal JVM tests do not require Node or a desktop checkout.
tasks.named<Test>("test") {
    exclude("**/DspDesktopInteropTest*")
}

tasks.register<Test>("desktopDspInteropTest") {
    group = "verification"
    description = "Round-trips real phone DSP packages through the desktop Core, without devices."
    dependsOn(tasks.named("testClasses"))
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    include("**/DspDesktopInteropTest.class")
    systemProperty("twilight.desktopRoot", providers.gradleProperty("desktopRoot")
        .orElse(rootProject.layout.projectDirectory.dir("../Twilight_Echo").asFile.absolutePath).get())
    systemProperty("twilight.node", providers.gradleProperty("nodeExecutable").orElse("node").get())
    systemProperty("twilight.interop.bridge", rootProject.file("tools/desktop-dsp-interop.mjs").absolutePath)
    systemProperty("twilight.interop.output", rootProject.layout.buildDirectory.dir("p1-interop/roundtrip").get().asFile.absolutePath)
    outputs.upToDateWhen { false }
}
