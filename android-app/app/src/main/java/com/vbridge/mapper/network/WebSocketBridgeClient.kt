package com.vbridge.mapper.network

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED
}

class WebSocketBridgeClient(private val context: Context) {

    private val prefs = context.getSharedPreferences("vbridge_network", Context.MODE_PRIVATE)

    var host: String
        get() = prefs.getString(KEY_HOST, DEFAULT_HOST) ?: DEFAULT_HOST
        set(value) = prefs.edit().putString(KEY_HOST, value.trim()).apply()

    var port: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    private val _connectionState = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionState: StateFlow<ConnectionStatus> = _connectionState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var isUserExplicitlyClosed = false

    private val client = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    var onSyncRequested: (() -> Unit)? = null

    fun connect() {
        isUserExplicitlyClosed = false
        reconnectJob?.cancel()

        if (_connectionState.value == ConnectionStatus.CONNECTED) return

        _connectionState.value = ConnectionStatus.CONNECTING
        val wsUrl = "ws://$host:$port"
        Log.i(TAG, "Connecting to WebSocket: $wsUrl")

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "🟢 WebSocket Connected to $host:$port")
                _connectionState.value = ConnectionStatus.CONNECTED
                onSyncRequested?.invoke()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "📩 WS Message: $text")
                try {
                    val obj = JSONObject(text)
                    if (obj.optString("request") == "SYNC_ACTIVE_PROFILE") {
                        Log.i(TAG, "Windows requested active profile sync")
                        onSyncRequested?.invoke()
                    }
                } catch (_: Exception) {}
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WS Closing: $code / $reason")
                _connectionState.value = ConnectionStatus.DISCONNECTED
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WS Closed: $code / $reason")
                _connectionState.value = ConnectionStatus.DISCONNECTED
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "🔴 WS Failure: ${t.message}")
                _connectionState.value = ConnectionStatus.DISCONNECTED
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (isUserExplicitlyClosed) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(3000)
            if (!isUserExplicitlyClosed && _connectionState.value != ConnectionStatus.CONNECTED) {
                Log.d(TAG, "Attempting auto-reconnect to ws://$host:$port...")
                connect()
            }
        }
    }

    fun disconnect() {
        isUserExplicitlyClosed = true
        reconnectJob?.cancel()
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        _connectionState.value = ConnectionStatus.DISCONNECTED
    }

    fun sendProfileActivated(
        presetId: Int,
        bindingId: Int,
        presetName: String,
        bindingName: String,
        packageName: String,
        controllerType: String,
        via: String,
        path: String,
        profileJson: JSONObject? = null
    ) {
        val payload = JSONObject().apply {
            put("event", "PROFILE_ACTIVATED")
            put("preset_id", presetId)
            put("keybinding_id", bindingId)
            put("preset_name", presetName)
            put("binding_name", bindingName)
            put("package", packageName)
            put("controller_type", controllerType)
            put("via", via)
            put("path", path)
            if (profileJson != null) {
                put("profile", profileJson)
            }
            put("timestamp", System.currentTimeMillis())
        }.toString()

        sendMessage(payload)
    }

    fun sendLiveKeyUpdate(key: String, x: Int, y: Int) {
        val payload = JSONObject().apply {
            put("event", "LIVE_KEY_UPDATE")
            put("key", key)
            put("x", x)
            put("y", y)
            put("timestamp", System.currentTimeMillis())
        }.toString()

        sendMessage(payload)
    }

    fun sendLiveCameraUpdate(pivotX: Int, pivotY: Int, resetRadius: Int = 260) {
        val payload = JSONObject().apply {
            put("event", "LIVE_CAMERA_UPDATE")
            put("pivot_x", pivotX)
            put("pivot_y", pivotY)
            put("reset_radius", resetRadius)
            put("timestamp", System.currentTimeMillis())
        }.toString()

        sendMessage(payload)
    }

    fun sendLiveJoystickUpdate(centerX: Int, centerY: Int, radius: Int = 140) {
        val payload = JSONObject().apply {
            put("event", "LIVE_JOYSTICK_UPDATE")
            put("center_x", centerX)
            put("center_y", centerY)
            put("radius", radius)
            put("timestamp", System.currentTimeMillis())
        }.toString()

        sendMessage(payload)
    }

    private fun sendMessage(json: String) {
        val ws = webSocket
        if (ws != null && _connectionState.value == ConnectionStatus.CONNECTED) {
            ws.send(json)
        } else {
            Log.d(TAG, "Message queued/skipped - WS not connected: $json")
        }
    }

    companion object {
        private const val TAG = "WebSocketBridgeClient"
        const val DEFAULT_HOST = "127.0.0.1"
        const val DEFAULT_PORT = 8889
        private const val KEY_HOST = "ws_host"
        private const val KEY_PORT = "ws_port"
    }
}
