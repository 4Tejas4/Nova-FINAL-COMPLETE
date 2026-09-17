package com.nova.ai

import android.content.Context

/** Coordinates explicit resume of a persisted autonomous task. */
class NovaTaskResumeEngine(private val context: Context) {
    private val tasks = NovaTaskManager(context)

    fun recoverInterruptedTasks() {
        tasks.markInterruptedRunningTasks()
    }

    fun latest(): NovaTaskManager.TaskRecord? = tasks.latestResumable()

    fun list(): List<NovaTaskManager.TaskRecord> = tasks.resumable()

    fun resume(taskId: String, callback: AgentLoop.Callback): Boolean {
        val record = tasks.get(taskId) ?: return false
        if (record.status != NovaTaskManager.STATUS_RUNNING &&
            record.status != NovaTaskManager.STATUS_PAUSED &&
            record.status != NovaTaskManager.STATUS_INTERRUPTED) return false

        AgentLoop(context).resume(record, callback)
        return true
    }

    fun resumeLatest(callback: AgentLoop.Callback): Boolean {
        val record = latest() ?: return false
        return resume(record.id, callback)
    }
}
