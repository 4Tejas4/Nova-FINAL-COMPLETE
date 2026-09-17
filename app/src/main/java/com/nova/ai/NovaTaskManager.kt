package com.nova.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Persistent task state for tasks that may outlive an Activity or process.
 * Only execution metadata is stored. Secrets and raw message contents are excluded.
 */
class NovaTaskManager(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class TaskRecord(
        val id: String,
        val request: String,
        val step: Int,
        val lastResult: String,
        val taskState: String,
        val actionHistory: List<String>,
        val startedAt: Long,
        val updatedAt: Long,
        val status: String
    )

    fun create(request: String): TaskRecord {
        val now = System.currentTimeMillis()
        return TaskRecord(
            id = UUID.randomUUID().toString(),
            request = sanitizeText(request, 1000),
            step = 0,
            lastResult = "",
            taskState = "Original request: ${sanitizeText(request, 1000)}",
            actionHistory = emptyList(),
            startedAt = now,
            updatedAt = now,
            status = STATUS_RUNNING
        ).also { save(it) }
    }

    fun checkpoint(
        id: String,
        request: String,
        step: Int,
        lastResult: String,
        taskState: String,
        actionHistory: List<String>,
        startedAt: Long,
        status: String = STATUS_RUNNING
    ) {
        save(TaskRecord(
            id = id,
            request = sanitizeText(request, 1000),
            step = step,
            lastResult = sanitizeText(lastResult, 1200),
            taskState = sanitizeText(taskState, 2500),
            actionHistory = actionHistory.takeLast(30).map { sanitizeText(it, 180) },
            startedAt = startedAt,
            updatedAt = System.currentTimeMillis(),
            status = status
        ))
    }

    fun markFinished(id: String, success: Boolean, message: String = "") {
        val old = get(id) ?: return
        save(old.copy(
            lastResult = sanitizeText(message, 1200),
            updatedAt = System.currentTimeMillis(),
            status = if (success) STATUS_COMPLETED else STATUS_FAILED
        ))
    }

    fun markPaused(id: String) {
        val old = get(id) ?: return
        save(old.copy(updatedAt = System.currentTimeMillis(), status = STATUS_PAUSED))
    }

    fun get(id: String): TaskRecord? = readAll().firstOrNull { it.id == id }

    fun latestResumable(): TaskRecord? = readAll()
        .filter { it.status == STATUS_RUNNING || it.status == STATUS_PAUSED || it.status == STATUS_INTERRUPTED }
        .maxByOrNull { it.updatedAt }

    fun resumable(): List<TaskRecord> = readAll()
        .filter { it.status == STATUS_RUNNING || it.status == STATUS_PAUSED || it.status == STATUS_INTERRUPTED }
        .sortedByDescending { it.updatedAt }

    fun markInterruptedRunningTasks() {
        val updated = readAll().map {
            if (it.status == STATUS_RUNNING) it.copy(status = STATUS_INTERRUPTED, updatedAt = System.currentTimeMillis()) else it
        }
        writeAll(updated)
    }

    fun delete(id: String) = writeAll(readAll().filterNot { it.id == id })

    fun clearCompleted() = writeAll(readAll().filter {
        it.status != STATUS_COMPLETED && it.status != STATUS_FAILED
    })

    private fun save(record: TaskRecord) {
        val all = readAll().toMutableList()
        all.removeAll { it.id == record.id }
        all.add(record)
        writeAll(all.sortedByDescending { it.updatedAt }.take(MAX_TASKS))
    }

    private fun readAll(): List<TaskRecord> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    val actions = o.optJSONArray("actionHistory")?.let { a ->
                        buildList { for (j in 0 until a.length()) add(a.optString(j)) }
                    } ?: emptyList()
                    add(TaskRecord(
                        id = o.optString("id"),
                        request = o.optString("request"),
                        step = o.optInt("step"),
                        lastResult = o.optString("lastResult"),
                        taskState = o.optString("taskState"),
                        actionHistory = actions,
                        startedAt = o.optLong("startedAt"),
                        updatedAt = o.optLong("updatedAt"),
                        status = o.optString("status", STATUS_INTERRUPTED)
                    ))
                }
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun writeAll(items: List<TaskRecord>) {
        val array = JSONArray()
        items.forEach { item ->
            val actions = JSONArray()
            item.actionHistory.forEach(actions::put)
            array.put(JSONObject().apply {
                put("id", item.id)
                put("request", item.request)
                put("step", item.step)
                put("lastResult", item.lastResult)
                put("taskState", item.taskState)
                put("actionHistory", actions)
                put("startedAt", item.startedAt)
                put("updatedAt", item.updatedAt)
                put("status", item.status)
            })
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private fun sanitizeText(value: String, max: Int): String = value
        .replace(Regex("(?i)(api[_ -]?key|token|password|authorization)\\s*[:=]\\s*\\S+"), "$1=[REDACTED]")
        .take(max)

    companion object {
        const val STATUS_RUNNING = "RUNNING"
        const val STATUS_PAUSED = "PAUSED"
        const val STATUS_INTERRUPTED = "INTERRUPTED"
        const val STATUS_COMPLETED = "COMPLETED"
        const val STATUS_FAILED = "FAILED"
        private const val PREFS = "nova_long_running_tasks"
        private const val KEY = "tasks"
        private const val MAX_TASKS = 20
    }
}
