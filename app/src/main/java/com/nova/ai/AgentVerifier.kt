package com.nova.ai

import android.content.Context

/**
 * Lightweight post-action verification. It never replaces the planner; it gives
 * the planner evidence about whether the last action appears to have worked.
 */
object AgentVerifier {
    data class Verification(val status: String, val detail: String, val confidence: Int)

    fun verify(context: Context, action: String, payload: String, result: CommandProcessor.CommandResult): Verification {
        if (!result.success) return Verification("FAILED", result.response, 0)
        val a = action.uppercase()
        val accessibility = NovaAccessibilityService.get()

        return when (a) {
            "CLICK", "CLICK_DESCRIPTION", "CLICK_ID", "LONG_CLICK", "TAP", "BACK", "SCROLL", "TYPE_TEXT", "CLEAR_TEXT", "FOCUS_TEXT_FIELD" -> {
                val ui = accessibility?.getInteractiveElementsSnapshot().orEmpty()
                if (ui.isNotBlank()) Verification("VERIFIED", "Android reported success and the UI is readable.", 75)
                else Verification("ACCEPTED", "Android reported success; UI verification is unavailable.", 55)
            }
            "READ_SCREEN", "READ_UI_STATE", "READ_TEXT", "STORE_SCREEN_TEXT", "SET_CONTEXT", "CHECK_CONTEXT",
            "QUERY_CONTACT", "GET_BATTERY", "GET_DEVICE_INFO", "LIST_APPS", "LIST_NOTIFICATIONS", "CHECK_APP_INSTALLED",
            "CHECK_NATIVE_CAPABILITY" -> Verification("VERIFIED", "The requested data/capability operation returned successfully.", 95)
            "SET_TIMER", "ALARM", "MAPS", "OPEN_URL", "DIAL", "SMS", "WHATSAPP_CHAT", "APP_DEEP_LINK", "DIRECT_APP_TARGET" -> {
                Verification("VERIFIED", "The native Android intent was accepted by a handler.", 80)
            }
            "CALENDAR_EVENT" -> Verification("ACCEPTED", "The calendar intent was launched; final save may depend on the calendar provider.", 65)
            "WAIT" -> Verification("VERIFIED", "Wait completed.", 100)
            else -> Verification("ACCEPTED", "Action returned success; no specialized verifier is available.", 60)
        }
    }
}
