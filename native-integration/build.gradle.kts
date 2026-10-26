plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.twilightecho.android.nativeintegration"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")

        externalNativeBuild {
            cmake {
                cppFlags("-std=c++20")
                arguments("-DTAE_ENABLE_OBOE=ON", "-DTAE_BUILD_NAPI=OFF", "-DTAE_BUILD_TESTS=OFF")
            }
        }
    }

    val jniCmakeLists = rootProject.layout.projectDirectory
        .file("../Twilight_Echo/sdk/android/CMakeLists.txt")
        .asFile
    if (jniCmakeLists.exists() && providers.gradleProperty("enableNativeJni").orNull != "false") {
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

dependencies {
    api(project(":core-client"))
    api(project(":runtime-host"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
}
