package com.nova.ai

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.app.SearchManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.Settings
import android.view.KeyEvent
import java.util.Locale

object CommandRouter {
    data class ActionResult(val response: String, val success: Boolean = true)

    private fun norm(value: String): String = value.lowercase(Locale.getDefault()).replace(Regex("[^a-z0-9]+"), "").trim()

    fun execute(context: Context, command: String): ActionResult? {
        val original = command.trim()
        val text = original.lowercase(Locale.getDefault()).replace(Regex("\\s+"), " ")
        if (text.isBlank()) return null

        fun has(vararg words: String) = words.any { text.contains(it) }

        if (text == "go back" || text == "back" || text == "press back" || text == "tap back")
            return ActionResult("Global Back cannot be controlled by a normal Android app without Accessibility access.", false)
        if (text.contains("clear recent") || text.contains("clear recents") || text.contains("close all recent"))
            return ActionResult("Clearing all recent apps requires privileged global UI access. This build does not use Accessibility.", false)

        if (has("go home", "home screen", "go to home", "open home", "close this app", "exit this app"))
            return executeAction(context, "HOME", "")

        if (text.contains("overlay") || text.contains("appear over other apps") || text.contains("background app opening"))
            return executeAction(context, "OVERLAY_PERMISSION", "")

        if (text == "open settings" || text.contains("android settings"))
            return executeAction(context, "SETTINGS", "")

        if (has("open camera", "launch camera", "start camera"))
            return executeAction(context, "CAMERA", "")

        if (has("turn on wifi", "turn off wifi", "wifi on", "wifi off", "wifi settings"))
            return executeAction(context, "WIFI_SETTINGS", "")

        if (has("turn on internet", "internet on", "enable internet", "turn off internet", "internet off", "disable internet", "mobile data", "data connection"))
            return executeAction(context, "INTERNET_PANEL", "")

        if (has("turn on bluetooth", "turn off bluetooth", "bluetooth settings"))
            return executeAction(context, "BLUETOOTH_SETTINGS", "")

        if (has("turn on location", "turn off location", "location on", "location off", "gps", "location settings"))
            return executeAction(context, "LOCATION_SETTINGS", "")

        if (has("airplane mode", "flight mode")) return executeAction(context, "AIRPLANE_SETTINGS", "")
        if (has("hotspot", "portable hotspot", "personal hotspot")) return executeAction(context, "HOTSPOT_SETTINGS", "")
        if (has("nfc", "near field communication")) return executeAction(context, "NFC_SETTINGS", "")
        if (has("battery saver", "power saving", "power saver")) return executeAction(context, "BATTERY_SAVER_SETTINGS", "")
        if (has("auto rotate", "screen rotation", "rotation lock")) return executeAction(context, "DISPLAY_SETTINGS", "")
        if (has("sound settings", "audio settings")) return executeAction(context, "SOUND_SETTINGS", "")

        if (has("turn on dnd", "turn off dnd", "do not disturb", "dont disturb", "dnd mode"))
            return executeAction(context, if (has("off", "disable", "turn off")) "DND_OFF" else "DND_ON", "")

        if (has("silent mode", "silent phone", "mute phone", "ringer mute")) return executeAction(context, "SILENT_MODE", "")
        if (has("vibrate mode", "vibration mode")) return executeAction(context, "VIBRATE_MODE", "")
        if (has("normal ringer", "sound mode", "ring mode")) return executeAction(context, "NORMAL_MODE", "")

        if (text.contains("search") && text.contains("play store")) {
            val query = extractAfter(text, listOf("for ", "play store for ", "play store "))
            if (query.isNotBlank()) return executeAction(context, "PLAY_STORE_SEARCH", query)
        }
        if ((text.contains("search") || text.contains("find")) && text.contains("youtube")) {
            val query = extractAfter(text, listOf("youtube for ", "youtube ", "on youtube "))
            if (query.isNotBlank()) return executeAction(context, "YOUTUBE_SEARCH", query)
        }
        if (text.startsWith("search google ") || text.startsWith("google search ") || text.startsWith("search the web ") || text.startsWith("web search ")) {
            val query = text.substringAfter(" ").substringAfter(" ").trim().ifBlank { "" }
            if (query.isNotBlank()) return executeAction(context, "WEB_SEARCH", query)
        }

        if (text.startsWith("search ") || text.startsWith("find ")) {
            val afterSearch = text.substringAfter(" ").trim()
            val marker = listOf(" in ", " on ").map { afterSearch.lastIndexOf(it) }.maxOrNull() ?: -1
            if (marker > 0) {
                val query = afterSearch.substring(0, marker).trim()
                val app = afterSearch.substring(marker + 4).trim()
                if (query.isNotBlank() && app.isNotBlank()) return executeAction(context, "SEARCH_APP", "$app|$query")
            }
        }

        if (text.startsWith("set music app ") || text.startsWith("use music app ") || text.startsWith("set my music app to ")) {
            val app = when {
                text.startsWith("set music app ") -> text.removePrefix("set music app ")
                text.startsWith("use music app ") -> text.removePrefix("use music app ")
                else -> text.removePrefix("set my music app to ")
            }.trim()
            if (app.isNotBlank()) return executeAction(context, "SET_MUSIC_APP", app)
        }

        if (isCallCommand(text)) {
            val target = extractCallTarget(text)
            if (target.isNotBlank()) return executeAction(context, "CALL", target)
        }
        if (text.startsWith("dial ")) {
            val target = text.removePrefix("dial ").trim()
            return executeAction(context, "DIAL", target)
        }

        if (text.startsWith("text ") || text.startsWith("sms ") || text.startsWith("send sms ")) {
            val parts = extractMessage(text)
            if (parts.first.isNotBlank() && parts.second.isNotBlank()) return executeAction(context, "SMS", "${parts.first}|${parts.second}")
        }

        if (text.contains("directions to ") || text.startsWith("navigate to ") || text.startsWith("navigate ") || text.startsWith("map to ")) {
            val destination = when {
                text.contains("directions to ") -> text.substringAfter("directions to ")
                text.startsWith("navigate to ") -> text.substringAfter("navigate to ")
                text.startsWith("navigate ") -> text.removePrefix("navigate ")
                else -> text.removePrefix("map to ")
            }.trim()
            if (destination.isNotBlank()) return executeAction(context, "MAPS", destination)
        }

        if (text.contains("brightness") && has("increase", "up", "higher", "more", "brighter")) return executeAction(context, "BRIGHTNESS_UP", "")
        if (text.contains("brightness") && has("decrease", "down", "lower", "less", "dimmer")) return executeAction(context, "BRIGHTNESS_DOWN", "")
        if (text.contains("volume") && has("increase", "up", "higher", "more", "louder")) return executeAction(context, "VOLUME_UP", "")
        if (text.contains("volume") && has("decrease", "down", "lower", "less", "quieter")) return executeAction(context, "VOLUME_DOWN", "")

        if (has("play pause music", "play pause media", "toggle music", "toggle media")) return executeAction(context, "MEDIA_PLAY_PAUSE", "")
        if (has("pause music", "pause media", "media pause", "stop music")) return executeAction(context, "MEDIA_PAUSE", "")
        if (has("play music", "play media", "resume music", "media play")) return executeAction(context, "MEDIA_PLAY", "")
        if (text == "next" || has("next song", "next track", "skip song", "skip track")) return executeAction(context, "MEDIA_NEXT", "")
        if (text == "previous" || has("previous song", "previous track", "last song", "previous track")) return executeAction(context, "MEDIA_PREVIOUS", "")

        if (has("flashlight on", "torch on", "turn on flashlight", "turn on torch")) return executeAction(context, "FLASHLIGHT_ON", "")
        if (has("flashlight off", "torch off", "turn off flashlight", "turn off torch")) return executeAction(context, "FLASHLIGHT_OFF", "")

        if (text.startsWith("set an alarm") || text.startsWith("set alarm") || text.startsWith("alarm for")) {
            val time = extractAlarmTime(text)
            if (time.isNotBlank()) return executeAction(context, "ALARM", time)
        }

        if (has("stop nova", "stop listening", "disable nova")) return executeAction(context, "STOP_NOVA", "")

        if (text.startsWith("open ") || text.startsWith("launch ") || text.startsWith("start ")) {
            val name = extractAppName(original)
            if (name.isNotBlank()) return executeAction(context, "OPEN_APP", name)
        }

        return null
    }

