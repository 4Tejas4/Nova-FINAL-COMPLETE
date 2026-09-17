package com.nova.ai

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Stage 67: final release-readiness audit. Reports facts; it never claims an APK was built unless verified externally. */
object NovaFinalReleaseAudit {
    data class Result(val passed: Boolean, val blocking: List<String>, val warnings: List<String>, val facts: List<String>)

    fun run(context: Context): Result {
        val blocking = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val facts = mutableListOf<String>()
        val pm = context.packageManager

        val pkg = context.packageName
        facts += "applicationId=$pkg"
        facts += "androidApi=${Build.VERSION.SDK_INT}"
        facts += "targetSdk=${context.applicationInfo.targetSdkVersion}"

        if (pkg != "com.nova.ai") blocking += "Unexpected applicationId: $pkg"
        if (Build.VERSION.SDK_INT < 21) blocking += "Unsupported Android version"

        val selfTest = NovaSelfTest.run(context)
        if (selfTest.none { it.id == "agent_core" && it.passed }) blocking += "Agent core self-test failed"
        if (NovaAccessibilityService.get() == null) warnings += "Accessibility is not currently connected; advanced UI automation will be unavailable."
        if (!NovaOnboardingState.isComplete(context)) warnings += "First-run onboarding is not marked complete."

        val mic = pm.checkPermission(android.Manifest.permission.RECORD_AUDIO, pkg) == PackageManager.PERMISSION_GRANTED
        if (!mic) warnings += "Microphone permission is not currently granted."

        facts += "selfTestChecks=${selfTest.size}"
        facts += "runtimeBuildVerification=not available in this environment"
        return Result(blocking.isEmpty(), blocking, warnings, facts)
    }

    fun report(context: Context): String = buildString {
        val result = run(context)
        appendLine("Nova Final Release Audit")
        appendLine("Status: ${if (result.passed) "RELEASE-READY SOURCE" else "BLOCKED"}")
        result.facts.forEach { appendLine("FACT: $it") }
        result.blocking.forEach { appendLine("BLOCKER: $it") }
        result.warnings.forEach { appendLine("WARNING: $it") }
        appendLine("APK build verification must be performed in an environment with the configured Android/Gradle dependencies.")
    }
}
