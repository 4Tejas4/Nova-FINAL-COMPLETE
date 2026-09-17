package com.nova.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class ConversationManager(context: Context) {
    companion object {
        private const val PREFS_NAME = "NovaPrefs"
        private const val KEY_CONVERSATION = "conversation_history"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun addMessage(role: String, content: String) {
        val history = getConversationHistory().toMutableList()
        history.add(mapOf("role" to role, "content" to content))
        saveConversation(history.takeLast(30))
    }

    @Synchronized
    fun getConversationHistory(): List<Map<String, String>> {
        return try {
            val json = JSONArray(prefs.getString(KEY_CONVERSATION, "[]") ?: "[]")
            val history = mutableListOf<Map<String, String>>()
            for (i in 0 until json.length()) {
                val obj = json.optJSONObject(i) ?: continue
                history.add(mapOf("role" to obj.optString("role", "user"), "content" to obj.optString("content", "")))
            }
            history
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun clearConversation() {
        prefs.edit().putString(KEY_CONVERSATION, "[]").apply()
    }

    private fun saveConversation(history: List<Map<String, String>>) {
        val array = JSONArray()
        for (msg in history) {
            array.put(JSONObject().put("role", msg["role"] ?: "user").put("content", msg["content"] ?: ""))
        }
        prefs.edit().putString(KEY_CONVERSATION, array.toString()).apply()
    }
}
