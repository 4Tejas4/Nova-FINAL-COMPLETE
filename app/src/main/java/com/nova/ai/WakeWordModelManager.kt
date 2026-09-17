package com.nova.ai

import android.content.Context
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/** Downloads the small official Vosk Indian-English model once, then reuses it offline. */
object WakeWordModelManager {
    private const val MODEL_DIR_NAME = "vosk-model-small-en-in-0.4"
    private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-in-0.4.zip"
    private const val ARCHIVE_NAME = "vosk-model-small-en-in-0.4.zip"

    interface Callback {
        fun onReady(modelPath: String)
        fun onError(message: String)
    }

    fun prepare(context: Context, callback: Callback) {
        val root = File(context.filesDir, "wakeword")
        val modelDir = File(root, MODEL_DIR_NAME)
        if (isValidModel(modelDir)) {
            callback.onReady(modelDir.absolutePath)
            return
        }

        Executors.newSingleThreadExecutor().execute {
            try {
                root.mkdirs()
                val archive = File(root, ARCHIVE_NAME)
                download(archive)
                if (modelDir.exists()) modelDir.deleteRecursively()
                unzipSafely(archive, root)
                archive.delete()
                if (!isValidModel(modelDir)) {
                    throw IllegalStateException("Downloaded Vosk model is incomplete")
                }
                callback.onReady(modelDir.absolutePath)
            } catch (e: Exception) {
                callback.onError("Wake-word model error: ${e.message ?: "unknown error"}")
            }
        }
    }

    private fun download(target: File) {
        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            requestMethod = "GET"
            instanceFollowRedirects = true
        }
        try {
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("Model download HTTP ${connection.responseCode}")
            }
            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun unzipSafely(zipFile: File, destination: File) {
        ZipInputStream(BufferedInputStream(zipFile.inputStream())).use { zis ->
            val destinationPath = destination.canonicalPath + File.separator
            while (true) {
                val entry: ZipEntry = zis.nextEntry ?: break
                val outFile = File(destination, entry.name)
                if (!outFile.canonicalPath.startsWith(destinationPath)) {
                    throw SecurityException("Unsafe model archive entry")
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val count = zis.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                }
                zis.closeEntry()
            }
        }
    }

    private fun isValidModel(dir: File): Boolean {
        return dir.isDirectory &&
            File(dir, "am/final.mdl").isFile &&
            File(dir, "conf/model.conf").isFile
    }
}
