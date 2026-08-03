package com.example.taskreminder.notifications

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Receives FCM pushes for due or changed reminders.
 *
 * TODO: Decode task reminder payloads and hand them to notification scheduling/escalation.
 * TODO: Register and sync the FCM token for this anonymous device UUID.
 */
class TaskReminderFirebaseMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        // TODO: Route push payload into reminder notification pipeline.
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // TODO: Store token locally and sync it to the backend for this device UUID.
    }
}
