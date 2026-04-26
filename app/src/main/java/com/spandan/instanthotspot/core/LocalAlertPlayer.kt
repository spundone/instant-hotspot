package com.spandan.instanthotspot.core

import android.content.Context
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Plays default ringtone + vibration (for “find device” / remote ring). Runs on the main looper.
 */
object LocalAlertPlayer {
    @JvmStatic
    fun playAttention(context: Context): Boolean {
        val app = context.applicationContext
        Handler(Looper.getMainLooper()).post {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vm = app.getSystemService(VibratorManager::class.java)?.defaultVibrator
                    vm?.vibrate(
                        VibrationEffect.createWaveform(
                            longArrayOf(0, 280, 120, 280, 120, 400),
                            -1,
                        ),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    val v = app.getSystemService(Vibrator::class.java)
                    v?.vibrate(longArrayOf(0, 280, 120, 280, 120, 400), -1)
                }
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                val rt = RingtoneManager.getRingtone(app, uri) ?: return@post
                @Suppress("DEPRECATION")
                rt.play()
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        rt.stop()
                    } catch (_: Exception) { }
                }, 7_200L)
            } catch (e: Exception) {
                DebugLog.append(app, "ALERT", "playAttention: ${e.message}")
            }
        }
        return true
    }
}
