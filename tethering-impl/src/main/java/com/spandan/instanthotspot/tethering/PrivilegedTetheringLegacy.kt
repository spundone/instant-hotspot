package com.spandan.instanthotspot.tethering

import android.annotation.SuppressLint
import android.content.Context
import android.net.TetheringManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TetheringManager APIs from API 30–35: [TetheringManager.startTethering] with
 * (int, boolean, executor, [StartTetheringCallback]) where the callback is a **class**.
 * This path must not be loaded on API 36+ (see [PrivilegedTetheringApi36]).
 */
internal object PrivilegedTetheringLegacy {
    @SuppressLint("MissingPermission", "InlinedApi")
    fun startWifiTetheringSync(context: Context, timeoutSec: Long): Boolean {
        val cm = context.applicationContext
        val latch = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        val exec = Executors.newSingleThreadExecutor()
        return try {
            val tm = cm.getSystemService(TetheringManager::class.java) ?: return false
            tm.startTethering(
                TetheringManager.TETHERING_WIFI,
                false,
                exec,
                object : TetheringManager.StartTetheringCallback() {
                    override fun onTetheringFailed(error: Int) {
                        latch.countDown()
                    }

                    override fun onTetheringStarted() {
                        ok.set(true)
                        latch.countDown()
                    }
                },
            )
            latch.await(timeoutSec, TimeUnit.SECONDS) && ok.get()
        } catch (_: Exception) {
            false
        } finally {
            exec.shutdownNow()
        }
    }

    @SuppressLint("InlinedApi")
    fun stopWifiTethering(context: Context): Boolean = runCatching {
        val tm = context.applicationContext
            .getSystemService(TetheringManager::class.java) ?: return@runCatching false
        tm.stopTethering(TetheringManager.TETHERING_WIFI)
        true
    }.getOrDefault(false)
}
