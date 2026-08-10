package com.example.taskreminder.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface TaskDao {
    @Insert
    suspend fun insert(task: TaskEntity): Long

    @Update
    suspend fun update(task: TaskEntity)

    @Delete
    suspend fun delete(task: TaskEntity)

    @Query("SELECT * FROM tasks")
    suspend fun getAll(): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE localId = :id")
    suspend fun getById(id: Long): TaskEntity?

    @Query("SELECT * FROM tasks WHERE syncStatus = 'pending'")
    suspend fun getPendingSyncTasks(): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE serverId = :serverId")
    suspend fun getByServerId(serverId: String): TaskEntity?

    @Query("SELECT * FROM tasks WHERE groupId = :groupId AND status = 'pending'")
    suspend fun getByGroupId(groupId: String): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE ownerIdentityId = :identityId AND groupId IS NULL AND status = 'pending'")
    suspend fun getByIdentityId(identityId: String): List<TaskEntity>
}