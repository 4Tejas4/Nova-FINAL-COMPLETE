package com.nova.ai

import android.content.Context

/** Stage 65: persistent onboarding/setup state. */
object NovaOnboardingState {
    private const val PREFS = "NovaOnboarding"
    private const val COMPLETE = "complete"

    fun isComplete(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(COMPLETE, false)
    fun setComplete(context: Context, value: Boolean = true) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(COMPLETE, value).apply()
}
