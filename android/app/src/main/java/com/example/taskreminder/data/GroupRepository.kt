package com.example.taskreminder.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter

class GroupRepository(context: Context) {
    private val deviceIdStore = DeviceIdStore(context)
    private val initialized = SupabaseClientProvider.initialized

    suspend fun createGroup(name: String?): Group {
        return withContext(Dispatchers.IO) {
            initialized
            val payload = JSONObject().apply {
                put("p_device_id", deviceIdStore.getDeviceId())
                put("p_name", name?.trim().takeUnless { it.isNullOrBlank() })
            }

            val response = postRpcForArray("create_group_for_device", payload)
            if (response.length() == 0) {
                throw IllegalStateException("Failed to create group.")
            }
            parseGroup(response.getJSONObject(0))
        }
    }

    suspend fun joinGroup(joinCode: String): Group? {
        return withContext(Dispatchers.IO) {
            initialized
            val normalizedJoinCode = joinCode.trim().uppercase()
            if (normalizedJoinCode.isEmpty()) {
                return@withContext null
            }

            val payload = JSONObject().apply {
                put("p_device_id", deviceIdStore.getDeviceId())
                put("p_join_code", normalizedJoinCode)
            }

            val response = postRpcForArray("join_group_for_device", payload)
            if (response.length() == 0) {
                return@withContext null
            }
            parseGroup(response.getJSONObject(0))
        }
    }

    suspend fun getMyGroups(): List<Group> {
        return withContext(Dispatchers.IO) {
            initialized
            val payload = JSONObject().apply {
                put("p_device_id", deviceIdStore.getDeviceId())
            }

            val response = postRpcForArray("get_groups_for_device", payload)
            List(response.length()) { index -> parseGroup(response.getJSONObject(index)) }
        }
    }

    suspend fun leaveGroup(groupId: String) {
        withContext(Dispatchers.IO) {
            initialized
            val payload = JSONObject().apply {
                put("p_device_id", deviceIdStore.getDeviceId())
                put("p_group_id", groupId)
            }

            postRpcNoBody("leave_group_for_device", payload)
        }
    }

    suspend fun deleteGroup(groupId: String) {
        withContext(Dispatchers.IO) {
            initialized
            val payload = JSONObject().apply {
                put("p_device_id", deviceIdStore.getDeviceId())
                put("p_group_id", groupId)
            }

            postRpcNoBody("delete_group_for_device", payload)
        }
    }

    private fun postRpcForArray(functionName: String, payload: JSONObject): JSONArray {
        val body = postRpc(functionName, payload, "return=representation")
        if (body.isBlank()) {
            return JSONArray()
        }
        return JSONArray(body)
    }

    private fun postRpcNoBody(functionName: String, payload: JSONObject) {
        postRpc(functionName, payload, "return=minimal")
    }

    private fun postRpc(
        functionName: String,
        payload: JSONObject,
        preferHeader: String,
    ): String {
        val connection = SupabaseClientProvider.openConnection("rpc/$functionName", "POST")
        try {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Prefer", preferHeader)

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(payload.toString())
            }

            if (connection.responseCode !in 200..299) {
                throw IllegalStateException(
                    "Group operation failed: ${connection.responseCode} ${SupabaseClientProvider.readBody(connection)}",
                )
            }

            return SupabaseClientProvider.readBody(connection)
        } finally {
            connection.disconnect()
        }
    }

    suspend fun getOrCreateIdentityLinkCode(): String {
        return withContext(Dispatchers.IO) {
            initialized
            val deviceId = deviceIdStore.getDeviceId()
            val payload = JSONObject().apply {
                put("p_device_id", deviceId)
            }
            val response = postRpcForArray("create_identity_for_device", payload)
            if (response.length() == 0) {
                throw IllegalStateException("Failed to create/get identity")
            }
            val result = response.getJSONObject(0)
            result.getString("link_code")
        }
    }

    suspend fun linkDeviceToIdentity(linkCode: String): String {
        return withContext(Dispatchers.IO) {
            initialized
            val deviceId = deviceIdStore.getDeviceId()
            val payload = JSONObject().apply {
                put("p_device_id", deviceId)
                put("p_link_code", linkCode)
            }
            val response = postRpcForArray("link_device_to_identity", payload)
            if (response.length() == 0) {
                throw IllegalStateException("Failed to link device to identity")
            }
            val result = response.getJSONObject(0)
            result.getString("link_code")
        }
    }

    private fun parseGroup(item: JSONObject): Group {
        return Group(
            id = item.getString("id"),
            joinCode = item.getString("join_code"),
            name = item.nullableString("name"),
            createdByIdentityId = item.nullableString("created_by_identity_id"),
        )
    }

    private fun JSONObject.nullableString(name: String): String? {
        if (isNull(name)) {
            return null
        }
        return optString(name).takeIf { it.isNotBlank() }
    }
}
