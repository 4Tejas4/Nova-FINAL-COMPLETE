package com.nova.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Small, privacy-conscious persistent memory for successful agent workflows.
 * It stores task fingerprints and action patterns, not raw requests, secrets, or message contents.
 */
class NovaPersistentMemory(context: Context) {
    data class Workflow(
        val fingerprint: String,
        val label: String,
        val actions: List<String>,
        val successes: Int,
        val failures: Int,
        val updatedAt: Long
    )

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun search(task: String, limit: Int = 5): List<Workflow> {
        val target = normalize(task)
        if (target.isBlank()) return emptyList()
        val all = readAll()
        val tokens = target.split(' ').filter { it.length >= 3 }.toSet()
        return all.map { workflow ->
            val labelTokens = normalize(workflow.label).split(' ').toSet()
            val overlap = tokens.count { it in labelTokens }
            workflow to overlap
        }.filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<Workflow, Int>> { it.second }
                .thenByDescending { it.first.successes }
                .thenByDescending { it.first.updatedAt })
            .take(limit)
            .map { it.first }
    }

    fun record(task: String, actions: List<String>, success: Boolean) {
        if (task.isBlank() || actions.isEmpty()) return
        val cleanActions = actions.takeLast(30).map { sanitizeAction(it) }.filter { it.isNotBlank() }
        if (cleanActions.isEmpty()) return
        val label = normalize(task).take(160)
        val fingerprint = fingerprint(label)
        val all = readAll().toMutableList()
        val old = all.firstOrNull { it.fingerprint == fingerprint }
        val updated = if (old == null) {
            Workflow(fingerprint, label, cleanActions, if (success) 1 else 0, if (success) 0 else 1, System.currentTimeMillis())
        } else {
            Workflow(
                fingerprint = fingerprint,
                label = old.label,
                actions = if (success) cleanActions else old.actions,
                successes = old.successes + if (success) 1 else 0,
                failures = old.failures + if (success) 0 else 1,
                updatedAt = System.currentTimeMillis()
            )
        }
        all.removeAll { it.fingerprint == fingerprint }
        all.add(updated)
        writeAll(all.sortedByDescending { it.updatedAt }.take(MAX_ENTRIES))
    }

    fun clear() = prefs.edit().remove(KEY).apply()

    private fun readAll(): List<Workflow> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    val actions = o.optJSONArray("actions")?.let { a ->
                        buildList { for (j in 0 until a.length()) add(a.optString(j)) }
                    } ?: emptyList()
                    add(Workflow(
                        o.optString("fingerprint"),
                        o.optString("label"),
                        actions,
                        o.optInt("successes"),
                        o.optInt("failures"),
                        o.optLong("updatedAt")
                    ))
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeAll(items: List<Workflow>) {
        val array = JSONArray()
        items.forEach { item ->
            val actions = JSONArray()
            item.actions.forEach(actions::put)
            array.put(JSONObject().apply {
                put("fingerprint", item.fingerprint)
                put("label", item.label)
                put("actions", actions)
                put("successes", item.successes)
                put("failures", item.failures)
                put("updatedAt", item.updatedAt)
            })
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private fun sanitizeAction(value: String): String {
        // Keep action names and a short payload shape, but avoid persisting likely secrets.
        val action = value.substringBefore('(').trim().uppercase()
        return action.take(80)
    }

    private fun normalize(value: String): String = value.lowercase()
        .replace(Regex("[^a-z0-9\\s]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    companion object {
        private const val PREFS = "nova_persistent_memory"
        private const val KEY = "workflows"
        private const val MAX_ENTRIES = 50
    }
}
