package com.nova.ai

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * Closed-loop executor: observe -> plan -> execute one action -> verify -> checkpoint.
 * Stage 55 adds persistent checkpoints so a long task can be resumed after interruption.
 */
class AgentLoop(private val context: Context) {
    interface Callback {
        fun onStatus(message: String)
        fun onFinished(success: Boolean, message: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private val taskContext = AgentContext()
    private val executor = AgentExecutor(context, taskContext)
    private val taskMemory = AgentTaskMemory()
    private val learning = NovaLearningEngine(context)
    private val plannerMemory = NovaPlannerMemoryBridge(context)
    private val reliability = NovaReliabilityEngine(context)
    private val performance = NovaPerformanceEngine(context)
    private val taskManager = NovaTaskManager(context)
    private var memoryHints = ""
    private var activeTask = ""
    private var taskId = ""
    private var running = false
    private var steps = 0
    private var lastResult = ""
    private var repeatedFailureCount = 0
    private val actionHistory = mutableListOf<String>()
    private var taskState = "No sub-goal has been completed yet."
    private var startedAt = 0L
    private var lastScreenSignature = ""
    private var sameScreenCount = 0

    fun start(task: String, callback: Callback) {
        if (running) {
            callback.onFinished(false, "Nova is already working on another task.")
            return
        }
        taskId = taskManager.create(task).id
        initialize(task, callback, null)
    }

    /** Resume from a persisted checkpoint. The current UI is always re-observed. */
    fun resume(record: NovaTaskManager.TaskRecord, callback: Callback) {
        if (running) {
            callback.onFinished(false, "Nova is already working on another task.")
            return
        }
        taskId = record.id
        initialize(record.request, callback, record)
    }

    /** Pause the task and preserve a checkpoint for explicit later resume. */
    fun currentTaskId(): String = taskId

    fun stop() {
        if (!running) return
        running = false
        checkpoint(NovaTaskManager.STATUS_PAUSED)
        main.removeCallbacksAndMessages(null)
    }

    private fun initialize(task: String, callback: Callback, record: NovaTaskManager.TaskRecord?) {
        running = true
        activeTask = task
        steps = record?.step ?: 0
        lastResult = record?.lastResult.orEmpty()
        repeatedFailureCount = 0
        actionHistory.clear()
        actionHistory.addAll(record?.actionHistory.orEmpty())
        taskMemory.clear()
        taskContext.clear()
        val goalPlan = NovaGoalDecomposer.decompose(task)
        memoryHints = plannerMemory.hintsFor(task) + "\n\n" + NovaGoalDecomposer.prompt(goalPlan)
        taskContext.put("available_capabilities", NovaCapabilityRegistry.snapshot(context))
        taskState = record?.taskState?.takeIf { it.isNotBlank() }
            ?: "Original request: $task\nNo sub-goal has been completed yet."
        startedAt = record?.startedAt?.takeIf { it > 0L } ?: System.currentTimeMillis()
        lastScreenSignature = ""
        sameScreenCount = 0
        checkpoint(NovaTaskManager.STATUS_RUNNING)
        callback.onStatus(if (record == null) "Starting long-running task" else "Resuming task from step ${record.step}")
        step(task, callback)
    }

    private fun step(task: String, callback: Callback) {
        if (!running) return
        val policy = performance.policy()
        if (steps >= minOf(MAX_STEPS, policy.maxSteps)) {
            finish(false, "I stopped because the task reached the maximum number of steps.", callback)
            return
        }
        if (System.currentTimeMillis() - startedAt >= MAX_RUNTIME_MS) {
            finish(false, "I stopped because the task took too long to complete. The checkpoint was saved.", callback)
            return
        }
        steps++
        checkpoint(NovaTaskManager.STATUS_RUNNING)
        callback.onStatus("Agent step $steps: reading the screen")

        val snapshot = NovaAccessibilityService.get()?.getScreenSnapshot()
            ?: "NO_ACCESSIBILITY_UI_AVAILABLE. Prefer native Android capabilities when possible; do not require Accessibility for native actions such as timers, alarms, maps, dialing, SMS composer, URLs, media, and system intents."
        val signature = snapshot.hashCode().toString()
        if (signature == lastScreenSignature) sameScreenCount++ else sameScreenCount = 0
        lastScreenSignature = signature
        if (sameScreenCount >= 5 && lastResult.startsWith("FAIL:")) {
            finish(false, "I could not make progress on the current screen. Last result: $lastResult", callback)
            return
        }
        if (!LocalModelManager.isInstalled(context)) {
            finish(false, "The local AI model is not installed. Open Nova Settings and download or load the GGUF model.", callback)
            return
        }

        AIClient.routeAgentRequest(
            provider = provider,
            apiKey = apiKey,
            conversationHistory = ConversationManager(context).getConversationHistory(),
            task = task,
            screenSnapshot = snapshot,
            previousResult = lastResult,
            actionHistory = actionHistory.takeLast(16),
            taskState = taskState,
            contextValues = taskContext.all(),
            memoryHints = memoryHints,
            endpoint = endpoint,
            modelName = SettingsActivity.getModelName(context),
            callback = object : AIClient.RouteCallback {
                override fun onSuccess(decision: AIClient.RouteDecision) {
                    if (!running) return
                    if (decision.requiresConfirmation) {
                        finish(false, decision.confirmationPrompt, callback)
                        return
                    }
                    val action = decision.action.uppercase()
                    if (decision.goal.isNotBlank() || decision.progress.isNotBlank() || decision.stateSummary.isNotBlank()) {
                        taskState = buildString {
                            if (decision.goal.isNotBlank()) append("Current goal: ${decision.goal}\n")
                            if (decision.progress.isNotBlank()) append("Progress: ${decision.progress}\n")
                            if (decision.stateSummary.isNotBlank()) append("Facts: ${decision.stateSummary}")
                        }.trim()
                    }
                    if (action == "FINISH") {
                        finish(true, decision.response.ifBlank { "Done." }, callback)
                        return
                    }
                    if (action.isBlank()) {
                        finish(false, decision.response.ifBlank { "The AI did not provide an action." }, callback)
                        return
                    }

                    callback.onStatus("Agent: $action")
                    val trace = "$action(${decision.payload})"
                    actionHistory.add(trace)
                    val result = executor.execute(AIClient.PlannedAction(action, decision.payload))
                    val verification = AgentVerifier.verify(context, action, decision.payload, result)
                    taskMemory.add(action, decision.payload, "${result.response} | ${verification.status}: ${verification.detail}", result.success)
                    lastResult = if (result.success) "OK: ${result.response} | VERIFY=${verification.status} (${verification.confidence}%): ${verification.detail}" else "FAIL: ${result.response}"
                    if (!result.success) {
                        repeatedFailureCount++
                        if (reliability.shouldRetry(action, result.response)) {
                            callback.onStatus("Transient failure on $action; retrying once with fresh state")
                            main.postDelayed({ step(task, callback) }, 1000L)
                            return
                        }
                    } else {
                        repeatedFailureCount = 0
                        reliability.reset(action)
                    }
                    if (!result.success && actionHistory.takeLast(2).distinct().size == 1) {
                        repeatedFailureCount = maxOf(repeatedFailureCount, 2)
                    }
                    checkpoint(NovaTaskManager.STATUS_RUNNING)
                    if (repeatedFailureCount >= 3) {
                        finish(false, "I couldn't complete the task. Last result: ${result.response}", callback)
                        return
                    }
                    main.postDelayed({ step(task, callback) }, maxOf(settleDelay(action), policy.delayMs))
                }

                override fun onError(message: String) {
                    finish(false, "AI agent error: $message", callback)
                }
            }
        )
    }

    private fun checkpoint(status: String) {
        if (taskId.isBlank() || activeTask.isBlank()) return
        taskManager.checkpoint(
            id = taskId,
            request = activeTask,
            step = steps,
            lastResult = lastResult,
            taskState = taskState,
            actionHistory = actionHistory,
            startedAt = startedAt,
            status = status
        )
    }

    private fun settleDelay(action: String): Long = when (action) {
        "OPEN_APP", "BACK", "SCROLL", "TAP", "CLICK", "CLICK_DESCRIPTION", "CLICK_ID" -> 900L
        "TYPE_TEXT" -> 500L
        "WAIT" -> 100L
        else -> 650L
    }

    private fun finish(success: Boolean, message: String, callback: Callback) {
        running = false
        main.removeCallbacksAndMessages(null)
        learning.record(activeTask, actionHistory, success)
        if (taskId.isNotBlank()) taskManager.markFinished(taskId, success, message)
        memoryHints = ""
        activeTask = ""
        taskId = ""
        main.post { callback.onFinished(success, message) }
    }

    companion object {
        private const val MAX_STEPS = 60
        private const val MAX_RUNTIME_MS = 180_000L
    }
}
