package com.spandan.instanthotspot.tethering

import android.annotation.SuppressLint
import android.content.Context
import android.net.TetheringManager
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.Executor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * API 36+ [TetheringManager]: [android.net.TetheringManager.StartTetheringCallback] is an
 * **interface**, and [TetheringManager.startTethering] takes [TetheringRequest]. Our compile
 * stub still models the pre-36 callback as a class; a direct anonymous subclass would throw
 * [IncompatibleClassChangeError] on the device, so this path uses reflection and [Proxy].
 */
internal object PrivilegedTetheringApi36 {
    private const val TETHERING_WIFI = 0

    @SuppressLint("MissingPermission", "InlinedApi")
    fun startWifiTetheringSync(context: Context, timeoutSec: Long): Boolean {
        val app = context.applicationContext
        val latch = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        val exec = Executors.newSingleThreadExecutor()
        return try {
            val tm = app.getSystemService(TetheringManager::class.java) ?: return false
            val request = buildWifiTetheringRequest()
            val startCb = Class.forName("android.net.TetheringManager\$StartTetheringCallback")
            val callback = newStartCallback(latch, ok, startCb)
            val m = findStartTethering3Arg(tm, request, startCb)
            m.invoke(tm, request, exec, callback)
            latch.await(timeoutSec, TimeUnit.SECONDS) && ok.get()
        } catch (_: Exception) {
            false
        } finally {
            exec.shutdownNow()
        }
    }

    @SuppressLint("InlinedApi")
    fun stopWifiTethering(context: Context): Boolean {
        val app = context.applicationContext
        val exec = Executors.newSingleThreadExecutor()
        return try {
            val tm = app.getSystemService(TetheringManager::class.java) ?: return false
            val request = buildWifiTetheringRequest()
            val latch = CountDownLatch(1)
            val ok = AtomicBoolean(false)
            val stopCb = Class.forName("android.net.TetheringManager\$StopTetheringCallback")
            val callback = newStopCallback(latch, ok, stopCb)
            val m = findStopTethering3Arg(tm, request, stopCb)
            m.invoke(tm, request, exec, callback)
            latch.await(8, TimeUnit.SECONDS) && ok.get()
        } catch (_: Exception) {
            false
        } finally {
            exec.shutdownNow()
        }
    }

    private fun buildWifiTetheringRequest(): Any {
        val builderClz = Class.forName("android.net.TetheringManager\$TetheringRequest\$Builder")
        val builder = builderClz
            .getConstructor(Int::class.javaPrimitiveType)
            .newInstance(TETHERING_WIFI)
        return builderClz.getMethod("build").invoke(builder) as Any
    }

    private fun findStartTethering3Arg(
        tm: TetheringManager,
        request: Any,
        startCallbackType: Class<*>,
    ): Method {
        val inst = request.javaClass
        for (m in tm.javaClass.methods) {
            if (m.name != "startTethering") continue
            val p = m.parameterTypes
            if (p.size != 3) continue
            if (!p[0].isAssignableFrom(inst)) continue
            if (p[1] != Executor::class.java) continue
            if (p[2] != startCallbackType) continue
            return m
        }
        throw NoSuchMethodException("startTethering(TetheringRequest, Executor, StartTetheringCallback)")
    }

    private fun findStopTethering3Arg(
        tm: TetheringManager,
        request: Any,
        stopCallbackType: Class<*>,
    ): Method {
        val inst = request.javaClass
        for (m in tm.javaClass.methods) {
            if (m.name != "stopTethering") continue
            val p = m.parameterTypes
            if (p.size != 3) continue
            if (!p[0].isAssignableFrom(inst)) continue
            if (p[1] != Executor::class.java) continue
            if (p[2] != stopCallbackType) continue
            return m
        }
        throw NoSuchMethodException("stopTethering(TetheringRequest, Executor, StopTetheringCallback)")
    }

    private fun newStartCallback(
        latch: CountDownLatch,
        ok: AtomicBoolean,
        ifType: Class<*>,
    ): Any {
        val h = InvocationHandler { _, method, _ ->
            when (method.name) {
                "onTetheringStarted" -> {
                    ok.set(true)
                    latch.countDown()
                }
                "onTetheringFailed" -> {
                    latch.countDown()
                }
            }
            null
        }
        return Proxy.newProxyInstance(ifType.classLoader, arrayOf(ifType), h)
    }

    private fun newStopCallback(
        latch: CountDownLatch,
        ok: AtomicBoolean,
        ifType: Class<*>,
    ): Any {
        val h = InvocationHandler { _, method, _ ->
            when (method.name) {
                "onStopTetheringSucceeded" -> {
                    ok.set(true)
                    latch.countDown()
                }
                "onStopTetheringFailed" -> {
                    latch.countDown()
                }
            }
            null
        }
        return Proxy.newProxyInstance(ifType.classLoader, arrayOf(ifType), h)
    }
}
