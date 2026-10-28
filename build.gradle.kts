import groovy.json.JsonSlurper
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
}

tasks.register<Copy>("syncAndroidPlugins") {
    group = "twilightCore"
    description = "Copies the shared mobile plugin coordinator and packaged NCM runtime built by scripts/build-android-plugins.mjs."
    val source = layout.projectDirectory.dir("../Twilight_Echo/dist/android-plugins")
    from(source) { include("repository.js", "catalog.json", "ncm.tep") }
    into(layout.projectDirectory.dir("plugin-host/src/main/assets/plugins"))
    doFirst { check(source.file("ncm.tep").asFile.exists()) { "Run node ../Twilight_Echo/scripts/build-android-plugins.mjs first" } }
}

/**
 * Copies the portable Music Core bundle and its lock manifest from the desktop repository
 * (`../Twilight_Echo/dist/portable-core/` as a sibling checkout, or `../../dist/portable-core/`
 * when staged in `sdk/android-app`). The bundle is shipped byte-for-byte as built there:
 * Android carries no patches, and `verifyPortableCore` rejects any drift from the lock.
 */
tasks.register<Copy>("syncPortableCore") {
    group = "twilightCore"
    description = "Copies music-core.js and core-lock.json from the desktop build into runtime-host assets."

    val siblingUpstreamDist = layout.projectDirectory.dir("../Twilight_Echo/dist/portable-core")
    val stagedUpstreamDist = layout.projectDirectory.dir("../../dist/portable-core")
    val resolvedUpstreamDist = if (siblingUpstreamDist.asFile.exists()) siblingUpstreamDist else stagedUpstreamDist
    val targetAssetsDir = layout.projectDirectory.dir("runtime-host/src/main/assets/core")

    from(resolvedUpstreamDist) { include("music-core.js", "core-lock.json") }
    into(targetAssetsDir)

    doFirst {
        check(resolvedUpstreamDist.file("music-core.js").asFile.exists()) {
            "No portable Core at ${resolvedUpstreamDist.asFile.absolutePath}; run `npm run build:portable-core` in Twilight_Echo"
        }
    }
    doLast {
        targetAssetsDir.file("core-lock.json").asFile.copyTo(layout.projectDirectory.file("core-lock.json").asFile, overwrite = true)
        // Instrumented tests replay the desktop scenario corpus against the shipped bundle.
        resolvedUpstreamDist.file("portable-core-scenarios.json").asFile.copyTo(
            layout.projectDirectory.file("app/src/androidTest/assets/core/portable-core-scenarios.json").asFile,
            overwrite = true
        )
    }
    finalizedBy("verifyPortableCore")
}

/** Fails the build when the shipped bundle does not match the hash and size its lock records. */
tasks.register("verifyPortableCore") {
    group = "twilightCore"
    description = "Checks runtime-host/src/main/assets/core/music-core.js against core-lock.json."
    val assets = layout.projectDirectory.dir("runtime-host/src/main/assets/core")
    val rootLock = layout.projectDirectory.file("core-lock.json")
    val scenarios = layout.projectDirectory.file("app/src/androidTest/assets/core/portable-core-scenarios.json")
    inputs.files(assets.file("music-core.js"), assets.file("core-lock.json"), rootLock, scenarios)
    doLast {
        val bundle = assets.file("music-core.js").asFile
        val lockFile = assets.file("core-lock.json").asFile
        check(bundle.exists() && lockFile.exists()) { "runtime-host is missing music-core.js or core-lock.json; run syncPortableCore" }
        val lock = JsonSlurper().parseText(lockFile.readText()) as Map<*, *>
        fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val sha256 = sha256(bundle)
        check(sha256 == lock["bundleSha256"]) {
            "music-core.js sha256 $sha256 does not match core-lock.json ${lock["bundleSha256"]}; rebuild it upstream instead of editing it"
        }
        check(bundle.length() == (lock["bundleBytes"] as Number).toLong()) { "music-core.js size differs from core-lock.json" }
        check(rootLock.asFile.readText() == lockFile.readText()) { "core-lock.json at the root differs from the runtime-host copy" }
        lock["scenariosSha256"]?.let { expected ->
            val corpus = scenarios.asFile
            check(corpus.exists() && sha256(corpus) == expected) {
                "app/src/androidTest/assets/core/portable-core-scenarios.json does not match core-lock.json; run syncPortableCore"
            }
        }
        if (lock["sourceDirty"] == true) {
            logger.warn("[verifyPortableCore] music-core.js was built from uncommitted Core sources (${lock["commit"]} + diff ${lock["sourceDiffSha256"]})")
        }
    }
}
