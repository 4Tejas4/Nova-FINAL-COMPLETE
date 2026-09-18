package com.nova.ai

import android.content.Context
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay

/** Real on-device llama.cpp inference bridge. No cloud provider is used here. */
object LocalAIEngine {
    private val SYSTEM = """
You are Nova, a local Android assistant. You run entirely on the user's phone. Never claim an action happened unless the executor reports success.
For ordinary questions return concise natural language.
For a phone task return ONLY JSON, no markdown:
{"type":"action","action":"ACTION","data":{},"response":"short status","requiresConfirmation":false,"goal":"","progress":"","stateSummary":""}
Use type=action for one next action, or type=chat for conversation. Complex tasks must be handled one safe step at a time.
Allowed actions include OPEN_APP, SEARCH_APP, PLAY_STORE_SEARCH, YOUTUBE_SEARCH, WEB_SEARCH, OPEN_URL, SETTINGS, CAMERA, FLASHLIGHT_ON, FLASHLIGHT_OFF, WIFI_SETTINGS, INTERNET_PANEL, BLUETOOTH_SETTINGS, LOCATION_SETTINGS, AIRPLANE_SETTINGS, HOTSPOT_SETTINGS, NFC_SETTINGS, BATTERY_SAVER_SETTINGS, DISPLAY_SETTINGS, SOUND_SETTINGS, HOME, DIAL, CALL, SMS, MAPS, BRIGHTNESS_UP, BRIGHTNESS_DOWN, VOLUME_UP, VOLUME_DOWN, SILENT_MODE, VIBRATE_MODE, NORMAL_MODE, DND_ON, DND_OFF, MEDIA_PLAY, MEDIA_PAUSE, MEDIA_PLAY_PAUSE, MEDIA_NEXT, MEDIA_PREVIOUS, ALARM, SET_TIMER, CALENDAR_EVENT, QUERY_CONTACT, GET_BATTERY, GET_DEVICE_INFO, LIST_APPS, LIST_NOTIFICATIONS, STOP_NOVA, READ_SCREEN, READ_UI_STATE, READ_TEXT, CLICK, CLICK_DESCRIPTION, CLICK_ID, LONG_CLICK, CLEAR_TEXT, FOCUS_TEXT_FIELD, TYPE_TEXT, SCROLL, BACK, TAP, WAIT, FINISH.
Never request passwords, OTPs, banking/UPI/payment authorization, security bypasses, or permission changes. CALL and STOP_NOVA require confirmation. SMS only opens/prefills the composer; it does not silently send.
""".trimIndent()

    private var engine: InferenceEngine? = null
    private var loadedPath: String? = null

    private fun getEngine(context: Context): InferenceEngine = engine ?: synchronized(this) {
        engine ?: AiChat.getInferenceEngine(context.applicationContext).also { engine = it }
    }

    fun status(context: Context): String = when (getEngine(context).state.value) {
        is InferenceEngine.State.ModelReady, is InferenceEngine.State.Generating -> "ready"
        is InferenceEngine.State.Error -> "error"
        else -> "loading"
    }

    fun ensureLoaded(context: Context) = runBlocking {
        withContext(Dispatchers.IO) {
            val file = LocalModelManager.modelFile(context)
            require(file.exists()) { "Local AI model is not installed. Download or load a GGUF model in Nova Settings." }
            require(LocalModelManager.hasValidMagic(file)) {
                "The model file is damaged or incomplete. Open Nova Settings and download the model again."
            }
            val e = getEngine(context)
            var waited = 0
            while (e.state.value is InferenceEngine.State.Uninitialized || e.state.value is InferenceEngine.State.Initializing) {
                if (waited++ > 600) error("Local llama.cpp engine initialization timed out")
                delay(100)
            }
            if (e.state.value is InferenceEngine.State.Error) {
                // Recover from a previous failed attempt instead of failing forever.
                try { e.cleanUp() } catch (_: Throwable) {}
            }
            if (loadedPath != file.absolutePath || e.state.value !is InferenceEngine.State.ModelReady) {
                try {
                    e.loadModel(file.absolutePath)
                    e.setSystemPrompt(SYSTEM)
                    loadedPath = file.absolutePath
                } catch (t: Throwable) {
                    throw RuntimeException(
                        "Nova could not initialise the local model: ${t.message ?: t.javaClass.simpleName}", t)
                }
            }
        }
    }

    fun complete(context: Context, prompt: String, maxTokens: Int = 700): String = runBlocking {
        ensureLoaded(context)
        val e = getEngine(context)
        val out = StringBuilder()
        e.sendUserPrompt(prompt, maxTokens).collect { out.append(it) }
        out.toString().trim()
    }

    fun shutdown() { engine?.cleanUp(); engine = null; loadedPath = null }
}
