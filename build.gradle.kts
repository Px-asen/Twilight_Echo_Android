plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
}

/**
 * Synchronizes the portable Music Core JS bundle and lock manifest from the upstream
 * Twilight_Echo repository (`../Twilight_Echo/dist/portable-core/` when checked out as a
 * sibling repository, or `../../dist/portable-core/` when staged in `sdk/android-app`)
 * into `runtime-host/src/main/assets/core/`.
 */
tasks.register<Copy>("syncPortableCore") {
    group = "twilightCore"
    description = "Copies portable-core bundle (music-core.js) and core-lock.json from upstream dist/portable-core into runtime-host assets."

    val siblingUpstreamDist = layout.projectDirectory.dir("../Twilight_Echo/dist/portable-core")
    val stagedUpstreamDist = layout.projectDirectory.dir("../../dist/portable-core")
    val resolvedUpstreamDist = if (siblingUpstreamDist.asFile.exists()) {
        siblingUpstreamDist
    } else {
        stagedUpstreamDist
    }
    val targetAssetsDir = layout.projectDirectory.dir("runtime-host/src/main/assets/core")

    from(resolvedUpstreamDist) {
        include("music-core.js", "core-lock.json")
    }
    into(targetAssetsDir)

    doFirst {
        val bundleFile = resolvedUpstreamDist.file("music-core.js").asFile
        if (!bundleFile.exists()) {
            logger.warn(
                "[syncPortableCore] Upstream bundle not found at ${bundleFile.absolutePath}. " +
                    "Retaining fallback assets in ${targetAssetsDir.asFile.absolutePath}."
            )
        }
    }
}
