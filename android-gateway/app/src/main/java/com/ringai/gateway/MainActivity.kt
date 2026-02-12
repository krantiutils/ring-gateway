package com.ringai.gateway

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.textfield.TextInputEditText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
        private const val PREFS_NAME = "gateway_prefs"
        private const val PREF_SERVER_URL = "server_url"
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.SEND_SMS
        )
    }

    private lateinit var serverUrlInput: TextInputEditText
    private lateinit var phoneNumberInput: TextInputEditText
    private lateinit var connectButton: Button
    private lateinit var testCallButton: Button
    private lateinit var playAudioButton: Button
    private lateinit var stopAudioButton: Button
    private lateinit var clearLogButton: Button
    private lateinit var statusLabel: TextView
    private lateinit var logView: TextView
    private lateinit var logScrollView: ScrollView

    private var gatewayService: GatewayService? = null
    private var serviceBound = false

    // For manual test mode (no server)
    private lateinit var callManager: CallManager
    private lateinit var audioInjector: AudioInjector
    private var inCall = false
    private var dialInitiated = false
    private var hasBeenOffhook = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as GatewayService.LocalBinder).getService()
            gatewayService = service
            serviceBound = true

            service.onStatusChange = { status, message ->
                runOnUiThread {
                    statusLabel.text = "Status: $status — $message"
                    updateButtonStates()
                }
            }
            service.onLogMessage = { msg -> log(msg) }
            updateButtonStates()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            gatewayService = null
            serviceBound = false
            updateButtonStates()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        serverUrlInput = findViewById(R.id.serverUrlInput)
        phoneNumberInput = findViewById(R.id.phoneNumberInput)
        connectButton = findViewById(R.id.connectButton)
        testCallButton = findViewById(R.id.testCallButton)
        playAudioButton = findViewById(R.id.playAudioButton)
        stopAudioButton = findViewById(R.id.stopAudioButton)
        clearLogButton = findViewById(R.id.clearLogButton)
        statusLabel = findViewById(R.id.statusLabel)
        logView = findViewById(R.id.logView)
        logScrollView = logView.parent as ScrollView

        callManager = CallManager(this) { state -> onCallStateChanged(state) }
        audioInjector = AudioInjector(this) { msg -> log(msg) }
        audioInjector.ensureTinyplay()
        callManager.startListening()

        // Restore saved server URL
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        serverUrlInput.setText(prefs.getString(PREF_SERVER_URL, "ws://192.168.1.100:8080/gateway"))

        connectButton.setOnClickListener { onConnectPressed() }
        testCallButton.setOnClickListener { onTestCallPressed() }
        playAudioButton.setOnClickListener { onPlayAudioPressed() }
        stopAudioButton.setOnClickListener { onStopAudioPressed() }
        clearLogButton.setOnClickListener { logView.text = "Ready.\n" }

        requestPermissionsIfNeeded()
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, GatewayService::class.java)
        bindService(intent, serviceConnection, 0)
    }

    override fun onStop() {
        super.onStop()
        if (serviceBound) {
            gatewayService?.onStatusChange = null
            gatewayService?.onLogMessage = null
            unbindService(serviceConnection)
            serviceBound = false
        }
    }

    override fun onResume() {
        super.onResume()
        if (inCall) {
            log("[INFO] Back in app. Press 'Play Audio' when the other person has answered.")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        callManager.stopListening()
        audioInjector.stop()
    }

    private fun onConnectPressed() {
        val service = gatewayService
        if (service != null && service.status != GatewayService.GatewayStatus.IDLE
            && service.status != GatewayService.GatewayStatus.DISCONNECTED) {
            // Disconnect
            val intent = Intent(this, GatewayService::class.java).apply {
                action = GatewayService.ACTION_STOP
            }
            startService(intent)
            connectButton.text = "Connect"
            return
        }

        val url = serverUrlInput.text?.toString()?.trim()
        if (url.isNullOrBlank()) {
            log("[ERROR] Enter a server URL")
            return
        }

        // Save URL
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(PREF_SERVER_URL, url).apply()

        val intent = Intent(this, GatewayService::class.java).apply {
            action = GatewayService.ACTION_START
            putExtra(GatewayService.EXTRA_SERVER_URL, url)
        }
        startForegroundService(intent)
        bindService(Intent(this, GatewayService::class.java), serviceConnection, 0)
        connectButton.text = "Disconnect"

        log("[GW] Starting gateway service → $url")
    }

    private fun onTestCallPressed() {
        val phoneNumber = phoneNumberInput.text?.toString()?.trim()
        if (phoneNumber.isNullOrEmpty()) {
            log("[ERROR] Enter a phone number first")
            return
        }

        if (!hasAllPermissions()) {
            log("[ERROR] Missing permissions — requesting again")
            requestPermissionsIfNeeded()
            return
        }

        dialInitiated = true
        hasBeenOffhook = false
        log("[CALL] Dialing $phoneNumber ...")
        log("[INFO] Dialer will open. Once the other person answers, swipe back and press 'Play Audio'.")
        callManager.dial(phoneNumber)
    }

    private fun onPlayAudioPressed() {
        if (!inCall) {
            log("[ERROR] No active call — dial first")
            return
        }
        log("[AUDIO] Starting root audio injection...")
        audioInjector.playTestAudio { success ->
            runOnUiThread {
                if (success) {
                    log("[RESULT] Audio injection completed — did the other phone hear it?")
                } else {
                    log("[RESULT] Audio injection failed — check root access")
                }
                stopAudioButton.isEnabled = false
            }
        }
        stopAudioButton.isEnabled = true
    }

    private fun onStopAudioPressed() {
        audioInjector.stop()
        stopAudioButton.isEnabled = false
    }

    private fun onCallStateChanged(state: CallManager.CallState) {
        runOnUiThread {
            log("[STATE] ${state.name}")
            statusLabel.text = "Status: ${state.name}"

            when (state) {
                CallManager.CallState.OFFHOOK -> {
                    inCall = true
                    hasBeenOffhook = true
                    playAudioButton.isEnabled = true
                    testCallButton.isEnabled = false
                }
                CallManager.CallState.IDLE -> {
                    if (dialInitiated && !hasBeenOffhook) {
                        log("[INFO] Ignoring spurious IDLE during dial setup")
                        return@runOnUiThread
                    }
                    inCall = false
                    dialInitiated = false
                    hasBeenOffhook = false
                    playAudioButton.isEnabled = false
                    stopAudioButton.isEnabled = false
                    testCallButton.isEnabled = true
                    audioInjector.stop()
                    log("[CALL] Call ended")
                }
                CallManager.CallState.ERROR -> {
                    inCall = false
                    playAudioButton.isEnabled = false
                    stopAudioButton.isEnabled = false
                    testCallButton.isEnabled = true
                    log("[ERROR] Call state error — check permissions")
                }
                else -> {}
            }
        }
    }

    private fun updateButtonStates() {
        val service = gatewayService
        if (service != null && service.status != GatewayService.GatewayStatus.IDLE) {
            connectButton.text = "Disconnect"
        } else {
            connectButton.text = "Connect"
        }
    }

    private fun log(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        runOnUiThread {
            logView.append("[$timestamp] $message\n")
            logScrollView.post { logScrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun hasAllPermissions(): Boolean {
        return REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissionsIfNeeded() {
        val missing = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            log("[PERM] Requesting: ${missing.joinToString()}")
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERMISSION_REQUEST_CODE)
        } else {
            log("[PERM] All permissions granted")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val denied = permissions.zip(grantResults.toList())
                .filter { it.second != PackageManager.PERMISSION_GRANTED }
                .map { it.first }
            if (denied.isEmpty()) {
                log("[PERM] All permissions granted")
            } else {
                log("[PERM] DENIED: ${denied.joinToString()}")
            }
        }
    }
}
