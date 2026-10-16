package com.vbridge.mapper.network

import android.content.Context
import android.util.Log
import com.vbridge.mapper.data.model.Preset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

class HostSignalClient(private val context: Context) {

    private val prefs = context.getSharedPreferences("vbridge_network", Context.MODE_PRIVATE)

    var host: String
        get() = prefs.getString(KEY_HOST, DEFAULT_HOST) ?: DEFAULT_HOST
        set(value) = prefs.edit().putString(KEY_HOST, value.trim()).apply()

    var port: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    suspend fun signalPresetActivated(preset: Preset): Result<String> = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("event", "PROFILE_ACTIVATED")
            put("package", preset.info.packageName)
            put("app_name", preset.info.appName)
            put("preset_id", preset.info.presetId)
            put("keybinding_id", preset.info.bindingId)
            put("preset_name", preset.info.name)
            put("path", "/sdcard/VbridgeBind/preset${preset.info.presetId}/keybinding${preset.info.bindingId}/")
            put("timestamp", System.currentTimeMillis())
        }.toString()

        sendJsonPayload(payload)
    }

    suspend fun sendTestPing(): Result<String> = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("event", "PING")
            put("source", "VBridgeMapper_Android")
            put("timestamp", System.currentTimeMillis())
        }.toString()

        sendJsonPayload(payload)
    }

    private fun sendJsonPayload(json: String): Result<String> {
        val targetHost = host
        val targetPort = port
        var socket: Socket? = null
        return try {
            socket = Socket()
            socket.soTimeout = TIMEOUT_MS
            socket.connect(InetSocketAddress(targetHost, targetPort), TIMEOUT_MS)

            val writer = OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)
            writer.write(json + "\n")
            writer.flush()

            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
            val response = try {
                reader.readLine() ?: "ACK"
            } catch (e: Exception) {
                "ACK (No response body)"
            }

            Log.i(TAG, "Sent signal to $targetHost:$targetPort -> Response: $response")
            Result.success("Success: $response")
        } catch (e: Exception) {
            val errMsg = "Failed to connect to Windows host ($targetHost:$targetPort): ${e.message}"
            Log.w(TAG, errMsg)
            Result.failure(Exception(errMsg, e))
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {}
        }
    }

    companion object {
        private const val TAG = "HostSignalClient"
        const val DEFAULT_HOST = "127.0.0.1"
        const val DEFAULT_PORT = 8889
        private const val TIMEOUT_MS = 2500
        private const val KEY_HOST = "windows_host"
        private const val KEY_PORT = "windows_port"
    }
}
