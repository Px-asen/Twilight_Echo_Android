package com.twilightecho.android.nativeintegration

import android.content.Context
import android.util.AtomicFile
import java.io.File

/** Public locked graphs only. Extraction is lazy and off the transport thread;
 * the production native loader verifies each graph against its compiled lock. */
internal object AutoMixAssets {
    private val prepared = mutableSetOf<String>()
    @Synchronized fun directory(context: Context): String {
        val folder = File(context.noBackupFilesDir, "automix-native-v1")
        if (folder.absolutePath !in prepared) {
            check(folder.isDirectory || folder.mkdirs()) { "public_assets_unavailable" }
            for (name in listOf("beat-this.tamg", "yamnet.tamg")) {
                val file = AtomicFile(File(folder, name))
                val output = file.startWrite()
                try {
                    context.assets.open("automix-native/$name").use { it.copyTo(output) }
                    file.finishWrite(output)
                } catch (error: Throwable) {
                    file.failWrite(output)
                    throw error
                }
            }
            prepared.add(folder.absolutePath)
        }
        return folder.absolutePath
    }
}
