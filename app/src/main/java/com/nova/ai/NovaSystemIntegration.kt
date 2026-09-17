package com.nova.ai

import android.content.Context

/** Stage 61 facade tying voice, queue, events, capabilities and diagnostics together. */
object NovaSystemIntegration {
    fun initialize(context: Context) {
        val app = context.applicationContext
        NovaCapabilityRegistry.snapshot(app)
        NovaEventEngine(app).prime()
        NovaTaskResumeEngine(app).recoverInterruptedTasks()
    }

    fun submit(context: Context, request: String, priority: Int = NovaTaskQueue.PRIORITY_NORMAL): NovaTaskQueue.QueueItem =
        NovaAgentCore(context).enqueue(request, priority)

    fun run(context: Context) = NovaBackgroundTaskService.start(context)

    fun securityReport(context: Context): String = NovaSecurityAudit(context.applicationContext).report()

    fun performancePolicy(context: Context): NovaPerformanceEngine.Policy = NovaPerformanceEngine(context.applicationContext).policy()
}
