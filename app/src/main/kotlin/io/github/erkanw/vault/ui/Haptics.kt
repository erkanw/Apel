package io.github.erkanw.vault.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Small vibrations for the moments that matter, three that feel different:
 *  - [tap], one short pulse at medium strength: sending and copying;
 *  - [confirm], two short pulses with a gap between them: the agent has answered;
 *  - [reject], one long pulse at full strength: something went wrong.
 * Off in Settings.
 *
 * They are patterns of our own (a length and a strength for each), not the system's predefined effects:
 * those are tuned differently on every phone, and on some the "heavy click" comes out lighter than the
 * "click". It drives the vibration motor directly, because the system's touch feedback for views is often
 * switched off, on Samsung phones in particular. Without a usable vibrator it falls back to that feedback.
 * Needs the VIBRATE permission (a normal one, granted at install).
 */
object Haptics {
    /** Set from Settings when the app starts and when the switch changes. */
    @Volatile var enabled: Boolean = true

    @Volatile private var vibrator: Vibrator? = null

    // Lengths in milliseconds, strengths from 1 to 255.
    private const val TAP_MS = 30L
    private const val TAP_STRENGTH = 170
    private const val BEAT_MS = 28L
    private const val BEAT_GAP_MS = 95L
    private const val BEAT_STRENGTH = 210
    private const val HEAVY_MS = 95L
    private const val HEAVY_STRENGTH = 255

    /** Looks up the vibration motor. Call once, with any context. */
    fun attach(context: Context) {
        val app = context.applicationContext
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            app.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    fun tap(view: View) = buzz(view, HapticFeedbackConstants.KEYBOARD_TAP) { v -> pulse(v, TAP_MS, TAP_STRENGTH) }

    fun confirm(view: View) = buzz(
        view,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.KEYBOARD_TAP,
    ) { v ->
        val timings = longArrayOf(0L, BEAT_MS, BEAT_GAP_MS, BEAT_MS)
        if (v.hasAmplitudeControl()) {
            VibrationEffect.createWaveform(timings, intArrayOf(0, BEAT_STRENGTH, 0, BEAT_STRENGTH), -1)
        } else {
            VibrationEffect.createWaveform(timings, -1)
        }
    }

    fun reject(view: View) = buzz(
        view,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS,
    ) { v -> pulse(v, HEAVY_MS, HEAVY_STRENGTH) }

    private fun pulse(v: Vibrator, ms: Long, strength: Int): VibrationEffect =
        VibrationEffect.createOneShot(ms, if (v.hasAmplitudeControl()) strength else VibrationEffect.DEFAULT_AMPLITUDE)

    private fun buzz(view: View, viewConstant: Int, effect: (Vibrator) -> VibrationEffect) {
        if (!enabled) return
        val v = vibrator
        if (v != null && v.hasVibrator()) {
            try {
                v.vibrate(effect(v))
                return
            } catch (e: Exception) {
                // Fall through to the view's own feedback.
            }
        }
        view.performHapticFeedback(viewConstant)
    }
}
