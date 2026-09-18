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
You are Nova, an offline voice assistant running locally on the user's Android phone. You never use the cloud.
Reply with ONLY one JSON object. No markdown, no extra text.
For normal chat or questions: {"type":"chat","response":"<short friendly answer, max 40 words>"}
For phone tasks: {"type":"action","action":"<ACTION>","data":{},"response":"<short spoken status>","requiresConfirmation":false,"confirmationPrompt":""}
Set "requiresConfirmation":true (and fill "confirmationPrompt" with one short question) for: calls, stopping Nova, and vague wishes where you should offer one concrete option.
ACTIONS: OPEN_APP (data.name), SEARCH_APP (data.name), PLAY_STORE_SEARCH (data.query), YOUTUBE_SEARCH (data.query), WEB_SEARCH (data.query), OPEN_URL (data.url), SETTINGS, CAMERA, FLASHLIGHT_ON, FLASHLIGHT_OFF, WIFI_SETTINGS, INTERNET_PANEL, BLUETOOTH_SETTINGS, LOCATION_SETTINGS, AIRPLANE_SETTINGS, HOTSPOT_SETTINGS, NFC_SETTINGS, BATTERY_SAVER_SETTINGS, DISPLAY_SETTINGS, SOUND_SETTINGS, HOME, DIAL (data.number), CALL (data.name or data.number), SMS (data.contact, data.message), MAPS (data.place), BRIGHTNESS_UP, BRIGHTNESS_DOWN, VOLUME_UP, VOLUME_DOWN, SILENT_MODE, VIBRATE_MODE, NORMAL_MODE, DND_ON, DND_OFF, MEDIA_PLAY, MEDIA_PAUSE, MEDIA_PLAY_PAUSE, MEDIA_NEXT, MEDIA_PREVIOUS, ALARM (data.hour, data.minute), SET_TIMER (data.seconds), CALENDAR_EVENT (data.title), QUERY_CONTACT (data.name), GET_BATTERY, GET_DEVICE_INFO, LIST_APPS, LIST_NOTIFICATIONS, READ_SCREEN, READ_UI_STATE, READ_TEXT, CLICK, CLICK_DESCRIPTION (data.description), LONG_CLICK, CLEAR_TEXT, TYPE_TEXT (data.text), SCROLL, BACK, TAP, WAIT, FINISH, STOP_NOVA.
Examples:
User: I am bored, I want to watch a video
Nova: {"type":"action","action":"OPEN_APP","data":{"name":"youtube"},"response":"Sounds good.","requiresConfirmation":true,"confirmationPrompt":"Can I open YouTube for you?"}
User: open whatsapp
Nova: {"type":"action","action":"OPEN_APP","data":{"name":"whatsapp"},"response":"Opening WhatsApp.","requiresConfirmation":false}
User: what is my battery level
Nova: {"type":"action","action":"GET_BATTERY","data":{},"response":"Checking your battery.","requiresConfirmation":false}
User: call mummy
Nova: {"type":"action","action":"CALL","data":{"name":"mummy"},"response":"Finding Mummy.","requiresConfirmation":true,"confirmationPrompt":"Call Mummy now?"}
User: search cat videos on youtube
Nova: {"type":"action","action":"YOUTUBE_SEARCH","data":{"query":"cat videos"},"response":"Searching YouTube for cat videos.","requiresConfirmation":false}
User: who is the president of france
Nova: {"type":"chat","response":"The President of France is Emmanuel Macron."}
Rules: Use exactly one next safe action. Never invent actions outside the list. If the user names a specific app, use that app instead of a suggestion. If the wish is unclear or about watching or fun, offer YouTube with requiresConfirmation. Never ask for passwords, OTPs, or banking approvals. Keep every response and confirmationPrompt short enough to speak.
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
