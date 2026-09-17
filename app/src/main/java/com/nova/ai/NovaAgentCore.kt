package com.nova.ai

import android.content.Context

/** Single public entry point for UI, wake service and background execution. */
class NovaAgentCore(private val context: Context) {
    private val resolver = NovaReferenceResolver(context)

    interface Callback {
        fun onStatus(message: String)
        fun onFinished(success: Boolean, message: String)
    }

    fun submitVoiceOrText(text: String, callback: Callback) {
        val manager = ConversationManager(context)
        val resolved = resolver.resolve(text, manager.getConversationHistory())
        val command = resolved.text
        if (command.isBlank()) {
            callback.onFinished(false, "I did not receive a command.")
            return
        }
        manager.addMessage("user", text)
        if (resolved.changed) callback.onStatus("Context: ${resolved.notes.joinToString()}")
        val processor = CommandProcessor(context)
        val direct = processor.confirmationPromptFor(command)
        if (direct != null) {
            callback.onFinished(false, direct)
            return
        }
        AgentLoop(context).start(command, object : AgentLoop.Callback {
            override fun onStatus(message: String) = callback.onStatus(message)
            override fun onFinished(success: Boolean, message: String) {
                manager.addMessage("assistant", message)
                callback.onFinished(success, message)
            }
        })
    }

    fun enqueue(text: String, priority: Int = NovaTaskQueue.PRIORITY_NORMAL): NovaTaskQueue.QueueItem {
        val resolved = resolver.resolve(text).text
        return NovaTaskScheduler(context).submit(resolved, priority)
    }

    fun capabilities(query: String = ""): String = NovaCapabilityDiscoveryEngine(context).report(query)
}
