package com.nova.ai

import android.content.Context

/** Turns verified agent executions into reusable, privacy-conscious workflow hints. */
class NovaLearningEngine(context: Context) {
    private val memory = NovaPersistentMemory(context)

    fun hintsFor(task: String): String {
        val matches = memory.search(task)
        if (matches.isEmpty()) return "No previous workflow matches were found."
        return matches.joinToString("\n") { w ->
            val reliability = if (w.successes + w.failures == 0) 0 else
                (w.successes * 100) / (w.successes + w.failures)
            "Previous pattern (${reliability}% successful): ${w.actions.joinToString(" -> ")}"
        }
    }

    fun record(task: String, actions: List<String>, success: Boolean) {
        memory.record(task, actions, success)
    }

    fun clear() = memory.clear()
}
