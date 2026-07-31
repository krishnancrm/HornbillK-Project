package com.example.hornbillk

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class NetworkService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    var currentServerConfig: ConnectionConfig? = null

    // --- 1. SMART LOGIN (Authentication + Registration) ---
    suspend fun login(config: ConnectionConfig, clientPhone: String): Boolean {
        return withContext(Dispatchers.IO) {
            val url = "https://${config.cloudflareTunnelUrl}/login"

            val jsonObject = JSONObject().apply {
                put("api_key", config.apiKey)
                put("alias", config.alias)
                // Send phone if provided, otherwise empty string
                put("phone_number", clientPhone)
            }

            val requestBody = jsonObject.toString().toRequestBody(JSON)
            val request = Request.Builder().url(url).post(requestBody).build()

            try {
                val response = client.newCall(request).execute()
                val responseBody = response.body?.string()

                if (response.isSuccessful && responseBody != null) {
                    val json = JSONObject(responseBody)
                    val serverSuccess = json.optBoolean("success", false)
                    val serverMsg = json.optString("message", "Unknown")

                    if (serverSuccess) {
                        Log.d("NetworkService", "Login/Register Success: $serverMsg")
                        currentServerConfig = config
                        return@withContext true
                    } else {
                        Log.w("NetworkService", "Server Rejected: $serverMsg")
                    }
                } else {
                    Log.w("NetworkService", "HTTP Error: ${response.code}")
                }
                false
            } catch (e: Exception) {
                Log.e("NetworkService", "Login Exception: ${e.message}")
                false
            }
        }
    }

    // --- 2. MONITORING CONTROL (Restored Real Logic) ---
    suspend fun startMonitor(cloudflareTunnelUrl: String, apiKey: String): Boolean {
        return withContext(Dispatchers.IO) {
            val url = "https://$cloudflareTunnelUrl/monitor/start"
            // Empty body for POST
            val body = "".toRequestBody("text/plain".toMediaType())

            val request = Request.Builder()
                .url(url)
                .post(body)
                .header("X-API-Key", apiKey)
                .build()

            try {
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    Log.d("NetworkService", "Monitor started successfully.")
                    true
                } else {
                    Log.e("NetworkService", "Start failed code: ${response.code}")
                    false
                }
            } catch (e: Exception) {
                Log.e("NetworkService", "Monitor start error: ${e.message}")
                false
            }
        }
    }

    suspend fun stopMonitor(cloudflareTunnelUrl: String, apiKey: String): Boolean {
        return withContext(Dispatchers.IO) {
            val url = "https://$cloudflareTunnelUrl/monitor/stop"
            val body = "".toRequestBody("text/plain".toMediaType())

            val request = Request.Builder()
                .url(url)
                .post(body)
                .header("X-API-Key", apiKey)
                .build()

            try {
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    Log.d("NetworkService", "Monitor stopped successfully.")
                    true
                } else {
                    Log.e("NetworkService", "Stop failed code: ${response.code}")
                    false
                }
            } catch (e: Exception) {
                Log.e("NetworkService", "Monitor stop error: ${e.message}")
                false
            }
        }
    }

    // --- 3. IMAGE LOADING ---
    suspend fun loadImageFromPi(cameraIndex: Int, config: ConnectionConfig): ByteArray? {
        return withContext(Dispatchers.IO) {
            val url = "https://${config.cloudflareTunnelUrl}/image_on_demand/$cameraIndex"
            val request = Request.Builder()
                .url(url)
                .get()
                .header("X-API-Key", config.apiKey)
                .build()
            try {
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    response.body?.bytes()
                } else {
                    Log.e("NetworkService", "Image load failed: ${response.code}")
                    null
                }
            } catch (e: Exception) {
                Log.e("NetworkService", "Image error: ${e.message}")
                null
            }
        }
    }

    // --- 4. IP FETCHING ---
    suspend fun getPublicIpV4(): String? {
        return withContext(Dispatchers.IO) {
            val request = Request.Builder().url("https://api.ipify.org").build()
            try { client.newCall(request).execute().body?.string()?.trim() } catch (e: Exception) { null }
        }
    }

    suspend fun getPublicIpV6(): String? {
        return withContext(Dispatchers.IO) {
            val request = Request.Builder().url("https://api6.ipify.org").build()
            try { client.newCall(request).execute().body?.string()?.trim() } catch (e: Exception) { null }
        }
    }

    // --- 5. ADMIN & SETTINGS ---
    suspend fun checkAdminPassword(password: String, cloudflareTunnelUrl: String, apiKey: String = ""): Boolean {
        return withContext(Dispatchers.IO) {
            val url = "https://$cloudflareTunnelUrl/admin/authenticate"
            val jsonObject = JSONObject().apply { put("password", password) }
            val requestBody = jsonObject.toString().toRequestBody(JSON)
            val key = if (apiKey.isNotEmpty()) apiKey else (currentServerConfig?.apiKey ?: "")
            val request = Request.Builder().url(url).post(requestBody).header("X-API-Key", key).build()
            try {
                val response = client.newCall(request).execute()
                val body = response.body?.string()
                response.isSuccessful && body != null && JSONObject(body).optBoolean("success", false)
            } catch (e: Exception) { false }
        }
    }
}