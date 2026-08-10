package com.example.taskreminder.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter

data class TaskRecord(
    val id: String? = null,
    val ownerIdentityId: String? = null,
    val groupId: String? = null,
    val title: String,
    val notes: String? = null,
    val dueAt: String,
    val nagIntervalMinutes: Int,
    val status: String = "pending",
    val completedAt: String? = null,
)

data class TaskInsertRequest(
    val ownerDeviceId: String? = null,
    val groupId: String? = null,
    val title: String,
    val notes: String? = null,
    val dueAt: String,
    val nagIntervalMinutes: Int,
    val status: String = "pending",
)

data class TaskUpdateRequest(
    val status: String,
    val completedAt: String,
)

class TaskRepository(context: Context) {
    private val deviceIdStore = DeviceIdStore(context)
    private val initialized = SupabaseClientProvider.initialized

    suspend fun fetchPendingTasks(groupId: String? = null): List<TaskRecord> {
        return withContext(Dispatchers.IO) {
            initialized
            val identityId = deviceIdStore.getIdentityId()
            val filters = mutableListOf("select=*", "status=eq.pending")
            if (groupId != null) {
                filters += "group_id=eq.$groupId"
            } else {
                filters += "owner_identity_id=eq.$identityId"
            }

            val connection = SupabaseClientProvider.openConnection("tasks?${filters.joinToString("&")}", "GET")
            try {
                if (connection.responseCode !in 200..299) {
                    throw IllegalStateException(
                        "Failed to load tasks: ${connection.responseCode} ${SupabaseClientProvider.readBody(connection)}",
                    )
                }

                parseTaskArray(SupabaseClientProvider.readBody(connection))
            } finally {
                connection.disconnect()
            }
        }
    }

    suspend fun insertTask(
        title: String,
        notes: String?,
        dueAt: String,
        nagIntervalMinutes: Int,
        groupId: String? = null,
    ) {
        withContext(Dispatchers.IO) {
            initialized
            val identityId = if (groupId == null) deviceIdStore.getIdentityId() else null
            val payload = JSONObject().apply {
                put("title", title)
                put("due_at", dueAt)
                put("nag_interval_minutes", nagIntervalMinutes)
                put("status", "pending")
                if (!notes.isNullOrBlank()) {
                    put("notes", notes.trim())
                }
                if (identityId != null) {
                    put("owner_identity_id", identityId as Any)
                }
                if (groupId != null) {
                    put("group_id", groupId)
                }
            }

            val connection = SupabaseClientProvider.openConnection("tasks", "POST")
            try {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Prefer", "return=minimal")

                OutputStreamWriter(connection.outputStream).use { writer ->
                    writer.write(payload.toString())
                }

                if (connection.responseCode !in 200..299) {
                    throw IllegalStateException(
                        "Failed to add task: ${connection.responseCode} ${SupabaseClientProvider.readBody(connection)}",
                    )
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    suspend fun markTaskDone(taskId: String) {
        withContext(Dispatchers.IO) {
            initialized
            val payload = JSONObject().apply {
                put("status", "done")
                put("completed_at", java.time.Instant.now().toString())
            }

            val connection = SupabaseClientProvider.openConnection("tasks?id=eq.$taskId", "PATCH")
            try {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Prefer", "return=minimal")

                OutputStreamWriter(connection.outputStream).use { writer ->
                    writer.write(payload.toString())
                }

                if (connection.responseCode !in 200..299) {
                    throw IllegalStateException(
                        "Failed to complete task: ${connection.responseCode} ${SupabaseClientProvider.readBody(connection)}",
                    )
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun parseTaskArray(jsonText: String): List<TaskRecord> {
        if (jsonText.isBlank()) {
            return emptyList()
        }

        val tasks = mutableListOf<TaskRecord>()
        val jsonArray = JSONArray(jsonText)
        for (index in 0 until jsonArray.length()) {
            val item = jsonArray.getJSONObject(index)
            tasks += TaskRecord(
                id = item.opt("id")?.takeIf { it != JSONObject.NULL }?.toString(),
                ownerIdentityId = item.opt("owner_identity_id")?.takeIf { it != JSONObject.NULL }?.toString(),
                groupId = item.opt("group_id")?.takeIf { it != JSONObject.NULL }?.toString(),
                title = item.getString("title"),
                notes = item.opt("notes")?.takeIf { it != JSONObject.NULL }?.toString(),
                dueAt = item.getString("due_at"),
                nagIntervalMinutes = item.getInt("nag_interval_minutes"),
                status = item.optString("status", "pending"),
                completedAt = item.opt("completed_at")?.takeIf { it != JSONObject.NULL }?.toString(),
            )
        }
        return tasks
    }
}
