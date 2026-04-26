package com.spandan.instanthotspot

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.WindowCompat
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.MaterialToolbar
import com.spandan.instanthotspot.core.AppPrefs
import com.spandan.instanthotspot.core.DebugLog
import com.spandan.instanthotspot.core.OnboardingV2
import com.spandan.instanthotspot.onboarding.OnboardingPagerAdapter
import com.spandan.instanthotspot.core.HotspotCommand
import com.spandan.instanthotspot.controller.BlePairingClient
import com.spandan.instanthotspot.controller.CommandSendStatus
import com.spandan.instanthotspot.controller.ControllerCommandSender
import com.spandan.instanthotspot.controller.PairingStartError
import com.spandan.instanthotspot.controller.PairingSession
import com.spandan.instanthotspot.core.HotspotController
import com.spandan.instanthotspot.core.HostCompatSummary
import com.spandan.instanthotspot.core.RandomSecret
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.spandan.instanthotspot.host.HostBleService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private var inOnboarding: Boolean = false
    private var useSimpleLayout: Boolean = false
    private lateinit var hostSection: View
    private lateinit var controllerSection: View
    private lateinit var hostSecretText: TextView
    private lateinit var hostPendingCodeText: TextView
    private lateinit var hostPairingStatusText: TextView
    private lateinit var hostRootStatusText: TextView
    private lateinit var hostPairedDeviceText: TextView
    private lateinit var secretInput: EditText
    private lateinit var controllerPairCodeText: TextView
    private lateinit var controllerPairedDeviceText: TextView
    private lateinit var clientConnectionStatusText: TextView
    private lateinit var clientHotspotConfigText: TextView
    private lateinit var hostDebugLogText: TextView
    private lateinit var controllerDebugLogText: TextView
    private lateinit var appVersionText: TextView
    private lateinit var hostInstallCompatText: TextView
    private lateinit var btnConfirmPairing: Button
    private lateinit var btnHostApproveCode: Button
    private lateinit var btnTogglePairingMode: Button
    private lateinit var btnHostReAdvertise: Button
    private lateinit var btnStartPairing: Button
    private lateinit var btnSaveManualSecret: Button
    private lateinit var btnClientHotspotOn: Button
    private lateinit var btnClientHotspotOff: Button
    private lateinit var btnClientSyncConfig: Button
    private lateinit var btnHostClearLogs: Button
    private lateinit var btnControllerClearLogs: Button
    private lateinit var btnUnpairHost: Button
    private lateinit var btnUnpairController: Button
    @Volatile private var activePairingSession: PairingSession? = null
    @Volatile private var pairingStartInProgress = false
    @Volatile private var pairingConfirmInProgress = false
    @Volatile private var rootCheckInProgress = false
    @Volatile private var latestRootGranted: Boolean? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    private val backgroundExecutor = Executors.newSingleThreadExecutor()
    private val statusRefreshRunnable = object : Runnable {
        override fun run() {
            renderCurrentSecret()
            maybeRefreshRootStatus()
            uiHandler.postDelayed(this, STATUS_REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        OnboardingV2.migrateIfNeeded(this)
        requestRuntimePermissions()
        if (!OnboardingV2.isFlowComplete(this)) {
            inOnboarding = true
            setContentView(R.layout.activity_onboarding)
            setupOnboardingUi()
            return
        }
        inOnboarding = false
        useSimpleLayout = AppPrefs.useSimpleHome(this) && currentMode() == MODE_CONTROLLER
        if (useSimpleLayout) {
            setContentView(R.layout.activity_main_simple)
            setupSimpleHome()
        } else {
            setContentView(R.layout.activity_main)
            setupFullMain()
        }
    }

    private fun setupOnboardingUi() {
        val ver = findViewById<TextView>(R.id.onboardingVersion)
        val step = findViewById<TextView>(R.id.onboardingStepLabel)
        runCatching {
            val p = packageManager.getPackageInfo(packageName, 0)
            val vn = p.versionName ?: "?"
            val vc = p.longVersionCode
            ver.text = "v$vn ($vc)"
        }.onFailure { ver.text = "" }
        val pager = findViewById<ViewPager2>(R.id.onboardingPager)
        pager.isUserInputEnabled = false
        pager.adapter = OnboardingPagerAdapter(this)
        var page = OnboardingV2.currentPageOrDone(this)
        if (page < 0) page = 0
        pager.setCurrentItem(page.coerceIn(0, OnboardingV2.PAGE_COUNT - 1), false)
        val back = findViewById<Button>(R.id.onboardingBack)
        val next = findViewById<Button>(R.id.onboardingNext)
        fun updateNavLabels(p: Int) {
            OnboardingV2.setPage(this, p)
            step.text = getString(R.string.onboarding_step, p + 1, OnboardingV2.PAGE_COUNT)
            back.isEnabled = p > 0
            next.text = if (p >= OnboardingV2.PAGE_COUNT - 1) {
                getString(R.string.onboarding_finish)
            } else {
                getString(R.string.onboarding_next)
            }
        }
        updateNavLabels(pager.currentItem)
        pager.registerOnPageChangeCallback(object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateNavLabels(position)
            }
        })
        back.setOnClickListener {
            if (pager.currentItem > 0) {
                pager.setCurrentItem(pager.currentItem - 1, true)
            } else {
                onBackPressedDispatcher.onBackPressed()
            }
        }
        next.setOnClickListener {
            if (pager.currentItem < OnboardingV2.PAGE_COUNT - 1) {
                pager.setCurrentItem(pager.currentItem + 1, true)
            } else {
                OnboardingV2.markFlowComplete(this, currentMode() == MODE_CONTROLLER)
                recreate()
            }
        }
    }

    private fun setupSimpleHome() {
        val t = findViewById<MaterialToolbar>(R.id.simpleToolbar)
        t.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_full_console -> {
                    AppPrefs.setUseSimpleHome(this, false)
                    recreate()
                    true
                }
                R.id.menu_rerun_onboarding -> {
                    OnboardingV2.resetFlow(this)
                    recreate()
                    true
                }
                else -> false
            }
        }
        findViewById<TextView>(R.id.simpleAppVersion).let { st ->
            runCatching {
                val p = packageManager.getPackageInfo(packageName, 0)
                st.text = "Version ${p.versionName} (${p.longVersionCode})"
            }
        }
        findViewById<Button>(R.id.btnSimpleOn).setOnClickListener {
            sendClientHotspotCommand(
                HotspotCommand.HOTSPOT_ON,
                "HOTSPOT_ON",
                R.string.toast_client_hotspot_on_sent,
            )
        }
        findViewById<Button>(R.id.btnSimpleOff).setOnClickListener {
            sendClientHotspotCommand(
                HotspotCommand.HOTSPOT_OFF,
                "HOTSPOT_OFF",
                R.string.toast_client_hotspot_off_sent,
            )
        }
        renderSimpleHome()
    }

    private fun renderSimpleHome() {
        if (!useSimpleLayout) return
        val t = findViewById<TextView>(R.id.simpleStatusText) ?: return
        val paired = AppPrefs.isClientPaired(this)
        val connected = AppPrefs.isHostReachableRecently(this)
        t.text = when {
            !paired -> getString(R.string.client_status_not_paired)
            connected -> getString(R.string.client_status_paired_connected)
            else -> getString(R.string.client_status_paired_disconnected)
        }
    }

    private fun setupFullMain() {
        appVersionText = findViewById(R.id.appVersionText)
        hostInstallCompatText = findViewById(R.id.hostInstallCompatText)
        runCatching {
            val p = packageManager.getPackageInfo(packageName, 0)
            val v = p.versionName ?: "unknown"
            val c = p.longVersionCode
            appVersionText.text = "Version $v ($c)"
            DebugLog.append(this, "APP", "Boot: versionName=$v versionCode=$c")
        }.onFailure {
            appVersionText.text = ""
        }
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val modeGroup = findViewById<RadioGroup>(R.id.modeGroup)
        val host = findViewById<RadioButton>(R.id.radioHost)
        val controller = findViewById<RadioButton>(R.id.radioController)
        hostSection = findViewById(R.id.hostSection)
        controllerSection = findViewById(R.id.controllerSection)
        hostSecretText = findViewById(R.id.hostSecretText)
        hostPendingCodeText = findViewById(R.id.hostPendingCodeText)
        hostPairingStatusText = findViewById(R.id.hostPairingStatusText)
        hostRootStatusText = findViewById(R.id.hostRootStatusText)
        hostPairedDeviceText = findViewById(R.id.hostPairedDeviceText)
        secretInput = findViewById(R.id.secretInput)
        btnStartPairing = findViewById(R.id.btnStartPairing)
        btnSaveManualSecret = findViewById(R.id.btnSaveManualSecret)
        btnConfirmPairing = findViewById(R.id.btnConfirmPairing)
        btnHostApproveCode = findViewById(R.id.btnHostApproveCode)
        btnTogglePairingMode = findViewById(R.id.btnTogglePairingMode)
        btnHostReAdvertise = findViewById(R.id.btnHostReAdvertise)
        controllerPairCodeText = findViewById(R.id.controllerPairCodeText)
        controllerPairedDeviceText = findViewById(R.id.controllerPairedDeviceText)
        clientConnectionStatusText = findViewById(R.id.clientConnectionStatusText)
        clientHotspotConfigText = findViewById(R.id.clientHotspotConfigText)
        hostDebugLogText = findViewById(R.id.hostDebugLogText)
        controllerDebugLogText = findViewById(R.id.controllerDebugLogText)
        btnClientHotspotOn = findViewById(R.id.btnClientHotspotOn)
        btnClientHotspotOff = findViewById(R.id.btnClientHotspotOff)
        btnClientSyncConfig = findViewById(R.id.btnClientSyncConfig)
        btnHostClearLogs = findViewById(R.id.btnHostClearLogs)
        btnControllerClearLogs = findViewById(R.id.btnControllerClearLogs)
        btnUnpairHost = findViewById(R.id.btnUnpairHost)
        btnUnpairController = findViewById(R.id.btnUnpairController)

        renderCurrentSecret()

        when (prefs.getString(KEY_MODE, MODE_CONTROLLER)) {
            MODE_HOST -> host.isChecked = true
            else -> controller.isChecked = true
        }
        val initialMode = if (host.isChecked) MODE_HOST else MODE_CONTROLLER
        applyModeUi(initialMode)
        if (initialMode == MODE_HOST) {
            ensureHostSecretInitialized()
            startService(Intent(this, HostBleService::class.java))
        }

        btnStartPairing.setOnClickListener {
            startPairingHandshake()
        }
        btnSaveManualSecret.setOnClickListener {
            saveManualSecret()
        }
        btnConfirmPairing.setOnClickListener {
            confirmPairingHandshake()
        }
        btnHostApproveCode.setOnClickListener {
            approveHostPendingCode()
        }
        btnTogglePairingMode.setOnClickListener {
            toggleHostPairingMode()
        }
        btnHostReAdvertise.setOnClickListener {
            forceHostReAdvertise()
        }
        btnClientHotspotOn.setOnClickListener {
            sendClientHotspotCommand(HotspotCommand.HOTSPOT_ON, "HOTSPOT_ON", R.string.toast_client_hotspot_on_sent)
        }
        btnClientHotspotOff.setOnClickListener {
            sendClientHotspotCommand(HotspotCommand.HOTSPOT_OFF, "HOTSPOT_OFF", R.string.toast_client_hotspot_off_sent)
        }
        btnClientSyncConfig.setOnClickListener {
            syncHotspotConfigToClient()
        }
        btnHostClearLogs.setOnClickListener {
            clearLogs()
        }
        btnControllerClearLogs.setOnClickListener {
            clearLogs()
        }
        btnUnpairHost.setOnClickListener {
            unpairAsHost()
        }
        btnUnpairController.setOnClickListener {
            unpairAsController()
        }

        secretInput.setOnEditorActionListener { _, _, _ ->
            saveManualSecret()
            true
        }

        modeGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = if (checkedId == R.id.radioHost) MODE_HOST else MODE_CONTROLLER
            prefs.edit().putString(KEY_MODE, mode).apply()
            if (mode == MODE_HOST) {
                ensureHostSecretInitialized()
                startService(Intent(this, HostBleService::class.java))
            } else {
                stopService(Intent(this, HostBleService::class.java))
            }
            applyModeUi(mode)
        }
    }

    override fun onResume() {
        super.onResume()
        if (inOnboarding) {
            return
        }
        if (useSimpleLayout) {
            renderSimpleHome()
            return
        }
        uiHandler.removeCallbacks(statusRefreshRunnable)
        uiHandler.post(statusRefreshRunnable)
    }

    override fun onPause() {
        if (!inOnboarding) {
            uiHandler.removeCallbacks(statusRefreshRunnable)
        }
        super.onPause()
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(statusRefreshRunnable)
        backgroundExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun requestRuntimePermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions += Manifest.permission.BLUETOOTH_SCAN
            permissions += Manifest.permission.BLUETOOTH_CONNECT
            permissions += Manifest.permission.BLUETOOTH_ADVERTISE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = permissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_CODE_PERMS)
        }
    }

    private fun applyModeUi(mode: String) {
        val hostVisible = mode == MODE_HOST
        hostSection.visibility = if (hostVisible) View.VISIBLE else View.GONE
        controllerSection.visibility = if (hostVisible) View.GONE else View.VISIBLE
        if (hostVisible) {
            refreshHostInstallCompat()
        }
        renderCurrentSecret()
        uiHandler.removeCallbacks(statusRefreshRunnable)
        uiHandler.post(statusRefreshRunnable)
    }

    private fun refreshHostInstallCompat() {
        val probe = AppPrefs.lastApStateLine(this)
        val apLine = if (probe.isNullOrBlank()) "Last AP probe: —" else "Last AP probe: $probe"
        hostInstallCompatText.text = HostCompatSummary.build(this) + "\n" + apLine
    }

    private fun renderCurrentSecret() {
        if (useSimpleLayout) {
            renderSimpleHome()
            return
        }
        if (currentMode() == MODE_HOST) {
            refreshHostInstallCompat()
        }
        val secret = AppPrefs.sharedSecret(this)
        val hasCustom = AppPrefs.hasNonDefaultSecret(this)
        hostSecretText.text = if (hasCustom) secret else getString(R.string.pairing_not_set)
        val pending = AppPrefs.pendingPairCode(this)
        hostPendingCodeText.text = if (pending.isNullOrBlank()) {
            getString(R.string.pending_code_none)
        } else {
            "Pending code: $pending"
        }
        val approved = AppPrefs.approvedPairCode(this)
        btnHostApproveCode.isEnabled = !pending.isNullOrBlank() && pending != approved
        val serviceActive = AppPrefs.isHostServiceActive(this)
        val pairingModeEnabled = AppPrefs.isPairingModeEnabled(this)
        hostPairingStatusText.text = when {
            !serviceActive -> getString(R.string.host_status_off)
            pairingModeEnabled -> getString(R.string.host_status_on_pairing_on)
            else -> getString(R.string.host_status_on_pairing_off)
        }
        hostRootStatusText.text = when (latestRootGranted) {
            true -> getString(R.string.root_status_granted)
            false -> getString(R.string.root_status_denied)
            null -> getString(R.string.root_status_unknown)
        }
        btnTogglePairingMode.text = if (pairingModeEnabled) {
            getString(R.string.disable_pairing_mode)
        } else {
            getString(R.string.enable_pairing_mode)
        }
        btnStartPairing.isEnabled = !pairingStartInProgress
        btnConfirmPairing.isEnabled = activePairingSession != null && !pairingConfirmInProgress
        val paired = AppPrefs.isClientPaired(this)
        val pairedHost = AppPrefs.lastPairedHost(this)
        val pairedController = AppPrefs.lastPairedController(this)
        val connected = AppPrefs.isHostReachableRecently(this)
        clientConnectionStatusText.text = when {
            !paired -> getString(R.string.client_status_not_paired)
            connected -> getString(R.string.client_status_paired_connected)
            else -> getString(R.string.client_status_paired_disconnected)
        }
        btnClientHotspotOn.isEnabled = paired && !pairingStartInProgress && !pairingConfirmInProgress
        btnClientHotspotOff.isEnabled = paired && !pairingStartInProgress && !pairingConfirmInProgress
        btnClientSyncConfig.isEnabled = paired && !pairingStartInProgress && !pairingConfirmInProgress
        if (paired && !pairingStartInProgress && activePairingSession == null) {
            btnStartPairing.isEnabled = false
        }
        val savedConfig = AppPrefs.lastSyncedHotspotConfig(this)
        clientHotspotConfigText.text = if (savedConfig.isNullOrBlank()) {
            getString(R.string.client_hotspot_config_empty)
        } else {
            formatHotspotConfigForDisplay(savedConfig)
        }
        hostPairedDeviceText.text = if (pairedController.isNullOrBlank()) {
            getString(R.string.host_paired_device_none)
        } else {
            getString(R.string.host_paired_device_value, pairedController)
        }
        controllerPairedDeviceText.text = if (pairedHost.isNullOrBlank()) {
            getString(R.string.controller_paired_device_none)
        } else {
            getString(R.string.controller_paired_device_value, pairedHost)
        }
        if (paired) {
            controllerPairCodeText.text = getString(R.string.already_paired_once)
        } else if (activePairingSession == null) {
            controllerPairCodeText.text = getString(R.string.pair_code_none)
        }
        val debug = DebugLog.read(this)
        hostDebugLogText.text = debug
        controllerDebugLogText.text = debug
        if (secretInput.text.isNullOrBlank()) {
            secretInput.setText(if (hasCustom) secret else "")
        }
    }

    private fun maybeRefreshRootStatus() {
        if (currentMode() != MODE_HOST || rootCheckInProgress) return
        rootCheckInProgress = true
        backgroundExecutor.execute {
            val granted = HotspotController.hasRootPermission()
            latestRootGranted = granted
            rootCheckInProgress = false
            if (!isFinishing && !isDestroyed) {
                runOnUiThread { renderCurrentSecret() }
            }
        }
    }

    private fun toggleHostPairingMode() {
        val next = !AppPrefs.isPairingModeEnabled(this)
        AppPrefs.setPairingModeEnabled(this, next)
        DebugLog.append(this, "HOST_UI", "Pairing mode toggled to ${if (next) "ON" else "OFF"}")
        renderCurrentSecret()
        Toast.makeText(
            this,
            if (next) getString(R.string.pairing_mode_enabled_toast) else getString(R.string.pairing_mode_disabled_toast),
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun unpairAsHost() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.unpair_or_reset_host)
            .setMessage(R.string.unpair_host_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.unpair_confirm) { _, _ ->
                val newSecret = generateReadableSecret()
                AppPrefs.unpairAsHostWithNewSecret(this, newSecret)
                AppPrefs.setPairingModeEnabled(this, true)
                activePairingSession = null
                DebugLog.append(this, "HOST_UI", "Host unpair: rotated secret, BLE service restart")
                stopService(Intent(this, HostBleService::class.java))
                if (currentMode() == MODE_HOST) {
                    startService(Intent(this, HostBleService::class.java))
                }
                renderCurrentSecret()
                Toast.makeText(this, R.string.unpair_host_done, Toast.LENGTH_LONG).show()
            }
            .show()
    }

    private fun unpairAsController() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.unpair_or_reset_controller)
            .setMessage(R.string.unpair_controller_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.unpair_confirm) { _, _ ->
                AppPrefs.unpairAsController(this)
                activePairingSession = null
                DebugLog.append(this, "CTRL_UI", "Controller unpair: cleared")
                renderCurrentSecret()
                Toast.makeText(this, R.string.unpair_controller_done, Toast.LENGTH_LONG).show()
            }
            .show()
    }

    private fun forceHostReAdvertise() {
        val intent = Intent(this, HostBleService::class.java).apply {
            action = HostBleService.ACTION_RESTART_ADVERTISING
        }
        startService(intent)
        Toast.makeText(this, getString(R.string.host_re_advertise_done), Toast.LENGTH_SHORT).show()
    }

    private fun approveHostPendingCode() {
        val pending = AppPrefs.pendingPairCode(this)
        if (pending.isNullOrBlank()) {
            DebugLog.append(this, "HOST_UI", "Approve pending code requested, but none available")
            Toast.makeText(this, getString(R.string.host_code_missing), Toast.LENGTH_SHORT).show()
            return
        }
        AppPrefs.setApprovedPairCode(this, pending)
        DebugLog.append(this, "HOST_UI", "Pending pairing code approved: $pending")
        renderCurrentSecret()
        Toast.makeText(this, getString(R.string.host_code_approved), Toast.LENGTH_SHORT).show()
    }

    private fun ensureHostSecretInitialized() {
        if (AppPrefs.hasNonDefaultSecret(this)) return
        AppPrefs.setSharedSecret(this, generateReadableSecret())
    }

    private fun generateReadableSecret(): String = RandomSecret.newReadable()

    private fun startPairingHandshake() {
        if (pairingStartInProgress || pairingConfirmInProgress) return
        DebugLog.append(this, "CTRL_UI", "Starting nearby pairing handshake")
        pairingStartInProgress = true
        btnStartPairing.isEnabled = false
        btnConfirmPairing.isEnabled = false
        backgroundExecutor.execute {
            val result = BlePairingClient.startPairing(this)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                pairingStartInProgress = false
                btnStartPairing.isEnabled = true
                val session = result.session
                if (session == null) {
                    val msg = when (result.error) {
                        PairingStartError.BLUETOOTH_OFF -> getString(R.string.pairing_failed_bluetooth_off)
                        PairingStartError.HOST_NOT_FOUND -> getString(R.string.pairing_failed_host_not_found)
                        PairingStartError.HOST_PAIRING_MODE_OFF -> getString(R.string.pairing_failed_pair_mode_off)
                        PairingStartError.HANDSHAKE_FAILED -> getString(R.string.pairing_failed_handshake)
                        null -> getString(R.string.pairing_failed)
                    }
                    DebugLog.append(this, "CTRL_UI", "Pairing start failed: ${result.error ?: "unknown"}")
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                activePairingSession = session
                DebugLog.append(this, "CTRL_UI", "Pairing code received: ${session.code}")
                controllerPairCodeText.text = "Controller code: ${session.code}"
                btnConfirmPairing.isEnabled = true
                Toast.makeText(this, getString(R.string.pairing_started), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveManualSecret() {
        val value = secretInput.text?.toString()?.trim().orEmpty()
        if (value.length < 12) {
            DebugLog.append(this, "CTRL_UI", "Manual/local pair rejected: secret too short")
            Toast.makeText(this, getString(R.string.secret_empty), Toast.LENGTH_SHORT).show()
            return
        }
        AppPrefs.setSharedSecret(this, value)
        AppPrefs.setClientPaired(this, true)
        AppPrefs.setLastPairedHost(this, "manual-secret")
        DebugLog.append(this, "CTRL_UI", "Manual/local pair applied with pasted secret")
        renderCurrentSecret()
        Toast.makeText(this, getString(R.string.manual_pair_saved), Toast.LENGTH_SHORT).show()
    }

    private fun confirmPairingHandshake() {
        val session = activePairingSession
        if (session == null) {
            DebugLog.append(this, "CTRL_UI", "Confirm pairing pressed without active session")
            Toast.makeText(this, getString(R.string.pairing_failed), Toast.LENGTH_SHORT).show()
            return
        }
        DebugLog.append(this, "CTRL_UI", "Sending PAIR_ECDH_CONFIRM for nonce ${session.nonce}")
        if (pairingConfirmInProgress || pairingStartInProgress) return
        pairingConfirmInProgress = true
        btnConfirmPairing.isEnabled = false
        backgroundExecutor.execute {
            val ok = BlePairingClient.confirmPairing(this, session)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                pairingConfirmInProgress = false
                if (!ok) {
                    DebugLog.append(this, "CTRL_UI", "Pairing confirm failed at host")
                    btnConfirmPairing.isEnabled = activePairingSession != null
                    Toast.makeText(this, getString(R.string.pairing_confirm_failed), Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                AppPrefs.setSharedSecret(this, session.candidateSecret)
                AppPrefs.setClientPaired(this, true)
                renderCurrentSecret()
                activePairingSession = null
                DebugLog.append(this, "CTRL_UI", "Pairing confirmed successfully")
                controllerPairCodeText.text = getString(R.string.pair_code_none)
                Toast.makeText(this, getString(R.string.pairing_confirmed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun sendClientHotspotCommand(
        command: HotspotCommand,
        logName: String,
        successStringRes: Int,
    ) {
        DebugLog.append(this, "CTRL_UI", "Sending $logName command")
        ControllerCommandSender.sendAsync(this, command) { status ->
            if (status == CommandSendStatus.SUCCESS) {
                AppPrefs.markHostReachableNow(this)
            }
            val message = when (status) {
                CommandSendStatus.SUCCESS -> getString(successStringRes)
                CommandSendStatus.NOT_PAIRED -> getString(R.string.client_status_not_paired)
                CommandSendStatus.BLUETOOTH_OFF -> "Bluetooth is off"
                CommandSendStatus.HOST_NOT_FOUND -> "Host not found nearby"
                CommandSendStatus.SEND_FAILED -> "Failed to send hotspot command"
            }
            DebugLog.append(this, "CTRL_UI", "$logName result: $status")
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            renderCurrentSecret()
        }
    }

    private fun syncHotspotConfigToClient() {
        btnClientSyncConfig.isEnabled = false
        DebugLog.append(this, "CTRL_UI", "Sync hotspot config requested")
        backgroundExecutor.execute {
            val config = BlePairingClient.fetchHotspotConfig(this)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (config == null) {
                    DebugLog.append(this, "CTRL_UI", "Hotspot config sync failed")
                    Toast.makeText(this, getString(R.string.client_sync_failed), Toast.LENGTH_SHORT).show()
                } else {
                    DebugLog.append(this, "CTRL_UI", "Hotspot config sync success: ${config.take(80)}")
                    clientHotspotConfigText.text = formatHotspotConfigForDisplay(config)
                    Toast.makeText(this, getString(R.string.client_sync_success), Toast.LENGTH_SHORT).show()
                }
                renderCurrentSecret()
            }
        }
    }

    private fun formatHotspotConfigForDisplay(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return getString(R.string.client_hotspot_config_empty)
        var out = t
            .replace(" ; ", "\n")
            .replace(" | ", "\n")
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
        if (!out.contains('\n') && out.length > 96) {
            out = out.chunked(96).joinToString("\n")
        }
        return out.take(8_000)
    }

    private fun currentMode(): String {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_MODE, MODE_CONTROLLER) ?: MODE_CONTROLLER
    }

    private fun clearLogs() {
        DebugLog.clear(this)
        Toast.makeText(this, getString(R.string.logs_cleared), Toast.LENGTH_SHORT).show()
        renderCurrentSecret()
    }

    companion object {
        private const val STATUS_REFRESH_MS = 2500L
        private const val REQUEST_CODE_PERMS = 11
        private const val PREFS_NAME = "instant_hotspot_prefs"
        private const val KEY_MODE = "mode"
        const val MODE_HOST = "host"
        const val MODE_CONTROLLER = "controller"
    }
}
