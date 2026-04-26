package com.spandan.instanthotspot.controller

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import android.widget.Toast
import com.spandan.instanthotspot.core.AppPrefs
import com.spandan.instanthotspot.core.BleProtocol
import com.spandan.instanthotspot.core.CommandCodec
import com.spandan.instanthotspot.core.CommandEnvelope
import com.spandan.instanthotspot.core.CommandSecurity
import com.spandan.instanthotspot.core.DebugLog
import com.spandan.instanthotspot.core.HotspotCommand
import com.spandan.instanthotspot.core.HostStateCodec
import com.spandan.instanthotspot.widget.HotspotWidgetProvider
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

enum class CommandSendStatus {
    SUCCESS,
    NOT_PAIRED,
    BLUETOOTH_OFF,
    HOST_NOT_FOUND,
    SEND_FAILED,
}

object ControllerCommandSender {
    data class HostDeviceSummary(
        val name: String?,
        val address: String,
    )
    /** For Quick Settings: toggles (host turns off if soft AP is up, otherwise on). */
    fun sendHotspotToggle(context: Context) {
        send(context, HotspotCommand.HOTSPOT_TOGGLE)
    }

    fun send(context: Context, command: HotspotCommand) {
        Thread {
            val status = sendViaBle(context, command)
            val message = when (status) {
                CommandSendStatus.SUCCESS -> "Sent: $command"
                CommandSendStatus.NOT_PAIRED -> "Device not paired yet"
                CommandSendStatus.BLUETOOTH_OFF -> "Bluetooth is off"
                CommandSendStatus.HOST_NOT_FOUND -> "Host not found nearby"
                CommandSendStatus.SEND_FAILED -> "Could not reach host device"
            }
            android.os.Handler(context.mainLooper).post {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    fun sendAsync(context: Context, command: HotspotCommand, callback: (CommandSendStatus) -> Unit) {
        Thread {
            val status = sendViaBle(context, command)
            android.os.Handler(context.mainLooper).post { callback(status) }
        }.start()
    }

    /**
     * Background: discover host, read [BleProtocol.STATE_CHAR_UUID], update prefs and widgets.
     * Respects [AppPrefs.shouldThrottleStateRefresh] unless [force] is true.
     */
    fun refreshHostStateAsync(context: Context, force: Boolean = false, onComplete: (() -> Unit)? = null) {
        Thread {
            if (!AppPrefs.isClientPaired(context)) {
                onComplete?.let { android.os.Handler(context.mainLooper).post(it) }
            } else if (!force && AppPrefs.shouldThrottleStateRefresh(context)) {
                onComplete?.let { android.os.Handler(context.mainLooper).post(it) }
            } else {
                fetchHostStateBlocking(context)
                onComplete?.let { android.os.Handler(context.mainLooper).post(it) }
            }
        }.start()
    }

    @SuppressLint("MissingPermission")
    private fun fetchHostStateBlocking(context: Context): Boolean {
        if (!AppPrefs.isClientPaired(context)) return false
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return false
        val adapter = manager.adapter ?: return false
        if (!adapter.isEnabled) return false
        val scanner = adapter.bluetoothLeScanner ?: return false
        val found = discoverHost(context, scanner) ?: return false
        val op = StateReadOperation(context)
        val gatt = found.connectGatt(context, false, op.callback, BluetoothDevice.TRANSPORT_LE)
        op.attachGatt(gatt)
        return op.awaitAndClose()
    }

    @SuppressLint("MissingPermission")
    private fun sendViaBle(context: Context, command: HotspotCommand): CommandSendStatus {
        DebugLog.append(context, "CTRL_CMD", "sendViaBle($command) called")
        if (!AppPrefs.isClientPaired(context)) return CommandSendStatus.NOT_PAIRED
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return CommandSendStatus.SEND_FAILED
        val adapter = manager.adapter ?: return CommandSendStatus.SEND_FAILED
        if (!adapter.isEnabled) return CommandSendStatus.BLUETOOTH_OFF

        val scanner = adapter.bluetoothLeScanner ?: return CommandSendStatus.SEND_FAILED
        val foundDevice = discoverHost(context, scanner) ?: return CommandSendStatus.HOST_NOT_FOUND
        DebugLog.append(context, "CTRL_CMD", "Host discovered: ${foundDevice.address}")
        val success = connectAndWrite(context, foundDevice, command)
        if (success) {
            AppPrefs.markHostReachableNow(context)
            DebugLog.append(context, "CTRL_CMD", "Command write successful")
            return CommandSendStatus.SUCCESS
        }
        DebugLog.append(context, "CTRL_CMD", "Command write failed")
        return CommandSendStatus.SEND_FAILED
    }

    @SuppressLint("MissingPermission")
    private fun discoverHost(context: Context, scanner: BluetoothLeScanner): BluetoothDevice? {
        val preferred = AppPrefs.preferredHostAddress(context)
        var matched: BluetoothDevice? = null
        val seen = linkedMapOf<String, BluetoothDevice>()
        val latch = CountDownLatch(1)
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val d = result.device ?: return
                if (d.address.isNullOrBlank()) return
                seen[d.address] = d
                if (preferred != null && d.address.equals(preferred, ignoreCase = true)) {
                    matched = d
                    latch.countDown()
                } else if (matched == null) {
                    matched = d
                }
            }
        }
        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(BleProtocol.SERVICE_UUID))
                .build(),
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(filters, settings, callback)
        latch.await(8, TimeUnit.SECONDS)
        scanner.stopScan(callback)
        if (matched == null && seen.isNotEmpty()) {
            matched = seen.values.firstOrNull()
        }
        return matched
    }

