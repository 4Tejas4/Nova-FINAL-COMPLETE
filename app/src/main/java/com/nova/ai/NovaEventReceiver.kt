package com.nova.ai

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Android broadcast bridge for safe, normalized proactive events. */
class NovaEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        NovaEventEngine(context.applicationContext).handleIntent(intent)
    }
}
