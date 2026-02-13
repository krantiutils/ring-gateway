package com.ringai.gateway

import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.DisconnectCause
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Minimal in-call UI required for the app to qualify as default dialer.
 *
 * Shows caller number, call state, duration timer, and basic controls
 * (answer/reject for incoming, hangup/hold for active calls).
 *
 * This activity is launched by RingInCallService when a call is added.
 * The gateway primarily operates headlessly via WebSocket commands, but
 * this UI is needed for the ROLE_DIALER system requirement.
 */
class InCallActivity : AppCompatActivity(), RingInCallService.CallControlListener {

    companion object {
        private const val TAG = "InCallActivity"
        const val ACTION_ANSWER = "com.ringai.gateway.ACTION_ANSWER"
        const val ACTION_REJECT = "com.ringai.gateway.ACTION_REJECT"
        private const val TIMER_UPDATE_INTERVAL_MS = 1000L
        private const val FINISH_DELAY_MS = 2000L
    }

    private lateinit var callStateLabel: TextView
    private lateinit var callerNumberLabel: TextView
    private lateinit var callDurationLabel: TextView
    private lateinit var incomingButtonsLayout: View
    private lateinit var activeButtonsLayout: View
    private lateinit var answerButton: Button
    private lateinit var rejectButton: Button
    private lateinit var holdButton: Button
    private lateinit var hangupButton: Button

    private val handler = Handler(Looper.getMainLooper())
    private var timerRunnable: Runnable? = null
    private var isHeld = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        allowOnLockScreen()
        setContentView(R.layout.activity_incall)

        callStateLabel = findViewById(R.id.callStateLabel)
        callerNumberLabel = findViewById(R.id.callerNumberLabel)
        callDurationLabel = findViewById(R.id.callDurationLabel)
        incomingButtonsLayout = findViewById(R.id.incomingButtonsLayout)
        activeButtonsLayout = findViewById(R.id.activeButtonsLayout)
        answerButton = findViewById(R.id.answerButton)
        rejectButton = findViewById(R.id.rejectButton)
        holdButton = findViewById(R.id.holdButton)
        hangupButton = findViewById(R.id.hangupButton)

        answerButton.setOnClickListener {
            RingInCallService.answer()
        }
        rejectButton.setOnClickListener {
            RingInCallService.reject()
        }
        holdButton.setOnClickListener {
            if (isHeld) {
                RingInCallService.unhold()
            } else {
                RingInCallService.hold()
            }
        }
        hangupButton.setOnClickListener {
            RingInCallService.hangup()
        }

        RingInCallService.addListener(this)
        handleIntent()
        updateUiFromCallState()
    }

    override fun onDestroy() {
        super.onDestroy()
        RingInCallService.removeListener(this)
        stopTimer()
        handler.removeCallbacksAndMessages(null)
    }

    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent()
    }

    private fun handleIntent() {
        when (intent?.action) {
            ACTION_ANSWER -> {
                RingInCallService.answer()
            }
            ACTION_REJECT -> {
                RingInCallService.reject()
            }
        }
    }

    override fun onCallAdded(call: Call) {
        runOnUiThread { updateUiFromCallState() }
    }

    override fun onCallRemoved(call: Call, disconnectCause: DisconnectCause?) {
        runOnUiThread {
            stopTimer()
            val causeLabel = disconnectCause?.label?.toString() ?: "Ended"
            callStateLabel.text = "Call Ended"
            callerNumberLabel.text = causeLabel
            callDurationLabel.visibility = View.GONE
            incomingButtonsLayout.visibility = View.GONE
            activeButtonsLayout.visibility = View.GONE

            handler.postDelayed({ finish() }, FINISH_DELAY_MS)
        }
    }

    override fun onCallStateChanged(call: Call, state: Int) {
        runOnUiThread { updateUiFromCallState() }
    }

    private fun updateUiFromCallState() {
        val call = RingInCallService.activeCall
        if (call == null) {
            if (RingInCallService.calls.isEmpty()) {
                callStateLabel.text = "No Active Call"
                callerNumberLabel.text = ""
                incomingButtonsLayout.visibility = View.GONE
                activeButtonsLayout.visibility = View.GONE
            }
            return
        }

        val handle = call.details.handle
        val callerNumber = handle?.schemeSpecificPart ?: "Unknown"
        callerNumberLabel.text = callerNumber

        when (call.state) {
            Call.STATE_RINGING -> {
                callStateLabel.text = "Incoming Call"
                incomingButtonsLayout.visibility = View.VISIBLE
                activeButtonsLayout.visibility = View.GONE
                callDurationLabel.visibility = View.GONE
                stopTimer()
            }
            Call.STATE_DIALING, Call.STATE_CONNECTING -> {
                callStateLabel.text = "Dialing..."
                incomingButtonsLayout.visibility = View.GONE
                activeButtonsLayout.visibility = View.VISIBLE
                holdButton.visibility = View.GONE
                callDurationLabel.visibility = View.GONE
                stopTimer()
            }
            Call.STATE_ACTIVE -> {
                callStateLabel.text = "Active Call"
                incomingButtonsLayout.visibility = View.GONE
                activeButtonsLayout.visibility = View.VISIBLE
                holdButton.visibility = View.VISIBLE
                isHeld = false
                holdButton.text = "Hold"
                callDurationLabel.visibility = View.VISIBLE
                startTimer()
            }
            Call.STATE_HOLDING -> {
                callStateLabel.text = "On Hold"
                incomingButtonsLayout.visibility = View.GONE
                activeButtonsLayout.visibility = View.VISIBLE
                holdButton.visibility = View.VISIBLE
                isHeld = true
                holdButton.text = "Resume"
                callDurationLabel.visibility = View.VISIBLE
            }
            Call.STATE_DISCONNECTING -> {
                callStateLabel.text = "Disconnecting..."
                incomingButtonsLayout.visibility = View.GONE
                activeButtonsLayout.visibility = View.GONE
                stopTimer()
            }
            else -> {
                callStateLabel.text = RingInCallService.getCallStateString()
            }
        }
    }

    private fun startTimer() {
        if (timerRunnable != null) return
        timerRunnable = object : Runnable {
            override fun run() {
                val durationMs = RingInCallService.getCallDurationMs()
                val seconds = (durationMs / 1000) % 60
                val minutes = (durationMs / 60000) % 60
                val hours = durationMs / 3600000
                callDurationLabel.text = if (hours > 0) {
                    String.format("%d:%02d:%02d", hours, minutes, seconds)
                } else {
                    String.format("%02d:%02d", minutes, seconds)
                }
                handler.postDelayed(this, TIMER_UPDATE_INTERVAL_MS)
            }
        }
        handler.post(timerRunnable!!)
    }

    private fun stopTimer() {
        timerRunnable?.let { handler.removeCallbacks(it) }
        timerRunnable = null
    }

    @Suppress("DEPRECATION")
    private fun allowOnLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(KeyguardManager::class.java)
            keyguardManager.requestDismissKeyguard(this, null)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
    }
}