    fun executeAction(context: Context, action: String, payload: String): ActionResult {
        return try {
            when (action.uppercase(Locale.getDefault())) {
                "OPEN_APP" -> openAnyApp(context, payload)
                "PLAY_STORE_SEARCH" -> openPlayStoreSearch(context, payload)
                "YOUTUBE_SEARCH" -> openUrl(context, "https://www.youtube.com/results?search_query=${Uri.encode(payload)}", "Searching YouTube for $payload.")
                "WEB_SEARCH" -> openUrl(context, "https://www.google.com/search?q=${Uri.encode(payload)}", "Searching the web for $payload.")
                "OPEN_URL" -> openUrl(context, payload, "Opening the web page.")
                "SETTINGS" -> openSettings(context)
                "CAMERA" -> openIntent(context, Intent("android.media.action.IMAGE_CAPTURE"), "Opening camera.")
                "WIFI_SETTINGS" -> openWifi(context)
                "INTERNET_PANEL" -> openInternet(context)
                "BLUETOOTH_SETTINGS" -> openSafeSettings(context, Settings.ACTION_BLUETOOTH_SETTINGS, "Opening Bluetooth settings.")
                "LOCATION_SETTINGS" -> openSafeSettings(context, Settings.ACTION_LOCATION_SOURCE_SETTINGS, "Opening location settings.")
                "AIRPLANE_SETTINGS" -> openSafeSettings(context, Settings.ACTION_AIRPLANE_MODE_SETTINGS, "Opening airplane-mode settings.")
                "HOTSPOT_SETTINGS" -> openSafeSettings(context, Settings.ACTION_WIRELESS_SETTINGS, "Opening network and hotspot settings.")
                "NFC_SETTINGS" -> openSafeSettings(context, Settings.ACTION_NFC_SETTINGS, "Opening NFC settings.")
                "BATTERY_SAVER_SETTINGS" -> openBatterySettings(context)
                "DISPLAY_SETTINGS" -> openSafeSettings(context, Settings.ACTION_DISPLAY_SETTINGS, "Opening display settings.")
                "SOUND_SETTINGS" -> openSafeSettings(context, Settings.ACTION_SOUND_SETTINGS, "Opening sound settings.")
                "OVERLAY_PERMISSION" -> openOverlaySettings(context)
                "HOME" -> goHome(context)
                "DIAL" -> dial(context, payload)
                "CALL" -> callTarget(context, payload)
                "SMS" -> sendSms(context, payload)
                "MAPS" -> maps(context, payload)
                "BRIGHTNESS_UP" -> changeBrightness(context, 25)
                "BRIGHTNESS_DOWN" -> changeBrightness(context, -25)
                "VOLUME_UP" -> changeVolume(context, AudioManager.ADJUST_RAISE)
                "VOLUME_DOWN" -> changeVolume(context, AudioManager.ADJUST_LOWER)
                "SILENT_MODE" -> setRinger(context, AudioManager.RINGER_MODE_SILENT, "Silent mode enabled.")
                "VIBRATE_MODE" -> setRinger(context, AudioManager.RINGER_MODE_VIBRATE, "Vibrate mode enabled.")
                "NORMAL_MODE" -> setRinger(context, AudioManager.RINGER_MODE_NORMAL, "Normal sound mode enabled.")
                "DND_ON" -> setDnd(context, true)
                "DND_OFF" -> setDnd(context, false)
                "MEDIA_PLAY_PAUSE" -> MusicController.togglePlayPause(context)
                "MEDIA_PLAY" -> MusicController.play(context)
                "MEDIA_PAUSE" -> MusicController.pause(context)
                "MEDIA_NEXT" -> MusicController.next(context)
                "MEDIA_PREVIOUS" -> MusicController.previous(context)
                "SET_MUSIC_APP" -> MusicController.setPreferredApp(context, payload)
                "SEARCH_APP" -> searchInApp(context, payload)
                "FLASHLIGHT_ON" -> setFlashlight(context, true)
                "FLASHLIGHT_OFF" -> setFlashlight(context, false)
                "ALARM" -> setAlarm(context, payload)
                "SET_TIMER" -> setTimer(context, payload)
                "CALENDAR_EVENT" -> setCalendarEvent(context, payload)
                "GET_BATTERY" -> ActionResult(PhoneDataProvider.battery(context))
                "GET_DEVICE_INFO" -> ActionResult(PhoneDataProvider.deviceInfo())
                "LIST_APPS" -> ActionResult(PhoneDataProvider.installedApps(context, payload))
                "STOP_NOVA" -> {
                    context.stopService(Intent(context, NovaWakeService::class.java))
                    ActionResult("Nova stopped.")
                }
                else -> ActionResult("I don't support that device action yet.", false)
            }
        } catch (e: Exception) {
            ActionResult("I couldn't complete that action: ${e.message ?: "Android rejected the request"}", false)
        }
    }

