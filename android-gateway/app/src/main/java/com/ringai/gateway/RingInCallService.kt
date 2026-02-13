package com.ringai.gateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.DisconnectCause
import android.telecom.InCallService
import android.telecom.VideoProfile
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * InCallService implementation that provides programmatic call control.
 *
 * Android's InCallService is the only way to get actual Call objects with
 * disconnect/hold/answer/DTMF capabilities. The system binds this service
 * when the app is the default dialer and a call is active.
 *
 * Other components access calls through the companion object's static methods.
 * This works because the service and GatewayService run in the same process.
 *
 * Thread safety: activeCalls and listeners use CopyOnWriteArrayList because
 * InCallService callbacks fire on the main thread, but GatewayService calls
 * control methods from OkHttp's background reader thread.
 */
class RingInCallService : InCallService() {

    companion object {
        private const val TAG = "RingInCallService"
        private const val INCOMING_CHANNEL_ID = "ring_ai_incoming_call"
        private const val INCOMING_NOTIFICATION_ID = 2
        private const val DTMF_TONE_DURATION_MS = 150L

        private val activeCalls = CopyOnWriteArrayList<Call>()
        private val listeners = CopyOnWriteArrayList<CallControlListener>()
        private val mainHandler = Handler(Looper.getMainLooper())
        private val pendingDtmfRunnables = mutableListOf<Runnable>()

        var lastDisconnectCause: DisconnectCause? = null
            private set

        var lastCallDurationMs: Long = 0L
            private set

        val calls: List<Call> get() = activeCalls.toList()

        val activeCall: Call?
            get() = activeCalls.lastOrNull { it.state != Call.STATE_DISCONNECTED }

        fun addListener(listener: CallControlListener) {
            listeners.add(listener)
        }

        fun removeListener(listener: CallControlListener) {
            listeners.remove(listener)
        }

        fun hangup(): Boolean {
            val call = activeCall ?: return false
            call.disconnect()
            return true
        }

        fun hold(): Boolean {
            val call = activeCall ?: return false
            if (!call.details.can(Call.Details.CAPABILITY_HOLD)) {
                Log.w(TAG, "Call does not support hold")
                return false
            }
            call.hold()
            return true
        }

        fun unhold(): Boolean {
            val call = activeCall ?: return false
            if (!call.details.can(Call.Details.CAPABILITY_HOLD)) {
                Log.w(TAG, "Call does not support unhold")
                return false
            }
            call.unhold()
            return true
        }

        fun answer(): Boolean {
            val call = activeCalls.lastOrNull { it.state == Call.STATE_RINGING } ?: return false
            call.answer(VideoProfile.STATE_AUDIO_ONLY)
            return true
        }

        fun reject(): Boolean {
            val call = activeCalls.lastOrNull { it.state == Call.STATE_RINGING } ?: return false
            call.reject(false, null)
            return true
        }

        /**
         * Send a single DTMF digit. The tone is played for DTMF_TONE_DURATION_MS
         * then stopped automatically. Guards against call disconnection between
         * play and stop.
         */
        fun sendDtmf(digit: Char): Boolean {
            val call = activeCall ?: return false
            if (digit !in "0123456789*#ABCD") {
                Log.w(TAG, "Invalid DTMF digit: $digit")
                return false
            }
            call.playDtmfTone(digit)
            val stopRunnable = Runnable {
                if (call in activeCalls) {
                    call.stopDtmfTone()
                }
            }
            synchronized(pendingDtmfRunnables) { pendingDtmfRunnables.add(stopRunnable) }
            mainHandler.postDelayed(stopRunnable, DTMF_TONE_DURATION_MS)
            return true
        }

        /**
         * Send a sequence of DTMF digits with proper inter-digit timing.
         * Each digit is played for DTMF_TONE_DURATION_MS with a 100ms gap.
         * Uses a valid-digit counter so skipped invalid chars don't create gaps.
         */
        fun sendDtmfSequence(digits: String): Boolean {
            val call = activeCall ?: return false
            val interDigitDelay = DTMF_TONE_DURATION_MS + 100L
            var validIndex = 0

            for (digit in digits) {
                if (digit !in "0123456789*#ABCD") {
                    Log.w(TAG, "Skipping invalid DTMF digit: $digit")
                    continue
                }
                val startDelay = validIndex * interDigitDelay
                val playRunnable = Runnable {
                    if (call in activeCalls) {
                        call.playDtmfTone(digit)
                    }
                }
                val stopRunnable = Runnable {
                    if (call in activeCalls) {
                        call.stopDtmfTone()
                    }
                }
                synchronized(pendingDtmfRunnables) {
                    pendingDtmfRunnables.add(playRunnable)
                    pendingDtmfRunnables.add(stopRunnable)
                }
                mainHandler.postDelayed(playRunnable, startDelay)
                mainHandler.postDelayed(stopRunnable, startDelay + DTMF_TONE_DURATION_MS)
                validIndex++
            }
            return validIndex > 0
        }

        fun getCallDurationMs(): Long {
            val call = activeCall ?: return lastCallDurationMs
            val connectTime = call.details.connectTimeMillis
            if (connectTime <= 0) return 0L
            return System.currentTimeMillis() - connectTime
        }

        fun getCallState(): Int? {
            return activeCall?.state
        }

        fun getCallStateString(): String {
            return when (activeCall?.state) {
                Call.STATE_NEW -> "NEW"
                Call.STATE_DIALING -> "DIALING"
                Call.STATE_RINGING -> "RINGING"
                Call.STATE_HOLDING -> "HOLDING"
                Call.STATE_ACTIVE -> "ACTIVE"
                Call.STATE_DISCONNECTED -> "DISCONNECTED"
                Call.STATE_CONNECTING -> "CONNECTING"
                Call.STATE_DISCONNECTING -> "DISCONNECTING"
                Call.STATE_SELECT_PHONE_ACCOUNT -> "SELECT_PHONE_ACCOUNT"
                Call.STATE_PULLING_CALL -> "PULLING"
                null -> "NO_CALL"
                else -> "UNKNOWN"
            }
        }

        private fun cancelPendingDtmfRunnables() {
            synchronized(pendingDtmfRunnables) {
                for (runnable in pendingDtmfRunnables) {
                    mainHandler.removeCallbacks(runnable)
                }
                pendingDtmfRunnables.clear()
            }
        }
    }

