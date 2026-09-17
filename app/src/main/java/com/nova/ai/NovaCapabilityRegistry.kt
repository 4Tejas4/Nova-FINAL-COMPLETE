package com.nova.ai

import android.content.Context
import android.content.Intent
import android.net.Uri

/** Universal capability registry: combines Nova actions with capabilities discoverable from installed apps/intents. */
object NovaCapabilityRegistry {
    data class Capability(val id: String, val kind: String, val description: String, val packageName: String = "", val resolvable: Boolean = true)

    private val native = listOf(
        "OPEN_APP","SEARCH_APP","OPEN_URL","WEB_SEARCH","PLAY_STORE_SEARCH","YOUTUBE_SEARCH","SETTINGS","CAMERA","FLASHLIGHT_ON","FLASHLIGHT_OFF",
        "WIFI_SETTINGS","INTERNET_PANEL","BLUETOOTH_SETTINGS","LOCATION_SETTINGS","AIRPLANE_SETTINGS","HOTSPOT_SETTINGS","NFC_SETTINGS",
        "BATTERY_SAVER_SETTINGS","DISPLAY_SETTINGS","SOUND_SETTINGS","HOME","DIAL","CALL","SMS","WHATSAPP_CHAT","MAPS","ALARM","SET_TIMER",
        "CALENDAR_EVENT","QUERY_CONTACT","GET_BATTERY","GET_DEVICE_INFO","LIST_APPS","LIST_NOTIFICATIONS","MEDIA_PLAY","MEDIA_PAUSE","MEDIA_NEXT","MEDIA_PREVIOUS",
        "BRIGHTNESS_UP","BRIGHTNESS_DOWN","VOLUME_UP","VOLUME_DOWN","SILENT_MODE","VIBRATE_MODE","NORMAL_MODE","DND_ON","DND_OFF","STOP_NOVA",
        "READ_SCREEN","READ_UI_STATE","READ_TEXT","CLICK","CLICK_DESCRIPTION","CLICK_ID","LONG_CLICK","CLEAR_TEXT","TYPE_TEXT","SCROLL","BACK","TAP","WAIT","FINISH"
    )

    fun all(context: Context): List<Capability> {
        val out = native.map { Capability(it, "NOVA_NATIVE", "Built-in Nova capability: $it") }.toMutableList()
        val apps = context.packageManager.getInstalledApplications(0)
        for (app in apps.take(200)) {
            val label = context.packageManager.getApplicationLabel(app).toString()
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).setPackage(app.packageName)
            val canWeb = context.packageManager.queryIntentActivities(intent, 0).isNotEmpty()
            if (canWeb) out.add(Capability("APP_WEB:${app.packageName}", "APP_INTENT", "Open web content with $label", app.packageName, true))
        }
        return out.distinctBy { it.id }
    }

    fun find(context: Context, query: String): List<Capability> {
        val q = query.trim().lowercase()
        if (q.isBlank()) return all(context).take(100)
        return all(context).filter { "${it.id} ${it.description} ${it.packageName}".lowercase().contains(q) }.take(50)
    }

    fun snapshot(context: Context): String = all(context).take(160).joinToString("\n") { "${it.id} | ${it.kind} | ${it.description}" }
}
