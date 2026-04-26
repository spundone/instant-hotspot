package com.spandan.instanthotspot.host

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
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
import android.app.PendingIntent
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.spandan.instanthotspot.MainActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import com.spandan.instanthotspot.R
import com.spandan.instanthotspot.core.AppPrefs
import com.spandan.instanthotspot.core.BleProtocol
import com.spandan.instanthotspot.core.CommandCodec
import com.spandan.instanthotspot.core.DebugLog
import com.spandan.instanthotspot.core.HostPairingPersistence
import com.spandan.instanthotspot.core.HostPendingPairSnapshot
import com.spandan.instanthotspot.core.HostStateCodec
import com.spandan.instanthotspot.core.HotspotCommand
import com.spandan.instanthotspot.core.HotspotController
import com.spandan.instanthotspot.core.LocalAlertPlayer
import com.spandan.instanthotspot.core.NetworkRadioTuning
import com.spandan.instanthotspot.core.HotspotStateProbe
import com.spandan.instanthotspot.core.PairedControllerRegistry
import com.spandan.instanthotspot.core.PairingCrypto
import com.spandan.instanthotspot.core.PairingCodec
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SuppressLint("MissingPermission")
class HostBleService : Service() {
    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private val pendingPairsByNonce = mutableMapOf<String, PendingPair>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val hostCommandExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ih-host-cmd")
    }

    private val refreshNotifRunnable = object : Runnable {
        override fun run() {
            if (!AppPrefs.isHostServiceActive(this@HostBleService)) return
            doRefreshHostForegroundNotif()
            mainHandler.postDelayed(this, NOTIFICATION_REFRESH_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureHostNotificationChannel()
        startOrUpdateHostForegroundNotification(buildHostForegroundNotification())
        AppPrefs.setHostServiceActive(this, true)
        restorePendingPairsFromDisk()
        DebugLog.append(this, "HOST_SVC", "Service created")
        startGattServer()
        startAdvertising()
        mainHandler.post(refreshNotifRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RESTART_ADVERTISING) {
            DebugLog.append(this, "HOST_SVC", "Force re-advertise requested")
            stopAdvertising()
            startAdvertising()
            mainHandler.post { doRefreshHostForegroundNotif() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(refreshNotifRunnable)
        mainHandler.removeCallbacks(verifyApRunnable)
        hostCommandExecutor.shutdown()
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
            HotspotCommand.HOTSPOT_ON -> HotspotController.enableHotspot(this)
            HotspotCommand.HOTSPOT_OFF -> HotspotController.disableHotspot(this)
            HotspotCommand.HOTSPOT_TOGGLE -> {
                when (HotspotStateProbe.currentApState(this)) {
                    HotspotStateProbe.ApState.UP -> HotspotController.disableHotspot(this)
                    HotspotStateProbe.ApState.DOWN, HotspotStateProbe.ApState.UNKNOWN -> {
                        HotspotController.enableHotspot(this)
                    }
                }
            }
            HotspotCommand.RING_REMOTE -> LocalAlertPlayer.playAttention(this)
            HotspotCommand.NET_5G_ONLY -> NetworkRadioTuning.apply5gOnly(this)
            HotspotCommand.NET_REVERT_MODE -> NetworkRadioTuning.revertPreferredMode(this)
        }
        DebugLog.append(
            this,
            "HOST_CMD",
            "Execution result=$ok :: ${HotspotController.lastHotspotExecutionReport()}",
        )
        if (command == HotspotCommand.HOTSPOT_ON ||
            command == HotspotCommand.HOTSPOT_OFF ||
            command == HotspotCommand.HOTSPOT_TOGGLE
        ) {
            mainHandler.removeCallbacks(verifyApRunnable)
            mainHandler.postDelayed(verifyApRunnable, 3_200L)
        }
        return ok
    }

    private val verifyApRunnable = Runnable {
        val st = HotspotStateProbe.currentApState(this)
        val line = when (st) {
            HotspotStateProbe.ApState.UP -> "Soft AP probe: ON"
            HotspotStateProbe.ApState.DOWN -> "Soft AP probe: still OFF (ROM may need Settings)"
            HotspotStateProbe.ApState.UNKNOWN -> "Soft AP probe: unknown (tethering stack)"
        }
        AppPrefs.setLastApStateLine(this, line)
        DebugLog.append(this, "HOST_CMD", "After toggle: $line")
        // One ongoing foreground notification is enough; avoid a second heads-up after each command.
        mainHandler.post { doRefreshHostForegroundNotif() }
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
        val stateCharacteristic = BluetoothGattCharacteristic(
            BleProtocol.STATE_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        val service = BluetoothGattService(BleProtocol.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(commandCharacteristic)
        service.addCharacteristic(pairingCharacteristic)
        service.addCharacteristic(configCharacteristic)
        service.addCharacteristic(stateCharacteristic)
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
            // Any throw here used to take down the app from the GATT / binder thread (e.g. long
            // characteristic value, OEM BLE bugs, or I/O in pairing).
            var response = "FAIL"
            try {
                response = when {
                    characteristic?.uuid == BleProtocol.COMMAND_CHAR_UUID && value != null && device != null ->
                        if (processCommandWrite(device, value)) "OK" else "FAIL"
                    characteristic?.uuid == BleProtocol.COMMAND_CHAR_UUID && value != null && device == null ->
                        "FAIL"
                    characteristic?.uuid == BleProtocol.PAIRING_CHAR_UUID && value != null && device != null ->
                        processPairingWrite(device.address, value)
                    else -> "FAIL"
                }
                if (characteristic?.uuid == BleProtocol.PAIRING_CHAR_UUID) {
                    // Keep the latest pairing status readable by clients after write.
                    runCatching {
                        characteristic.value = response.toByteArray(Charsets.UTF_8)
                    }
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
            } catch (t: Throwable) {
                DebugLog.append(
                    this@HostBleService,
                    "HOST_BLE",
                    "onCharacteristicWriteRequest: ${t.javaClass.simpleName} ${t.message}",
                )
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                }
            }
        }

        override fun onCharacteristicReadRequest(
            device: android.bluetooth.BluetoothDevice?,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic?,
        ) {
            val response: ByteArray? = try {
                when (characteristic?.uuid) {
                    BleProtocol.PAIRING_CHAR_UUID -> characteristic.value ?: byteArrayOf()
                    BleProtocol.CONFIG_CHAR_UUID -> HotspotController.hotspotConfigSummary().toByteArray(Charsets.UTF_8)
                    BleProtocol.STATE_CHAR_UUID ->
                        HostStateCodec.encodeFromProbe(HotspotStateProbe.currentApState(this@HostBleService))
                    else -> null
                }
            } catch (t: Throwable) {
                DebugLog.append(
                    this@HostBleService,
                    "HOST_BLE",
                    "onCharacteristicReadRequest: ${t.javaClass.simpleName} ${t.message}",
                )
                null
            }
            if (response != null) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, response)
            } else {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
            }
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {}

    private fun processCommandWrite(device: BluetoothDevice, raw: ByteArray): Boolean {
        if (AppPrefs.isHostBondAllowlistEnabled(this)) {
            if (device.bondState != BluetoothDevice.BOND_BONDED) {
                DebugLog.append(
                    this,
                    "HOST_CMD",
                    "Rejected command from ${device.address}: bond allowlist requires paired (bonded) device",
                )
                return false
            }
        }
        val envelope = CommandCodec.decode(raw) ?: return false
        val payload = CommandCodec.payload(envelope.command, envelope.timestampMs, envelope.nonce)
        val resolution = PairedControllerRegistry.resolveCommandSecret(
            this,
            device.address,
            payload,
            envelope.signature,
        )
        if (resolution == null) {
            DebugLog.append(
                this,
                "HOST_CMD",
                "Signature reject from ${device.address} (try each paired secret if MAC changed — BLE privacy)",
            )
            return false
        }
        resolution.remapFromStoredAddress?.let { old ->
            PairedControllerRegistry.replaceBluetoothAddress(this, old, device.address)
            DebugLog.append(
                this,
                "HOST_CMD",
                "Paired controller address updated $old → ${device.address} (BLE privacy / MAC rotation)",
            )
        }

        // Basic replay window plus monotonic timestamp check.
        val now = System.currentTimeMillis()
        if (kotlin.math.abs(now - envelope.timestampMs) > TimeUnit.MINUTES.toMillis(2)) return false
        val lastAccepted = AppPrefs.lastAcceptedTimestamp(this)
        if (envelope.timestampMs <= lastAccepted) return false

        // Commit idempotency and respond immediately. Hotspot / tethering can block for many seconds
        // and must not run on the GATT / binder thread or BLE clients time out the write.
        AppPrefs.setLastAcceptedTimestamp(this, envelope.timestampMs)
        val command = envelope.command
        hostCommandExecutor.execute {
            try {
                val ok = handleIncomingCommand(command)
                if (!ok) {
                    DebugLog.append(this, "HOST_CMD", "Command finished without success: $command")
                }
            } catch (t: Throwable) {
                DebugLog.append(this, "HOST_CMD", "Command error: ${t.message}")
            }
        }
        return true
    }

    private data class PendingPair(
        val nonce: String,
        val candidateSecret: String,
        val code: String,
        val controllerPublicKeyB64: String,
        val hostPublicKeyB64: String,
        val createdAtMs: Long,
    )

    private fun persistPendingPairsToDisk() {
        val list = pendingPairsByNonce.values.map { p ->
            HostPendingPairSnapshot(
                nonce = p.nonce,
                candidateSecret = p.candidateSecret,
                code = p.code,
                controllerPublicKeyB64 = p.controllerPublicKeyB64,
                hostPublicKeyB64 = p.hostPublicKeyB64,
                createdAtMs = p.createdAtMs,
            )
        }
        HostPairingPersistence.replaceAll(this, list)
    }

    private fun restorePendingPairsFromDisk() {
        val now = System.currentTimeMillis()
        val maxAge = TimeUnit.MINUTES.toMillis(2)
        val loaded = HostPairingPersistence.load(this)
        var kept = 0
        for (e in loaded) {
            if (now - e.createdAtMs > maxAge) continue
            pendingPairsByNonce[e.nonce] = PendingPair(
                nonce = e.nonce,
                candidateSecret = e.candidateSecret,
                code = e.code,
                controllerPublicKeyB64 = e.controllerPublicKeyB64,
                hostPublicKeyB64 = e.hostPublicKeyB64,
                createdAtMs = e.createdAtMs,
            )
            kept++
        }
        if (kept != loaded.size) {
            persistPendingPairsToDisk()
        } else if (kept > 0) {
            val latest = pendingPairsByNonce.values.maxByOrNull { it.createdAtMs }
            if (latest != null) {
                AppPrefs.setPendingPairCode(this, latest.code)
            }
        }
    }

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
        persistPendingPairsToDisk()
        AppPrefs.setPendingPairCode(this, code)
        AppPrefs.setApprovedPairCode(this, null)
        DebugLog.append(this, "HOST_PAIR", "Generated code $code for $deviceAddress")
        // Characteristic value is set in GATT onCharacteristicWriteRequest from the return string
        // (avoids a second write path that can throw on some stacks).
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

        PairedControllerRegistry.upsert(this, deviceAddress, pending.candidateSecret)
        AppPrefs.setPendingPairCode(this, null)
        AppPrefs.setApprovedPairCode(this, null)
        AppPrefs.setLastPairedController(this, deviceAddress)
        AppPrefs.setHostPairedSinceMs(this, System.currentTimeMillis())
        pendingPairsByNonce.remove(nonce)
        persistPendingPairsToDisk()
        DebugLog.append(this, "HOST_PAIR", "Pairing success for $deviceAddress")
        mainHandler.post { doRefreshHostForegroundNotif() }
        return "PAIR_ECDH_OK"
    }

    private fun ensureHostNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_host_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        channel.description = getString(R.string.notif_host_channel_desc)
        channel.setShowBadge(true)
        manager.createNotificationChannel(channel)
    }

    private fun doRefreshHostForegroundNotif() {
        val n = runCatching { buildHostForegroundNotification() }
            .onFailure { t ->
                DebugLog.append(
                    this,
                    "HOST_SVC",
                    "buildHostForegroundNotification: ${t.javaClass.simpleName} ${t.message}",
                )
            }
            .getOrNull() ?: return
        startOrUpdateHostForegroundNotification(n)
    }

    private fun startOrUpdateHostForegroundNotification(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun buildHostForegroundNotification(): Notification {
        ensureHostNotificationChannel()
        val ap = HotspotStateProbe.currentApState(this)
        val (titleRes, shortChip) = when (ap) {
            HotspotStateProbe.ApState.UP -> R.string.notif_host_title_hotspot_on to
                getString(R.string.notif_host_short_hotspot_on)
            HotspotStateProbe.ApState.DOWN -> R.string.notif_host_title_hotspot_off to
                getString(R.string.notif_host_short_hotspot_off)
            HotspotStateProbe.ApState.UNKNOWN -> R.string.notif_host_title_probe_unknown to
                getString(R.string.notif_host_short_status_unknown)
        }
        val apWord = when (ap) {
            HotspotStateProbe.ApState.UP -> getString(R.string.widget_hotspot_on)
            HotspotStateProbe.ApState.DOWN -> getString(R.string.widget_hotspot_off)
            HotspotStateProbe.ApState.UNKNOWN -> getString(R.string.notif_host_ap_state_line_unknown)
        }
        val sinceLine = when {
            AppPrefs.lastPairedController(this).isNullOrBlank() -> getString(
                R.string.notif_host_line_paired_since,
                getString(R.string.notif_host_paired_none),
            )
            AppPrefs.hostPairedSinceMs(this) <= 0L -> getString(
                R.string.notif_host_line_paired_since,
                getString(R.string.notif_host_paired_since_unknown),
            )
            else -> getString(
                R.string.notif_host_line_paired_since,
                formatPairedSinceInstant(AppPrefs.hostPairedSinceMs(this)),
            )
        }
        val bigText = buildString {
            appendLine(getString(R.string.notif_host_line_access_point, apWord))
            appendLine(getString(R.string.notif_host_line_paired, friendlyPairedControllerLine()))
            appendLine(sinceLine)
        }
        val now = System.currentTimeMillis()
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP,
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        var b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_hotspot_tile)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentTitle(getString(titleRes))
            .setContentText(getString(R.string.notif_host_content_listening))
            .setContentIntent(open)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText.trimEnd()))
            .setShowWhen(true)
            .setWhen(now)
        b = trySetShortCriticalText(tryRequestPromotedOngoing(b), shortChip)
        return b.build()
    }

    private fun friendlyPairedControllerLine(): String {
        val entries = PairedControllerRegistry.all(this)
        if (entries.isEmpty()) {
            return getString(R.string.notif_host_paired_none)
        }
        if (entries.size == 1) {
            val e = entries[0]
            return friendlyOneControllerLine(e.address)
        }
        return getString(R.string.notif_host_controllers_count, entries.size)
    }

    private fun friendlyOneControllerLine(addr: String): String {
        if (addr.isBlank()) {
            return getString(R.string.notif_host_paired_none)
        }
        val name = runCatching {
            val a = getSystemService(BluetoothManager::class.java)?.adapter ?: return@runCatching null
            a.getRemoteDevice(addr).name?.trim()
        }.getOrNull()
        return if (name.isNullOrBlank()) {
            addr
        } else {
            "$name · $addr"
        }
    }

    private fun formatPairedSinceInstant(epochMs: Long): String {
        val at = Instant.ofEpochMilli(epochMs)
            .atZone(ZoneId.systemDefault())
        return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).format(at)
    }

    private fun tryRequestPromotedOngoing(b: NotificationCompat.Builder): NotificationCompat.Builder {
        if (Build.VERSION.SDK_INT < 35) return b
        return runCatching {
            val m = b.javaClass.getMethod("setRequestPromotedOngoing", java.lang.Boolean.TYPE)
            m.invoke(b, true)
            b
        }.getOrElse { b }
    }

    private fun trySetShortCriticalText(b: NotificationCompat.Builder, t: String): NotificationCompat.Builder {
        if (Build.VERSION.SDK_INT < 35) return b
        return runCatching {
            val m = b.javaClass.getMethod("setShortCriticalText", CharSequence::class.java)
            m.invoke(b, t)
            b
        }.getOrElse { b }
    }

    companion object {
        /** Newer channel (IMPORTANCE_DEFAULT) so promoted live-updates can apply; older ID kept unused. */
        private const val CHANNEL_ID = "host_ble_status"
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_REFRESH_MS = 20_000L
        const val ACTION_RESTART_ADVERTISING = "com.spandan.instanthotspot.action.RESTART_ADVERTISING"
    }
}
