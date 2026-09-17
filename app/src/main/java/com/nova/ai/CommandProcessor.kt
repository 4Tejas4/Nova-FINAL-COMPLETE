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

    fun confirmationPromptFor(userText: String): String? {
        val text = userText.lowercase().replace(Regex("\\s+"), " ").trim()
        val isStop = text == "stop nova" || text == "stop assistant" || text.contains("turn off nova")
        val isCall = text.startsWith("call ") || text.startsWith("phone ") || text.contains("make a call") || text.contains("call my")
        return when {
            isStop -> "Stopping Nova needs confirmation. Say yes to confirm, or say cancel."
            isCall -> "Placing a phone call needs confirmation. Say yes to confirm, or say cancel."
            else -> null
        }
    }

    fun executeConfirmedCommand(userText: String): CommandResult {
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
