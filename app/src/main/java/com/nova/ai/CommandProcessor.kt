package com.nova.ai

import android.content.Context

class CommandProcessor(private val context: Context) {

    data class CommandResult(
        val isCommand: Boolean,
        val response: String,
        val success: Boolean = true
    )

    fun processCommand(userText: String): CommandResult {
        val confirmation = confirmationPromptFor(userText)
        if (confirmation != null) {
            return CommandResult(true, confirmation, false)
        }
        val result = CommandRouter.execute(context, userText)
        return if (result != null) {
            CommandResult(true, result.response, result.success)
        } else {
            CommandResult(false, "Not a device command", true)
        }
    }

    /** True when the user vaguely wants entertainment (e.g. "I am bored, want to watch a video"). */
    private fun wantsEntertainmentSuggestion(userText: String): Boolean {
        val t = userText.lowercase().replace(Regex("\\s+"), " ").trim()
        // If a specific app was named, normal routing should handle it instead.
        if (Regex("\\b(netflix|prime video|amazon prime|hotstar|jiocinema|voot|sonyliv|instagram|twitch|tiktok)\\b").containsMatchIn(t)) return false
        // Explicit searches (e.g. "search cat videos on youtube") are not vague wishes.
        if (t.contains("youtube") || Regex("^(search|find|look up)\\b").containsMatchIn(t)) return false
        val bored = Regex("\\b(bored|entertain( me|ment)?|something fun)\\b").containsMatchIn(t)
        val watchSomething = Regex("\\b(watch|see|show me)\\b").containsMatchIn(t) &&
                Regex("\\b(video(s)?|movie(s)?|something|clip(s)?|reels?)\\b").containsMatchIn(t)
        return bored || watchSomething
    }

    fun confirmationPromptFor(userText: String): String? {
        val text = userText.lowercase().replace(Regex("\\s+"), " ").trim()
        val isStop = text == "stop nova" || text == "stop assistant" || text.contains("turn off nova")
        val isCall = text.startsWith("call ") || text.startsWith("phone ") || text.contains("make a call") || text.contains("call my")
        return when {
            isStop -> "Stopping Nova needs confirmation. Say yes to confirm, or say cancel."
            isCall -> "Placing a phone call needs confirmation. Say yes to confirm, or say cancel."
            wantsEntertainmentSuggestion(userText) -> "Can I open YouTube for you? Say yes or no."
            else -> null
        }
    }

    fun executeConfirmedCommand(userText: String): CommandResult {
        if (wantsEntertainmentSuggestion(userText)) {
            val r = CommandRouter.executeAction(context, "OPEN_APP", "youtube")
            return CommandResult(true, r.response, r.success)
        }
        val result = CommandRouter.execute(context, userText)
            ?: return CommandResult(false, "I couldn't execute that command.", false)
        return CommandResult(true, result.response, result.success)
    }

    fun executeAction(action: String, payload: String): CommandResult {
        val result = CommandRouter.executeAction(context, action, payload)
        return CommandResult(true, result.response, result.success)
    }

    fun executePlan(plan: List<AIClient.PlannedAction>): CommandResult {
        if (plan.isEmpty()) return CommandResult(false, "The action plan was empty.", false)
        val responses = mutableListOf<String>()
        for (step in plan) {
            val result = CommandRouter.executeAction(context, step.action, step.payload)
            responses.add(result.response)
            if (!result.success) return CommandResult(true, result.response, false)
        }
        return CommandResult(true, responses.lastOrNull() ?: "Done.", true)
    }
}
