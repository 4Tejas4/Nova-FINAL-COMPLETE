package com.nova.ai

import android.content.Context
import android.os.Build

/** Stage 66: deterministic runtime self-test. No network calls and no destructive actions. */
object NovaSelfTest {
    data class Check(val id: String, val passed: Boolean, val detail: String)

    fun run(context: Context): List<Check> {
        val app = context.applicationContext
        val checks = mutableListOf<Check>()
        fun add(id: String, passed: Boolean, detail: String) { checks += Check(id, passed, detail) }

        add("android", Build.VERSION.SDK_INT >= 21, "Android API ${Build.VERSION.SDK_INT}")
        add("agent_core", runCatching { NovaAgentCore(app) }.isSuccess, "Agent core can be constructed")
        add("capabilities", runCatching { NovaCapabilityRegistry.snapshot(app) }.getOrNull() != null, "Capability registry responds")
        add("events", runCatching { NovaEventEngine(app) }.isSuccess, "Event engine can be constructed")
        add("queue", runCatching { NovaTaskScheduler(app).list() }.isSuccess, "Persistent task queue is readable")
        add("memory", runCatching { NovaPersistentMemory(app) }.isSuccess, "Persistent memory is available")
        add("security", runCatching { NovaSecurityAudit(app).audit() }.isSuccess, "Security audit runs")
        add("performance", runCatching { NovaPerformanceEngine(app).policy() }.isSuccess, "Performance policy runs")
        add("onboarding", runCatching { NovaOnboardingState.isComplete(app) }.isSuccess, "Onboarding state is readable")
        add("accessibility", NovaAccessibilityService.get() != null, "Accessibility service connected")

        return checks
    }

    fun report(context: Context): String = buildString {
        appendLine("Nova Stage 66 Self-Test")
        appendLine("Android API: ${Build.VERSION.SDK_INT}")
        run(context).forEach { appendLine("${if (it.passed) "PASS" else "WARN"} ${it.id}: ${it.detail}") }
    }
}
