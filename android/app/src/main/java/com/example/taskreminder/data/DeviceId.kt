package com.example.taskreminder.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.util.UUID

private val Context.deviceIdDataStore by preferencesDataStore(name = "device_identity")

private val DEVICE_ID_KEY = stringPreferencesKey("device_id")
private val IDENTITY_ID_KEY = stringPreferencesKey("identity_id")

class DeviceIdStore(private val context: Context) {
    private val dataStore = context.deviceIdDataStore
    private val initialized = SupabaseClientProvider.initialized

    suspend fun getDeviceId(): String {
        initialized
        val existingId = dataStore.data.first()[DEVICE_ID_KEY]
        if (!existingId.isNullOrBlank()) {
            SupabaseClientProvider.deviceId = existingId
            return existingId
        }

        val deviceId = UUID.randomUUID().toString()
        dataStore.edit { preferences ->
            preferences[DEVICE_ID_KEY] = deviceId
        }
        return deviceId
    }

    suspend fun getIdentityId(): String {
        initialized

        // Check if we have a cached identity_id
        val cachedIdentityId = dataStore.data.first()[IDENTITY_ID_KEY]
        if (!cachedIdentityId.isNullOrBlank()) {
            // Ensure x-device-id header is set for subsequent RLS queries
            val deviceId = dataStore.data.first()[DEVICE_ID_KEY]
            if (deviceId != null) {
                SupabaseClientProvider.deviceId = deviceId
            }
            return cachedIdentityId
        }

        // Get device_id and set header for subsequent RPC calls
        val deviceId = getDeviceId()
        SupabaseClientProvider.deviceId = deviceId

        // Try to fetch existing identity for this device
        val identityResponse = fetchIdentityForDevice(deviceId)

        // If we got a valid identity_id, cache and return it
        if (identityResponse != null &&
            !identityResponse.isNullOrBlank() &&
            identityResponse.lowercase() != "null") {
            // Strip surrounding quotes if present (e.g., if response is quoted JSON string)
            val cleanedId = identityResponse.trim()
            val unquotedId = if (cleanedId.startsWith('"') && cleanedId.endsWith('"') && cleanedId.length >= 2) {
                cleanedId.substring(1, cleanedId.length - 1)
            } else {
                cleanedId
            }
            dataStore.edit { preferences ->
                preferences[IDENTITY_ID_KEY] = unquotedId
            }
            return unquotedId
        }

        // No identity exists, create a new one
        val newIdentityId = createIdentityForDevice(deviceId)
        dataStore.edit { preferences ->
            preferences[IDENTITY_ID_KEY] = newIdentityId
        }
        return newIdentityId
    }

    private suspend fun fetchIdentityForDevice(deviceId: String): String? {
        val connection = SupabaseClientProvider.openConnection("rpc/identity_for_device", "POST")
        return try {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Prefer", "return=representation")

            val payload = JSONObject().apply {
                put("p_device_id", deviceId)
            }

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(payload.toString())
            }

            if (connection.responseCode !in 200..299) {
                throw IllegalStateException(
                    "Failed to fetch identity for device: ${connection.responseCode} ${SupabaseClientProvider.readBody(connection)}",
                )
            }

            SupabaseClientProvider.readBody(connection)
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun createIdentityForDevice(deviceId: String): String {
        val connection = SupabaseClientProvider.openConnection("rpc/create_identity_for_device", "POST")
        return try {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Prefer", "return=representation")

            val payload = JSONObject().apply {
                put("p_device_id", deviceId)
            }

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(payload.toString())
            }

            if (connection.responseCode !in 200..299) {
                throw IllegalStateException(
                    "Failed to create identity for device: ${connection.responseCode} ${SupabaseClientProvider.readBody(connection)}",
                )
            }

            val responseBody = SupabaseClientProvider.readBody(connection)
            // Parse JSON array response and extract identity_id from first object
            val jsonArray = JSONArray(responseBody)
            if (jsonArray.length() == 0) {
                throw IllegalStateException("Create identity returned empty array")
            }
            val firstObject = jsonArray.getJSONObject(0)
            val identityId = firstObject.getString("identity_id")
            return identityId
        } finally {
            connection.disconnect()
        }
    }}
