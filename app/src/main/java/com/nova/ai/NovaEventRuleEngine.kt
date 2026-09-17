package com.nova.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Persistent, explicitly enabled proactive rules. Rules only enqueue normal Nova tasks. */
class NovaEventRuleEngine(context: Context) {
    data class Rule(
        val id: String,
        val name: String,
        val eventType: String,
        val conditionKey: String = "",
        val conditionValue: String = "",
        val task: String,
        val priority: Int = NovaTaskQueue.PRIORITY_NORMAL,
        val cooldownMs: Long = 60_000L,
        val enabled: Boolean = false,
        val lastTriggered: Long = 0L
    )

    private val prefs = context.applicationContext.getSharedPreferences("nova_event_rules", Context.MODE_PRIVATE)

    fun create(name: String, eventType: String, task: String, conditionKey: String = "", conditionValue: String = "", priority: Int = NovaTaskQueue.PRIORITY_NORMAL, cooldownMs: Long = 60_000L, approved: Boolean = false): Rule {
        val rule = Rule(UUID.randomUUID().toString(), name.trim().take(100), eventType.trim().uppercase(), conditionKey.trim(), conditionValue.trim(), task.trim().take(1000), priority, cooldownMs.coerceAtLeast(0), approved)
        save(readAll() + rule)
        return rule
    }

    fun setEnabled(id: String, enabled: Boolean) = update(id) { it.copy(enabled = enabled) }
    fun delete(id: String) = save(readAll().filterNot { it.id == id })
    fun all(): List<Rule> = readAll()

    fun matching(event: NovaEventEngine.Event, context: Map<String, String>): List<Rule> {
        val now = System.currentTimeMillis()
        return readAll().filter { r ->
            r.enabled && r.eventType == event.type.uppercase() &&
                now - r.lastTriggered >= r.cooldownMs &&
                (r.conditionKey.isBlank() || context[r.conditionKey].equals(r.conditionValue, true) || event.payload[r.conditionKey].equals(r.conditionValue, true))
        }
    }

    fun markTriggered(id: String) = update(id) { it.copy(lastTriggered = System.currentTimeMillis()) }

    private fun update(id: String, f: (Rule) -> Rule) = save(readAll().map { if (it.id == id) f(it) else it })

    private fun save(items: List<Rule>) {
        val a = JSONArray()
        items.takeLast(100).forEach { r ->
            a.put(JSONObject().apply {
                put("id", r.id); put("name", r.name); put("eventType", r.eventType); put("conditionKey", r.conditionKey); put("conditionValue", r.conditionValue)
                put("task", r.task); put("priority", r.priority); put("cooldownMs", r.cooldownMs); put("enabled", r.enabled); put("lastTriggered", r.lastTriggered)
            })
        }
        prefs.edit().putString("rules", a.toString()).apply()
    }

    private fun readAll(): List<Rule> {
        val raw = prefs.getString("rules", null) ?: return emptyList()
        return try {
            val a = JSONArray(raw)
            buildList {
                for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i) ?: continue
                    add(Rule(o.optString("id"), o.optString("name"), o.optString("eventType"), o.optString("conditionKey"), o.optString("conditionValue"), o.optString("task"), o.optInt("priority", 50), o.optLong("cooldownMs", 60000), o.optBoolean("enabled", false), o.optLong("lastTriggered", 0)))
                }
            }
        } catch (_: Exception) { emptyList() }
    }
}
