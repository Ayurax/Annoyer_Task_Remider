package com.example.taskreminder.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val localId: Long = 0,
    val serverId: String?,
    val ownerIdentityId: String?,
    val groupId: String?,
    val title: String,
    val notes: String?,
    val dueAt: String,
    val nagIntervalMinutes: Int,
    val status: String, // "pending" or "done"
    val syncStatus: String, // "pending", "synced", "failed"
    val completedAt: String?
)