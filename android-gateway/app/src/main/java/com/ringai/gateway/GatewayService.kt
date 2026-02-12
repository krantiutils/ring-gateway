package com.ringai.gateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.telephony.SmsManager
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.concurrent.TimeUnit

class GatewayService : Service() {

    companion object {
        private const val TAG = "GatewayService"
        private const val CHANNEL_ID = "ring_ai_gateway"
        private const val NOTIFICATION_ID = 1
        private const val RECONNECT_DELAY_MS = 5000L
        private const val HEARTBEAT_INTERVAL_MS = 15000L

        const val ACTION_START = "com.ringai.gateway.START"
        const val ACTION_STOP = "com.ringai.gateway.STOP"
        const val EXTRA_SERVER_URL = "server_url"
    }

    enum class GatewayStatus {
        IDLE, CONNECTING, CONNECTED, DIALING, IN_CALL, PLAYING_AUDIO, DISCONNECTED, ERROR
    }

    inner class LocalBinder : Binder() {
        fun getService(): GatewayService = this@GatewayService
    }

    private val binder = LocalBinder()
    private var httpClient: OkHttpClient? = null
    private var webSocket: WebSocket? = null
    private var serverUrl: String? = null

    private lateinit var callManager: CallManager
    private lateinit var audioInjector: AudioInjector

    var status = GatewayStatus.IDLE
        private set

    var onStatusChange: ((GatewayStatus, String) -> Unit)? = null
    var onLogMessage: ((String) -> Unit)? = null

