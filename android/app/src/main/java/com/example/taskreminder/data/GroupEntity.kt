package com.example.taskreminder.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey(autoGenerate = true) val localId: Long = 0,
    val serverId: String?,
    val joinCode: String,
    val name: String?,
    val createdByIdentityId: String?,
    val syncStatus: String = "synced"
)