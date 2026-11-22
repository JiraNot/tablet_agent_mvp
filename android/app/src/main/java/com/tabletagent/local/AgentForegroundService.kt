package com.tabletagent.local

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground Service to keep Tablet Agent background processes alive
 * during long Plan/Build task executions and prevent Android OEM task killers
 * from interrupting Termux / local agent runs.
 */
class AgentForegroundService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null

    private var currentTaskId: String = ""
    private var currentProject: String = ""
    private var currentStatus: String = "RUNNING"
    private var currentPrompt: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY

        when (action) {
            ACTION_START -> {
                currentTaskId = intent.getStringExtra(EXTRA_TASK_ID) ?: ""
                currentProject = intent.getStringExtra(EXTRA_PROJECT) ?: "Workspace"
                currentStatus = intent.getStringExtra(EXTRA_STATUS) ?: "QUEUED"
                currentPrompt = intent.getStringExtra(EXTRA_PROMPT) ?: ""

                acquireWakeLock()
                val notification = buildNotification(
                    title = "Tablet Agent: $currentProject",
                    text = "[$currentStatus] ${currentPrompt.take(80)}"
                )

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            }

            ACTION_UPDATE -> {
                val taskId = intent.getStringExtra(EXTRA_TASK_ID)
                if (taskId != null && taskId == currentTaskId) {
                    val status = intent.getStringExtra(EXTRA_STATUS)
                    if (!status.isNullOrBlank()) currentStatus = status

                    val details = intent.getStringExtra(EXTRA_DETAILS)
                    val text = if (!details.isNullOrBlank()) {
                        "[$currentStatus] $details"
                    } else {
                        "[$currentStatus] ${currentPrompt.take(80)}"
                    }

                    val notification = buildNotification(
                        title = "Tablet Agent: $currentProject",
                        text = text
                    )
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.notify(NOTIFICATION_ID, notification)
                }
            }

            ACTION_CANCEL_TASK -> {
                val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: currentTaskId
                if (taskId.isNotBlank()) {
                    serviceScope.launch {
                        try {
                            val token = SecureDeviceToken.get(applicationContext)
                            if (token.isNotBlank()) {
                                LocalApi(token).cancelTask(taskId)
                            }
                        } catch (_: Exception) {}
                        stopServiceInternal()
                    }
                } else {
                    stopServiceInternal()
                }
            }

            ACTION_STOP -> {
                stopServiceInternal()
            }
        }

        return START_NOT_STICKY
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "TabletAgent:ForegroundExecutionWakeLock"
            )?.apply {
                setReferenceCounted(false)
                acquire(30 * 60 * 1000L) // 30 minutes safe ceiling
            }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    private fun stopServiceInternal() {
        releaseWakeLock()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
        serviceScope.cancel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Tablet Agent Tasks",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress and prevents background suspension during active agent runs"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, text: String): android.app.Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flagImmutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or flagImmutable
        )

        val cancelIntent = Intent(this, AgentForegroundService::class.java).apply {
            action = ACTION_CANCEL_TASK
            putExtra(EXTRA_TASK_ID, currentTaskId)
        }
        val cancelPendingIntent = PendingIntent.getService(
            this,
            1,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or flagImmutable
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel Task", cancelPendingIntent)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "tablet_agent_channel"
        const val NOTIFICATION_ID = 2026

        const val ACTION_START = "com.tabletagent.local.ACTION_START"
        const val ACTION_UPDATE = "com.tabletagent.local.ACTION_UPDATE"
        const val ACTION_STOP = "com.tabletagent.local.ACTION_STOP"
        const val ACTION_CANCEL_TASK = "com.tabletagent.local.ACTION_CANCEL_TASK"

        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_PROJECT = "project"
        const val EXTRA_STATUS = "status"
        const val EXTRA_PROMPT = "prompt"
        const val EXTRA_DETAILS = "details"

        fun start(context: Context, taskId: String, project: String, status: String, prompt: String) {
            val intent = Intent(context, AgentForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TASK_ID, taskId)
                putExtra(EXTRA_PROJECT, project)
                putExtra(EXTRA_STATUS, status)
                putExtra(EXTRA_PROMPT, prompt)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun update(context: Context, taskId: String, status: String, details: String = "") {
            val intent = Intent(context, AgentForegroundService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_TASK_ID, taskId)
                putExtra(EXTRA_STATUS, status)
                putExtra(EXTRA_DETAILS, details)
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, AgentForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
