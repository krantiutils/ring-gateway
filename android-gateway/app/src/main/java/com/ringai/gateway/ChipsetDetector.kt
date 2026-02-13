package com.ringai.gateway

import android.os.Build
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Detects the device's SoC family at runtime using multiple heuristic strategies.
 *
 * Detection order:
 * 1. /sys/devices/platform probes (most reliable — checks for chipset-specific HAL nodes)
 * 2. Build.HARDWARE / Build.BOARD (ro.hardware / ro.product.board)
 * 3. /proc/cpuinfo Hardware field (fallback for older kernels)
 *
 * Thread-safe: detection runs once and result is cached.
 */
object ChipsetDetector {

    private const val TAG = "ChipsetDetector"

    enum class SoC {
        EXYNOS,
        QUALCOMM,
        MEDIATEK,
        UNKNOWN
    }

    @Volatile
    private var cached: SoC? = null

    /**
     * Returns detected SoC, running detection on first call and caching the result.
     */
    fun detect(): SoC {
        cached?.let { return it }
        val result = runDetection()
        cached = result
        Log.i(TAG, "Detected SoC: $result (hw=${Build.HARDWARE}, board=${Build.BOARD})")
        return result
    }

    /** Clears cached result, forcing re-detection on next [detect] call. */
    fun invalidate() {
        cached = null
    }

    private fun runDetection(): SoC {
        // Strategy 1: Platform sysfs nodes — most reliable
        sysfsDetect()?.let { return it }

        // Strategy 2: Build properties
        buildPropDetect()?.let { return it }

        // Strategy 3: /proc/cpuinfo Hardware field
        cpuinfoDetect()?.let { return it }

        Log.w(TAG, "Could not determine SoC family")
        return SoC.UNKNOWN
    }

    /**
     * Checks /sys/devices/platform for chipset-specific audio subsystem nodes.
     * These are created by vendor kernel drivers and are definitive.
     */
    private fun sysfsDetect(): SoC? {
        // Samsung Exynos: ABOX audio subsystem
        if (File("/sys/devices/platform").list()?.any { it.startsWith("abox") } == true) {
            Log.d(TAG, "sysfs: found abox* → EXYNOS")
            return SoC.EXYNOS
        }

        val socDir = File("/sys/devices/platform/soc")
        if (socDir.isDirectory) {
            val children = socDir.list() ?: emptyArray()

            // Qualcomm ADSP / audio DSP nodes
            if (children.any { it.contains("qcom") || it.contains("msm") || it.contains("adsp") }) {
                Log.d(TAG, "sysfs: found qcom/msm/adsp in /sys/devices/platform/soc → QUALCOMM")
                return SoC.QUALCOMM
            }

            // MediaTek AFE audio node
            if (children.any { it.contains("mt") && it.contains("afe") }) {
                Log.d(TAG, "sysfs: found mt*afe* in /sys/devices/platform/soc → MEDIATEK")
                return SoC.MEDIATEK
            }
        }

        return null
    }

    /**
     * Matches Build.HARDWARE and Build.BOARD against known chipset identifiers.
     */
    private fun buildPropDetect(): SoC? {
        val hw = Build.HARDWARE.lowercase()
        val board = Build.BOARD.lowercase()

        // Exynos identifiers
        val exynosPatterns = listOf("exynos", "samsungexynos", "universal", "erd")
        if (exynosPatterns.any { hw.contains(it) || board.contains(it) }) {
            Log.d(TAG, "buildProp: matched EXYNOS (hw=$hw, board=$board)")
            return SoC.EXYNOS
        }

        // Qualcomm identifiers
        val qualcommPatterns = listOf("qcom", "qualcomm", "msm", "sdm", "sm", "snapdragon", "kona", "lahaina", "taro", "kalama", "pineapple")
        if (qualcommPatterns.any { hw.contains(it) || board.contains(it) }) {
            Log.d(TAG, "buildProp: matched QUALCOMM (hw=$hw, board=$board)")
            return SoC.QUALCOMM
        }

        // MediaTek identifiers
        val mtPatterns = listOf("mt", "mediatek", "mtk")
        if (mtPatterns.any { hw.contains(it) || board.contains(it) }) {
            Log.d(TAG, "buildProp: matched MEDIATEK (hw=$hw, board=$board)")
            return SoC.MEDIATEK
        }

        return null
    }

    /**
     * Reads /proc/cpuinfo and checks the Hardware field.
     * Some kernels populate this; others don't (especially 4.x+).
     */
    private fun cpuinfoDetect(): SoC? {
        return try {
            val cpuinfo = File("/proc/cpuinfo").readText().lowercase()

            if (cpuinfo.contains("exynos") || cpuinfo.contains("samsung")) {
                Log.d(TAG, "cpuinfo: matched EXYNOS")
                return SoC.EXYNOS
            }
            if (cpuinfo.contains("qualcomm") || cpuinfo.contains("qcom")) {
                Log.d(TAG, "cpuinfo: matched QUALCOMM")
                return SoC.QUALCOMM
            }
            if (cpuinfo.contains("mediatek") || cpuinfo.contains("mt6") || cpuinfo.contains("mt8")) {
                Log.d(TAG, "cpuinfo: matched MEDIATEK")
                return SoC.MEDIATEK
            }

            null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read /proc/cpuinfo: ${e.message}")
            null
        }
    }
}
