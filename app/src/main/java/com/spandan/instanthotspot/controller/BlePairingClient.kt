package com.spandan.instanthotspot.controller

import android.annotation.SuppressLint
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
import com.spandan.instanthotspot.core.AppPrefs
import com.spandan.instanthotspot.core.BleProtocol
import com.spandan.instanthotspot.core.PairedHostRegistry
import com.spandan.instanthotspot.core.DebugLog
import com.spandan.instanthotspot.core.PairingCrypto
import com.spandan.instanthotspot.core.PairingCodec
import java.security.KeyPair
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class PairingSession(
    val nonce: String,
    val candidateSecret: String,
    val code: String,
    val localPublicKeyB64: String,
    val remotePublicKeyB64: String,
)

enum class PairingStartError {
    BLUETOOTH_OFF,
    HOST_NOT_FOUND,
    HOST_PAIRING_MODE_OFF,
    HANDSHAKE_FAILED,
}

data class PairingStartResult(
    val session: PairingSession? = null,
    val error: PairingStartError? = null,
)

object BlePairingClient {
    @SuppressLint("MissingPermission")
    fun startPairing(context: Context): PairingStartResult {
        DebugLog.append(context, "CTRL_PAIR", "startPairing() called")
        val manager = context.getSystemService(BluetoothManager::class.java)
            ?: return PairingStartResult(error = PairingStartError.HANDSHAKE_FAILED)
        val adapter = manager.adapter ?: return PairingStartResult(error = PairingStartError.HANDSHAKE_FAILED)
        if (!adapter.isEnabled) return PairingStartResult(error = PairingStartError.BLUETOOTH_OFF)
        val scanner = adapter.bluetoothLeScanner ?: return PairingStartResult(error = PairingStartError.HANDSHAKE_FAILED)
        val host = discoverHost(context, scanner) ?: return PairingStartResult(error = PairingStartError.HOST_NOT_FOUND)
        DebugLog.append(context, "CTRL_PAIR", "Host discovered: ${host.address}")

        val nonce = UUID.randomUUID().toString().take(12)
        val localKeyPair: KeyPair = PairingCrypto.generateKeyPair()
        val localPublic = PairingCrypto.encodePublicKey(localKeyPair.public)
        val initMessage = PairingCodec.encode(listOf("PAIR_ECDH_INIT", nonce, localPublic))
        val response = connectWriteRead(context, host, initMessage)
            ?: return PairingStartResult(error = PairingStartError.HANDSHAKE_FAILED)
        val parts = PairingCodec.decode(response)
        DebugLog.append(context, "CTRL_PAIR", "Init response: ${parts.joinToString("|").take(120)}")
        if (parts.isNotEmpty() && parts[0].startsWith("PAIR_INIT_")) {
            return PairingStartResult(error = PairingStartError.HANDSHAKE_FAILED)
        }
        if (parts.isNotEmpty() && parts[0] == "PAIR_MODE_OFF") {
            return PairingStartResult(error = PairingStartError.HOST_PAIRING_MODE_OFF)
        }
        if (parts.size >= 3 && parts[0] == "PAIR_ECDH_CODE") {
            val code = parts[1]
            val remotePublic = parts[2]
            val remotePublicKey = PairingCrypto.decodePublicKey(remotePublic)
            val secretMaterial = PairingCrypto.deriveSharedSecretMaterial(localKeyPair.private, remotePublicKey)
            val derivedCode = PairingCrypto.shortAuthCode(secretMaterial, nonce, localPublic, remotePublic)
            if (derivedCode != code) return PairingStartResult(error = PairingStartError.HANDSHAKE_FAILED)
            val derivedSecret = PairingCrypto.derivePairingSecret(
                secretMaterial = secretMaterial,
                nonce = nonce,
                localPublicKeyB64 = localPublic,
                remotePublicKeyB64 = remotePublic,
            )
            return PairingStartResult(
                session = PairingSession(nonce, derivedSecret, code, localPublic, remotePublic),
            )
        }
        DebugLog.append(context, "CTRL_PAIR", "Handshake failed: unexpected init payload")
        return PairingStartResult(error = PairingStartError.HANDSHAKE_FAILED)
    }

