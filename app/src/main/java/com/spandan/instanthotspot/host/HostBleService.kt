package com.spandan.instanthotspot.host

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.spandan.instanthotspot.R
import com.spandan.instanthotspot.core.AppPrefs
import com.spandan.instanthotspot.core.BleProtocol
import com.spandan.instanthotspot.core.CommandCodec
import com.spandan.instanthotspot.core.CommandSecurity
import com.spandan.instanthotspot.core.DebugLog
import com.spandan.instanthotspot.core.HotspotCommand
import com.spandan.instanthotspot.core.HotspotController
import com.spandan.instanthotspot.core.PairingCrypto
import com.spandan.instanthotspot.core.PairingCodec
import java.util.concurrent.TimeUnit

class HostBleService : Service() {
    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private val pendingPairsByNonce = mutableMapOf<String, PendingPair>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, createNotification())
        AppPrefs.setHostServiceActive(this, true)
        DebugLog.append(this, "HOST_SVC", "Service created")
        startGattServer()
        startAdvertising()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RESTART_ADVERTISING) {
            DebugLog.append(this, "HOST_SVC", "Force re-advertise requested")
            stopAdvertising()
            startAdvertising()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopAdvertising()
        AppPrefs.setHostServiceActive(this, false)
        DebugLog.append(this, "HOST_SVC", "Service destroyed")
        gattServer?.close()
        gattServer = null
        super.onDestroy()
    }

    fun handleIncomingCommand(command: HotspotCommand): Boolean {
        DebugLog.append(this, "HOST_CMD", "Incoming command: $command")
        val ok = when (command) {
            HotspotCommand.HOTSPOT_ON -> HotspotController.enableHotspotRoot()
            HotspotCommand.HOTSPOT_OFF -> HotspotController.disableHotspotRoot()
            HotspotCommand.HOTSPOT_TOGGLE -> HotspotController.enableHotspotRoot()
        }
        DebugLog.append(
            this,
            "HOST_CMD",
            "Execution result=$ok :: ${HotspotController.lastHotspotExecutionReport()}",
        )
        return ok
    }

    @SuppressLint("MissingPermission")
    private fun startGattServer() {
        val manager = getSystemService(BluetoothManager::class.java) ?: return
        val adapter = manager.adapter ?: return
        if (!adapter.isEnabled) return

        val server = manager.openGattServer(this, gattServerCallback) ?: return
        val commandCharacteristic = BluetoothGattCharacteristic(
            BleProtocol.COMMAND_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val pairingCharacteristic = BluetoothGattCharacteristic(
            BleProtocol.PAIRING_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_WRITE or BluetoothGattCharacteristic.PERMISSION_READ,
        )
        val configCharacteristic = BluetoothGattCharacteristic(
            BleProtocol.CONFIG_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        val service = BluetoothGattService(BleProtocol.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(commandCharacteristic)
        service.addCharacteristic(pairingCharacteristic)
        service.addCharacteristic(configCharacteristic)
        server.addService(service)
        gattServer = server
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising() {
        val manager = getSystemService(BluetoothManager::class.java) ?: return
        val adapter = manager.adapter ?: return
        if (!adapter.isEnabled) return

        val leAdvertiser = adapter.bluetoothLeAdvertiser ?: return
        advertiser = leAdvertiser

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()

        val data = AdvertiseData.Builder()
            .addServiceUuid(android.os.ParcelUuid(BleProtocol.SERVICE_UUID))
            .setIncludeDeviceName(false)
            .build()

        leAdvertiser.startAdvertising(settings, data, advertiseCallback)
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvertising() {
        advertiser?.stopAdvertising(advertiseCallback)
        advertiser = null
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onCharacteristicWriteRequest(
            device: android.bluetooth.BluetoothDevice?,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            val response = when {
                characteristic?.uuid == BleProtocol.COMMAND_CHAR_UUID && value != null ->
                    if (processCommandWrite(value)) "OK" else "FAIL"
                characteristic?.uuid == BleProtocol.PAIRING_CHAR_UUID && value != null && device != null ->
                    processPairingWrite(device.address, value)
                else -> "FAIL"
            }
            if (characteristic?.uuid == BleProtocol.PAIRING_CHAR_UUID) {
                // Keep the latest pairing status readable by clients after write.
                characteristic.value = response.toByteArray(Charsets.UTF_8)
            }
            if (responseNeeded) {
                val statusCode = when {
                    characteristic?.uuid == BleProtocol.PAIRING_CHAR_UUID -> BluetoothGatt.GATT_SUCCESS
                    response != "FAIL" -> BluetoothGatt.GATT_SUCCESS
                    else -> BluetoothGatt.GATT_FAILURE
                }
                gattServer?.sendResponse(
                    device,
                    requestId,
                    statusCode,
                    0,
                    response.toByteArray(Charsets.UTF_8),
                )
            }
        }

        override fun onCharacteristicReadRequest(
            device: android.bluetooth.BluetoothDevice?,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic?,
        ) {
            val response: ByteArray? = when (characteristic?.uuid) {
                BleProtocol.PAIRING_CHAR_UUID -> characteristic.value ?: byteArrayOf()
                BleProtocol.CONFIG_CHAR_UUID -> HotspotController.hotspotConfigSummary().toByteArray(Charsets.UTF_8)
                else -> null
            }
            if (response != null) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, response)
            } else {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
            }
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {}

    private fun processCommandWrite(raw: ByteArray): Boolean {
        val envelope = CommandCodec.decode(raw) ?: return false
        val payload = CommandCodec.payload(envelope.command, envelope.timestampMs, envelope.nonce)
        val secret = AppPrefs.sharedSecret(this)
        if (!CommandSecurity.verify(payload, envelope.signature, secret)) return false

        // Basic replay window plus monotonic timestamp check.
        val now = System.currentTimeMillis()
        if (kotlin.math.abs(now - envelope.timestampMs) > TimeUnit.MINUTES.toMillis(2)) return false
        val lastAccepted = AppPrefs.lastAcceptedTimestamp(this)
        if (envelope.timestampMs <= lastAccepted) return false

        val executed = handleIncomingCommand(envelope.command)
        if (executed) {
            AppPrefs.setLastAcceptedTimestamp(this, envelope.timestampMs)
        }
        return executed
    }

    private data class PendingPair(
        val nonce: String,
        val candidateSecret: String,
        val code: String,
        val controllerPublicKeyB64: String,
        val hostPublicKeyB64: String,
        val createdAtMs: Long,
    )

    private fun processPairingWrite(deviceAddress: String, raw: ByteArray): String {
        if (!AppPrefs.isPairingModeEnabled(this)) {
            DebugLog.append(this, "HOST_PAIR", "Rejected $deviceAddress: pairing mode OFF")
            return "PAIR_MODE_OFF"
        }
        val message = PairingCodec.decode(raw)
        if (message.isEmpty()) {
            DebugLog.append(this, "HOST_PAIR", "Rejected $deviceAddress: empty payload")
            return "FAIL"
        }
        DebugLog.append(this, "HOST_PAIR", "Message from $deviceAddress: ${message[0]}")
        return when (message[0]) {
            "PAIR_ECDH_INIT" -> onPairEcdhInit(deviceAddress, message)
            "PAIR_ECDH_CONFIRM" -> onPairEcdhConfirm(deviceAddress, message)
            else -> "FAIL"
        }
    }

    private fun onPairEcdhInit(deviceAddress: String, message: List<String>): String {
        if (message.size < 3) {
            DebugLog.append(this, "HOST_PAIR", "Init failed for $deviceAddress: bad format")
            return "PAIR_INIT_BAD_FORMAT"
        }
        val nonce = message[1]
        val controllerPublicB64 = message[2]
        if (nonce.length < 8 || controllerPublicB64.length < 32) {
            DebugLog.append(this, "HOST_PAIR", "Init failed for $deviceAddress: bad nonce/pubkey length")
            return "PAIR_INIT_BAD_VALUES"
        }
        val controllerPublicKey = runCatching { PairingCrypto.decodePublicKey(controllerPublicB64) }.getOrNull()
            ?: run {
                DebugLog.append(this, "HOST_PAIR", "Init failed for $deviceAddress: invalid public key")
                return "PAIR_INIT_BAD_PUBKEY"
            }
        val hostKeyPair = PairingCrypto.generateKeyPair()
        val hostPublicB64 = PairingCrypto.encodePublicKey(hostKeyPair.public)
        val secretMaterial = PairingCrypto.deriveSharedSecretMaterial(hostKeyPair.private, controllerPublicKey)
        val code = PairingCrypto.shortAuthCode(secretMaterial, nonce, controllerPublicB64, hostPublicB64)
        val candidateSecret = PairingCrypto.derivePairingSecret(
            secretMaterial = secretMaterial,
            nonce = nonce,
            localPublicKeyB64 = controllerPublicB64,
            remotePublicKeyB64 = hostPublicB64,
        )
        pendingPairsByNonce[nonce] = PendingPair(
            nonce = nonce,
            candidateSecret = candidateSecret,
            code = code,
            controllerPublicKeyB64 = controllerPublicB64,
            hostPublicKeyB64 = hostPublicB64,
            createdAtMs = System.currentTimeMillis(),
        )
        AppPrefs.setPendingPairCode(this, code)
        AppPrefs.setApprovedPairCode(this, null)
        DebugLog.append(this, "HOST_PAIR", "Generated code $code for $deviceAddress")
        gattServer?.getService(BleProtocol.SERVICE_UUID)
            ?.getCharacteristic(BleProtocol.PAIRING_CHAR_UUID)
            ?.value = PairingCodec.encode(listOf("PAIR_ECDH_CODE", code, hostPublicB64))
        return "PAIR_ECDH_CODE|$code|$hostPublicB64"
    }

    private fun onPairEcdhConfirm(deviceAddress: String, message: List<String>): String {
        if (message.size < 4) return "PAIR_CONFIRM_BAD_FORMAT"
        val nonce = message[1]
        val controllerPublicB64 = message[2]
        val hostPublicB64 = message[3]
        val pending = pendingPairsByNonce[nonce] ?: run {
            DebugLog.append(this, "HOST_PAIR", "Confirm failed for $deviceAddress: no pending nonce $nonce")
            return "PAIR_CONFIRM_NO_PENDING"
        }
        val notExpired = System.currentTimeMillis() - pending.createdAtMs <= TimeUnit.MINUTES.toMillis(2)
        if (!notExpired || pending.nonce != nonce) return "PAIR_CONFIRM_NONCE_EXPIRED"
        if (pending.controllerPublicKeyB64 != controllerPublicB64 || pending.hostPublicKeyB64 != hostPublicB64) {
            DebugLog.append(this, "HOST_PAIR", "Confirm failed for $deviceAddress: key mismatch")
            return "PAIR_CONFIRM_KEY_MISMATCH"
        }
        if (AppPrefs.approvedPairCode(this) != pending.code) {
            DebugLog.append(this, "HOST_PAIR", "Confirm failed for $deviceAddress: host code not approved")
            return "PAIR_CONFIRM_NOT_APPROVED"
        }

        AppPrefs.setSharedSecret(this, pending.candidateSecret)
        AppPrefs.setPendingPairCode(this, null)
        AppPrefs.setApprovedPairCode(this, null)
        AppPrefs.setLastPairedController(this, deviceAddress)
        pendingPairsByNonce.remove(nonce)
        DebugLog.append(this, "HOST_PAIR", "Pairing success for $deviceAddress")
        gattServer?.getService(BleProtocol.SERVICE_UUID)
            ?.getCharacteristic(BleProtocol.PAIRING_CHAR_UUID)
            ?.value = PairingCodec.encode(listOf("PAIR_ECDH_OK"))
        return "PAIR_ECDH_OK"
    }

    private fun createNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Instant Hotspot Host",
                NotificationManager.IMPORTANCE_LOW,
            )
            manager.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Instant Hotspot Host active")
            .setContentText("Listening for offline hotspot commands")
            .setSmallIcon(R.drawable.ic_hotspot_tile)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "host_ble_service"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_RESTART_ADVERTISING = "com.spandan.instanthotspot.action.RESTART_ADVERTISING"
    }
}
