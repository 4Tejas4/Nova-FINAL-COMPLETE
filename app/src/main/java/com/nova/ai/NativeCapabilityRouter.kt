package com.nova.ai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import java.util.Calendar

/**
 * Native Android capability layer.
 * Prefer OS intents/services over opening an app and tapping its UI.
 * Accessibility remains a fallback for apps that expose no useful native integration.
 */
object NativeCapabilityRouter {
    fun canHandle(action: String): Boolean = when (action.uppercase()) {
        "SET_TIMER", "ALARM", "CALENDAR_EVENT", "MAPS", "OPEN_URL", "DIAL", "SMS",
        "WHATSAPP_CHAT", "APP_DEEP_LINK", "DIRECT_APP_TARGET" -> true
        else -> false
    }

    fun execute(context: Context, action: String, payload: String): CommandProcessor.CommandResult? {
        return try {
            when (action.uppercase()) {
                "SET_TIMER" -> setTimer(context, payload)
                "ALARM" -> setAlarm(context, payload)
                "CALENDAR_EVENT" -> calendarEvent(context, payload)
                "MAPS" -> openMaps(context, payload)
                "OPEN_URL" -> openUrl(context, payload)
                "DIAL" -> dial(context, payload)
                "SMS" -> sms(context, payload)
                "WHATSAPP_CHAT" -> whatsappChat(context, payload)
                "APP_DEEP_LINK" -> appDeepLink(context, payload)
                "DIRECT_APP_TARGET" -> directAppTarget(context, payload)
                else -> null
            }
        } catch (e: Exception) {
            CommandProcessor.CommandResult(false, e.message ?: "Native Android action failed.", false)
        }
    }

