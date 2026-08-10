package com.example.taskreminder.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter

data class TaskRecord(
    val id: String? = null,
    val localId: Long? = null,
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
    val deviceIdStore = DeviceIdStore(context)
    private val localDatabase = LocalDatabase.getInstance(context)
    private val taskDao = localDatabase.taskDao()
    private val initialized = SupabaseClientProvider.initialized

    suspend fun fetchPendingTasks(groupId: String? = null, identityId: String? = null): List<TaskRecord> {
        return withContext(Dispatchers.IO) {
            initialized
            trySyncFromSupabase(groupId, identityId)

            val entities = if (groupId != null) {
                taskDao.getByGroupId(groupId)
            } else if (identityId != null) {
                taskDao.getByIdentityId(identityId)
            } else {
                taskDao.getAll()
            }

            entities.filter { it.status == "pending" }.map { entityToRecord(it) }
        }
    }

    suspend fun insertTask(
        title: String,
        notes: String?,
        dueAt: String,
        nagIntervalMinutes: Int,
        groupId: String? = null,
    ): Long {
        return withContext(Dispatchers.IO) {
            initialized
            val identityId = if (groupId == null) deviceIdStore.getIdentityId() else null

            val task = TaskEntity(
                localId = 0,
                serverId = null,
                ownerIdentityId = identityId,
                groupId = groupId,
                title = title,
                notes = notes,
                dueAt = dueAt,
                nagIntervalMinutes = nagIntervalMinutes,
                status = "pending",
                syncStatus = "pending",
                completedAt = null
            )
            val localId = taskDao.insert(task)

            try {
                val serverId = insertToSupabase(task, identityId)
                taskDao.update(task.copy(localId = localId, serverId = serverId, syncStatus = "synced"))
            } catch (e: Exception) {
            }

            localId
        }
    }

    suspend fun markTaskDone(localId: Long) {
        withContext(Dispatchers.IO) {
            initialized
            val task = taskDao.getById(localId) ?: return@withContext

            val updatedTask = task.copy(
                status = "done",
                completedAt = java.time.Instant.now().toString(),
                syncStatus = "pending"
            )
            taskDao.update(updatedTask)

            try {
                if (task.serverId != null) {
                    markTaskDoneOnSupabase(task.serverId)
                    taskDao.update(updatedTask.copy(syncStatus = "synced"))
                }
            } catch (e: Exception) {
            }
        }
    }

    suspend fun syncTask(task: TaskEntity) {
        initialized
        if (task.serverId == null) {
            val serverId = insertToSupabase(task, task.ownerIdentityId)
            taskDao.update(task.copy(serverId = serverId, syncStatus = "synced"))
        } else if (task.status == "done") {
            markTaskDoneOnSupabase(task.serverId)
            taskDao.update(task.copy(syncStatus = "synced"))
        }
    }

    private suspend fun trySyncFromSupabase(groupId: String?, identityId: String?) {
        val identityToQuery = identityId ?: return
        val filters = mutableListOf("select=*", "status=eq.pending")
        if (groupId != null) {
            filters += "group_id=eq.$groupId"
        } else {
            filters += "owner_identity_id=eq.$identityToQuery"
        }

        val connection = SupabaseClientProvider.openConnection(
            "tasks?${filters.joinToString("&")}", "GET"
        )
        try {
            if (connection.responseCode !in 200..299) {
                return
            }

            val body = SupabaseClientProvider.readBody(connection)
            val supabaseTasks = parseTaskArray(body)
            supabaseTasks.forEach { record ->
                val existing = taskDao.getByServerId(record.id!!)
                val entity = TaskEntity(
                    localId = existing?.localId ?: 0,
                    serverId = record.id,
                    ownerIdentityId = record.ownerIdentityId,
                    groupId = record.groupId,
                    title = record.title,
                    notes = record.notes,
                    dueAt = record.dueAt,
                    nagIntervalMinutes = record.nagIntervalMinutes,
                    status = record.status,
                    syncStatus = "synced",
                    completedAt = record.completedAt
                )
                if (existing != null) {
                    taskDao.update(entity.copy(localId = existing.localId))
                } else {
                    taskDao.insert(entity)
                }
            }
        } catch (e: Exception) {
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun insertToSupabase(task: TaskEntity, identityId: String?): String {
        val payload = JSONObject().apply {
            put("title", task.title)
            put("due_at", task.dueAt)
            put("nag_interval_minutes", task.nagIntervalMinutes)
            put("status", task.status)
            if (!task.notes.isNullOrBlank()) {
                put("notes", task.notes.trim())
            }
            if (identityId != null) {
                put("owner_identity_id", identityId as Any)
            }
            if (task.groupId != null) {
                put("group_id", task.groupId)
            }
        }

        val connection = SupabaseClientProvider.openConnection("tasks", "POST")
        try {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Prefer", "return=representation")

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(payload.toString())
            }

            if (connection.responseCode !in 200..299) {
                throw IllegalStateException(
                    "Failed to add task: ${connection.responseCode} ${SupabaseClientProvider.readBody(connection)}",
                )
            }

            val body = SupabaseClientProvider.readBody(connection)
            if (body.isBlank()) {
                throw IllegalStateException("Failed to add task: empty response")
            }
            val jsonArray = JSONArray(body)
            val firstObject = jsonArray.getJSONObject(0)
            return firstObject.getString("id")
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun markTaskDoneOnSupabase(serverId: String) {
        val payload = JSONObject().apply {
            put("status", "done")
            put("completed_at", java.time.Instant.now().toString())
        }

        val connection = SupabaseClientProvider.openConnection("tasks?id=eq.$serverId", "PATCH")
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

    private fun entityToRecord(entity: TaskEntity): TaskRecord {
        return TaskRecord(
            id = entity.serverId,
            localId = entity.localId,
            ownerIdentityId = entity.ownerIdentityId,
            groupId = entity.groupId,
            title = entity.title,
            notes = entity.notes,
            dueAt = entity.dueAt,
            nagIntervalMinutes = entity.nagIntervalMinutes,
            status = entity.status,
            completedAt = entity.completedAt,
        )
    }
}