    @SuppressLint("MissingPermission")
    fun confirmPairing(context: Context, session: PairingSession): Boolean {
        DebugLog.append(context, "CTRL_PAIR", "confirmPairing() called for code ${session.code}")
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return false
        val adapter = manager.adapter ?: return false
        if (!adapter.isEnabled) return false
        val scanner = adapter.bluetoothLeScanner ?: return false
        val host = discoverHost(context, scanner) ?: return false
        val message = PairingCodec.encode(
            listOf("PAIR_ECDH_CONFIRM", session.nonce, session.localPublicKeyB64, session.remotePublicKeyB64),
        )
        val response = connectWriteRead(context, host, message) ?: return false
        val parts = PairingCodec.decode(response)
        DebugLog.append(context, "CTRL_PAIR", "Confirm response: ${parts.joinToString("|").take(120)}")
        val ok = parts.isNotEmpty() && parts[0] == "PAIR_ECDH_OK"
        if (ok) {
            val label = runCatching { host.name }
                .getOrNull()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            PairedHostRegistry.upsert(
                context = context,
                address = host.address,
                secret = session.candidateSecret,
                displayName = label,
            )
            AppPrefs.setSharedSecret(context, session.candidateSecret)
            AppPrefs.setLastPairedHost(context, host.address)
            AppPrefs.setPreferredHostAddress(context, host.address)
            AppPrefs.setPairedHostDisplayName(context, label)
            AppPrefs.setClientPaired(context, true)
            AppPrefs.markHostReachableNow(context)
        }
        return ok
    }

