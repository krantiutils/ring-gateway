package com.ringai.gateway

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Probes ALSA PCM devices on the device via root shell to find voice uplink/downlink paths.
 *
 * Reads /proc/asound/pcm and searches for chipset-specific voice PCM device names.
 * Requires root access (su).
 *
 * /proc/asound/pcm format:
 *   CC-DD: description : description : playback N : capture N
 *   e.g. "00-08: Samsung RDMA8 : : playback 1"
 */
class AlsaProber(
    private val context: Context,
    private val onLog: (String) -> Unit
) {
    companion object {
        private const val TAG = "AlsaProber"
    }

    /**
     * Parsed representation of a single /proc/asound/pcm line.
     */
    data class PcmDevice(
        val card: Int,
        val device: Int,
        val name: String,
        val hasPlayback: Boolean,
        val hasCapture: Boolean,
        val rawLine: String
    )

    /**
     * Reads /proc/asound/pcm via su and returns all parsed PCM devices.
     * Returns empty list on failure (no root, file not found, etc).
     */
    fun listPcmDevices(): List<PcmDevice> {
        val raw = execRoot("cat /proc/asound/pcm")
        if (raw == null) {
            onLog("[PROBE] Failed to read /proc/asound/pcm — root access required")
            return emptyList()
        }

        val devices = mutableListOf<PcmDevice>()
        for (line in raw.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            val parsed = parsePcmLine(trimmed)
            if (parsed != null) {
                devices.add(parsed)
            } else {
                Log.w(TAG, "Unparseable PCM line: $trimmed")
            }
        }

        onLog("[PROBE] Found ${devices.size} PCM devices")
        return devices
    }

    /**
     * Probes for voice uplink (TX) device based on chipset type.
     * Returns the best candidate PcmDevice, or null if none found.
     */
    fun findVoiceTxDevice(chipset: ChipsetDetector.SoC): PcmDevice? {
        val devices = listPcmDevices()
        if (devices.isEmpty()) return null

        val candidates = when (chipset) {
            ChipsetDetector.SoC.EXYNOS -> findExynosTx(devices)
            ChipsetDetector.SoC.QUALCOMM -> findQualcommTx(devices)
            ChipsetDetector.SoC.MEDIATEK -> findMediatekTx(devices)
            ChipsetDetector.SoC.UNKNOWN -> {
                onLog("[PROBE] Unknown chipset — trying all patterns")
                findExynosTx(devices) + findQualcommTx(devices) + findMediatekTx(devices)
            }
        }

        if (candidates.isEmpty()) {
            onLog("[PROBE] No voice TX candidates found for $chipset")
            logAllDevices(devices)
            return null
        }

        val best = candidates.first()
        onLog("[PROBE] Voice TX candidate: card=${best.card} device=${best.device} name='${best.name}'")
        if (candidates.size > 1) {
            onLog("[PROBE] ${candidates.size - 1} additional candidates found")
            candidates.drop(1).forEach {
                onLog("[PROBE]   alt: card=${it.card} device=${it.device} name='${it.name}'")
            }
        }

        return best
    }

    /**
     * Probes for voice downlink (RX / capture) device based on chipset type.
     * Returns the best candidate PcmDevice, or null if none found.
     */
    fun findVoiceRxDevice(chipset: ChipsetDetector.SoC): PcmDevice? {
        val devices = listPcmDevices()
        if (devices.isEmpty()) return null

        val candidates = when (chipset) {
            ChipsetDetector.SoC.EXYNOS -> findExynosRx(devices)
            ChipsetDetector.SoC.QUALCOMM -> findQualcommRx(devices)
            ChipsetDetector.SoC.MEDIATEK -> findMediatekRx(devices)
            ChipsetDetector.SoC.UNKNOWN -> {
                findExynosRx(devices) + findQualcommRx(devices) + findMediatekRx(devices)
            }
        }

        if (candidates.isEmpty()) {
            onLog("[PROBE] No voice RX candidates found for $chipset")
            return null
        }

        val best = candidates.first()
        onLog("[PROBE] Voice RX candidate: card=${best.card} device=${best.device} name='${best.name}'")
        return best
    }

    /**
     * Runs full auto-probe: detects chipset, finds TX and RX devices, and returns a config.
     * Falls back to chipset defaults if probing fails.
     */
    fun autoProbe(): AudioDeviceConfig {
        val chipset = ChipsetDetector.detect()
        onLog("[PROBE] Auto-probing for chipset: $chipset")

        val defaults = AudioDeviceConfig.defaultForChipset(chipset)

        val txDevice = findVoiceTxDevice(chipset)
        val rxDevice = findVoiceRxDevice(chipset)

        val config = defaults.copy(
            card = txDevice?.card ?: defaults.card,
            deviceTx = txDevice?.device ?: defaults.deviceTx,
            deviceRx = rxDevice?.device ?: defaults.deviceRx,
            probed = txDevice != null
        )

        if (txDevice != null) {
            onLog("[PROBE] Auto-probe succeeded: card=${config.card} tx=${config.deviceTx} rx=${config.deviceRx}")
        } else {
            onLog("[PROBE] Auto-probe failed, using defaults: card=${config.card} tx=${config.deviceTx}")
        }

        return config
    }

    // --- Chipset-specific TX search patterns ---

    private fun findExynosTx(devices: List<PcmDevice>): List<PcmDevice> {
        // Samsung Exynos ABOX: look for RDMA8 (device 8) — playback to voice uplink
        val patterns = listOf("rdma8", "rdma 8", "spus out8")
        return devices.filter { d ->
            d.hasPlayback && patterns.any { d.name.lowercase().contains(it) }
        }.ifEmpty {
            // Fallback: device 8 on card 0 is the known Exynos RDMA8
            devices.filter { it.card == 0 && it.device == 8 && it.hasPlayback }
        }
    }

    private fun findQualcommTx(devices: List<PcmDevice>): List<PcmDevice> {
        // Qualcomm ADSP: VoiceMMode1, Voice Tx, or voice uplink playback path
        val patterns = listOf("voicemmode1", "voice tx", "voice_tx", "voice mmode1")
        return devices.filter { d ->
            d.hasPlayback && patterns.any { d.name.lowercase().contains(it) }
        }
    }

    private fun findMediatekTx(devices: List<PcmDevice>): List<PcmDevice> {
        // MediaTek AFE: Voice_MD1, Voice_Voip_BT, or voice uplink
        val patterns = listOf("voice_md1", "voice_voip", "voice md1", "voice_ul", "voice ul")
        return devices.filter { d ->
            d.hasPlayback && patterns.any { d.name.lowercase().contains(it) }
        }
    }

    // --- Chipset-specific RX search patterns ---

    private fun findExynosRx(devices: List<PcmDevice>): List<PcmDevice> {
        // Exynos: WDMA for capture from voice downlink
        val patterns = listOf("wdma", "sifs")
        return devices.filter { d ->
            d.hasCapture && patterns.any { d.name.lowercase().contains(it) }
        }
    }

    private fun findQualcommRx(devices: List<PcmDevice>): List<PcmDevice> {
        val patterns = listOf("voicemmode1", "voice rx", "voice_rx", "voice mmode1")
        return devices.filter { d ->
            d.hasCapture && patterns.any { d.name.lowercase().contains(it) }
        }
    }

    private fun findMediatekRx(devices: List<PcmDevice>): List<PcmDevice> {
        val patterns = listOf("voice_md1", "voice_dl", "voice dl")
        return devices.filter { d ->
            d.hasCapture && patterns.any { d.name.lowercase().contains(it) }
        }
    }

    // --- Utility ---

    private fun logAllDevices(devices: List<PcmDevice>) {
        onLog("[PROBE] All PCM devices:")
        devices.forEach { d ->
            val type = buildString {
                if (d.hasPlayback) append("P")
                if (d.hasCapture) append("C")
            }
            onLog("[PROBE]   ${d.card}-${String.format("%02d", d.device)}: [$type] ${d.name}")
        }
    }

    /**
     * Parses a /proc/asound/pcm line.
     *
     * Format: "CC-DD: name : subname : playback N : capture N"
     * The card-device prefix is always present. The rest is colon-separated.
     */
    private fun parsePcmLine(line: String): PcmDevice? {
        // Parse "CC-DD" prefix
        val colonIdx = line.indexOf(':')
        if (colonIdx < 0) return null

        val prefix = line.substring(0, colonIdx).trim()
        val dashIdx = prefix.indexOf('-')
        if (dashIdx < 0) return null

        val card = prefix.substring(0, dashIdx).toIntOrNull() ?: return null
        val device = prefix.substring(dashIdx + 1).toIntOrNull() ?: return null

        val rest = line.substring(colonIdx + 1)
        val segments = rest.split(':').map { it.trim() }

        val name = segments.firstOrNull() ?: ""
        val hasPlayback = segments.any { it.lowercase().startsWith("playback") }
        val hasCapture = segments.any { it.lowercase().startsWith("capture") }

        return PcmDevice(card, device, name, hasPlayback, hasCapture, line)
    }

    /**
     * Executes a command via su (root) and returns stdout, or null on failure.
     */
    private fun execRoot(command: String): String? {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val stdout = BufferedReader(InputStreamReader(process.inputStream)).readText()
            val stderr = BufferedReader(InputStreamReader(process.errorStream)).readText()
            val exitCode = process.waitFor()

            if (exitCode != 0) {
                Log.w(TAG, "Root command failed ($exitCode): $command\nstderr: $stderr")
                onLog("[PROBE] Root command failed ($exitCode): $command")
                return null
            }

            stdout
        } catch (e: Exception) {
            Log.e(TAG, "Failed to exec root command: $command", e)
            onLog("[PROBE] Root exec failed: ${e.message}")
            null
        }
    }
}
