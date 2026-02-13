package com.ringai.gateway

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Injects audio into a live cellular call via root access to ALSA PCM device.
 *
 * Chipset-agnostic: uses [ChipsetDetector] and [AlsaProber] to determine the correct
 * ALSA card and device at runtime, falling back to per-chipset defaults.
 *
 * Supports three tinyalsa binaries:
 * - tinyplay: write PCM to ALSA device (audio injection into voice TX)
 * - tinycap:  read PCM from ALSA device (voice RX capture)
 * - tinymix:  query/set ALSA mixer controls (probing and path setup)
 *
 * Requires: rooted device with su binary, tinyalsa binaries in assets.
 */
class AudioInjector(
    private val context: Context,
    private val onLog: (String) -> Unit
) {
    companion object {
        private const val TAG = "AudioInjector"

        private const val TINYPLAY_ASSET = "tinyplay_arm64"
        private const val TINYCAP_ASSET = "tinycap_arm64"
        private const val TINYMIX_ASSET = "tinymix_arm64"

        private const val TINYPLAY_BIN = "tinyplay"
        private const val TINYCAP_BIN = "tinycap"
        private const val TINYMIX_BIN = "tinymix"
    }

    private var tinyplayPath: String? = null
    private var tinycapPath: String? = null
    private var tinymixPath: String? = null

    private var currentProcess: Process? = null
    private var deviceConfig: AudioDeviceConfig? = null

    @Volatile
    var isPlaying = false
        private set

    /**
     * Extracts all tinyalsa binaries from assets to app's files dir on first call.
     * Returns path to tinyplay executable, or null on failure.
     */
    fun ensureBinaries(): String? {
        tinyplayPath = extractBinary(TINYPLAY_ASSET, TINYPLAY_BIN)
        tinycapPath = extractBinary(TINYCAP_ASSET, TINYCAP_BIN)
        tinymixPath = extractBinary(TINYMIX_ASSET, TINYMIX_BIN)

        if (tinyplayPath == null) {
            onLog("[ERROR] Failed to extract tinyplay — audio injection will not work")
        }
        return tinyplayPath
    }

    /**
     * Backward-compatible alias for [ensureBinaries].
     */
    fun ensureTinyplay(): String? = ensureBinaries()

    /**
     * Initializes audio device config by probing the device.
     * Loads from SharedPreferences if previously probed, otherwise runs auto-probe.
     *
     * Call this once during service startup (after [ensureBinaries]).
     */
    fun initDeviceConfig(): AudioDeviceConfig {
        // If a manual override is saved, always use it
        val saved = AudioDeviceConfig.load(context)
        if (saved != null && saved.manualOverride) {
            onLog("[AUDIO] Using manual override config: card=${saved.card} tx=${saved.deviceTx} rx=${saved.deviceRx}")
            deviceConfig = saved
            return saved
        }

        // If previously probed and chipset matches, reuse
        val currentChipset = ChipsetDetector.detect()
        if (saved != null && saved.probed && saved.chipset == currentChipset) {
            onLog("[AUDIO] Using cached probe result: card=${saved.card} tx=${saved.deviceTx} rx=${saved.deviceRx}")
            deviceConfig = saved
            return saved
        }

        // Auto-probe
        onLog("[AUDIO] Running auto-probe for chipset: $currentChipset")
        val prober = AlsaProber(context, onLog)
        val probed = prober.autoProbe()
        probed.save(context)
        deviceConfig = probed
        return probed
    }

    /**
     * Returns the current device config, running [initDeviceConfig] if needed.
     */
    fun getDeviceConfig(): AudioDeviceConfig {
        return deviceConfig ?: initDeviceConfig()
    }

    /**
     * Applies a manual override for the audio device configuration.
     * Persists to SharedPreferences and takes effect immediately.
     */
    fun setManualOverride(card: Int, deviceTx: Int, deviceRx: Int) {
        val config = AudioDeviceConfig(
            chipset = ChipsetDetector.detect(),
            card = card,
            deviceTx = deviceTx,
            deviceRx = deviceRx,
            probed = false,
            manualOverride = true
        )
        config.save(context)
        deviceConfig = config
        onLog("[AUDIO] Manual override set: card=$card tx=$deviceTx rx=$deviceRx")
    }

    /**
     * Clears any manual override and re-runs auto-probe on next use.
     */
    fun clearOverride() {
        AudioDeviceConfig.clear(context)
        deviceConfig = null
        onLog("[AUDIO] Override cleared — will re-probe on next use")
    }

    /**
     * Plays a WAV file through the voice call uplink via root ALSA access.
     * Uses the detected (or overridden) card and device.
     *
     * @param wavPath absolute path to a WAV file on the device filesystem
     * @param onComplete callback when playback finishes (success or failure)
     */
    fun playFile(wavPath: String, onComplete: ((Boolean) -> Unit)? = null) {
        stop()

        val binPath = tinyplayPath ?: ensureBinaries()
        if (binPath == null) {
            onLog("[ERROR] tinyplay binary not available")
            onComplete?.invoke(false)
            return
        }

        if (!File(wavPath).exists()) {
            onLog("[ERROR] Audio file not found: $wavPath")
            onComplete?.invoke(false)
            return
        }

        val config = getDeviceConfig()
        if (config.deviceTx < 0) {
            onLog("[ERROR] No TX device configured — run auto-probe or set manually")
            onComplete?.invoke(false)
            return
        }

        Thread {
            try {
                val cmd = "$binPath $wavPath -D ${config.card} -d ${config.deviceTx}"
                onLog("[AUDIO] Executing: su -c '$cmd'")
                onLog("[AUDIO] Config: chipset=${config.chipset} card=${config.card} device=${config.deviceTx} " +
                    "(probed=${config.probed}, override=${config.manualOverride})")

                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
                currentProcess = process
                isPlaying = true

                val stdout = process.inputStream.bufferedReader().readText()
                val stderr = process.errorStream.bufferedReader().readText()
                val exitCode = process.waitFor()

                isPlaying = false
                currentProcess = null

                if (stdout.isNotBlank()) onLog("[AUDIO] $stdout")
                if (stderr.isNotBlank()) onLog("[AUDIO] stderr: $stderr")

                val success = exitCode == 0
                if (success) {
                    onLog("[AUDIO] Playback completed successfully")
                } else {
                    onLog("[ERROR] tinyplay exited with code $exitCode")
                }
                onComplete?.invoke(success)
            } catch (e: Exception) {
                Log.e(TAG, "tinyplay execution failed", e)
                onLog("[ERROR] tinyplay failed: ${e.message}")
                isPlaying = false
                currentProcess = null
                onComplete?.invoke(false)
            }
        }.start()
    }

    /**
     * Plays the bundled test audio file.
     */
    fun playTestAudio(onComplete: ((Boolean) -> Unit)? = null) {
        val testFile = File(context.filesDir, "test_audio.wav")
        if (!testFile.exists()) {
            try {
                context.assets.open("test_audio.wav").use { input ->
                    FileOutputStream(testFile).use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: Exception) {
                onLog("[ERROR] Failed to extract test audio: ${e.message}")
                onComplete?.invoke(false)
                return
            }
        }
        playFile(testFile.absolutePath, onComplete)
    }

    fun stop() {
        try {
            currentProcess?.let {
                it.destroy()
                onLog("[AUDIO] Stopped playback")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping tinyplay", e)
        }
        currentProcess = null
        isPlaying = false
    }

    /** Returns the path to tinymix binary, or null if not extracted. */
    fun getTinymixPath(): String? = tinymixPath

    /** Returns the path to tinycap binary, or null if not extracted. */
    fun getTinycapPath(): String? = tinycapPath

    /**
     * Extracts a binary from assets to app's files dir.
     * Returns path if already extracted and executable, or after successful extraction.
     * Returns null on failure.
     */
    private fun extractBinary(assetName: String, outputName: String): String? {
        val outFile = File(context.filesDir, outputName)
        if (outFile.exists() && outFile.canExecute()) {
            return outFile.absolutePath
        }

        return try {
            context.assets.open(assetName).use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
            outFile.setExecutable(true, false)
            onLog("[AUDIO] Extracted $outputName to ${outFile.absolutePath}")
            outFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract $assetName", e)
            onLog("[ERROR] Failed to extract $outputName: ${e.message}")
            null
        }
    }
}
