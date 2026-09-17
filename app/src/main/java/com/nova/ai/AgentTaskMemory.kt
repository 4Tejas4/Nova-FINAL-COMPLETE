package com.nova.ai

/** Small in-memory trace for the current autonomous task. It is intentionally not persisted. */
class AgentTaskMemory {
    data class Entry(val action: String, val payload: String, val result: String, val success: Boolean)

    private val entries = mutableListOf<Entry>()

    fun add(action: String, payload: String, result: String, success: Boolean) {
        entries.add(Entry(action, payload, result, success))
        if (entries.size > 20) entries.removeAt(0)
    }

    fun recent(limit: Int = 12): List<Entry> = entries.takeLast(limit)

    fun repeatedFailure(action: String, payload: String): Boolean {
        val last = entries.takeLast(2)
        return last.size == 2 && last.all { !it.success && it.action.equals(action, true) && it.payload == payload }
    }

    fun summary(): String = recent().joinToString("\\n") {
        "${if (it.success) "OK" else "FAIL"}: ${it.action}(${it.payload}) -> ${it.result}"
    }

    fun clear() = entries.clear()
}
