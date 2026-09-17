package com.nova.ai

import android.content.Context

/** Executes one generic agent action against native capabilities or the current UI. */
class AgentExecutor(private val context: Context, private val taskContext: AgentContext) {
    private val processor = CommandProcessor(context)

    fun execute(step: AIClient.PlannedAction): CommandProcessor.CommandResult {
        val action = step.action.uppercase()
        val payload = step.payload
        val accessibility = NovaAccessibilityService.get()

        NativeCapabilityRouter.execute(context, action, payload)?.let { return it }

        return when (action) {
            "READ_SCREEN" -> CommandProcessor.CommandResult(true, accessibility?.getScreenSnapshot() ?: "NO_ACCESSIBILITY_SERVICE", accessibility != null)
            "READ_UI_STATE" -> CommandProcessor.CommandResult(true, accessibility?.getInteractiveElementsSnapshot() ?: "NO_ACCESSIBILITY_SERVICE", accessibility != null)
            "READ_TEXT" -> {
                val value = accessibility?.readText(payload)
                if (value != null) CommandProcessor.CommandResult(true, "TEXT_VALUE=$value", true)
                else CommandProcessor.CommandResult(false, "Could not find visible text: $payload", false)
            }
            "STORE_SCREEN_TEXT" -> {
                val p = payload.split('|', limit = 2)
                val target = p.getOrNull(0)?.trim().orEmpty()
                val key = p.getOrNull(1)?.trim().orEmpty()
                if (target.isBlank() || key.isBlank()) CommandProcessor.CommandResult(false, "Use target|key.", false)
                else {
                    val value = accessibility?.readText(target)
                    if (value == null) CommandProcessor.CommandResult(false, "Could not find visible text: $target", false)
                    else { taskContext.put(key, value); CommandProcessor.CommandResult(true, "Saved $key from the screen.", true) }
                }
            }
            "QUERY_CONTACT" -> CommandProcessor.CommandResult(true, PhoneDataProvider.queryContact(context, payload), true)
            "GET_BATTERY" -> CommandProcessor.CommandResult(true, PhoneDataProvider.battery(context), true)
            "GET_DEVICE_INFO" -> CommandProcessor.CommandResult(true, PhoneDataProvider.deviceInfo(), true)
            "LIST_APPS" -> CommandProcessor.CommandResult(true, PhoneDataProvider.installedApps(context, payload), true)
            "LIST_NOTIFICATIONS" -> CommandProcessor.CommandResult(true, PhoneDataProvider.activeNotifications(), true)
            "SET_CONTEXT" -> {
                val p = payload.split('|', limit = 2)
                val key = p.getOrNull(0)?.trim().orEmpty()
                val value = p.getOrNull(1).orEmpty()
                if (key.isBlank()) CommandProcessor.CommandResult(false, "Context key is empty.", false)
                else { taskContext.put(key, value); CommandProcessor.CommandResult(true, "Saved task context: $key", true) }
            }
            "CHECK_CONTEXT" -> {
                val p = payload.split('|', limit = 3)
                val key = p.getOrNull(0)?.trim().orEmpty()
                val expected = p.getOrNull(1)?.trim().orEmpty()
                val mode = p.getOrNull(2)?.trim()?.uppercase().orEmpty().ifBlank { "EQUALS" }
                if (key.isBlank()) CommandProcessor.CommandResult(false, "Context key is empty.", false)
                else {
                    val actual = taskContext.get(key)
                    val matched = when (mode) {
                        "CONTAINS" -> actual.contains(expected, ignoreCase = true)
                        "NOT_EQUALS" -> !actual.equals(expected, ignoreCase = true)
                        "NOT_CONTAINS" -> !actual.contains(expected, ignoreCase = true)
                        else -> actual.equals(expected, ignoreCase = true)
                    }
                    CommandProcessor.CommandResult(true, "CONDITION_${if (matched) "TRUE" else "FALSE"}: $key=$actual", true)
                }
            }
            "CHECK_APP_INSTALLED" -> {
                val name = payload.trim()
                val apps = PhoneDataProvider.installedApps(context, name)
                val found = apps.contains(name, ignoreCase = true) || apps.contains("$name\n", ignoreCase = true)
                CommandProcessor.CommandResult(true, "APP_INSTALLED=${found}: $name", true)
            }
            "CHECK_NATIVE_CAPABILITY" -> {
                val capability = payload.trim().uppercase()
                val supported = NativeCapabilityRouter.canHandle(capability)
                CommandProcessor.CommandResult(true, "NATIVE_CAPABILITY=$capability SUPPORTED=$supported", true)
            }
            "CLICK" -> result(accessibility?.clickText(payload) == true, "Clicked $payload", "Could not find or click $payload")
            "CLICK_DESCRIPTION" -> result(accessibility?.clickDescription(payload) == true, "Clicked control $payload", "Could not find control $payload")
            "CLICK_ID" -> result(accessibility?.clickViewId(payload) == true, "Clicked $payload", "Could not find element $payload")
            "LONG_CLICK" -> result(accessibility?.longClickText(payload) == true, "Long-pressed $payload", "Could not long-press $payload")
            "CLEAR_TEXT" -> result(accessibility?.clearFocusedText() == true, "Cleared text field", "Could not clear text field")
            "TYPE_TEXT" -> result(accessibility?.typeText(payload) == true, "Typed text", "Could not type text")
            "FOCUS_TEXT_FIELD" -> result(accessibility?.focusTextField(payload.ifBlank { null }) == true, "Focused text field", "Could not focus a text field")
            "SCROLL" -> result(accessibility?.scroll(!payload.equals("backward", true)) == true, "Scrolled", "Could not scroll")
            "BACK" -> result(accessibility?.pressBack() == true, "Went back", "Could not press Back")
            "TAP" -> {
                val p = payload.split(',').mapNotNull { it.trim().toFloatOrNull() }
                result(p.size == 2 && accessibility?.tap(p[0], p[1]) == true, "Tapped screen", "Could not tap coordinates")
            }
            "WAIT" -> {
                val ms = payload.toLongOrNull()?.coerceIn(100L, 5000L) ?: 800L
                Thread.sleep(ms)
                CommandProcessor.CommandResult(true, "Waited ${ms}ms", true)
            }
            else -> processor.executeAction(action, payload)
        }
    }

    private fun result(ok: Boolean, successText: String, failureText: String) =
        CommandProcessor.CommandResult(true, if (ok) successText else failureText, ok)
}
