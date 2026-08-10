package com.example.taskreminder.data

import com.example.taskreminder.BuildConfig
import java.net.HttpURLConnection
import java.net.URL

object SupabaseClientProvider {
    val supabaseUrl: String by lazy {
        BuildConfig.SUPABASE_URL.trim().trimEnd('/')
    }

    val supabaseAnonKey: String by lazy {
        BuildConfig.SUPABASE_ANON_KEY.trim()
    }

    val restBaseUrl: String by lazy {
        "$supabaseUrl/rest/v1"
    }

    fun restUrl(path: String): String {
        return "$restBaseUrl/$path"
    }

    fun openConnection(path: String, method: String): HttpURLConnection {
        val connection = URL(restUrl(path)).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("apikey", supabaseAnonKey)
        connection.setRequestProperty("Authorization", "Bearer $supabaseAnonKey")
        connection.setRequestProperty("Accept", "application/json")
        return connection
    }

    fun readBody(connection: HttpURLConnection): String {
        val responseStream = when {
            connection.responseCode in 200..299 -> connection.inputStream
            else -> connection.errorStream
        } ?: return ""

        return responseStream.bufferedReader().use { it.readText() }
    }

    val initialized: Boolean by lazy {
        require(supabaseUrl.isNotBlank()) {
            "SUPABASE_URL must be set in android/local.properties"
        }
        require(supabaseAnonKey.isNotBlank()) {
            "SUPABASE_ANON_KEY must be set in android/local.properties"
        }
        true
    }
}