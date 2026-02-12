package com.ringai.gateway

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

/**
 * Manages outgoing calls and monitors call state transitions.
 *
 * Uses ACTION_CALL to initiate calls (requires CALL_PHONE permission).
 * Monitors state via TelephonyCallback (API 31+) or PhoneStateListener (legacy).
 */
class CallManager(
    private val context: Context,
    private val onStateChange: (CallState) -> Unit
) {
    enum class CallState {
        IDLE,
        DIALING,
        OFFHOOK,
        RINGING,
        ERROR
    }

    private val telephonyManager =
        context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    private var legacyListener: PhoneStateListener? = null
    private var telephonyCallback: TelephonyCallback? = null
    private var currentState = CallState.IDLE

    fun startListening() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            onStateChange(CallState.ERROR)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // API 31+ uses TelephonyCallback
            val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    handleCallState(state)
                }
            }
            telephonyCallback = callback
            telephonyManager.registerTelephonyCallback(
                context.mainExecutor,
                callback
            )
        } else {
            // API 28-30 uses deprecated PhoneStateListener
            @Suppress("DEPRECATION")
            val listener = object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    handleCallState(state)
                }
            }
            legacyListener = listener
            @Suppress("DEPRECATION")
            telephonyManager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
        }
    }

    fun stopListening() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyCallback?.let { telephonyManager.unregisterTelephonyCallback(it) }
            telephonyCallback = null
        } else {
            @Suppress("DEPRECATION")
            legacyListener?.let {
                telephonyManager.listen(it, PhoneStateListener.LISTEN_NONE)
            }
            legacyListener = null
        }
    }

    fun dial(phoneNumber: String): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            onStateChange(CallState.ERROR)
            return false
        }

        val sanitized = phoneNumber.replace(Regex("[^0-9+*#]"), "")
        if (sanitized.isEmpty()) {
            onStateChange(CallState.ERROR)
            return false
        }

        return try {
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$sanitized")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            currentState = CallState.DIALING
            onStateChange(CallState.DIALING)
            true
        } catch (e: Exception) {
            onStateChange(CallState.ERROR)
            false
        }
    }

    private fun handleCallState(state: Int) {
        val newState = when (state) {
            TelephonyManager.CALL_STATE_IDLE -> CallState.IDLE
            TelephonyManager.CALL_STATE_RINGING -> CallState.RINGING
            TelephonyManager.CALL_STATE_OFFHOOK -> CallState.OFFHOOK
            else -> CallState.IDLE
        }
        if (newState != currentState) {
            currentState = newState
            onStateChange(newState)
        }
    }
}
