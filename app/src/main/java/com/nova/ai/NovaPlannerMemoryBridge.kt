package com.nova.ai

import android.content.Context

/**
 * Connects persistent workflow experience to the planner without exposing raw
 * private message contents, secrets, or authentication material.
 */
class NovaPlannerMemoryBridge(context: Context) {
    private val memory = NovaPersistentMemory(context)

    fun hintsFor(task: String, limit: Int = 5): String {
        val matches = memory.search(task, limit)
        if (matches.isEmpty()) return "No relevant previous workflow experience is available."

        return buildString {
            appendLine("RELEVANT PREVIOUS WORKFLOW EXPERIENCE:")
            matches.forEachIndexed { index, workflow ->
                val total = workflow.successes + workflow.failures
                val reliability = if (total == 0) 0 else (workflow.successes * 100) / total
                appendLine("${index + 1}. Match: ${workflow.label}")
                appendLine("   Reliability: $reliability% (${workflow.successes} successes, ${workflow.failures} failures)")
                appendLine("   Action pattern: ${workflow.actions.joinToString(" -> ")}")
            }
            append("Use these only as candidate patterns. Re-observe the current UI and validate every action; never assume an old screen, target, permission, coordinate, or app state is still true.")
        }
    }

    fun record(task: String, actions: List<String>, success: Boolean) {
        memory.record(task, actions, success)
    }
}
