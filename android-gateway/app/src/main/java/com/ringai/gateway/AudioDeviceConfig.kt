package com.ringai.gateway

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONObject

/**
 * Persistent configuration for the ALSA PCM device used for voice audio injection/capture.
 *
 * Stored in SharedPreferences under "audio_device_config".
 * Supports auto-detected defaults per chipset and manual overrides via WebSocket.
 */
data class AudioDeviceConfig(
    val chipset: ChipsetDetector.SoC,
    val card: Int,
    val deviceTx: Int,
    val deviceRx: Int,
    val probed: Boolean,
    val manualOverride: Boolean
) {
    companion object {
        private const val TAG = "AudioDeviceConfig"
        private const val PREFS_NAME = "audio_device_config"
        private const val KEY_CHIPSET = "chipset"
        private const val KEY_CARD = "card"
        private const val KEY_DEVICE_TX = "device_tx"
        private const val KEY_DEVICE_RX = "device_rx"
        private const val KEY_PROBED = "probed"
        private const val KEY_MANUAL_OVERRIDE = "manual_override"

        /**
         * Returns the hardcoded default ALSA mapping for a given chipset.
         * These are starting points — auto-probing refines them.
         */
        fun defaultForChipset(chipset: ChipsetDetector.SoC): AudioDeviceConfig {
            return when (chipset) {
                ChipsetDetector.SoC.EXYNOS -> AudioDeviceConfig(
                    chipset = chipset,
                    card = 0,
                    deviceTx = 8,   // RDMA8 — voice uplink (TX to modem)
                    deviceRx = -1,  // Not yet mapped for capture
                    probed = false,
                    manualOverride = false
                )
                ChipsetDetector.SoC.QUALCOMM -> AudioDeviceConfig(
                    chipset = chipset,
                    card = 0,
                    deviceTx = 2,   // Common: VoiceMMode1 TX path (varies per device)
                    deviceRx = -1,
                    probed = false,
                    manualOverride = false
                )
                ChipsetDetector.SoC.MEDIATEK -> AudioDeviceConfig(
                    chipset = chipset,
                    card = 0,
                    deviceTx = 2,   // Common: Voice_MD1 or Voice_Voip_BT (varies per device)
                    deviceRx = -1,
                    probed = false,
                    manualOverride = false
                )
                ChipsetDetector.SoC.UNKNOWN -> AudioDeviceConfig(
                    chipset = chipset,
                    card = 0,
                    deviceTx = 0,
                    deviceRx = -1,
                    probed = false,
                    manualOverride = false
                )
            }
        }

        /** Loads persisted config, or returns null if none saved. */
        fun load(context: Context): AudioDeviceConfig? {
            val prefs = prefs(context)
            if (!prefs.contains(KEY_CHIPSET)) return null

            return try {
                AudioDeviceConfig(
                    chipset = ChipsetDetector.SoC.valueOf(prefs.getString(KEY_CHIPSET, "UNKNOWN")!!),
                    card = prefs.getInt(KEY_CARD, 0),
                    deviceTx = prefs.getInt(KEY_DEVICE_TX, 0),
                    deviceRx = prefs.getInt(KEY_DEVICE_RX, -1),
                    probed = prefs.getBoolean(KEY_PROBED, false),
                    manualOverride = prefs.getBoolean(KEY_MANUAL_OVERRIDE, false)
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load audio config: ${e.message}")
                null
            }
        }

        /** Clears all persisted audio device config. */
        fun clear(context: Context) {
            prefs(context).edit().clear().apply()
            Log.i(TAG, "Audio device config cleared")
        }

        private fun prefs(context: Context): SharedPreferences {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    /** Persists this config to SharedPreferences. */
    fun save(context: Context) {
        prefs(context).edit()
            .putString(KEY_CHIPSET, chipset.name)
            .putInt(KEY_CARD, card)
            .putInt(KEY_DEVICE_TX, deviceTx)
            .putInt(KEY_DEVICE_RX, deviceRx)
            .putBoolean(KEY_PROBED, probed)
            .putBoolean(KEY_MANUAL_OVERRIDE, manualOverride)
            .apply()
        Log.i(TAG, "Saved: $this")
    }

    /** Serializes to JSON for WebSocket responses. */
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("chipset", chipset.name)
            put("card", card)
            put("device_tx", deviceTx)
            put("device_rx", deviceRx)
            put("probed", probed)
            put("manual_override", manualOverride)
        }
    }
}
