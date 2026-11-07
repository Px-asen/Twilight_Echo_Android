plugins {
    alias(libs.plugins.android.library)
}

val licenseAssets = layout.buildDirectory.dir("generated/license-assets")
val stageQuickJsLicenseAssets by tasks.registering(Sync::class) {
    from("upstream/LICENSE", "QUICKJS-LICENSE", "NOTICE.md")
    into(licenseAssets.map { it.dir("licenses/quickjs-0.9.2") })
}

android {
    namespace = "app.cash.quickjs"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        minSdk = 26
        externalNativeBuild {
            cmake {
                arguments("-DANDROID_STL=c++_static", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
            }
        }
    }

    sourceSets.getByName("main") {
        java.srcDirs("upstream/quickjs/common/java", "upstream/quickjs/android/src/main/java")
        assets.srcDir(licenseAssets)
    }

    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
            version = "3.25.0+"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.named("preBuild") { dependsOn(stageQuickJsLicenseAssets) }

dependencies {
    api(libs.androidx.annotation)
}
