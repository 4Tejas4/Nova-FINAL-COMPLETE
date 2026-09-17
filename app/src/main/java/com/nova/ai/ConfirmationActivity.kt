package com.nova.ai

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.Bundle
import android.graphics.Color
import android.graphics.drawable.ColorDrawable

class ConfirmationActivity : Activity() {
    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_MESSAGE = "message"
        const val ACTION_YES = "com.nova.ai.CONFIRM_YES"
        const val ACTION_CANCEL = "com.nova.ai.CONFIRM_CANCEL"
        const val ACTION_DISMISSED = "com.nova.ai.CONFIRM_DISMISSED"
    }

    private var finishedByButton = false
    private var dialog: AlertDialog? = null

    private val closeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_DISMISSED) {
                finishedByButton = true
                dialog?.dismiss()
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val filter = IntentFilter(ACTION_DISMISSED)
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(closeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(closeReceiver, filter)
        showDialog()
    }

    private fun showDialog() {
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Confirm action"
        val message = intent.getStringExtra(EXTRA_MESSAGE) ?: "This action needs confirmation."
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("CANCEL") { _, _ ->
                finishedByButton = true
                sendResult(ACTION_CANCEL)
            }
            .setPositiveButton("YES") { _, _ ->
                finishedByButton = true
                sendResult(ACTION_YES)
            }
            .setOnCancelListener {
                if (!finishedByButton) sendResult(ACTION_CANCEL)
            }
            .create()
        this.dialog = dialog
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.rgb(80, 145, 235))
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.GRAY)
        }
        dialog.show()
    }

    private fun sendResult(action: String) {
        sendBroadcast(Intent(action).setPackage(packageName))
        finish()
    }

    override fun onBackPressed() {
        sendResult(ACTION_CANCEL)
        super.onBackPressed()
    }

    override fun onDestroy() {
        try { unregisterReceiver(closeReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }
}
