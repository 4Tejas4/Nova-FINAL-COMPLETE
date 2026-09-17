package com.nova.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * Android-aware foreground worker for queued autonomous tasks.
 * Android may stop background work, so every task remains checkpointed and resumable.
 */
class NovaBackgroundTaskService : Service() {
    private val scheduler by lazy { NovaTaskScheduler(this) }
    private val queue by lazy { NovaTaskQueue(this) }
    private var loop: AgentLoop? = null
    private var currentQueueId: String = ""

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, notification("Nova task manager ready"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ENQUEUE -> {
                val request = intent.getStringExtra(EXTRA_REQUEST).orEmpty()
                if (request.isNotBlank()) scheduler.submit(request, intent.getIntExtra(EXTRA_PRIORITY, NovaTaskQueue.PRIORITY_NORMAL))
            }
            ACTION_CANCEL -> intent.getStringExtra(EXTRA_QUEUE_ID)?.let { cancelQueued(it) }
            ACTION_PAUSE -> pauseCurrent()
        }
        runNext()
        return START_STICKY
    }

    private fun runNext() {
        if (loop != null) return
        val item = scheduler.next() ?: run {
            stopSelf()
            return
        }
        currentQueueId = item.id
        queue.markRunning(item.id, "pending")
        updateNotification("Nova working: ${item.request.take(60)}")
        loop = AgentLoop(this).also { agent ->
            agent.start(item.request, object : AgentLoop.Callback {
                override fun onStatus(message: String) { updateNotification("Nova: ${message.take(100)}") }
                override fun onFinished(success: Boolean, message: String) {
                    if (success) queue.markCompleted(item.id, message) else queue.markFailed(item.id, message)
                    loop = null
                    currentQueueId = ""
                    runNext()
                }
            })
            queue.markRunning(item.id, agent.currentTaskId())
        }
    }

    private fun cancelQueued(id: String) {
        if (id == currentQueueId) {
            loop?.stop()
            queue.cancel(id)
            loop = null
            currentQueueId = ""
        } else queue.cancel(id)
    }

    private fun pauseCurrent() {
        if (currentQueueId.isNotBlank()) {
            loop?.stop()
            queue.markPaused(currentQueueId, "Paused; task checkpoint preserved")
            loop = null
            currentQueueId = ""
        }
    }

    override fun onDestroy() {
        loop?.stop()
        loop = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Nova")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(open)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).setContentTitle("Nova").setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info).setContentIntent(open).setOngoing(true).build()
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(text))
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Nova task manager", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    companion object {
        const val ACTION_ENQUEUE = "com.nova.ai.ENQUEUE_TASK"
        const val ACTION_CANCEL = "com.nova.ai.CANCEL_TASK"
        const val ACTION_PAUSE = "com.nova.ai.PAUSE_TASK"
        const val EXTRA_REQUEST = "request"
        const val EXTRA_PRIORITY = "priority"
        const val EXTRA_QUEUE_ID = "queue_id"
        private const val CHANNEL_ID = "nova_tasks"
        private const val NOTIFICATION_ID = 55056

        fun start(context: Context) {
            val intent = Intent(context, NovaBackgroundTaskService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }

        fun enqueue(context: Context, request: String, priority: Int = NovaTaskQueue.PRIORITY_NORMAL) {
            val intent = Intent(context, NovaBackgroundTaskService::class.java).apply {
                action = ACTION_ENQUEUE
                putExtra(EXTRA_REQUEST, request)
                putExtra(EXTRA_PRIORITY, priority)
            }
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }
    }
}
