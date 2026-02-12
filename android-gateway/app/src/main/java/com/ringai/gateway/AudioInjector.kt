package com.ringai.gateway

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Injects audio into a live cellular call via root access to ALSA PCM device.
 *
 * On Samsung Exynos (ABOX), during a CP call the audio HAL routes SPUS OUT8 → SIFS5
 * (TX mixer). Writing PCM to RDMA8 (card 0, device 8) sends audio directly into the
 * voice call uplink, bypassing Android's audio policy.
 *
 * Requires: rooted device with su binary, tinyplay_arm64 in assets.
 */
class AudioInjector(
    private val context: Context,
    private val onLog: (String) -> Unit
) {
    companion object {
        private const val TAG = "AudioInjector"
        private const val TINYPLAY_ASSET = "tinyplay_arm64"
        private const val TINYPLAY_BIN = "tinyplay"
        private const val CARD = 0
        private const val DEVICE = 8
    }

    private var tinyplayPath: String? = null
    private var currentProcess: Process? = null

    @Volatile
    var isPlaying = false
        private set

    /**
     * Extracts tinyplay binary from assets to app's files dir on first call.
     * Returns path to the executable, or null on failure.
     */
    fun ensureTinyplay(): String? {
        tinyplayPath?.let { return it }

        val outFile = File(context.filesDir, TINYPLAY_BIN)
        if (outFile.exists() && outFile.canExecute()) {
            tinyplayPath = outFile.absolutePath
            return tinyplayPath
        }

        return try {
            context.assets.open(TINYPLAY_ASSET).use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
            outFile.setExecutable(true, false)
            tinyplayPath = outFile.absolutePath
            onLog("[AUDIO] Extracted tinyplay to ${outFile.absolutePath}")
            tinyplayPath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract tinyplay", e)
            onLog("[ERROR] Failed to extract tinyplay: ${e.message}")
            null
        }
    }

    /**
     * Plays a WAV file through the voice call uplink via root ALSA access.
     * @param wavPath absolute path to a WAV file on the device filesystem
     * @param onComplete callback when playback finishes (success or failure)
     */
    fun playFile(wavPath: String, onComplete: ((Boolean) -> Unit)? = null) {
        stop()

        val binPath = ensureTinyplay()
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

        Thread {
            try {
                val cmd = "su -c '$binPath $wavPath -D $CARD -d $DEVICE'"
                onLog("[AUDIO] Executing: $cmd")

                val process = Runtime.getRuntime().exec(arrayOf("su", "-c",
                    "$binPath $wavPath -D $CARD -d $DEVICE"))
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
}