    private fun isCallCommand(text: String): Boolean =
        text.startsWith("call ") || text.startsWith("phone ") || text.startsWith("please call ") ||
            text.startsWith("call to ") || text.startsWith("ring ")

    private fun extractCallTarget(text: String): String = when {
        text.startsWith("please call ") -> text.removePrefix("please call ")
        text.startsWith("call to ") -> text.removePrefix("call to ")
        text.startsWith("call ") -> text.removePrefix("call ")
        text.startsWith("phone ") -> text.removePrefix("phone ")
        text.startsWith("ring ") -> text.removePrefix("ring ")
        else -> ""
    }.trim().removeSuffix(" please")

    private fun extractAppName(original: String): String = original.replaceFirst(Regex("^(?i)(open|launch|start)\\s+"), "").trim()
        .replace(Regex("(?i)\\b(app|application)\\b"), "").trim()

    private fun openAnyApp(context: Context, requestedName: String): ActionResult {
        val wantedRaw = requestedName.trim()
        val wanted = norm(wantedRaw)
        if (wanted.isBlank()) return ActionResult("Tell me which app to open.", false)

        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
        var bestPackage: String? = null
        var bestLabel = ""
        var bestScore = Int.MIN_VALUE
        for (info in apps) {
            val label = info.loadLabel(pm).toString().trim()
            val n = norm(label)
            if (n.isBlank()) continue
            val distance = levenshtein(n, wanted)
            val score = when {
                n == wanted -> 2000
                n.startsWith(wanted) -> 1500 - (n.length - wanted.length)
                n.contains(wanted) -> 1300 - (n.length - wanted.length)
                wanted.contains(n) && n.length >= 3 -> 1200 - (wanted.length - n.length)
                distance <= 2 && wanted.length >= 5 -> 900 - distance
                else -> Int.MIN_VALUE
            }
            if (score > bestScore) {
                bestScore = score
                bestPackage = info.activityInfo.packageName
                bestLabel = label
            }
        }

        if (bestPackage == null || bestScore <= 0) return ActionResult("I couldn't find an installed app matching $wantedRaw.", false)

        val launch = pm.getLaunchIntentForPackage(bestPackage)
            ?: return ActionResult("$bestLabel is installed, but Android did not expose a launch activity.", false)
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            context.startActivity(launch)
            ActionResult("Opening $bestLabel.")
        } catch (e: Exception) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                ActionResult("Android blocked the background app launch. Enable 'Display over other apps' for Nova, then try again.", false)
            } else ActionResult("Android blocked opening $bestLabel from the background.", false)
        }
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val cur = IntArray(b.length + 1)
            cur[0] = i + 1
            for (j in b.indices) cur[j + 1] = minOf(cur[j] + 1, prev[j + 1] + 1, prev[j] + if (a[i] == b[j]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }

    private fun normalizeNumber(value: String): String = value.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }

    private fun callTarget(context: Context, target: String): ActionResult {
        val number = normalizeNumber(target)
        return if (number.count { it.isDigit() } >= 3) callNumber(context, number) else callContactByName(context, target)
    }

    data class CallTarget(val displayName: String, val number: String)

    fun resolveCallTarget(context: Context, target: String): CallTarget? {
        val number = normalizeNumber(target)
        if (number.count { it.isDigit() } >= 3) return CallTarget(target.trim(), number)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val wanted = norm(target)
        if (wanted.isBlank()) return null
        val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)
        var exact: CallTarget? = null
        var partial: CallTarget? = null
        context.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection, null, null, null)?.use { c ->
            val ni = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val pi = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (c.moveToNext()) {
                val display = if (ni >= 0) c.getString(ni).orEmpty() else ""
                val phone = if (pi >= 0) c.getString(pi).orEmpty() else ""
                val n = norm(display)
                if (display.isNotBlank() && phone.isNotBlank() && n == wanted) { exact = CallTarget(display, normalizeNumber(phone)); break }
                if (partial == null && display.isNotBlank() && phone.isNotBlank() && n.length >= 3 && (n.contains(wanted) || wanted.contains(n))) partial = CallTarget(display, normalizeNumber(phone))
            }
        }
        return exact ?: partial
    }

    private fun callContactByName(context: Context, name: String): ActionResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)
            return ActionResult("Contacts permission is not enabled. Allow Contacts permission for Nova.", false)
        val found = resolveCallTarget(context, name) ?: return ActionResult("I couldn't find a contact named $name.", false)
        val result = callNumber(context, found.number)
        return if (result.success) ActionResult("Calling ${found.displayName}.") else result
    }

    private fun callNumber(context: Context, number: String): ActionResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && context.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED)
            return ActionResult("Phone calling permission is not enabled. Allow Phone permission for Nova.", false)
        context.startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(number)}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return ActionResult("Calling $number.")
    }

    private fun dial(context: Context, target: String): ActionResult {
        val number = normalizeNumber(target)
        if (number.isBlank()) return ActionResult("I need a phone number to dial.", false)
        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return ActionResult("Opening the dialer.")
    }

    private fun openIntent(context: Context, intent: Intent, message: String): ActionResult {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return ActionResult(message)
    }

    private fun openSettings(context: Context): ActionResult = openIntent(context, Intent(Settings.ACTION_SETTINGS), "Opening settings.")

    private fun openSafeSettings(context: Context, action: String, message: String): ActionResult = openIntent(context, Intent(action), message)

    private fun openWifi(context: Context): ActionResult {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val panel = Intent(Settings.Panel.ACTION_WIFI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(panel)
            ActionResult("Android does not let a normal app silently switch Wi-Fi on or off on Android 10+, so I opened the Wi-Fi controls.", false)
        } else openSafeSettings(context, Settings.ACTION_WIFI_SETTINGS, "Opening Wi-Fi settings.")
    }

    private fun openInternet(context: Context): ActionResult {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                context.startActivity(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                ActionResult("Android does not expose a normal third-party API to silently switch mobile data or all Internet connectivity on Android 10+, so I opened the Internet controls.", false)
            } catch (_: Exception) {
                openSafeSettings(context, Settings.ACTION_WIRELESS_SETTINGS, "Opening network settings.")
            }
        } else openSafeSettings(context, Settings.ACTION_WIRELESS_SETTINGS, "Opening network settings.")
    }

    private fun openBatterySettings(context: Context): ActionResult {
        val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.ACTION_BATTERY_SAVER_SETTINGS else Settings.ACTION_SETTINGS
        return openSafeSettings(context, action, "Opening battery-saver settings.")
    }

    private fun openOverlaySettings(context: Context): ActionResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return ActionResult("Background app opening is already allowed on this Android version.")
        if (Settings.canDrawOverlays(context)) return ActionResult("Background app opening permission is already enabled.")
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return ActionResult("Enable Display over other apps for Nova, then app opening from the background will be allowed by Android.")
    }

    private fun openUrl(context: Context, url: String, message: String): ActionResult {
        val clean = url.trim()
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) return ActionResult("That is not a valid web address.", false)
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(clean)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return ActionResult(message)
    }

    private fun openPlayStoreSearch(context: Context, query: String): ActionResult {
        if (query.isBlank()) return ActionResult("Tell me what to search for in the Play Store.", false)
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=${Uri.encode(query)}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ActionResult("Searching Play Store for $query.")
        } catch (_: Exception) {
            openUrl(context, "https://play.google.com/store/search?q=${Uri.encode(query)}", "Opening Play Store search for $query.")
        }
    }

    private fun sendSms(context: Context, payload: String): ActionResult {
        val parts = payload.split("|", limit = 2)
        if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) return ActionResult("I need a phone number and message.", false)
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(parts[0].trim())}"))
            .putExtra("sms_body", parts[1].trim()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return ActionResult("Opening a message to ${parts[0].trim()}.")
    }

    private fun maps(context: Context, destination: String): ActionResult {
        val clean = destination.trim()
        if (clean.isBlank()) return ActionResult("Tell me where you want to go.", false)
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(clean)}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ActionResult("Opening directions to $clean.")
        } catch (_: Exception) {
            openUrl(context, "https://www.google.com/maps/search/?api=1&query=${Uri.encode(clean)}", "Opening maps for $clean.")
        }
    }

    private fun changeBrightness(context: Context, deltaPercent: Int): ActionResult {
        if (!Settings.System.canWrite(context)) {
            val i = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))
            context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return ActionResult("Allow Nova to modify system settings, then repeat the brightness command.", false)
        }
        val current = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
        val step = (255 * 10 / 100).coerceAtLeast(1) * if (deltaPercent > 0) 1 else -1
        val next = (current + step).coerceIn(0, 255)
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, next)
        return ActionResult(if (deltaPercent > 0) "Brightness increased by about 10 percent." else "Brightness decreased by about 10 percent.")
    }

    private fun changeVolume(context: Context, direction: Int): ActionResult {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.adjustSuggestedStreamVolume(direction, AudioManager.USE_DEFAULT_STREAM_TYPE, 0)
        return ActionResult(if (direction == AudioManager.ADJUST_RAISE) "Volume increased." else "Volume decreased.")
    }

    private fun setRinger(context: Context, mode: Int, message: String): ActionResult {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.ringerMode = mode
        return ActionResult(message)
    }

    private fun setDnd(context: Context, enable: Boolean): ActionResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return ActionResult("DND control is not available on this Android version.", false)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) {
            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return ActionResult("Grant Do Not Disturb access to Nova, then repeat the command.", false)
        }
        return if (Build.VERSION.SDK_INT >= 35) {
            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ActionResult("Android 15 and newer restrict direct global DND changes, so I opened DND controls.", false)
        } else {
            nm.setInterruptionFilter(if (enable) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
            ActionResult(if (enable) "Do Not Disturb enabled." else "Do Not Disturb disabled.")
        }
    }

    private fun mediaKey(context: Context, keyCode: Int, message: String): ActionResult {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        return ActionResult(message)
    }

    private fun setAlarm(context: Context, time: String): ActionResult {
        val parts = time.split(":")
        if (parts.size != 2) return ActionResult("I need an alarm time such as 07:30.", false)
        val hour = parts[0].toIntOrNull() ?: return ActionResult("Invalid alarm time.", false)
        val minute = parts[1].toIntOrNull() ?: return ActionResult("Invalid alarm time.", false)
        if (hour !in 0..23 || minute !in 0..59) return ActionResult("Invalid alarm time.", false)
        val intent = Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(android.provider.AlarmClock.EXTRA_HOUR, hour)
            putExtra(android.provider.AlarmClock.EXTRA_MINUTES, minute)
            putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return ActionResult("Opening the alarm setup for %02d:%02d.".format(hour, minute))
    }

    private fun setTimer(context: Context, secondsText: String): ActionResult {
        val seconds=secondsText.toLongOrNull()?.coerceIn(1L, 24L*60L*60L) ?: return ActionResult("I need the timer duration in seconds.", false)
        val intent=Intent(android.provider.AlarmClock.ACTION_SET_TIMER).apply { putExtra(android.provider.AlarmClock.EXTRA_LENGTH, seconds.toInt()); putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, false); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        context.startActivity(intent); return ActionResult("Opening a ${seconds}-second timer.")
    }

    private fun setCalendarEvent(context: Context, payload: String): ActionResult {
        return try {
            val j=org.json.JSONObject(payload); val title=j.optString("title").trim(); if(title.isBlank()) return ActionResult("I need a calendar event title.",false)
            val intent=Intent(Intent.ACTION_INSERT).setData(android.provider.CalendarContract.Events.CONTENT_URI).apply { putExtra(android.provider.CalendarContract.Events.TITLE,title); if(j.has("startMillis")) putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME,j.optLong("startMillis")); if(j.has("endMillis")) putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME,j.optLong("endMillis")); putExtra(android.provider.CalendarContract.Events.EVENT_LOCATION,j.optString("location")); putExtra(android.provider.CalendarContract.Events.DESCRIPTION,j.optString("description")); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            context.startActivity(intent); ActionResult("Opening calendar event setup for $title.")
        } catch(e:Exception) { ActionResult("I couldn't create the calendar event: ${e.message}",false) }
    }

    private fun searchInApp(context: Context, payload: String): ActionResult {
        val parts = payload.split("|", limit = 2)
        if (parts.size != 2) return ActionResult("I need both the app and the search query.", false)
        val appName = parts[0].trim()
        val query = parts[1].trim()
        if (appName.isBlank() || query.isBlank()) return ActionResult("I need both the app and the search query.", false)
        val packageName = findAppPackage(context, appName) ?: return ActionResult("I couldn't find an installed app named $appName.", false)
        val appLabel = getAppLabel(context, packageName)

        val searchIntent = Intent(Intent.ACTION_SEARCH).setPackage(packageName).putExtra(SearchManager.QUERY, query)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val searchHandlers = context.packageManager.queryIntentActivities(searchIntent, PackageManager.MATCH_ALL)
        if (searchHandlers.isNotEmpty()) {
            context.startActivity(searchIntent)
            return ActionResult("Searching $appLabel for $query.")
        }

        val deepLink = when (packageName) {
            "com.google.android.youtube" -> "https://www.youtube.com/results?search_query=${Uri.encode(query)}"
            "com.spotify.music" -> "spotify:search:${Uri.encode(query)}"
            "com.google.android.apps.maps" -> "geo:0,0?q=${Uri.encode(query)}"
            "com.android.vending" -> "market://search?q=${Uri.encode(query)}"
            else -> null
        }
        if (deepLink != null) {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(deepLink)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return ActionResult("Searching $appLabel for $query.")
        }

        return try {
            context.startActivity(context.packageManager.getLaunchIntentForPackage(packageName)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ActionResult("$appLabel does not expose a standard search action. I opened it instead.", false)
        } catch (_: Exception) {
            ActionResult("I couldn't open $appLabel.", false)
        }
    }

    private fun findAppPackage(context: Context, requestedName: String): String? {
        val wanted = norm(requestedName)
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        var best: String? = null
        var score = Int.MIN_VALUE
        for (info in pm.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)) {
            val label = info.loadLabel(pm).toString().trim()
            val n = norm(label)
            if (n.isBlank()) continue
            val distance = levenshtein(n, wanted)
            val s = when {
                n == wanted -> 2000
                n.startsWith(wanted) -> 1500
                n.contains(wanted) -> 1300
                wanted.contains(n) && n.length >= 3 -> 1200
                distance <= 2 && wanted.length >= 5 -> 900 - distance
                else -> Int.MIN_VALUE
            }
            if (s > score) { score = s; best = info.activityInfo.packageName }
        }
        return if (score > 0) best else null
    }

    private fun getAppLabel(context: Context, packageName: String): String {
        return try { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(packageName, 0)).toString() } catch (_: Exception) { packageName }
    }

    private fun setFlashlight(context: Context, enabled: Boolean): ActionResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return ActionResult("Flashlight control is not available on this Android version.", false)
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        val cameraId = cameraManager.cameraIdList.firstOrNull {
            cameraManager.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return ActionResult("I couldn't find a flashlight on this device.", false)
        cameraManager.setTorchMode(cameraId, enabled)
        return ActionResult(if (enabled) "Flashlight turned on." else "Flashlight turned off.")
    }

    private fun extractMessage(text: String): Pair<String, String> {
        val body = text.replaceFirst(Regex("^(send sms|sms|text)\\s+"), "").trim()
        val separators = listOf(" saying ", " message ", " that says ")
        for (sep in separators) if (body.contains(sep)) return body.substringBefore(sep).trim() to body.substringAfter(sep).trim()
        return "" to ""
    }

    private fun extractAfter(text: String, prefixes: List<String>): String {
        for (prefix in prefixes) if (text.contains(prefix)) return text.substringAfter(prefix).trim()
        return ""
    }

    private fun extractAlarmTime(text: String): String {
        val m = Regex("\\b([01]?\\d|2[0-3])[:.]([0-5]\\d)\\b").find(text)
        if (m != null) return "%02d:%02d".format(m.groupValues[1].toInt(), m.groupValues[2].toInt())
        return ""
    }

    private fun goHome(context: Context): ActionResult {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
        return ActionResult("Going to the home screen.")
    }
}
