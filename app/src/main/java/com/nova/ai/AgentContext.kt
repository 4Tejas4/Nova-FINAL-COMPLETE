package com.nova.ai

/**
 * Short-lived context shared by all steps of one autonomous task.
 * It is cleared when the task starts/finishes and is never persisted.
 */
class AgentContext {
    private val values = linkedMapOf<String, String>()

    fun put(key: String, value: String) {
        val clean = key.trim()
        if (clean.isNotBlank()) values[clean] = value
    }

    fun get(key: String): String = values[key.trim()].orEmpty()

    fun all(): Map<String, String> = values.toMap()

    fun summary(): String = values.entries.joinToString("\n") { "${it.key}=${it.value}" }

    fun clear() = values.clear()
}
