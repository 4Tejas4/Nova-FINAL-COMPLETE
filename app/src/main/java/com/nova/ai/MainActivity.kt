package com.nova.ai

import android.Manifest
import android.animation.ObjectAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Voice-first home screen: animated orb, status line and the mic button.
 * Typed conversation lives in ChatActivity; settings in SettingsActivity.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var orb: NovaOrbView
    private lateinit var status: TextView
    private lateinit var actionText: TextView
    private lateinit var eq: NovaEqView
    private lateinit var micButton: ImageButton
    private lateinit var micGlow: View
    private var pulse: ObjectAnimator? = null
    private val micPermissionCode = 101
    private val handler = Handler(Looper.getMainLooper())
    private var serviceRunning = false

    private val chatReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.nova.ai.CHAT_UPDATED") {
                setStatus("Working on your request…", boost = true)
                handler.postDelayed({ if (serviceRunning && !isFinishing) setStatus("Listening • say “Hey Nova”", boost = false) }, 2600)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppContext.init(this)
        setContentView(R.layout.activity_main)
        orb = findViewById(R.id.orb)
        status = findViewById(R.id.statusText)
        actionText = findViewById(R.id.actionText)
        eq = findViewById(R.id.eq)
        micButton = findViewById(R.id.micButton)
        micGlow = findViewById(R.id.micGlow)

        findViewById<View>(R.id.settingsEntry).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<View>(R.id.chatButton).setOnClickListener { startActivity(Intent(this, ChatActivity::class.java)) }
        micButton.setOnClickListener { toggleNova() }

        val reduceFx = getSharedPreferences("NovaPrefs", MODE_PRIVATE).getBoolean("reduce_fx", false)
        if (reduceFx) orb.setIntensity(0.75f)
        updateState()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter("com.nova.ai.CHAT_UPDATED")
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(chatReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(chatReceiver, filter)
        updateState()
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(chatReceiver) } catch (_: Exception) {}
    }

    private fun setStatus(text: String, boost: Boolean) {
        status.text = text
        eq.setActive(boost)
        orb.setIntensity(if (boost) 1.9f else if (serviceRunning) 1.25f else 0.95f)
    }

    private fun toggleNova() {
        val needNotif = Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED || needNotif) {
            val perms = if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
            else arrayOf(Manifest.permission.RECORD_AUDIO)
            ActivityCompat.requestPermissions(this, perms, micPermissionCode)
            return
        }
        if (serviceRunning) stopNova() else startNova()
    }

    private fun startNova() {
        if (!LocalModelManager.isInstalled(this)) {
            actionText.text = "I need my AI model first — opening Settings…"
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        try {
            val i = Intent(this, NovaWakeService::class.java).setAction(NovaWakeService.ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
            serviceRunning = true
            setStatus("Listening • say “Hey Nova”", boost = true)
            actionText.text = "Tap the mic to stop Nova"
            startPulse()
        } catch (e: Exception) {
            actionText.text = "Could not start Nova: ${e.message}"
        }
    }

    private fun stopNova() {
        stopService(Intent(this, NovaWakeService::class.java).setAction(NovaWakeService.ACTION_STOP))
        serviceRunning = false
        setStatus("Ready", boost = false)
        actionText.text = "Say “Hey Nova” to begin"
        stopPulse()
    }

    private fun updateState() {
        if (!serviceRunning) {
            status.text = if (LocalModelManager.isInstalled(this)) "Ready" else "Model not loaded"
            actionText.text = if (LocalModelManager.isInstalled(this)) "Say “Hey Nova” to begin"
            else "Tap the mic — Nova will guide you to download the model"
        }
    }

    private fun startPulse() {
        pulse?.cancel()
        pulse = ObjectAnimator.ofFloat(micGlow, "alpha", 0.35f, 1f).apply {
            duration = 1100
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            interpolator = LinearInterpolator()
            start()
        }
    }

    private fun stopPulse() {
        pulse?.cancel()
        micGlow.alpha = 1f
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == micPermissionCode && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            toggleNova()
        } else if (requestCode == micPermissionCode) {
            actionText.text = "Microphone permission is required. Allow it in Settings → Apps → Nova → Permissions."
        }
    }

    override fun onDestroy() {
        stopPulse()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
