package com.nova.ai

import android.content.Context
import java.util.Locale

/** Resolves conversational references without inventing facts. */
class NovaReferenceResolver(private val context: Context) {
    data class Resolution(val text: String, val changed: Boolean, val notes: List<String> = emptyList())

    fun resolve(text: String, history: List<Map<String, String>> = ConversationManager(context).getConversationHistory()): Resolution {
        val original = text.trim()
        var result = original
        val notes = mutableListOf<String>()
        if (original.isBlank()) return Resolution(result, false)
        val previousUser = history.asReversed().firstOrNull { it["role"] == "user" }?.get("content").orEmpty().trim()

        val continuation = original.lowercase(Locale.ROOT)
        if (previousUser.isNotBlank() && continuation.matches(Regex("(do it|do that|go ahead|continue|keep going|try again|repeat that)"))) {
            result = "Continue the previous request: $previousUser"
            notes += "expanded continuation"
            return Resolution(result, true, notes)
        }

        // Keep references explicit enough for the planner to re-observe the live UI.
        if (previousUser.isNotBlank() && Regex("\\b(it|that|this|there)\\b", RegexOption.IGNORE_CASE).containsMatchIn(result)) {
            result = "${result.trim()} (reference from previous request: $previousUser)"
            notes += "attached previous-request context"
        }
        return Resolution(result, result != original, notes)
    }
}
