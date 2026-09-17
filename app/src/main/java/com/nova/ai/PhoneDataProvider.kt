package com.nova.ai

import android.content.Context
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.provider.ContactsContract
import android.os.Build

/** Read-only phone data helpers used by the agent. No UI automation is required. */
object PhoneDataProvider {
    fun queryContact(context: Context, name: String): String {
        if (name.isBlank()) return "CONTACT_QUERY_EMPTY"
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return "READ_CONTACTS_PERMISSION_REQUIRED"
        }
        val rows = mutableListOf<String>()
        val projection = arrayOf(ContactsContract.Contacts.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        context.contentResolver.query(uri, projection, "${ContactsContract.Contacts.DISPLAY_NAME} LIKE ?", arrayOf("%$name%"), "${ContactsContract.Contacts.DISPLAY_NAME} ASC")?.use { c ->
            val n = c.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
            val p = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (c.moveToNext() && rows.size < 10) {
                rows += "${c.getString(n)}|${c.getString(p)}"
            }
        }
        return if (rows.isEmpty()) "CONTACT_NOT_FOUND" else rows.joinToString("\n")
    }

    fun battery(context: Context): String {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val percent = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = if (Build.VERSION.SDK_INT >= 23) bm.isCharging else false
        return "batteryPercent=$percent;charging=$charging"
    }

    fun deviceInfo(): String = "manufacturer=${Build.MANUFACTURER};model=${Build.MODEL};android=${Build.VERSION.RELEASE};sdk=${Build.VERSION.SDK_INT}"

    fun installedApps(context: Context, query: String = ""): String {
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { query.isBlank() || pm.getApplicationLabel(it).toString().contains(query, ignoreCase = true) }
            .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }
            .take(30)
        return if (apps.isEmpty()) "NO_APPS_FOUND" else apps.joinToString("\n") { "${pm.getApplicationLabel(it)}|${it.packageName}" }
    }

    fun activeNotifications(): String {
        val service = NovaNotificationListenerService.instance ?: return "NOTIFICATION_ACCESS_UNAVAILABLE"
        val items = service.activeNotifications?.take(30).orEmpty()
        return if (items.isEmpty()) "NO_ACTIVE_NOTIFICATIONS" else items.joinToString("\n") {
            val title = it.notification.extras.getCharSequence("android.title")?.toString().orEmpty()
            val text = it.notification.extras.getCharSequence("android.text")?.toString().orEmpty()
            "${it.packageName}|$title|$text"
        }
    }
}
