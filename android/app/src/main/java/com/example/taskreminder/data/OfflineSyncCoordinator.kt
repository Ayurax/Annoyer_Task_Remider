package com.example.taskreminder.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build

class OfflineSyncCoordinator(private val context: Context) {

    private val localDatabase = LocalDatabase.getInstance(context)
    private val taskRepository = TaskRepository(context)
    private val groupRepository = GroupRepository(context)

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            connectivityManager.activeNetwork ?: return false
            connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)?.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_INTERNET
            ) == true
        } else {
            @Suppress("DEPRECATION")
            connectivityManager.activeNetworkInfo?.isConnectedOrConnecting == true
        }
    }

    suspend fun syncPendingTasks() {
        if (!isNetworkAvailable()) return
        val pendingTasks = localDatabase.taskDao().getPendingSyncTasks()
        pendingTasks.forEach { task ->
            try {
                taskRepository.syncTask(task)
            } catch (e: Exception) {
                localDatabase.taskDao().update(task.copy(syncStatus = "failed"))
            }
        }
    }

    suspend fun syncUnsyncedGroups() {
        if (!isNetworkAvailable()) return
        val unsyncedGroups = localDatabase.groupDao().getUnsyncedGroups()
        unsyncedGroups.forEach { group ->
            try {
                val createdGroup = groupRepository.createGroup(group.name)
                localDatabase.groupDao().update(
                    group.copy(
                        serverId = createdGroup.id,
                        joinCode = createdGroup.joinCode,
                        syncStatus = "synced"
                    )
                )
            } catch (e: Exception) {
            }
        }
    }

    suspend fun manuallySync() {
        syncPendingTasks()
        syncUnsyncedGroups()
    }
}
