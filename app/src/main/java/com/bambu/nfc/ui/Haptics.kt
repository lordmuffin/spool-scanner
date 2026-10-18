package com.bambu.nfc.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Tag feedback. Reader mode runs with FLAG_READER_NO_PLATFORM_SOUNDS, so this is the only signal
 * that a tap registered: one short pulse on detection, a distinct double pulse when a read fails.
 */
class Haptics(context: Context) {

    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    /** Tag detected. Safe to call from the reader-mode binder thread. */
    fun tagDetected() = vibrate(longArrayOf(0, 60))

    /** Tag couldn't be read or written. */
    fun error() = vibrate(longArrayOf(0, 70, 90, 70))

    private fun vibrate(pattern: LongArray) {
        val v = vibrator?.takeIf { it.hasVibrator() } ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) {
                val effect = if (pattern.size == 2) {
                    VibrationEffect.createOneShot(pattern[1], VibrationEffect.DEFAULT_AMPLITUDE)
                } else {
                    VibrationEffect.createWaveform(pattern, -1)
                }
                v.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(pattern, -1)
            }
        }
    }
}
