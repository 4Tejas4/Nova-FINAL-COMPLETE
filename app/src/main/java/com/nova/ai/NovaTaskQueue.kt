package com.nova.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Persistent priority queue for multiple autonomous tasks. */
class NovaTaskQueue(context: Context) {
    data class QueueItem(
        val id: String,
        val request: String,
        val priority: Int,
        val createdAt: Long,
        val updatedAt: Long,
        val state: String,
        val taskId: String = "",
        val lastMessage: String = ""
    )

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enqueue(request: String, priority: Int = PRIORITY_NORMAL): QueueItem {
        val now = System.currentTimeMillis()
        val item = QueueItem(
            id = UUID.randomUUID().toString(),
            request = request.trim().take(1000),
            priority = priority.coerceIn(PRIORITY_LOW, PRIORITY_HIGH),
            createdAt = now,
            updatedAt = now,
            state = STATE_QUEUED
        )
        save(readAll() + item)
        return item
    }

    fun next(): QueueItem? = readAll()
        .filter { it.state == STATE_QUEUED }
        .sortedWith(compareByDescending<QueueItem> { it.priority }.thenBy { it.createdAt })
        .firstOrNull()

    fun get(id: String): QueueItem? = readAll().firstOrNull { it.id == id }

    fun all(): List<QueueItem> = readAll().sortedWith(compareByDescending<QueueItem> { it.priority }.thenBy { it.createdAt })

    fun markRunning(id: String, taskId: String) = update(id) { it.copy(state = STATE_RUNNING, taskId = taskId, updatedAt = now()) }
    fun markPaused(id: String, message: String = "") = update(id) { it.copy(state = STATE_PAUSED, lastMessage = message.take(500), updatedAt = now()) }
    fun markCompleted(id: String, message: String = "") = update(id) { it.copy(state = STATE_COMPLETED, lastMessage = message.take(500), updatedAt = now()) }
    fun markFailed(id: String, message: String = "") = update(id) { it.copy(state = STATE_FAILED, lastMessage = message.take(500), updatedAt = now()) }
    fun requeue(id: String) = update(id) { it.copy(state = STATE_QUEUED, updatedAt = now()) }
    fun cancel(id: String) = update(id) { it.copy(state = STATE_CANCELLED, updatedAt = now()) }
    fun remove(id: String) = save(readAll().filterNot { it.id == id })

    fun clearFinished() = save(readAll().filter { it.state !in FINISHED_STATES })

    private fun update(id: String, transform: (QueueItem) -> QueueItem) {
        save(readAll().map { if (it.id == id) transform(it) else it })
    }

    private fun now() = System.currentTimeMillis()

    private fun save(items: List<QueueItem>) {
        val array = JSONArray()
        items.takeLast(MAX_ITEMS).forEach { item ->
            array.put(JSONObject().apply {
                put("id", item.id); put("request", item.request); put("priority", item.priority)
                put("createdAt", item.createdAt); put("updatedAt", item.updatedAt); put("state", item.state)
                put("taskId", item.taskId); put("lastMessage", item.lastMessage)
            })
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private fun readAll(): List<QueueItem> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val a = JSONArray(raw)
            buildList {
                for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i) ?: continue
                    add(QueueItem(o.optString("id"), o.optString("request"), o.optInt("priority", PRIORITY_NORMAL),
                        o.optLong("createdAt"), o.optLong("updatedAt"), o.optString("state", STATE_QUEUED),
                        o.optString("taskId"), o.optString("lastMessage")))
                }
            }
        } catch (_: Exception) { emptyList() }
    }

    companion object {
        const val PRIORITY_LOW = 0
        const val PRIORITY_NORMAL = 50
        const val PRIORITY_HIGH = 100
        const val STATE_QUEUED = "QUEUED"
        const val STATE_RUNNING = "RUNNING"
        const val STATE_PAUSED = "PAUSED"
        const val STATE_COMPLETED = "COMPLETED"
        const val STATE_FAILED = "FAILED"
        const val STATE_CANCELLED = "CANCELLED"
        private const val PREFS = "nova_task_queue"
        private const val KEY = "queue"
        private const val MAX_ITEMS = 50
        private val FINISHED_STATES = setOf(STATE_COMPLETED, STATE_FAILED, STATE_CANCELLED)
    }
}