    @SuppressLint("MissingPermission")
    fun fetchHotspotConfig(context: Context): String? {
        if (!AppPrefs.isClientPaired(context)) return null
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return null
        val adapter = manager.adapter ?: return null
        if (!adapter.isEnabled) return null
        val scanner = adapter.bluetoothLeScanner ?: return null
        val host = discoverHost(context, scanner) ?: return null
        val response = connectReadCharacteristic(context, host, BleProtocol.CONFIG_CHAR_UUID) ?: return null
        val value = String(response, Charsets.UTF_8).trim()
        if (value.isNotEmpty()) {
            AppPrefs.markHostReachableNow(context)
            AppPrefs.setLastSyncedHotspotConfig(context, value)
            return value
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private fun discoverHost(context: Context, scanner: BluetoothLeScanner): BluetoothDevice? {
        val preferred = AppPrefs.preferredHostAddress(context)
        var found: BluetoothDevice? = null
        val seen = linkedMapOf<String, BluetoothDevice>()
        val latch = CountDownLatch(1)
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val d = result.device ?: return
                val address = d.address ?: return
                if (address.isBlank()) return
                seen[address] = d
                if (preferred != null && address.equals(preferred, ignoreCase = true)) {
                    found = d
                    latch.countDown()
                } else if (found == null) {
                    found = d
                }
            }
        }
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(BleProtocol.SERVICE_UUID)).build(),
        )
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(filters, settings, callback)
        latch.await(12, TimeUnit.SECONDS)
        scanner.stopScan(callback)
        if (found == null && seen.isNotEmpty()) {
            found = seen.values.firstOrNull()
        }
        return found
    }

    @SuppressLint("MissingPermission")
    private fun connectWriteRead(context: Context, device: BluetoothDevice, payload: ByteArray): ByteArray? {
        val op = WriteReadOperation(context, payload)
        val gatt = device.connectGatt(context, false, op.callback, BluetoothDevice.TRANSPORT_LE)
        op.attachGatt(gatt)
        return op.awaitAndClose()
    }

    @SuppressLint("MissingPermission")
    private fun connectReadCharacteristic(
        context: Context,
        device: BluetoothDevice,
        characteristicUuid: java.util.UUID,
    ): ByteArray? {
        val op = ReadOnlyOperation(characteristicUuid)
        val gatt = device.connectGatt(context, false, op.callback, BluetoothDevice.TRANSPORT_LE)
        op.attachGatt(gatt)
        return op.awaitAndClose()
    }

    @SuppressLint("MissingPermission")
    private class WriteReadOperation(
        private val context: Context,
        private val payload: ByteArray,
    ) {
        private val done = CountDownLatch(1)
        private var gatt: BluetoothGatt? = null
        private var response: ByteArray? = null
        private var discoveryStarted = false

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    DebugLog.append(context, "CTRL_PAIR", "GATT connect failed status=$status")
                    done.countDown()
                    return
                }
                if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                        DebugLog.append(context, "CTRL_PAIR", "GATT connected, requesting MTU 247")
                        val requested = gatt.requestMtu(247)
                        if (!requested) {
                            DebugLog.append(context, "CTRL_PAIR", "MTU request not accepted, discovering services")
                            startDiscovery(gatt)
                        }
                    } else {
                        DebugLog.append(context, "CTRL_PAIR", "GATT connected, discovering services")
                        startDiscovery(gatt)
                    }
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                DebugLog.append(context, "CTRL_PAIR", "MTU changed to $mtu (status=$status)")
                startDiscovery(gatt)
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    DebugLog.append(context, "CTRL_PAIR", "Service discovery failed status=$status")
                    done.countDown()
                    return
                }
                val service: BluetoothGattService = gatt.getService(BleProtocol.SERVICE_UUID) ?: run {
                    done.countDown()
                    return
                }
                val characteristic: BluetoothGattCharacteristic =
                    service.getCharacteristic(BleProtocol.PAIRING_CHAR_UUID) ?: run {
                        done.countDown()
                        return
                    }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeCharacteristic(
                        characteristic,
                        payload,
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                    )
                } else {
                    characteristic.value = payload
                    gatt.writeCharacteristic(characteristic)
                }
                DebugLog.append(context, "CTRL_PAIR", "Pairing write sent (${payload.size} bytes)")
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    DebugLog.append(context, "CTRL_PAIR", "Pairing write failed status=$status")
                    done.countDown()
                    return
                }
                DebugLog.append(context, "CTRL_PAIR", "Pairing write success, reading response")
                SystemClock.sleep(80)
                gatt.readCharacteristic(characteristic)
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int,
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    response = value
                    DebugLog.append(context, "CTRL_PAIR", "Pairing read success (${value.size} bytes)")
                } else {
                    DebugLog.append(context, "CTRL_PAIR", "Pairing read failed status=$status")
                }
                done.countDown()
            }

            @Suppress("DEPRECATION")
            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    response = characteristic.value
                    DebugLog.append(context, "CTRL_PAIR", "Pairing read success (legacy callback)")
                } else {
                    DebugLog.append(context, "CTRL_PAIR", "Pairing read failed legacy status=$status")
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
        fun awaitAndClose(): ByteArray? {
            done.await(12, TimeUnit.SECONDS)
            if (response == null) {
                DebugLog.append(context, "CTRL_PAIR", "Pairing operation completed without response payload")
            }
            gatt?.disconnect()
            gatt?.close()
            return response
        }
    }

    @SuppressLint("MissingPermission")
    private class ReadOnlyOperation(private val targetUuid: java.util.UUID) {
        private val done = CountDownLatch(1)
        private var gatt: BluetoothGatt? = null
        private var response: ByteArray? = null
        private var discoveryStarted = false

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    done.countDown()
                    return
                }
                if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                        if (!gatt.requestMtu(517)) {
                            startDiscovery(gatt)
                        }
                    } else {
                        startDiscovery(gatt)
                    }
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                startDiscovery(gatt)
            }

            private fun startDiscovery(gatt: BluetoothGatt) {
                if (discoveryStarted) return
                discoveryStarted = true
                gatt.discoverServices()
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    done.countDown()
                    return
                }
                val service = gatt.getService(BleProtocol.SERVICE_UUID) ?: run {
                    done.countDown()
                    return
                }
                val characteristic = service.getCharacteristic(targetUuid) ?: run {
                    done.countDown()
                    return
                }
                gatt.readCharacteristic(characteristic)
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int,
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS) response = value
                done.countDown()
            }

            @Suppress("DEPRECATION")
            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS) response = characteristic.value
                done.countDown()
            }
        }

        fun attachGatt(gatt: BluetoothGatt) {
            this.gatt = gatt
        }

        @SuppressLint("MissingPermission")
        fun awaitAndClose(): ByteArray? {
            done.await(10, TimeUnit.SECONDS)
            gatt?.disconnect()
            gatt?.close()
            return response
        }
    }
}
