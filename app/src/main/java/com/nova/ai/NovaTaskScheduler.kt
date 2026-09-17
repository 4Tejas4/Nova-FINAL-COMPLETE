package com.nova.ai

import android.content.Context

/** Queue policy and recovery coordinator. */
class NovaTaskScheduler(context: Context) {
    private val queue = NovaTaskQueue(context)

    fun submit(request: String, priority: Int = NovaTaskQueue.PRIORITY_NORMAL): NovaTaskQueue.QueueItem = queue.enqueue(request, priority)
    fun next(): NovaTaskQueue.QueueItem? = queue.next()
    fun list(): List<NovaTaskQueue.QueueItem> = queue.all()
    fun cancel(id: String) = queue.cancel(id)
    fun pause(id: String) = queue.markPaused(id, "Paused by user")
    fun resume(id: String) = queue.requeue(id)
    fun clearFinished() = queue.clearFinished()
}
