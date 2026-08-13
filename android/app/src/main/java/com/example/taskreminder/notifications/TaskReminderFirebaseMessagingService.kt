package com.example.taskreminder.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.taskreminder.R
import com.example.taskreminder.data.DeviceIdStore
import com.example.taskreminder.data.SupabaseClientProvider
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.OutputStreamWriter

class TaskReminderFirebaseMessagingService : FirebaseMessagingService() {

    private val deviceIdStore by lazy { DeviceIdStore(this) }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val deviceId = deviceIdStore.getDeviceId()
                val payload = JSONObject().apply {
                    put("fcm_token", token)
                }

                val connection = SupabaseClientProvider.openConnection(
                    "devices?id=eq.$deviceId", "PATCH"
                )
                try {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.setRequestProperty("Prefer", "return=minimal")

                    OutputStreamWriter(connection.outputStream).use { writer ->
                        writer.write(payload.toString())
                    }

                    if (connection.responseCode !in 200..299) {
                        Log.e(
                            "FCM",
                            "Failed to update FCM token: ${connection.responseCode} ${SupabaseClientProvider.readBody(connection)}",
                        )
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                Log.e("FCM", "Error syncing FCM token", e)
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        val data = message.data
        val taskId = data["task_id"]
        val title = data["title"] ?: "Task Reminder"
        val dueAt = data["due_at"]
        val nagCount = data["nag_count"]

        showNotification(taskId, title, dueAt, nagCount)
    }

    private fun showNotification(
        taskId: String?,
        title: String,
        dueAt: String?,
        nagCount: String?,
    ) {
        val channelId = "task_reminders"
        val channelName = "Task Reminders"

        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_HIGH,
            )
            channel.enableVibration(true)
            channel.enableLights(true)
            notificationManager.createNotificationChannel(channel)
        }

        val contentText = if (dueAt != null) "Due: $dueAt" else "You have a pending reminder"
        val notificationTitle = if (nagCount != null && nagCount != "1") {
            "$title (Nag #$nagCount)"
        } else {
            title
        }

        val intent = Intent(this, com.example.taskreminder.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("task_id", taskId)
        }

        val notificationId = taskId?.hashCode() ?: System.currentTimeMillis().toInt()

        val pendingIntent = PendingIntent.getActivity(
            this,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(notificationTitle)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(
                        buildString {
                            append(notificationTitle)
                            if (dueAt != null) {
                                append("\nDue: $dueAt")
                            }
                        },
                    ),
            )
            .setContentText(contentText)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(android.app.Notification.DEFAULT_VIBRATE or android.app.Notification.DEFAULT_SOUND)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOngoing(false)
            .setOnlyAlertOnce(false)

        NotificationManagerCompat.from(this).notify(notificationId, builder.build())
    }
}
