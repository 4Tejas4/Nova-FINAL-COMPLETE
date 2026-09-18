package com.nova.ai

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener as VoskRecognitionListener
import org.vosk.android.SpeechService
import java.util.Locale

class NovaWakeService : Service(), TextToSpeech.OnInitListener {

    private var agentLoop: AgentLoop? = null

    companion object {
        private const val TAG = "NovaWakeService"
        private const val CHANNEL_ID = "nova_voice_channel"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.nova.ai.START_NOVA"
        const val ACTION_STOP = "com.nova.ai.STOP_NOVA"
        private const val UTT_WAKE = "nova_wake_reply"
        private const val UTT_REPLY = "nova_reply"
        private const val UTT_VERIFY = "nova_verify_prompt"
    }

    private var wakeModel: Model? = null
    private var wakeRecognizer: Recognizer? = null
    private var wakeSpeechService: SpeechService? = null
    private var commandVoskRecognizer: Recognizer? = null
    private var commandVoskService: SpeechService? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var stopping = false
    private var commandListening = false
    private var verificationListening = false
    private var loadingWakeEngine = false
    private var wakeEngineReady = false
    private val wakeRestartHandler = Handler(Looper.getMainLooper())
    private var wakeRestartAttempts = 0
    private var lastWakeTriggerAt = 0L
    private var commandCycleActiveAt = 0L
    private var pendingDecision: AIClient.RouteDecision? = null
    private var pendingRawCommand: String? = null
    private var verificationAttempts = 0
    private lateinit var conversationManager: ConversationManager

