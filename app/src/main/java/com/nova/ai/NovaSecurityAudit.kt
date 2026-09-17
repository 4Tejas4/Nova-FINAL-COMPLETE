package com.nova.ai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Stage 63: centralized permission/safety audit. */
class NovaSecurityAudit(private val context: Context) {
    data class Finding(val id: String, val severity: Severity, val title: String, val detail: String)
    enum class Severity { INFO, WARNING, REQUIRED }

    fun audit(): List<Finding> {
        val out = mutableListOf<Finding>()
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            out += Finding("mic", Severity.REQUIRED, "Microphone permission", "Voice activation cannot work until microphone access is granted.")
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)
            out += Finding("contacts", Severity.WARNING, "Contacts permission", "Contact lookup and contact-based calling are unavailable.")
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            out += Finding("notifications", Severity.WARNING, "Notification permission", "Nova may be unable to show task progress notifications.")
        out += Finding("accessibility", if (NovaAccessibilityService.instance == null) Severity.WARNING else Severity.INFO,
            "Accessibility automation", if (NovaAccessibilityService.instance == null) "Not connected. UI automation is limited." else "Connected.")
        out += Finding("notification_access", Severity.INFO, "Notification listener", "Notification Access is controlled by Android Settings and is not bypassed by Nova.")
        out += Finding("confirmation", Severity.INFO, "Action confirmations", "Sensitive actions such as calls are confirmation-gated.")
        out += Finding("privacy", Severity.INFO, "Privacy boundary", "Nova only uses data exposed by Android permissions and connected services.")
        return out
    }

    fun report(): String = audit().joinToString("\n") { "${it.severity}: ${it.title} - ${it.detail}" }
}