    private var reconnectRunnable: Runnable? = null
    private var heartbeatRunnable: Runnable? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        callManager = CallManager(this) { state -> onCallStateChanged(state) }
        audioInjector = AudioInjector(this) { msg -> log(msg) }
        audioInjector.ensureTinyplay()
        callManager.startListening()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val url = intent.getStringExtra(EXTRA_SERVER_URL)
                if (url.isNullOrBlank()) {
                    log("[ERROR] No server URL provided")
                    stopSelf()
                    return START_NOT_STICKY
                }
                serverUrl = url
                startForeground(NOTIFICATION_ID, buildNotification("Connecting..."))
                connectWebSocket(url)
            }
            ACTION_STOP -> {
                disconnect()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        disconnect()
        callManager.stopListening()
        audioInjector.stop()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun connectWebSocket(url: String) {
        updateStatus(GatewayStatus.CONNECTING, "Connecting to $url")

        httpClient = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(10, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder().url(url).build()
        webSocket = httpClient!!.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                updateStatus(GatewayStatus.CONNECTED, "Connected to server")
                sendStatus()
                startHeartbeat()
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleCommand(text)
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                log("[WS] Server closing: $code $reason")
                ws.close(1000, null)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                updateStatus(GatewayStatus.DISCONNECTED, "Disconnected: $reason")
                scheduleReconnect()
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                updateStatus(GatewayStatus.DISCONNECTED, "Connection failed: ${t.message}")
                scheduleReconnect()
            }
        })
    }

    private fun disconnect() {
        stopHeartbeat()
        reconnectRunnable?.let { handler.removeCallbacks(it) }
        reconnectRunnable = null
        webSocket?.close(1000, "Client stopping")
        webSocket = null
        httpClient?.dispatcher?.executorService?.shutdown()
        httpClient = null
        updateStatus(GatewayStatus.IDLE, "Stopped")
    }

    private fun scheduleReconnect() {
        stopHeartbeat()
        val url = serverUrl ?: return
        reconnectRunnable = Runnable {
            log("[WS] Reconnecting...")
            connectWebSocket(url)
        }
        handler.postDelayed(reconnectRunnable!!, RECONNECT_DELAY_MS)
    }

    private fun startHeartbeat() {
        stopHeartbeat()
        heartbeatRunnable = object : Runnable {
            override fun run() {
                sendStatus()
                handler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
            }
        }
        handler.postDelayed(heartbeatRunnable!!, HEARTBEAT_INTERVAL_MS)
    }

    private fun stopHeartbeat() {
        heartbeatRunnable?.let { handler.removeCallbacks(it) }
        heartbeatRunnable = null
    }

    private fun handleCommand(text: String) {
        try {
            val json = JSONObject(text)
            val command = json.getString("command")
            val id = json.optString("id", "")

            log("[CMD] Received: $command (id=$id)")

            when (command) {
                "MAKE_CALL" -> {
                    val number = json.getString("number")
                    handleMakeCall(id, number)
                }
                "PLAY_AUDIO" -> {
                    val audioUrl = json.optString("url", "")
                    val audioPath = json.optString("path", "")
                    handlePlayAudio(id, audioUrl, audioPath)
                }
                "HANGUP" -> {
                    handleHangup(id)
                }
                "SEND_SMS" -> {
                    val number = json.getString("number")
                    val message = json.getString("message")
                    handleSendSms(id, number, message)
                }
                "PING" -> {
                    sendResponse(id, "PONG", true)
                }
                else -> {
                    log("[CMD] Unknown command: $command")
                    sendResponse(id, "UNKNOWN_COMMAND", false, "Unknown command: $command")
                }
            }
        } catch (e: Exception) {
            log("[ERROR] Failed to parse command: ${e.message}")
        }
    }

    private fun handleMakeCall(id: String, number: String) {
        updateStatus(GatewayStatus.DIALING, "Dialing $number")
        val success = callManager.dial(number)
        if (success) {
            sendResponse(id, "DIALING", true)
        } else {
            updateStatus(GatewayStatus.ERROR, "Dial failed")
            sendResponse(id, "DIAL_FAILED", false, "Failed to initiate call")
        }
    }

    private fun handlePlayAudio(id: String, audioUrl: String, audioPath: String) {
        if (status != GatewayStatus.IN_CALL) {
            sendResponse(id, "NOT_IN_CALL", false, "No active call")
            return
        }

        updateStatus(GatewayStatus.PLAYING_AUDIO, "Playing audio")

        val wavPath: String = when {
            audioPath.isNotBlank() -> audioPath
            audioUrl.isNotBlank() -> {
                val downloaded = downloadAudio(audioUrl)
                if (downloaded == null) {
                    sendResponse(id, "DOWNLOAD_FAILED", false, "Failed to download audio")
                    updateStatus(GatewayStatus.IN_CALL, "In call")
                    return
                }
                downloaded
            }
            else -> {
                // Play bundled test audio
                val testFile = File(filesDir, "test_audio.wav")
                if (!testFile.exists()) {
                    assets.open("test_audio.wav").use { input ->
                        FileOutputStream(testFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                }
                testFile.absolutePath
            }
        }

        audioInjector.playFile(wavPath) { success ->
            if (success) {
                sendResponse(id, "AUDIO_COMPLETE", true)
            } else {
                sendResponse(id, "AUDIO_FAILED", false, "Playback failed")
            }
            if (status == GatewayStatus.PLAYING_AUDIO) {
                updateStatus(GatewayStatus.IN_CALL, "In call")
            }
        }
        sendResponse(id, "PLAYING", true)
    }

    private fun handleHangup(id: String) {
        audioInjector.stop()
        // Programmatic hangup not possible without InCallService.
        // Log and notify — the call will end when the other party hangs up,
        // or the server can instruct the user.
        log("[CALL] Hangup requested — audio stopped, call must end from dialer")
        sendResponse(id, "HANGUP_REQUESTED", true,
            "Audio stopped. Programmatic hangup requires InCallService registration.")
    }

    @Suppress("DEPRECATION")
    private fun handleSendSms(id: String, number: String, message: String) {
        try {
            val smsManager = SmsManager.getDefault()
            val parts = smsManager.divideMessage(message)
            if (parts.size == 1) {
                smsManager.sendTextMessage(number, null, message, null, null)
            } else {
                smsManager.sendMultipartTextMessage(number, null, parts, null, null)
            }
            log("[SMS] Sent to $number: ${message.take(50)}...")
            sendResponse(id, "SMS_SENT", true)
        } catch (e: Exception) {
            log("[ERROR] SMS failed: ${e.message}")
            sendResponse(id, "SMS_FAILED", false, e.message ?: "Unknown error")
        }
    }

    private fun downloadAudio(url: String): String? {
        return try {
            log("[AUDIO] Downloading from $url")
            val file = File(cacheDir, "audio_${System.currentTimeMillis()}.wav")
            URL(url).openStream().use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
            log("[AUDIO] Downloaded ${file.length()} bytes to ${file.absolutePath}")
            file.absolutePath
        } catch (e: Exception) {
            log("[ERROR] Download failed: ${e.message}")
            null
        }
    }

    private fun onCallStateChanged(state: CallManager.CallState) {
        when (state) {
            CallManager.CallState.OFFHOOK -> {
                updateStatus(GatewayStatus.IN_CALL, "Call connected")
                sendEvent("CALL_CONNECTED")
            }
            CallManager.CallState.IDLE -> {
                if (status == GatewayStatus.IN_CALL || status == GatewayStatus.PLAYING_AUDIO
                    || status == GatewayStatus.DIALING) {
                    audioInjector.stop()
                    updateStatus(
                        if (webSocket != null) GatewayStatus.CONNECTED else GatewayStatus.IDLE,
                        "Call ended"
                    )
                    sendEvent("CALL_ENDED")
                }
            }
            CallManager.CallState.DIALING -> {
                updateStatus(GatewayStatus.DIALING, "Dialing")
            }
            CallManager.CallState.ERROR -> {
                updateStatus(GatewayStatus.ERROR, "Call error")
                sendEvent("CALL_ERROR")
            }
            else -> {}
        }
    }

    private fun sendResponse(id: String, type: String, success: Boolean, message: String? = null) {
        val json = JSONObject().apply {
            put("type", "response")
            put("id", id)
            put("result", type)
            put("success", success)
            message?.let { put("message", it) }
        }
        webSocket?.send(json.toString())
    }

    private fun sendEvent(event: String) {
        val json = JSONObject().apply {
            put("type", "event")
            put("event", event)
            put("status", status.name)
        }
        webSocket?.send(json.toString())
    }

    private fun sendStatus() {
        val json = JSONObject().apply {
            put("type", "heartbeat")
            put("status", status.name)
            put("audioPlaying", audioInjector.isPlaying)
        }
        webSocket?.send(json.toString())
    }

    private fun updateStatus(newStatus: GatewayStatus, message: String) {
        status = newStatus
        log("[STATUS] $newStatus — $message")
        updateNotification("$newStatus: $message")
        handler.post { onStatusChange?.invoke(newStatus, message) }
    }

    private fun log(msg: String) {
        Log.d(TAG, msg)
        handler.post { onLogMessage?.invoke(msg) }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Ring AI Gateway",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Gateway service status"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Ring AI Gateway")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