    private val confirmationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ConfirmationActivity.ACTION_YES -> confirmPendingAction()
                ConfirmationActivity.ACTION_CANCEL -> cancelVerification("Okay, I cancelled that action.")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        NovaSystemIntegration.initialize(this)
        conversationManager = ConversationManager(this)
        val confirmationFilter = IntentFilter().apply {
            addAction(ConfirmationActivity.ACTION_YES)
            addAction(ConfirmationActivity.ACTION_CANCEL)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(confirmationReceiver, confirmationFilter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(confirmationReceiver, confirmationFilter)
        createNotificationChannel()
        try {
            startForeground(NOTIFICATION_ID, buildNotification("Starting Nova wake-word engine…"))
        } catch (t: Throwable) {
            Log.e(TAG, "Unable to start foreground service", t)
            // If we cannot promote to foreground (e.g. MIUI/Android 14 FGS
            // restrictions), stop now: a started service that never calls
            // startForeground is killed by the system with a crash a few
            // seconds later.
            val reason = t.message ?: "foreground service blocked"
            try { updateNotification("Nova could not start: $reason") } catch (_: Throwable) {}
            stopping = true
            fgStartFailed = true
            stopSelf()
            return
        }

        tts = TextToSpeech(this, this)
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                runOnMainThread {
                    if (stopping) return@runOnMainThread
                    when (utteranceId) {
                        UTT_WAKE -> startCommandListening()
                        UTT_REPLY -> finishCommandCycle()
                        UTT_VERIFY -> startVerificationListening()
                        else -> if (!commandListening && !verificationListening) startWakeWordEngine()
                    }
                }
            }

            override fun onError(utteranceId: String?) {
                runOnMainThread {
                    if (stopping) return@runOnMainThread
                    when (utteranceId) {
                        UTT_WAKE -> startCommandListening()
                        UTT_REPLY -> finishCommandCycle()
                        UTT_VERIFY -> startVerificationListening()
                        else -> if (!commandListening && !verificationListening) startWakeWordEngine()
                    }
                }
            }
        })

        if (hasMicPermission()) startWakeWordEngine()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopping = true
                stopEverything()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                stopping = false
                if (!commandListening && !verificationListening && !loadingWakeEngine) startWakeWordEngine()
            }
        }
        return START_STICKY
    }

    private fun hasMicPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun startWakeWordEngine() {
        if (stopping || commandListening || verificationListening || loadingWakeEngine) return
        if (!hasMicPermission()) {
            speakOnce("Microphone permission is required.")
            return
        }

        if (wakeEngineReady && wakeModel != null) {
            startContinuousWakeListening()
            return
        }

        loadingWakeEngine = true
        updateNotification("Preparing offline Nova wake word…")
        WakeWordModelManager.prepare(this, object : WakeWordModelManager.Callback {
            override fun onReady(modelPath: String) {
                runOnMainThread {
                    loadingWakeEngine = false
                    if (stopping) return@runOnMainThread
                    try {
                        wakeModel?.close()
                        wakeModel = Model(modelPath)
                        wakeEngineReady = true
                        startContinuousWakeListening()
                    } catch (e: Exception) {
                        Log.e(TAG, "Vosk model initialization failed", e)
                        updateNotification("Nova wake engine failed")
                        speakOnce("I could not start the Nova wake word engine.")
                    }
                }
            }

            override fun onError(message: String) {
                runOnMainThread {
                    loadingWakeEngine = false
                    Log.e(TAG, message)
                    updateNotification("Wake-word model download failed")
                    speakOnce("The offline wake word model could not be prepared. Check your internet connection and try again.")
                }
            }
        })
    }

    private fun startContinuousWakeListening() {
        if (stopping || commandListening || verificationListening || wakeModel == null) return
        stopWakeListening()
        try {
            wakeRecognizer = Recognizer(wakeModel, 16000.0f, "[\"nova\", \"nova\", \"hey nova\", \"[unk]\"]")
            wakeSpeechService = SpeechService(wakeRecognizer, 16000.0f)
            wakeSpeechService?.startListening(wakeListener)
            wakeRestartAttempts = 0
            updateNotification("Listening for “Hey Nova”")
        } catch (e: Exception) {
            Log.e(TAG, "Could not start Vosk listening", e)
            restartWakeListening()
        }
    }

    private val wakeListener = object : VoskRecognitionListener {
        override fun onPartialResult(hypothesis: String?) {
            if (containsWakeWord(extractVoskText(hypothesis, "partial"))) triggerWakeWord()
        }
        override fun onResult(hypothesis: String?) {
            if (containsWakeWord(extractVoskText(hypothesis, "text"))) triggerWakeWord()
        }
        override fun onFinalResult(hypothesis: String?) {
            if (containsWakeWord(extractVoskText(hypothesis, "text"))) triggerWakeWord()
        }
        override fun onError(e: Exception?) {
            Log.e(TAG, "Vosk wake listener error", e)
            scheduleWakeRestart("Wake engine error")
        }
        override fun onTimeout() {
            scheduleWakeRestart("Wake engine timeout")
        }
    }

    private fun triggerWakeWord() {
        runOnMainThread {
            if (stopping || commandListening || verificationListening) return@runOnMainThread
            val now = System.currentTimeMillis()
            if (now - lastWakeTriggerAt < 1800L) return@runOnMainThread
            lastWakeTriggerAt = now
            commandCycleActiveAt = now
            commandListening = true
            stopWakeListening()
            updateNotification("Nova activated • listening for command")
            speakAndThenListen("Yes?")
        }
    }

    private fun speakAndThenListen(text: String) {
        if (!ttsReady || tts == null) {
            startCommandListening()
            return
        }
        try { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTT_WAKE) }
        catch (_: Exception) { startCommandListening() }
    }

    private fun startCommandListening() {
        runOnMainThread {
            if (stopping || verificationListening) return@runOnMainThread
            stopCommandRecognizer()
            val model = wakeModel ?: run { finishCommandCycle(); return@runOnMainThread }
            try {
                commandVoskRecognizer = Recognizer(model, 16000.0f)
                commandVoskService = SpeechService(commandVoskRecognizer, 16000.0f)
                commandVoskService?.startListening(commandListener)
                updateNotification("Local speech recognition • listening for your command")
            } catch (e: Exception) {
                Log.e(TAG, "Could not start local command recognition", e)
                finishCommandCycle()
            }
        }
    }

    private fun startVerificationListening() {
        runOnMainThread {
            if (stopping || !verificationListening) return@runOnMainThread
            stopCommandRecognizer()
            val model = wakeModel ?: run { cancelVerification("The local speech engine is not ready."); return@runOnMainThread }
            try {
                commandVoskRecognizer = Recognizer(model, 16000.0f)
                commandVoskService = SpeechService(commandVoskRecognizer, 16000.0f)
                commandVoskService?.startListening(verificationVoskListener)
                updateNotification("VERIFICATION • local speech • say “Yes” or “Cancel”")
            } catch (e: Exception) {
                Log.e(TAG, "Could not start local verification recognition", e)
                cancelVerification("Local speech recognition could not start. The action was cancelled.")
            }
        }
    }

    private val commandListener = object : VoskRecognitionListener {
        override fun onPartialResult(hypothesis: String?) {}
        override fun onResult(hypothesis: String?) { if(!commandListening) return; val text=extractVoskText(hypothesis,"text"); if(text.isNotBlank()) handleCommand(text) }
        override fun onFinalResult(hypothesis: String?) { if(!commandListening) return; val text=extractVoskText(hypothesis,"text"); if(text.isNotBlank()) handleCommand(text) else finishCommandCycle() }
        override fun onError(e: Exception?) { Log.e(TAG,"Local command recognition error",e); finishCommandCycle() }
        override fun onTimeout() { finishCommandCycle() }
    }

    private val verificationVoskListener = object : VoskRecognitionListener {
        override fun onPartialResult(hypothesis: String?) { handleVerificationText(extractVoskText(hypothesis,"partial")) }
        override fun onResult(hypothesis: String?) { handleVerificationText(extractVoskText(hypothesis,"text")) }
        override fun onFinalResult(hypothesis: String?) { handleVerificationText(extractVoskText(hypothesis,"text")) }
        override fun onError(e: Exception?) { verificationAttempts++; if(verificationAttempts<2) speakVerificationPrompt() else cancelVerification("Verification timed out. The action was cancelled.") }
        override fun onTimeout() { verificationAttempts++; if(verificationAttempts<2) speakVerificationPrompt() else cancelVerification("Verification timed out. The action was cancelled.") }
    }

    private fun handleVerificationText(text:String){
        if(!verificationListening || text.isBlank()) return
        when { isConfirmationYes(text) -> confirmPendingAction(); containsCancel(text) -> cancelVerification("Okay, I cancelled that action.") }
    }

    private fun handleCommand(text: String) {
        stopCommandRecognizer()
        conversationManager.addMessage("user", text)
        val processor = CommandProcessor(this)
        val directConfirmation = processor.confirmationPromptFor(text)
        if (directConfirmation != null) {
            pendingRawCommand = text
            pendingDecision = null
            verificationAttempts = 0
            conversationManager.addMessage("assistant", directConfirmation)
            broadcastChatRefresh()
            verificationListening = true
            commandListening = false
            updateNotification("VERIFICATION • say “Yes” to confirm")
            showConfirmationPopupForRawCommand(text)
            speakVerificationPrompt(directConfirmation)
            return
        }
        // AI is now the primary decision maker. Simple device actions can still
        // be delegated by AgentExecutor, but complex requests are not hard-coded.
        askAi(text)
    }

    private fun askAi(command: String) {
        agentLoop?.stop()
        agentLoop = AgentLoop(this)
        conversationManager.addMessage("assistant", "Working on it...")
        broadcastChatRefresh()
        speak("Working on it.")
        agentLoop?.start(command, object : AgentLoop.Callback {
            override fun onStatus(message: String) {
                updateNotification("AGENT • $message")
            }

            override fun onFinished(success: Boolean, message: String) {
                conversationManager.addMessage("assistant", message)
                broadcastChatRefresh()
                speak(message)
                updateNotification(if (success) "READY • Task complete" else "READY • Task stopped")
                finishCommandCycle()
            }
        })
    }

    private fun handleDecision(decision: AIClient.RouteDecision) {
        val response = decision.response.ifBlank { "I can do that." }
        val mustConfirm = decision.requiresConfirmation || decision.action.uppercase() == "CALL" || decision.action.uppercase() == "STOP_NOVA" || decision.plan.any { it.action.uppercase() == "CALL" || it.action.uppercase() == "STOP_NOVA" }
        if (mustConfirm) {
            pendingDecision = decision
            pendingRawCommand = null
            verificationAttempts = 0
            conversationManager.addMessage("assistant", response + "\n\n" + decision.confirmationPrompt)
            broadcastChatRefresh()
            verificationListening = true
            commandListening = false
            showConfirmationPopupForDecision(decision)
            speakVerificationPrompt(decision.confirmationPrompt)
            return
        }
        executeDecision(decision)
    }

    private fun executeDecision(decision: AIClient.RouteDecision) {
        val processor = CommandProcessor(this)
        val result = when {
            decision.type == "action" && decision.action.isNotBlank() -> processor.executeAction(decision.action, decision.payload)
            decision.type == "plan" && decision.plan.isNotEmpty() -> processor.executePlan(decision.plan)
            else -> CommandProcessor.CommandResult(true, decision.response.ifBlank { "Done." }, true)
        }
        conversationManager.addMessage("assistant", if (result.success) decision.response.ifBlank { result.response } else result.response)
        broadcastChatRefresh()
        speak(if (result.success) decision.response.ifBlank { result.response } else result.response)
    }

    private fun showConfirmationPopupForRawCommand(raw: String) {
        val lower = raw.lowercase(Locale.ROOT).trim()
        if (lower == "stop nova" || lower == "stop assistant" || lower.contains("turn off nova")) {
            launchConfirmationPopup(
                "Stop Nova?",
                "Nova will stop listening for the wake word.\n\nSay \"Yes\" or tap YES to confirm."
            )
            return
        }

        val target = when {
            lower.startsWith("call ") -> raw.trim().substring(5).trim()
            lower.startsWith("phone ") -> raw.trim().substring(6).trim()
            lower.contains("make a call to ") -> raw.substringAfter("make a call to ").trim()
            lower.contains("call my ") -> raw.substringAfter("call my ").trim()
            else -> ""
        }

        if (target.isNotBlank()) {
            val resolved = CommandRouter.resolveCallTarget(this, target)
            if (resolved != null) {
                launchConfirmationPopup(
                    "Confirm phone call",
                    "Who: ${resolved.displayName}\nNumber: ${resolved.number}\n\nSay \"Yes\" or tap YES to place the call."
                )
            } else {
                launchConfirmationPopup(
                    "Confirm phone call",
                    "Call target: $target\n\nSay \"Yes\" or tap YES to continue."
                )
            }
        }
    }

    private fun showConfirmationPopupForDecision(decision: AIClient.RouteDecision) {
        val action = decision.action.uppercase(Locale.ROOT)
        var payload = decision.payload
        if (decision.type == "plan") {
            val callStep = decision.plan.firstOrNull { it.action.uppercase(Locale.ROOT) == "CALL" }
            if (callStep != null) payload = callStep.payload
        }
        if (action == "CALL" || decision.plan.any { it.action.uppercase(Locale.ROOT) == "CALL" }) {
            val target = payload.trim()
            val resolved = if (target.isNotBlank()) CommandRouter.resolveCallTarget(this, target) else null
            val who = resolved?.displayName ?: target.ifBlank { "selected contact" }
            val number = resolved?.number
            val numberLine = if (!number.isNullOrBlank()) "\nNumber: $number" else ""
            launchConfirmationPopup(
                "Confirm phone call",
                "Who: $who$numberLine\n\nSay “Yes” or tap YES to place the call."
            )
        } else if (action == "STOP_NOVA" || decision.plan.any { it.action.uppercase(Locale.ROOT) == "STOP_NOVA" }) {
            launchConfirmationPopup(
                "Stop Nova?",
                "Nova will stop listening for the wake word.\n\nSay “Yes” or tap YES to confirm."
            )
        } else {
            launchConfirmationPopup(
                "Confirm action",
                (decision.response.ifBlank { "This action needs confirmation." }) + "\n\nSay “Yes” or tap YES to confirm."
            )
        }
    }

    private fun launchConfirmationPopup(title: String, message: String) {
        try {
            val intent = Intent(this, ConfirmationActivity::class.java).apply {
                putExtra(ConfirmationActivity.EXTRA_TITLE, title)
                putExtra(ConfirmationActivity.EXTRA_MESSAGE, message)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Could not show confirmation popup", e)
        }
    }

    private fun closeConfirmationPopup() {
        sendBroadcast(Intent(ConfirmationActivity.ACTION_DISMISSED).setPackage(packageName))
    }

    private fun speakVerificationPrompt(prompt: String = "This action needs confirmation. Say yes to confirm, or say cancel.") {
        runOnMainThread {
            if (stopping) return@runOnMainThread
            verificationListening = true
            stopCommandRecognizer()
            if (!ttsReady || tts == null) {
                startVerificationListening()
                return@runOnMainThread
            }
            try { tts?.speak(prompt, TextToSpeech.QUEUE_FLUSH, null, UTT_VERIFY) }
            catch (_: Exception) { startVerificationListening() }
        }
    }

    private fun confirmPendingAction() {
        stopCommandRecognizer()
        verificationListening = false
        commandListening = false
        conversationManager.addMessage("user", "Yes")
        broadcastChatRefresh()
        updateNotification("Verification accepted • executing action")
        closeConfirmationPopup()

        val decision = pendingDecision
        val raw = pendingRawCommand
        pendingDecision = null
        pendingRawCommand = null
        if (decision != null) {
            executeDecision(decision)
            return
        }
        if (!raw.isNullOrBlank()) {
            val result = CommandProcessor(this).executeConfirmedCommand(raw)
            conversationManager.addMessage("assistant", result.response)
            broadcastChatRefresh()
            speak(result.response)
            return
        }
        cancelVerification("There is nothing waiting for verification.")
    }

    private fun cancelVerification(message: String) {
        stopCommandRecognizer()
        pendingDecision = null
        pendingRawCommand = null
        verificationListening = false
        commandListening = false
        closeConfirmationPopup()
        conversationManager.addMessage("assistant", message)
        broadcastChatRefresh()
        speak(message)
    }

    private fun isConfirmationYes(text: String): Boolean {
        val normalized = text.lowercase(Locale.ROOT).replace(Regex("[^a-z ]"), " ").replace(Regex("\\s+"), " ").trim()
        return normalized == "yes" || normalized == "yes please" || normalized == "confirm" || normalized == "confirmed" || normalized == "do it"
    }

    private fun containsCancel(text: String): Boolean = text.lowercase(Locale.ROOT).contains("cancel") || text.lowercase(Locale.ROOT).contains("never mind")

    private fun speak(text: String) {
        runOnMainThread {
            if (stopping) return@runOnMainThread
            if (!ttsReady || tts == null) {
                finishCommandCycle()
                return@runOnMainThread
            }
            try { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTT_REPLY) }
            catch (_: Exception) { finishCommandCycle() }
        }
    }

    private fun speakOnce(text: String) {
        runOnMainThread { try { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nova_notice") } catch (_: Exception) {} }
    }

    private fun finishCommandCycle() {
        commandCycleActiveAt = 0L
        runOnMainThread {
            if (stopping) return@runOnMainThread
            stopCommandRecognizer()
            commandListening = false
            verificationListening = false
            pendingDecision = null
            pendingRawCommand = null
            startWakeWordEngine()
        }
    }

    private fun extractVoskText(hypothesis: String?, key: String): String {
        if (hypothesis.isNullOrBlank()) return ""
        return try { JSONObject(hypothesis).optString(key, "").lowercase(Locale.ROOT) } catch (_: Exception) { hypothesis.lowercase(Locale.ROOT) }
    }

    private fun containsWakeWord(text: String): Boolean {
        val normalized = text.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (normalized.isBlank()) return false
        // Prefer the requested phrase, while retaining the short "Nova" fallback
        // for noisy environments where Vosk drops the word "hey".
        if (Regex("\\bhey nova\\b").containsMatchIn(normalized)) return true
        return Regex("^nova$").matches(normalized) || Regex("^nova ").containsMatchIn(normalized)
    }

    private fun restartWakeListening() {
        scheduleWakeRestart("Wake listener restart")
    }

    private fun scheduleWakeRestart(reason: String) {
        runOnMainThread {
            if (stopping || commandListening || verificationListening || loadingWakeEngine) return@runOnMainThread
            wakeRestartHandler.removeCallbacksAndMessages(null)
            wakeRestartAttempts = (wakeRestartAttempts + 1).coerceAtMost(8)
            val delay = (250L * (1L shl (wakeRestartAttempts - 1))).coerceAtMost(8_000L)
            updateNotification("Wake engine recovering • ${delay / 1000.0}s")
            Log.w(TAG, "$reason; restarting wake engine in ${delay}ms (attempt $wakeRestartAttempts)")
            wakeRestartHandler.postDelayed({
                if (!stopping && !commandListening && !verificationListening) {
                    try {
                        stopWakeListening()
                        if (wakeEngineReady && wakeModel != null) startContinuousWakeListening() else startWakeWordEngine()
                    } catch (e: Exception) {
                        Log.e(TAG, "Wake engine recovery failed", e)
                        scheduleWakeRestart("Wake recovery failed")
                    }
                }
            }, delay)
        }
    }

    private fun stopWakeListening() {
        try { wakeSpeechService?.stop() } catch (_: Exception) {}
        try { wakeSpeechService?.shutdown() } catch (_: Exception) {}
        wakeSpeechService = null
        try { wakeRecognizer?.close() } catch (_: Exception) {}
        wakeRecognizer = null
    }

    private fun stopCommandRecognizer() {
        try { commandVoskService?.stop() } catch (_: Exception) {}
        try { commandVoskService?.shutdown() } catch (_: Exception) {}
        commandVoskService = null
        try { commandVoskRecognizer?.close() } catch (_: Exception) {}
        commandVoskRecognizer = null
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // START_STICKY lets Android recreate the service if the process is reclaimed.
        // Do not launch a second foreground service from here, which can race with Android's own lifecycle handling.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stopping = true
        agentLoop?.stop()
        agentLoop = null
        wakeRestartHandler.removeCallbacksAndMessages(null)
        stopEverything()
        try { unregisterReceiver(confirmationReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun stopEverything() {
        wakeRestartHandler.removeCallbacksAndMessages(null)
        stopWakeListening()
        stopCommandRecognizer()
        try { wakeModel?.close() } catch (_: Exception) {}
        wakeModel = null
        wakeEngineReady = false
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Nova voice assistant", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun buildNotification(text: String): Notification {
        val pending = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID).setContentTitle("Nova").setContentText(text).setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentIntent(pending).setOngoing(true).build()
        } else {
            Notification.Builder(this).setContentTitle("Nova").setContentText(text).setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentIntent(pending).setOngoing(true).build()
        }
    }

    private fun updateNotification(text: String) {
        try { (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, buildNotification(text)) } catch (_: Exception) {}
    }

    private fun broadcastChatRefresh() {
        sendBroadcast(Intent("com.nova.ai.CHAT_UPDATED").setPackage(packageName))
    }

    private fun runOnMainThread(action: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(action)
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) tts?.language = Locale("en", "IN")
    }

    override fun onBind(intent: Intent?): IBinder? = null

}
