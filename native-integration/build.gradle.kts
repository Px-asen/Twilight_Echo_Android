plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

val ffmpegRoot = providers.gradleProperty("ffmpegRoot")
    .orElse(providers.environmentVariable("TAE_FFMPEG_ROOT"))
    .getOrElse(rootProject.file(".gradle/ffmpeg/7.1.2").absolutePath)
val jniCmakeLists = rootProject.file("../Twilight_Echo/sdk/android/CMakeLists.txt")
val nativeJniEnabled = jniCmakeLists.exists() && providers.gradleProperty("enableNativeJni").orNull != "false"
val sharedRoot = jniCmakeLists.parentFile.parentFile.parentFile
val autoMixAssets = layout.buildDirectory.dir("generated/automix-assets")
val stageAutoMixAssets by tasks.registering(Exec::class) {
    group = "twilightCore"
    description = "Stages only the locked public AutoMix graphs, manifest and licenses."
    inputs.dir(sharedRoot.resolve("resources/audio-engine/automix-native"))
    inputs.file(sharedRoot.resolve("audio-engine/automix-native/dependencies.lock.json"))
    inputs.dir(sharedRoot.resolve("audio-engine/automix-native/licenses"))
    inputs.file(sharedRoot.resolve("scripts/stage-automix-native-assets.cjs"))
    outputs.dir(autoMixAssets)
    commandLine("node", sharedRoot.resolve("scripts/stage-automix-native-assets.cjs").absolutePath,
        sharedRoot.resolve("resources/audio-engine/automix-native").absolutePath,
        autoMixAssets.get().dir("automix-native").asFile.absolutePath)
}

android {
    namespace = "com.twilightecho.android.nativeintegration"
    compileSdk = 35
    buildFeatures { buildConfig = true }
    if (nativeJniEnabled) sourceSets.getByName("main").assets.srcDir(autoMixAssets)

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("boolean", "NATIVE_AUDIO_ENABLED", nativeJniEnabled.toString())

        externalNativeBuild {
            cmake {
                cppFlags("-std=c++20")
                arguments("-DTAE_ENABLE_OBOE=ON", "-DTAE_BUILD_NAPI=OFF", "-DTAE_BUILD_TESTS=OFF")
                arguments("-DTAE_FFMPEG_ROOT=${file(ffmpegRoot).invariantSeparatorsPath}")
            }
        }
    }

    if (nativeJniEnabled) {
        externalNativeBuild {
            cmake {
                path = jniCmakeLists
                version = "3.25.0+"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

if (nativeJniEnabled) tasks.named("preBuild") { dependsOn(stageAutoMixAssets) }

dependencies {
    api(project(":core-client"))
    api(project(":runtime-host"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
