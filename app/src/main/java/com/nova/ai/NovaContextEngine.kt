package com.nova.ai

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Lightweight, permission-safe device context used by proactive rules and planning. */
class NovaContextEngine(private val context: Context) {

    fun snapshot(): Map<String, String> {
        val out = mutableMapOf(
            "battery_percent" to "unknown",
            "charging" to "unknown",
            "network" to "unknown",
            "bluetooth_enabled" to "false",
            "screen_state" to "unknown"
        )

        try {
            val battery = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            val level = battery?.getIntExtra("level", -1) ?: -1
            val scale = battery?.getIntExtra("scale", 100) ?: 100
            val charging = when (battery?.getIntExtra("status", -1)) {
                2, 5 -> "true"
                else -> "false"
            }
            if (level >= 0) out["battery_percent"] = ((level * 100) / scale.coerceAtLeast(1)).toString()
            out["charging"] = charging
        } catch (_: Throwable) {
        }

        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
            out["network"] = when {
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "wifi"
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "cellular"
                caps != null -> "other"
                else -> "offline"
            }
        } catch (_: Throwable) {
            // ACCESS_NETWORK_STATE or connectivity lookup unavailable; leave "unknown".
        }

        try {
            out["bluetooth_enabled"] = (BluetoothAdapter.getDefaultAdapter()?.isEnabled == true).toString()
        } catch (_: Throwable) {
        }

        return out
    }
}
