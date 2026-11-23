package com.tabletagent.local.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Haptic feedback utility for Tablet Agent background completions,
 * destructive action approvals, and UI micro-interactions.
 */
object HapticFeedbackHelper {

    fun vibrateSuccess(context: Context) {
        // Double gentle tap for successful task completion
        val pattern = longArrayOf(0, 70, 60, 90)
        val amplitudes = intArrayOf(0, 180, 0, 255)
        vibratePattern(context, pattern, amplitudes)
    }

    fun vibrateAlert(context: Context) {
        // Triple distinct pulse for approval required
        val pattern = longArrayOf(0, 100, 70, 100, 70, 120)
        val amplitudes = intArrayOf(0, 200, 0, 220, 0, 255)
        vibratePattern(context, pattern, amplitudes)
    }

    fun vibrateError(context: Context) {
        // Long single buzz for task failure
        val pattern = longArrayOf(0, 250)
        val amplitudes = intArrayOf(0, 255)
        vibratePattern(context, pattern, amplitudes)
    }

    fun vibrateClick(context: Context) {
        vibrateSingle(context, 25, 120)
    }

    private fun getVibrator(context: Context): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private fun vibratePattern(context: Context, pattern: LongArray, amplitudes: IntArray) {
        runCatching {
            val vibrator = getVibrator(context) ?: return
            if (!vibrator.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (vibrator.hasAmplitudeControl() && pattern.size == amplitudes.size) {
                    val effect = VibrationEffect.createWaveform(pattern, amplitudes, -1)
                    vibrator.vibrate(effect)
                } else {
                    val effect = VibrationEffect.createWaveform(pattern, -1)
                    vibrator.vibrate(effect)
                }
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, -1)
            }
        }
    }

    private fun vibrateSingle(context: Context, durationMs: Long, amplitude: Int = 180) {
        runCatching {
            val vibrator = getVibrator(context) ?: return
            if (!vibrator.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val eff = if (vibrator.hasAmplitudeControl()) {
                    VibrationEffect.createOneShot(durationMs, amplitude.coerceIn(1, 255))
                } else {
                    VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
                }
                vibrator.vibrate(eff)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(durationMs)
            }
        }
    }
}