    private fun setTimer(context: Context, payload: String): CommandProcessor.CommandResult {
        val seconds = payload.toLongOrNull()?.coerceIn(1L, 86400L)
            ?: return CommandProcessor.CommandResult(false, "I couldn't understand the timer duration.", false)
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds.toInt())
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return CommandProcessor.CommandResult(true, "Timer set for ${formatDuration(seconds)}.", true)
    }

    private fun setAlarm(context: Context, payload: String): CommandProcessor.CommandResult {
        val parts = payload.split('|', limit = 2)
        val time = parts.firstOrNull().orEmpty()
        val hm = time.split(':')
        if (hm.size != 2) return CommandProcessor.CommandResult(false, "I couldn't understand the alarm time.", false)
        val hour = hm[0].toIntOrNull()?.coerceIn(0, 23) ?: return CommandProcessor.CommandResult(false, "Invalid alarm hour.", false)
        val minute = hm[1].toIntOrNull()?.coerceIn(0, 59) ?: return CommandProcessor.CommandResult(false, "Invalid alarm minute.", false)
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            if (parts.size == 2 && parts[1].isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, parts[1])
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return CommandProcessor.CommandResult(true, "Alarm set for %02d:%02d.".format(hour, minute), true)
    }

    private fun calendarEvent(context: Context, payload: String): CommandProcessor.CommandResult {
        // title|startMillis|endMillis|location|description
        val p = payload.split('|')
        val title = p.getOrNull(0).orEmpty()
        val start = p.getOrNull(1)?.toLongOrNull()
        val end = p.getOrNull(2)?.toLongOrNull()
        if (title.isBlank() || start == null) return CommandProcessor.CommandResult(false, "I need a calendar event title and start time.", false)
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, title)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
            if (end != null) putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
            p.getOrNull(3)?.takeIf { it.isNotBlank() }?.let { putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
            p.getOrNull(4)?.takeIf { it.isNotBlank() }?.let { putExtra(CalendarContract.Events.DESCRIPTION, it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return CommandProcessor.CommandResult(true, "Opening the calendar event composer.", true)
    }

    private fun openMaps(context: Context, destination: String): CommandProcessor.CommandResult {
        val uri = Uri.parse("geo:0,0?q=" + Uri.encode(destination))
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        return CommandProcessor.CommandResult(true, "Opening directions for $destination.", true)
    }

    private fun openUrl(context: Context, url: String): CommandProcessor.CommandResult {
        val safe = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(safe)).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        return CommandProcessor.CommandResult(true, "Opening $safe", true)
    }

    private fun dial(context: Context, number: String): CommandProcessor.CommandResult {
        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        return CommandProcessor.CommandResult(true, "Opening the dialer.", true)
    }

    private fun sms(context: Context, payload: String): CommandProcessor.CommandResult {
        val p = payload.split('|', limit = 2)
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(p.getOrNull(0).orEmpty())}")).apply {
            p.getOrNull(1)?.let { putExtra("sms_body", it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return CommandProcessor.CommandResult(true, "Opening the message composer.", true)
    }


    /**
     * Opens a WhatsApp conversation directly using the contact's phone number.
     * Payload: contact name, or phone number. Optional prefilled message after |.
     */
    private fun whatsappChat(context: Context, payload: String): CommandProcessor.CommandResult {
        val parts = payload.split('|', limit = 2)
        val person = parts.firstOrNull()?.trim().orEmpty()
        if (person.isBlank()) return CommandProcessor.CommandResult(false, "I need a WhatsApp contact name or number.", false)

        val number = if (person.any { it.isDigit() }) person else findContactNumber(context, person)
            ?: return CommandProcessor.CommandResult(false, "I couldn't find a phone number for $person in your contacts.", false)

        val digits = number.filter { it.isDigit() }
        if (digits.length < 7) return CommandProcessor.CommandResult(false, "The contact number for $person is not valid.", false)

        val uriBuilder = StringBuilder("https://wa.me/").append(digits)
        parts.getOrNull(1)?.takeIf { it.isNotBlank() }?.let {
            uriBuilder.append("?text=").append(Uri.encode(it))
        }

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uriBuilder.toString())).apply {
            setPackage("com.whatsapp")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            CommandProcessor.CommandResult(true, "Opening the WhatsApp chat with $person.", true)
        } catch (_: Exception) {
            // WhatsApp may not be installed. Let Android handle the same direct-chat link.
            val fallback = Intent(Intent.ACTION_VIEW, Uri.parse(uriBuilder.toString())).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(fallback)
                CommandProcessor.CommandResult(true, "Opening the WhatsApp chat with $person.", true)
            } catch (e: Exception) {
                CommandProcessor.CommandResult(false, "WhatsApp could not open this chat.", false)
            }
        }
    }



    /**
     * Builds a direct destination for common apps. This avoids opening an app home
     * screen first. Payload: app|target|query(optional)
     */
    private fun directAppTarget(context: Context, payload: String): CommandProcessor.CommandResult {
        val p = payload.split('|', limit = 3)
        val app = p.getOrNull(0)?.trim().orEmpty()
        val target = p.getOrNull(1)?.trim().orEmpty()
        val query = p.getOrNull(2)?.trim().orEmpty()
        if (app.isBlank() || target.isBlank()) return CommandProcessor.CommandResult(false, "I need an app and destination.", false)

        val a = app.lowercase()
        val intent = when {
            a.contains("youtube") -> {
                val uri = if (target.equals("search", true))
                    "https://www.youtube.com/results?search_query=${Uri.encode(query)}"
                else "https://www.youtube.com/$target"
                Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage("com.google.android.youtube")
            }
            a.contains("instagram") -> {
                val username = target.removePrefix("@").trim('/')
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.instagram.com/${Uri.encode(username)}/")).setPackage("com.instagram.android")
            }
            a.contains("spotify") -> {
                val uri = when (target.lowercase()) {
                    "search" -> "spotify:search:${Uri.encode(query)}"
                    else -> if (target.startsWith("spotify:")) target else "spotify:$target"
                }
                Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage("com.spotify.music")
            }
            a.contains("telegram") -> {
                val username = target.removePrefix("@").trim('/')
                Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?domain=${Uri.encode(username)}")).setPackage("org.telegram.messenger")
            }
            a.contains("gmail") -> {
                val uri = if (target.equals("compose", true)) "mailto:" + Uri.encode(query) else "https://mail.google.com/"
                Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage("com.google.android.gm")
            }
            a.contains("chrome") || a.contains("browser") -> {
                val url = if (target.startsWith("http://") || target.startsWith("https://")) target else "https://$target"
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage("com.android.chrome")
            }
            a.contains("play store") || a.contains("playstore") -> {
                val uri = if (target.equals("search", true))
                    "https://play.google.com/store/search?q=${Uri.encode(query)}&c=apps"
                else if (target.startsWith("http")) target else "market://details?id=$target"
                Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage("com.android.vending")
            }
            else -> return CommandProcessor.CommandResult(false, "No native direct destination is known for $app. I will need the app's supported deep link or UI fallback.", false)
        }.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

        return try {
            context.startActivity(intent)
            CommandProcessor.CommandResult(true, "Opening $app directly at $target.", true)
        } catch (_: Exception) {
            CommandProcessor.CommandResult(false, "$app is not installed or rejected this direct destination.", false)
        }
    }

    /**
     * Opens a supported app directly at a native/deep-link destination.
     * Payload: app|uri|optionalPackage
     * The URI is supplied by Nova's capability rules, not by arbitrary UI tapping.
     */
    private fun appDeepLink(context: Context, payload: String): CommandProcessor.CommandResult {
        val p = payload.split('|', limit = 3)
        val app = p.getOrNull(0)?.trim().orEmpty()
        val uriText = p.getOrNull(1)?.trim().orEmpty()
        val packageName = p.getOrNull(2)?.trim().orEmpty()
        if (app.isBlank() || uriText.isBlank()) {
            return CommandProcessor.CommandResult(false, "I need the app and direct destination.", false)
        }

        if (!CapabilityDiscovery.canResolve(context, uriText, packageName.takeIf { it.isNotBlank() })) {
            return CommandProcessor.CommandResult(false, "$app does not expose a compatible direct destination on this device.", false)
        }

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uriText)).apply {
            if (packageName.isNotBlank()) setPackage(packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            CommandProcessor.CommandResult(true, "Opening $app directly.", true)
        } catch (_: Exception) {
            CommandProcessor.CommandResult(false, "$app does not support this direct destination on this device.", false)
        }
    }

    private fun findContactNumber(context: Context, name: String): String? {
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} = ?"
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            selection,
            arrayOf(name),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER))
            }
        }

        // Natural-language names such as "my brother" may not exactly match the saved contact name.
        val likeSelection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            likeSelection,
            arrayOf("%$name%"),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER))
            }
        }
        return null
    }

    private fun formatDuration(seconds: Long): String {
        val minutes = seconds / 60
        val secs = seconds % 60
        return when {
            minutes > 0 && secs > 0 -> "$minutes minute${if (minutes == 1L) "" else "s"} and $secs second${if (secs == 1L) "" else "s"}"
            minutes > 0 -> "$minutes minute${if (minutes == 1L) "" else "s"}"
            else -> "$secs second${if (secs == 1L) "" else "s"}"
        }
    }
}