    interface CallControlListener {
        fun onCallAdded(call: Call)
        fun onCallRemoved(call: Call, disconnectCause: DisconnectCause?, durationMs: Long)
        fun onCallStateChanged(call: Call, state: Int)
    }

    private val callCallbacks = mutableMapOf<Call, Call.Callback>()

    override fun onCreate() {
        super.onCreate()
        createIncomingCallChannel()
        Log.d(TAG, "RingInCallService created")
    }

    override fun onCallAdded(call: Call) {
        Log.d(TAG, "Call added: handle=${call.details.handle}, state=${call.state}")
        activeCalls.add(call)

        val callback = object : Call.Callback() {
            override fun onStateChanged(call: Call, state: Int) {
                Log.d(TAG, "Call state changed: $state")
                for (listener in listeners) {
                    listener.onCallStateChanged(call, state)
                }
            }
        }
        callCallbacks[call] = callback
        call.registerCallback(callback)

        if (call.state == Call.STATE_RINGING) {
            showIncomingCallNotification(call)
        }
        launchInCallActivity(call)

        for (listener in listeners) {
            listener.onCallAdded(call)
        }
    }

    override fun onCallRemoved(call: Call) {
        Log.d(TAG, "Call removed")

        // Capture duration BEFORE removing call from activeCalls
        val connectTime = call.details.connectTimeMillis
        val durationMs = if (connectTime > 0) {
            System.currentTimeMillis() - connectTime
        } else {
            0L
        }
        lastCallDurationMs = durationMs

        val disconnectCause = call.details.disconnectCause
        lastDisconnectCause = disconnectCause

        cancelPendingDtmfRunnables()
        callCallbacks.remove(call)?.let { call.unregisterCallback(it) }
        activeCalls.remove(call)
        cancelIncomingCallNotification()

        for (listener in listeners) {
            listener.onCallRemoved(call, disconnectCause, durationMs)
        }
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        Log.d(TAG, "Audio state changed: route=${audioState?.route}")
    }

    private fun showIncomingCallNotification(call: Call) {
        val handle = call.details.handle
        val callerNumber = handle?.schemeSpecificPart ?: "Unknown"

        val fullScreenIntent = Intent(this, InCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this, 0, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val answerIntent = Intent(this, InCallActivity::class.java).apply {
            action = InCallActivity.ACTION_ANSWER
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val answerPendingIntent = PendingIntent.getActivity(
            this, 1, answerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val rejectIntent = Intent(this, InCallActivity::class.java).apply {
            action = InCallActivity.ACTION_REJECT
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val rejectPendingIntent = PendingIntent.getActivity(
            this, 2, rejectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(this, INCOMING_CHANNEL_ID)
            .setContentTitle("Incoming Call")
            .setContentText(callerNumber)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .addAction(
                Notification.Action.Builder(
                    null, "Answer", answerPendingIntent
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    null, "Reject", rejectPendingIntent
                ).build()
            )
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_CALL)
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(INCOMING_NOTIFICATION_ID, notification)
    }

    private fun cancelIncomingCallNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.cancel(INCOMING_NOTIFICATION_ID)
    }

    private fun launchInCallActivity(call: Call) {
        val intent = Intent(this, InCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(intent)
    }

    private fun createIncomingCallChannel() {
        val channel = NotificationChannel(
            INCOMING_CHANNEL_ID,
            "Incoming Calls",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Incoming call alerts"
            setSound(null, null)
            enableVibration(true)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
}
