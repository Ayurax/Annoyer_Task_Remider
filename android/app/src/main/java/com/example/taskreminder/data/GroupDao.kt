package com.example.taskreminder.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface GroupDao {
    @Insert
    suspend fun insert(group: GroupEntity): Long

    @Update
    suspend fun update(group: GroupEntity)

    @Delete
    suspend fun delete(group: GroupEntity)

    @Query("SELECT * FROM groups")
    suspend fun getAll(): List<GroupEntity>

    @Query("SELECT * FROM groups WHERE localId = :id")
    suspend fun getById(id: Long): GroupEntity?

    @Query("SELECT * FROM groups WHERE serverId IS NULL")
    suspend fun getUnsyncedGroups(): List<GroupEntity>
}