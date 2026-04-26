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

    @SuppressLint("MissingPermission")
    private fun sendViaBle(context: Context, command: HotspotCommand): CommandSendStatus {
        DebugLog.append(context, "CTRL_CMD", "sendViaBle($command) called")
        if (!AppPrefs.isClientPaired(context)) return CommandSendStatus.NOT_PAIRED
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return CommandSendStatus.SEND_FAILED
        val adapter = manager.adapter ?: return CommandSendStatus.SEND_FAILED
        if (!adapter.isEnabled) return CommandSendStatus.BLUETOOTH_OFF

        val scanner = adapter.bluetoothLeScanner ?: return CommandSendStatus.SEND_FAILED
        val foundDevice = discoverHost(scanner) ?: return CommandSendStatus.HOST_NOT_FOUND
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
    private fun discoverHost(scanner: BluetoothLeScanner): BluetoothDevice? {
        var matched: BluetoothDevice? = null
        val latch = CountDownLatch(1)
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                matched = result.device
                latch.countDown()
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
        return matched
    }

    @SuppressLint("MissingPermission")
    private fun connectAndWrite(context: Context, device: BluetoothDevice, command: HotspotCommand): Boolean {
        val operation = BleWriteOperation(context, command)
        val gatt = device.connectGatt(context, false, operation.callback, BluetoothDevice.TRANSPORT_LE)
        operation.attachGatt(gatt)
        return operation.awaitAndClose()
    }

    private class BleWriteOperation(
        private val context: Context,
        private val command: HotspotCommand,
    ) {
        private val done = CountDownLatch(1)
        @Volatile private var success = false
        @Volatile private var gatt: BluetoothGatt? = null
        @Volatile private var retryWithNoResponse = false
        @Volatile private var discoveryStarted = false

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
                    DebugLog.append(context, "CTRL_CMD", "Command write success")
                    done.countDown()
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
            done.await(12, TimeUnit.SECONDS)
            SystemClock.sleep(100)
            gatt?.disconnect()
            gatt?.close()
            return success
        }
    }
}
