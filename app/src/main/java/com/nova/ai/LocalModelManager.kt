package com.nova.ai

import android.content.Context
import android.net.Uri
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Owns the downloadable on-device GGUF model. The model is deliberately not bundled in the APK. */
object LocalModelManager {
    const val MODEL_NAME = "qwen2.5-0.5b-instruct-q4_k_m.gguf"
    const val MODEL_URL = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf?download=true"
    /** Primary + mirror sources; tried in order on each retry. */
    private val MODEL_URLS = listOf(
        "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf?download=true",
        "https://hf-mirror.com/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf"
    )

    fun modelDir(context: Context): File = File(context.getExternalFilesDir("models") ?: context.filesDir, "nova_models").apply { mkdirs() }
    fun modelFile(context: Context): File = File(modelDir(context), MODEL_NAME)
    fun isInstalled(context: Context): Boolean = modelFile(context).let { it.exists() && it.length() > 10_000_000L && hasValidMagic(it) }

    /** GGUF files start with the ASCII magic "GGUF". */
    fun hasValidMagic(file: File): Boolean = try {
        file.inputStream().use { input ->
            val magic = ByteArray(4)
            val n = input.read(magic)
            n == 4 && magic[0] == 'G'.code.toByte() && magic[1] == 'G'.code.toByte() &&
                magic[2] == 'U'.code.toByte() && magic[3] == 'F'.code.toByte()
        }
    } catch (_: Exception) { false }

    fun copyFromUri(context: Context, uri: Uri) {
        val target = modelFile(context)
        val tmp = File(target.parentFile, target.name + ".part")
        context.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().buffered().use { input.copyTo(it) } }
            ?: error("Could not open the selected model file")
        if (tmp.length() < 10_000_000L) { tmp.delete(); error("The selected file is too small to be a GGUF model") }
        if (!tmp.renameTo(target)) error("Could not install model")
    }

    fun download(context: Context, progress: (Int) -> Unit = {}) {
        val target = modelFile(context)
        if (isInstalled(context)) { progress(100); return }
        val tmp = File(target.parentFile, target.name + ".part")
        var lastProgress = -1
        var attempt = 0
        while (true) {
            attempt++
            try {
                // Alternate between the primary and mirror sources on retries.
                val url = MODEL_URLS[(attempt - 1) % MODEL_URLS.size]
                val start2 = if (tmp.exists()) tmp.length() else 0L
                val c = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000; readTimeout = 120_000; requestMethod = "GET"
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Nova/100")
                    if (start2 > 0L) setRequestProperty("Range", "bytes=$start2-")
                }
                c.connect()
                val code = c.responseCode
                if (code !in 200..299) { c.disconnect(); error("Model download failed: HTTP $code") }
                val resumed = code == 206 && start2 > 0L
                val total = if (resumed) start2 + c.contentLengthLong else c.contentLengthLong
                java.io.FileOutputStream(tmp, resumed).use { output ->
                    c.inputStream.buffered().use { input ->
                        val buffer = ByteArray(256 * 1024)
                        var done = if (resumed) start2 else 0L
                        while (true) {
                            val n = input.read(buffer); if (n < 0) break
                            output.write(buffer, 0, n); done += n
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                if (pct != lastProgress) { progress(pct); lastProgress = pct }
                            }
                        }
                        output.fd.sync()
                    }
                }
                c.disconnect()
                require(hasValidMagic(tmp)) { "Downloaded file is not a valid GGUF model" }
                if (total > 0) require(tmp.length() >= total) { "Download incomplete (${tmp.length()}/${total} bytes)" }
                if (!tmp.renameTo(target)) { tmp.delete(); error("Could not finalize downloaded model") }
                progress(100)
                return
            } catch (t: Throwable) {
                if (attempt >= 4) {
                    tmp.delete()
                    throw RuntimeException("Model download failed: ${t.message ?: t.javaClass.simpleName}", t)
                }
                try { Thread.sleep(1_500L * attempt) } catch (_: InterruptedException) {}
            }
        }
    }
}
