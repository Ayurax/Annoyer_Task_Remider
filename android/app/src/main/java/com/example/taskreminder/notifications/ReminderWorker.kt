package com.example.taskreminder.notifications

/**
 * WorkManager entry point for reminder notification work.
 *
 * TODO: Use scheduled work only for locally queued reminder handling that follows an FCM wake-up.
 * Android should stay dormant and wake via FCM push instead of self-polling the backend.
 * TODO: Stop all reminder work immediately once the related task status becomes done.
 */
class ReminderWorker
