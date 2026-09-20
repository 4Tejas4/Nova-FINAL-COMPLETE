package com.nova.ai

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Chat screen mirroring the reference design: bubbles, timestamps, composer. */
class ChatActivity : AppCompatActivity() {

    private lateinit var chatList: LinearLayout
    private lateinit var chatScroll: ScrollView
    private lateinit var input: EditText
    private val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())
    private var busy = false

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.nova.ai.CHAT_UPDATED") reloadHistory()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppContext.init(this)
        setContentView(R.layout.activity_chat)
        chatList = findViewById(R.id.chatList)
        chatScroll = findViewById(R.id.chatScroll)
        input = findViewById(R.id.messageInput)

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.sendButton).setOnClickListener { sendText() }
        findViewById<View>(R.id.attachButton).setOnClickListener {
            Toast.makeText(this, "Attachments are not available in the offline build", Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.menuButton).setOnClickListener { showMenu() }

        input.setOnEditorActionListener { _, _, _ -> sendText(); true }
        reloadHistory()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter("com.nova.ai.CHAT_UPDATED")
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(refreshReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(refreshReceiver, filter)
        reloadHistory()
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(refreshReceiver) } catch (_: Exception) {}
    }

    private fun showMenu() {
        AlertDialog.Builder(this)
            .setTitle("Conversation")
            .setItems(arrayOf("Clear chat history", "Settings")) { _, which ->
                when (which) {
                    0 -> {
                        ConversationManager(this).clearConversation()
                        reloadHistory()
                        Toast.makeText(this, "Conversation cleared", Toast.LENGTH_SHORT).show()
                    }
                    1 -> startActivity(Intent(this, SettingsActivity::class.java))
                }
            }
            .show()
    }

    private fun sendText() {
        val text = input.text.toString().trim()
        if (text.isEmpty() || busy) return
        input.setText("")
        addBubble(text, isUser = true, withTime = true)
        busy = true
        NovaAgentCore(this).submitVoiceOrText(text, object : NovaAgentCore.Callback {
            override fun onStatus(message: String) {
                runOnUiThread { addTransientStatus(message) }
            }

            override fun onFinished(success: Boolean, message: String) {
                runOnUiThread {
                    removeTransientStatus()
                    addBubble(message, isUser = false, withTime = true)
                    busy = false
                }
            }
        })
    }

    private fun reloadHistory() {
        chatList.removeAllViews()
        val history = ConversationManager(this).getConversationHistory()
        if (history.isEmpty()) {
            val t = TextView(this)
            t.text = "Say “Hey Nova” with the mic, or type below. Everything is processed on this phone."
            t.setTextColor(getColor(R.color.nova_text_faint))
            t.textSize = 13f
            t.gravity = Gravity.CENTER
            t.setPadding(30, 40, 30, 40)
            chatList.addView(t)
            return
        }
        for (msg in history) {
            addBubble(
                msg["content"] ?: "",
                isUser = msg["role"] == "user",
                withTime = false
            )
        }
        chatScroll.post { chatScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private var transientView: TextView? = null

    private fun addTransientStatus(message: String) {
        removeTransientStatus()
        val t = TextView(this)
        t.text = "• $message"
        t.setTextColor(getColor(R.color.nova_text_faint))
        t.textSize = 12f
        t.setPadding(8, 6, 8, 6)
        chatList.addView(t)
        transientView = t
        chatScroll.post { chatScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun removeTransientStatus() {
        transientView?.let { chatList.removeView(it) }
        transientView = null
    }

    private fun addBubble(text: String, isUser: Boolean, withTime: Boolean) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.BOTTOM
        row.setPadding(0, 6, 0, 6)

        val bubble = TextView(this)
        bubble.text = text
        bubble.textSize = 15f
        bubble.setTextColor(getColor(if (isUser) R.color.nova_user_text else R.color.nova_text))
        bubble.setPadding(36, 26, 36, 26)
        bubble.background = getDrawable(if (isUser) R.drawable.bg_user_bubble else R.drawable.bg_ai_bubble)
        val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.weight = 1f
        lp.marginEnd = if (isUser) dp(8) else 0
        lp.marginStart = if (isUser) 0 else dp(8)
        bubble.layoutParams = lp

        if (isUser) {
            row.addView(bubble)
            row.addView(avatar())
        } else {
            row.addView(avatar())
            row.addView(bubble)
        }

        if (withTime) {
            val time = TextView(this)
            time.text = timeFormat.format(Date())
            time.textSize = 10f
            time.setTextColor(getColor(R.color.nova_text_faint))
            time.setPadding(4, 2, 4, 0)
            val row2 = LinearLayout(this)
            row2.orientation = LinearLayout.HORIZONTAL
            row2.gravity = if (isUser) Gravity.END else Gravity.START
            row2.addView(time)
            chatList.addView(row)
            chatList.addView(row2)
        } else {
            chatList.addView(row)
        }
        chatScroll.post { chatScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun avatar(): ImageView {
        val v = ImageView(this)
        v.setImageResource(R.drawable.ic_orb_avatar)
        v.layoutParams = LinearLayout.LayoutParams(dp(26), dp(26))
        return v
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
