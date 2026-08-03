package com.example.taskreminder.data

/**
 * Coordinates offline-first writes and Supabase synchronization.
 *
 * TODO: Android should read/write local Room state first, then sync pending changes to Supabase
 * when connectivity returns.
 * TODO: Add sync-conflict handling here for edits made on multiple devices or group members.
 */
class OfflineSyncCoordinator