    @SuppressLint("MissingPermission")
    fun scanNearbyHosts(context: Context, timeoutMs: Long = 8000L): List<HostDeviceSummary> {
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return emptyList()
        val adapter = manager.adapter ?: return emptyList()
        if (!adapter.isEnabled) return emptyList()
        val scanner = adapter.bluetoothLeScanner ?: return emptyList()
        val seen = linkedMapOf<String, HostDeviceSummary>()
        val done = CountDownLatch(1)
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val d = result.device ?: return
                val address = d.address ?: return
                if (address.isBlank()) return
                seen[address] = HostDeviceSummary(name = d.name, address = address)
            }
        }
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(BleProtocol.SERVICE_UUID)).build(),
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(filters, settings, callback)
        done.await(timeoutMs, TimeUnit.MILLISECONDS)
        scanner.stopScan(callback)
        return seen.values.toList()
    }

    @SuppressLint("MissingPermission")
    private fun connectAndWrite(context: Context, device: BluetoothDevice, command: HotspotCommand): Boolean {
        val operation = BleWriteOperation(context, command)
        val gatt = device.connectGatt(context, false, operation.callback, BluetoothDevice.TRANSPORT_LE)
        operation.attachGatt(gatt)
        return operation.awaitAndClose()
    }

    @SuppressLint("MissingPermission")
    private class BleWriteOperation(
        private val context: Context,
        private val command: HotspotCommand,
    ) {
        private val done = CountDownLatch(1)
        @Volatile private var success = false
        @Volatile private var gatt: BluetoothGatt? = null
        @Volatile private var retryWithNoResponse = false
        @Volatile private var discoveryStarted = false
        @Volatile private var awaitingStateRead = false

        private fun finishAfterStateOrNow(g: BluetoothGatt) {
            val st = g.getService(BleProtocol.SERVICE_UUID)
                ?.getCharacteristic(BleProtocol.STATE_CHAR_UUID)
            if (st != null) {
                awaitingStateRead = true
                g.readCharacteristic(st)
            } else {
                done.countDown()
            }
        }

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    DebugLog.append(context, "CTRL_CMD", "GATT connect failed status=$status")
                    done.countDown()
                    return
                }
                if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                    DebugLog.append(context, "CTRL_CMD", "GATT connected, requesting MTU 247")
                    val requested = gatt.requestMtu(247)
                    if (!requested) {
                        DebugLog.append(context, "CTRL_CMD", "MTU request not accepted, discovering services")
                        startDiscovery(gatt)
                    }
                } else if (newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) {
                    if (awaitingStateRead) {
                        awaitingStateRead = false
                    }
                    done.countDown()
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                DebugLog.append(context, "CTRL_CMD", "MTU changed to $mtu (status=$status)")
                startDiscovery(gatt)
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    DebugLog.append(context, "CTRL_CMD", "Service discovery failed status=$status")
                    done.countDown()
                    return
                }
                val service: BluetoothGattService = gatt.getService(BleProtocol.SERVICE_UUID) ?: run {
                    DebugLog.append(context, "CTRL_CMD", "Service not found on host")
                    done.countDown()
                    return
                }
                val characteristic: BluetoothGattCharacteristic =
                    service.getCharacteristic(BleProtocol.COMMAND_CHAR_UUID) ?: run {
                        DebugLog.append(context, "CTRL_CMD", "Command characteristic missing on host")
                        done.countDown()
                        return
                    }

                val now = System.currentTimeMillis()
                val nonce = UUID.randomUUID().toString().take(12)
                val payload = CommandCodec.payload(command, now, nonce)
                val secret = AppPrefs.sharedSecret(context)
                val signature = CommandSecurity.sign(payload, secret)
                val envelope = CommandEnvelope(command, now, nonce, signature)
                val bytes = CommandCodec.encode(envelope)
                DebugLog.append(context, "CTRL_CMD", "Writing command payload (${bytes.size} bytes)")

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeCharacteristic(
                        characteristic,
                        bytes,
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                    )
                } else {
                    characteristic.value = bytes
                    gatt.writeCharacteristic(characteristic)
                }
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    success = true
                    DebugLog.append(context, "CTRL_CMD", "Command write success, reading ap state if available")
                    finishAfterStateOrNow(gatt)
                    return
                }
                DebugLog.append(context, "CTRL_CMD", "Command write failed status=$status")
                if (!retryWithNoResponse) {
                    retryWithNoResponse = true
                    val retryService = gatt.getService(BleProtocol.SERVICE_UUID)
                    val retryCharacteristic = retryService?.getCharacteristic(BleProtocol.COMMAND_CHAR_UUID)
                    if (retryCharacteristic != null) {
                        val now = System.currentTimeMillis()
                        val nonce = UUID.randomUUID().toString().take(12)
                        val payload = CommandCodec.payload(command, now, nonce)
                        val secret = AppPrefs.sharedSecret(context)
                        val signature = CommandSecurity.sign(payload, secret)
                        val envelope = CommandEnvelope(command, now, nonce, signature)
                        val bytes = CommandCodec.encode(envelope)
                        DebugLog.append(context, "CTRL_CMD", "Retrying command write with NO_RESPONSE")
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                            gatt.writeCharacteristic(
                                retryCharacteristic,
                                bytes,
                                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
                            )
                        } else {
                            retryCharacteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                            retryCharacteristic.value = bytes
                            gatt.writeCharacteristic(retryCharacteristic)
                        }
                        return
                    }
                }
                done.countDown()
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int,
            ) {
                if (awaitingStateRead && characteristic.uuid == BleProtocol.STATE_CHAR_UUID) {
                    awaitingStateRead = false
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        val ap = HostStateCodec.parseClient(value)
                        AppPrefs.setLastHostApState(context, ap)
                        AppPrefs.markHostStateFetchedNow(context)
                        HotspotWidgetProvider.requestUpdateAll(context)
                    }
                    done.countDown()
                }
            }

            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION", "RedundantSuppression")
            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
                if (awaitingStateRead && characteristic.uuid == BleProtocol.STATE_CHAR_UUID) {
                    awaitingStateRead = false
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        @Suppress("DEPRECATION")
                        val value = characteristic.value
                        val ap = HostStateCodec.parseClient(value)
                        AppPrefs.setLastHostApState(context, ap)
                        AppPrefs.markHostStateFetchedNow(context)
                        HotspotWidgetProvider.requestUpdateAll(context)
                    }
                    done.countDown()
                }
            }
        }

        private fun startDiscovery(gatt: BluetoothGatt) {
            if (discoveryStarted) return
            discoveryStarted = true
            gatt.discoverServices()
        }

        fun attachGatt(gatt: BluetoothGatt) {
            this.gatt = gatt
        }

        @SuppressLint("MissingPermission")
        fun awaitAndClose(): Boolean {
            done.await(15, TimeUnit.SECONDS)
            SystemClock.sleep(100)
            gatt?.disconnect()
            gatt?.close()
            return success
        }
    }

    @SuppressLint("MissingPermission")
    private class StateReadOperation(private val context: Context) {
        private val done = CountDownLatch(1)
        private var gatt: BluetoothGatt? = null
        @Volatile private var discoveryStarted = false
        @Volatile private var readOk = false

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    done.countDown()
                    return
                }
                if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                    if (!gatt.requestMtu(247)) {
                        startDiscovery(gatt)
                    }
                } else if (newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) {
                    done.countDown()
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                startDiscovery(gatt)
            }

            private fun startDiscovery(g: BluetoothGatt) {
                if (discoveryStarted) return
                discoveryStarted = true
                g.discoverServices()
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    done.countDown()
                    return
                }
                val s = gatt.getService(BleProtocol.SERVICE_UUID) ?: run {
                    done.countDown()
                    return
                }
                val c = s.getCharacteristic(BleProtocol.STATE_CHAR_UUID) ?: run {
                    done.countDown()
                    return
                }
                gatt.readCharacteristic(c)
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int,
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == BleProtocol.STATE_CHAR_UUID) {
                    val ap = HostStateCodec.parseClient(value)
                    AppPrefs.setLastHostApState(context, ap)
                    AppPrefs.markHostStateFetchedNow(context)
                    readOk = true
                    HotspotWidgetProvider.requestUpdateAll(context)
                }
                done.countDown()
            }

            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION", "RedundantSuppression")
            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
                if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == BleProtocol.STATE_CHAR_UUID) {
                    @Suppress("DEPRECATION")
                    val v = characteristic.value
                    val ap = HostStateCodec.parseClient(v)
                    AppPrefs.setLastHostApState(context, ap)
                    AppPrefs.markHostStateFetchedNow(context)
                    readOk = true
                    HotspotWidgetProvider.requestUpdateAll(context)
                }
                done.countDown()
            }
        }

        fun attachGatt(g: BluetoothGatt) {
            gatt = g
        }

        @SuppressLint("MissingPermission")
        fun awaitAndClose(): Boolean {
            done.await(12, TimeUnit.SECONDS)
            SystemClock.sleep(100)
            gatt?.disconnect()
            gatt?.close()
            return readOk
        }
    }
}
