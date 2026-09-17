package com.nova.ai

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.provider.Settings
import android.view.KeyEvent
import android.os.Handler
import android.os.Looper
import java.util.Locale

object MusicController {
    private const val PREFS = "NovaPrefs"
    private const val KEY_MUSIC_PACKAGE = "music_app_package"

    fun getPreferredPackage(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MUSIC_PACKAGE, "")?.trim().orEmpty()

    fun getPreferredLabel(context: Context): String {
        val pkg = getPreferredPackage(context)
        if (pkg.isBlank()) return "Not set"
        return try {
            context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) { pkg }
    }

    fun setPreferredApp(context: Context, requestedName: String): CommandRouter.ActionResult {
        val pkg = findLauncherPackage(context, requestedName)
            ?: return CommandRouter.ActionResult("I couldn't find an installed music app named $requestedName.", false)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_MUSIC_PACKAGE, pkg).apply()
        val label = context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
        return CommandRouter.ActionResult("Preferred music app set to $label.")
    }

    fun play(context: Context): CommandRouter.ActionResult = control(context, "play") { it.play() }
    fun pause(context: Context): CommandRouter.ActionResult = control(context, "pause") { it.pause() }
    fun next(context: Context): CommandRouter.ActionResult = control(context, "next") { it.skipToNext() }
    fun previous(context: Context): CommandRouter.ActionResult = control(context, "previous") { it.skipToPrevious() }

    fun togglePlayPause(context: Context): CommandRouter.ActionResult {
        val preferred = getPreferredPackage(context)
        if (preferred.isBlank()) return CommandRouter.ActionResult("Set a preferred music app in Nova Settings, then repeat the command.", false)
        if (!isNotificationAccessEnabled(context)) return CommandRouter.ActionResult("Enable Music Control Access in Nova Settings first.", false)
        val controller = findController(context)
        if (controller != null) {
            val state = controller.playbackState?.state
            if (state == android.media.session.PlaybackState.STATE_PLAYING) controller.transportControls.pause()
            else controller.transportControls.play()
            return CommandRouter.ActionResult("Toggled playback in ${getPreferredLabel(context)}.")
        }
        launchPreferredApp(context)
        Handler(Looper.getMainLooper()).postDelayed({
            val retry = findController(context)
            if (retry != null) {
                val retryState = retry.playbackState?.state
                if (retryState == android.media.session.PlaybackState.STATE_PLAYING) retry.transportControls.pause() else retry.transportControls.play()
            }
        }, 900)
        return CommandRouter.ActionResult("I couldn't see an active media session yet. I opened ${getPreferredLabel(context)} and will try playback again.", true)
    }

    private fun control(context: Context, label: String, action: (MediaController.TransportControls) -> Unit): CommandRouter.ActionResult {
        if (getPreferredPackage(context).isBlank()) return CommandRouter.ActionResult("Set a preferred music app in Nova Settings, then repeat the command.", false)
        if (!isNotificationAccessEnabled(context)) return CommandRouter.ActionResult("Enable Music Control Access in Nova Settings first.", false)
        val controller = findController(context)
        if (controller != null) {
            action(controller.transportControls)
            return CommandRouter.ActionResult("$label sent to ${getPreferredLabel(context)}.")
        }
        launchPreferredApp(context)
        Handler(Looper.getMainLooper()).postDelayed({
            val retry = findController(context)
            if (retry != null) {
                when (label) {
                    "play" -> retry.transportControls.play()
                    "pause" -> retry.transportControls.pause()
                    "next" -> retry.transportControls.skipToNext()
                    "previous" -> retry.transportControls.skipToPrevious()
                }
            }
        }, 900)
        return CommandRouter.ActionResult("I couldn't see an active media session yet. I opened ${getPreferredLabel(context)} and will try $label again.", true)
    }

    private fun findController(context: Context): MediaController? {
        val listener = ComponentName(context, NovaNotificationListenerService::class.java)
        if (!isNotificationAccessEnabled(context)) return null
        return try {
            val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val sessions = manager.getActiveSessions(listener)
            val preferred = getPreferredPackage(context)
            sessions.firstOrNull { preferred.isNotBlank() && it.packageName == preferred }
                ?: sessions.firstOrNull()
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun launchPreferredApp(context: Context) {
        val pkg = getPreferredPackage(context)
        if (pkg.isBlank()) return
        try {
            val i = context.packageManager.getLaunchIntentForPackage(pkg) ?: return
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
        } catch (_: Exception) {}
    }

    fun isNotificationAccessEnabled(context: Context): Boolean {
        val raw = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
        val component = ComponentName(context, NovaNotificationListenerService::class.java).flattenToString()
        return raw.split(':').any { it.equals(component, ignoreCase = true) }
    }

    fun openNotificationAccessSettings(context: Context) {
        context.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun findLauncherPackage(context: Context, requestedName: String): String? {
        val wanted = norm(requestedName)
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        var best: String? = null
        var score = Int.MIN_VALUE
        for (info in pm.queryIntentActivities(launcher, 0)) {
            val label = info.loadLabel(pm).toString()
            val n = norm(label)
            val s = when {
                n == wanted -> 2000
                n.startsWith(wanted) -> 1500
                n.contains(wanted) -> 1300
                wanted.contains(n) && n.length >= 3 -> 1200
                levenshtein(n, wanted) <= 2 && wanted.length >= 5 -> 900
                else -> Int.MIN_VALUE
            }
            if (s > score) { score = s; best = info.activityInfo.packageName }
        }
        return if (score > 0) best else null
    }

    private fun norm(value: String): String = value.lowercase(Locale.getDefault()).replace(Regex("[^a-z0-9]+"), "")

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
}
