package com.example.taskreminder.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [TaskEntity::class, GroupEntity::class], version = 1, exportSchema = false)
abstract class LocalDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun groupDao(): GroupDao

    companion object {
        @Volatile
        private var INSTANCE: LocalDatabase? = null

        fun getInstance(context: Context): LocalDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context) =
            Room.databaseBuilder(context.applicationContext, LocalDatabase::class.java, "task_reminder_db")
                .fallbackToDestructiveMigration()
                .build()
    }
}