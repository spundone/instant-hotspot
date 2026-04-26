package com.spandan.instanthotspot

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.bluetooth.BluetoothManager
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.WindowCompat
import androidx.transition.TransitionManager
import com.google.android.material.transition.MaterialFade
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.materialswitch.MaterialSwitch
import com.spandan.instanthotspot.main.ControllerMainConsolePagerAdapter
import com.spandan.instanthotspot.main.HostMainConsolePagerAdapter
import com.spandan.instanthotspot.core.AppPrefs
import com.spandan.instanthotspot.core.DebugLog
import com.spandan.instanthotspot.core.OnboardingV2
import com.spandan.instanthotspot.onboarding.OnboardingPagerAdapter
import com.spandan.instanthotspot.core.PairedControllerRegistry
import com.spandan.instanthotspot.core.PairedHostEntry
import com.spandan.instanthotspot.core.PairedHostRegistry
import com.spandan.instanthotspot.core.HotspotCommand
import com.spandan.instanthotspot.controller.BlePairingClient
import com.spandan.instanthotspot.controller.CommandSendStatus
import com.spandan.instanthotspot.controller.ControllerCommandSender
import com.spandan.instanthotspot.controller.ControllerCommandSender.HostDeviceSummary
import com.spandan.instanthotspot.controller.PairingStartError
import com.spandan.instanthotspot.controller.PairingSession
import com.spandan.instanthotspot.core.HotspotConfigParser
import com.spandan.instanthotspot.core.HotspotController
import com.spandan.instanthotspot.core.LocalAlertPlayer
import com.spandan.instanthotspot.core.NetworkPing
import com.spandan.instanthotspot.core.NetworkRadioTuning
import com.spandan.instanthotspot.core.HostOnboarding
import com.spandan.instanthotspot.core.HostCompatSummary
import com.spandan.instanthotspot.core.AppTooling
import com.spandan.instanthotspot.core.ProjectInfo
import com.spandan.instanthotspot.core.WifiStatusHelper
import com.spandan.instanthotspot.core.UpdateChecker
import com.spandan.instanthotspot.core.RandomSecret
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.spandan.instanthotspot.host.HostBleService
import com.spandan.instanthotspot.widget.HotspotWidgetProvider
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private var inOnboarding: Boolean = false
    private var useSimpleLayout: Boolean = false
    private var hostPendingCodeText: TextView? = null
    private var hostPairingStatusText: TextView? = null
    private var hostRootStatusText: TextView? = null
    private var hostPairedDeviceText: TextView? = null
    private var controllerPairCodeText: TextView? = null
    /** Simple controller home: same pairing flow as full console. */
    private var simplePairCodeText: TextView? = null
    private var btnSimpleStartPairing: MaterialButton? = null
    private var btnSimpleConfirmPairing: MaterialButton? = null
    private var btnSimpleScanHosts: MaterialButton? = null
    private var simpleSelectedHostText: TextView? = null
    private var controllerPairedDeviceText: TextView? = null
    private var clientConnectionStatusText: TextView? = null
    private var clientHotspotConfigText: TextView? = null
    private var hostDebugLogText: TextView? = null
    private var controllerDebugLogText: TextView? = null
    private var appVersionText: TextView? = null
    private var hostInstallCompatText: TextView? = null
    private var btnConfirmPairing: Button? = null
    private var btnHostApproveCode: Button? = null
    /** Approve on Pair tab; [btnHostApproveCode] is on the Hotspot tab. */
    private var btnHostApproveOnPair: MaterialButton? = null
    private var btnTogglePairingMode: Button? = null
    /** Same action as [btnTogglePairingMode] on the Hotspot tab; always visible on Pair. */
    private var btnHostPairingModeToggle: Button? = null
    private var btnHostReAdvertise: Button? = null
    private var btnStartPairing: Button? = null
    private var btnClientHotspotOn: Button? = null
    private var btnClientHotspotOff: Button? = null
    private var btnClientSyncConfig: Button? = null
    private var btnScanNearbyHosts: Button? = null
    private var btnOpenWifiWithManual: Button? = null
    private var controllerSelectedHostText: TextView? = null
    private var manualSsidInput: EditText? = null
    private var manualPasswordInput: EditText? = null
    private var btnHostClearLogs: Button? = null
    private var btnControllerClearLogs: Button? = null
    private var btnUnpairHost: Button? = null
    private var btnUnpairController: Button? = null
    private var switchHostBondAllowlist: MaterialSwitch? = null
    private var controllerSavedHostsList: ViewGroup? = null
    private var hostPairedControllersList: ViewGroup? = null
    @Volatile private var activePairingSession: PairingSession? = null
    @Volatile private var pairingStartInProgress = false
    @Volatile private var pairingConfirmInProgress = false
    @Volatile private var rootCheckInProgress = false
    @Volatile private var latestRootGranted: Boolean? = null
    private var mainConsoleWired: Boolean = false
    private var wireMainFullConsoleReschedule: Runnable? = null
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
        runCatching {
            val p = packageManager.getPackageInfo(packageName, 0)
            val vn = p.versionName ?: "?"
            val vc = p.longVersionCode
            ver.text = "v$vn ($vc) • ${BuildConfig.GIT_SHA}"
        }.onFailure { ver.text = "" }
        val pager = findViewById<ViewPager2>(R.id.onboardingPager)
        pager.isUserInputEnabled = false
        pager.adapter = OnboardingPagerAdapter(this)
        var page = OnboardingV2.currentPageOrDone(this)
        if (page < 0) page = 0
        val lastIndex = (OnboardingV2.pageCount(this) - 1).coerceAtLeast(0)
        page = page.coerceIn(0, lastIndex)
        if (OnboardingV2.currentPageOrDone(this) != page) {
            OnboardingV2.setPage(this, page)
        }
        pager.setCurrentItem(page, false)
        updateOnboardingNavForPage(pager.currentItem)
        pager.registerOnPageChangeCallback(object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateOnboardingNavForPage(position)
                refreshOnboardingNextState()
            }
        })
        val back = findViewById<Button>(R.id.onboardingBack)
        val next = findViewById<Button>(R.id.onboardingNext)
        back.setOnClickListener {
            if (pager.currentItem > 0) {
                pager.setCurrentItem(pager.currentItem - 1, true)
            } else {
                onBackPressedDispatcher.onBackPressed()
            }
        }
        next.setOnClickListener {
            val total = OnboardingV2.pageCount(this@MainActivity)
            if (pager.currentItem < total - 1) {
                if (pager.currentItem == 1 && isOnboardingHostPrereqBlocking()) {
                    Toast.makeText(this, getString(R.string.ob_host_prereq_block_next), Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                pager.setCurrentItem(pager.currentItem + 1, true)
            } else {
                OnboardingV2.markFlowComplete(this)
                recreate()
            }
        }
        refreshOnboardingNextState()
    }

    private fun updateOnboardingNavForPage(p: Int) {
        val step = findViewById<TextView>(R.id.onboardingStepLabel) ?: return
        val back = findViewById<Button>(R.id.onboardingBack) ?: return
        val next = findViewById<Button>(R.id.onboardingNext) ?: return
        val total = OnboardingV2.pageCount(this)
        OnboardingV2.setPage(this, p)
        step.text = getString(R.string.onboarding_step, p + 1, total)
        back.isEnabled = p > 0
        next.text = if (p >= total - 1) {
            getString(R.string.onboarding_finish)
        } else {
            getString(R.string.onboarding_next)
        }
    }

    /**
     * Host vs controller on the role step changes total step count (controller-only shortcuts page).
     * Do not replace the pager adapter if [OnboardingV2.pageCount] is unchanged: the role fragment is
     * preloaded (offscreen) while the user is still on welcome, and re-binding the adapter there
     * recreates all fragments and crashes the app.
     */
    fun onOnboardingModeChanged() {
        if (!inOnboarding) return
        val pager = findViewById<ViewPager2>(R.id.onboardingPager) ?: return
        val newCount = OnboardingV2.pageCount(this)
        val current = pager.adapter as? FragmentStateAdapter
        if (current != null && current.itemCount == newCount) {
            updateOnboardingNavForPage(pager.currentItem)
            refreshOnboardingNextState()
            return
        }
        val newIndex = pager.currentItem.coerceIn(0, (newCount - 1).coerceAtLeast(0))
        pager.adapter = OnboardingPagerAdapter(this)
        pager.setCurrentItem(newIndex, false)
        OnboardingV2.setPage(this, newIndex)
        updateOnboardingNavForPage(pager.currentItem)
        refreshOnboardingNextState()
    }

    private fun isOnboardingHostPrereqBlocking(): Boolean {
        return AppPrefs.isOnboardingWantsHost(this) && !HostOnboarding.isHostSetupSatisfied(this)
    }

    fun refreshOnboardingNextState() {
        if (!inOnboarding) return
        val next = findViewById<Button>(R.id.onboardingNext) ?: return
        val pager = findViewById<ViewPager2>(R.id.onboardingPager) ?: return
        val blocked = pager.currentItem == 1 && isOnboardingHostPrereqBlocking()
        next.isEnabled = !blocked
    }

    private fun setupSimpleHome() {
        val t = findViewById<MaterialToolbar>(R.id.simpleToolbar)
        t.setTitle(R.string.main_title)
        runCatching {
            val p = packageManager.getPackageInfo(packageName, 0)
            t.subtitle = "v${p.versionName} • ${BuildConfig.GIT_SHA}"
        }
        t.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }
                R.id.menu_sync_hotspot -> {
                    syncHotspotConfigToClient()
                    true
                }
                R.id.menu_open_wifi -> {
                    openWifiWithManualCredentials()
                    true
                }
                R.id.menu_unpair_controller -> {
                    unpairAsController()
                    true
                }
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
                R.id.menu_project_tools -> {
                    showProjectToolsDialog()
                    true
                }
                else -> false
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
        simplePairCodeText = findViewById(R.id.simplePairCodeText)
        btnSimpleStartPairing = findViewById(R.id.btnSimpleStartPairing)
        btnSimpleConfirmPairing = findViewById(R.id.btnSimpleConfirmPairing)
        btnSimpleScanHosts = findViewById(R.id.btnSimpleScanHosts)
        simpleSelectedHostText = findViewById(R.id.simpleSelectedHostText)
        controllerSavedHostsList = findViewById(R.id.controllerSavedHostsList)
        btnSimpleStartPairing?.setOnClickListener { startPairingHandshake() }
        btnSimpleConfirmPairing?.setOnClickListener { confirmPairingHandshake() }
        btnSimpleScanHosts?.setOnClickListener { scanAndSelectNearbyHosts() }
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
        renderSelectedHostTarget()
        btnSimpleStartPairing?.isEnabled = !pairingStartInProgress
        btnSimpleConfirmPairing?.isEnabled = activePairingSession != null && !pairingConfirmInProgress
        when {
            activePairingSession != null -> Unit
            paired -> simplePairCodeText?.text = getString(R.string.controller_pair_add_another_hint)
            else -> simplePairCodeText?.text = getString(R.string.pair_code_none)
        }
        rebuildPairedHostRows()
    }

    private fun setupFullMain() {
        val bottomNav = findViewById<BottomNavigationView>(R.id.mainBottomNav)
        bottomNav.menu.clear()
        if (currentMode() == MODE_HOST) {
            bottomNav.inflateMenu(R.menu.main_bottom_nav_host)
        } else {
            bottomNav.inflateMenu(R.menu.main_bottom_nav_controller)
        }
        val viewPager = findViewById<ViewPager2>(R.id.mainViewPager)
        viewPager.adapter = if (currentMode() == MODE_HOST) {
            HostMainConsolePagerAdapter(this)
        } else {
            ControllerMainConsolePagerAdapter(this)
        }
        viewPager.setOffscreenPageLimit(3)
        bottomNav.setOnItemSelectedListener { item ->
            val i = when (item.itemId) {
                R.id.main_nav_pair -> 0
                R.id.main_nav_hotspot -> 1
                R.id.main_nav_logs -> 2
                R.id.main_nav_tools -> 3
                else -> 0
            }
            if (viewPager.currentItem != i) {
                viewPager.setCurrentItem(i, true)
            }
            true
        }
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                when (position) {
                    0 -> bottomNav.selectedItemId = R.id.main_nav_pair
                    1 -> bottomNav.selectedItemId = R.id.main_nav_hotspot
                    2 -> bottomNav.selectedItemId = R.id.main_nav_logs
                    3 -> bottomNav.selectedItemId = R.id.main_nav_tools
                }
            }
        })
        viewPager.post { scheduleWireMainFullConsole(0) }
    }

    /** Each tab is a fragment; [wireMainFullConsole] must not run until offscreen children exist. */
    private fun isMainConsoleLayoutReady(): Boolean {
        return if (currentMode() == MODE_HOST) {
            findViewById<View>(R.id.modeGroup) != null
                && findViewById<View>(R.id.btnTogglePairingMode) != null
                && findViewById<View>(R.id.hostDebugLogText) != null
                && findViewById<View>(R.id.btnOpenSettings) != null
        } else {
            findViewById<View>(R.id.modeGroup) != null
                && findViewById<View>(R.id.btnClientHotspotOn) != null
                && findViewById<View>(R.id.controllerDebugLogText) != null
                && findViewById<View>(R.id.btnOpenSettings) != null
        }
    }

    private fun scheduleWireMainFullConsole(attempt: Int) {
        if (isFinishing) return
        if (mainConsoleWired) return
        supportFragmentManager.executePendingTransactions()
        if (isMainConsoleLayoutReady()) {
            wireMainFullConsoleReschedule?.let { uiHandler.removeCallbacks(it) }
            wireMainFullConsoleReschedule = null
            wireMainFullConsole()
            return
        }
        if (attempt >= 50) {
            wireMainFullConsoleReschedule?.let { uiHandler.removeCallbacks(it) }
            wireMainFullConsoleReschedule = null
            runCatching { wireMainFullConsole() }
                .onFailure { e ->
                    DebugLog.append(
                        this,
                        "UI",
                        "Main console wire failed (layout not ready after retries): $e",
                    )
                }
            if (!mainConsoleWired) mainConsoleWired = true
            return
        }
        val r = Runnable { scheduleWireMainFullConsole(attempt + 1) }
        wireMainFullConsoleReschedule = r
        uiHandler.postDelayed(r, 16L)
    }

    private fun wireMainFullConsole() {
        if (mainConsoleWired) return
        if (currentMode() == MODE_HOST) {
            wireHostMainConsole()
        } else {
            wireControllerMainConsole()
        }
    }

    private fun applyVersionToHeader() {
        appVersionText = findViewById(R.id.appVersionText)
        runCatching {
            val p = packageManager.getPackageInfo(packageName, 0)
            val v = p.versionName ?: "unknown"
            val c = p.longVersionCode
            appVersionText?.text = "Version $v ($c) • ${BuildConfig.GIT_SHA}"
            DebugLog.append(this, "APP", "Boot: versionName=$v versionCode=$c")
        }.onFailure {
            appVersionText?.text = ""
        }
    }

    private fun wireProjectAndToolsButtons() {
        findViewById<MaterialButton>(R.id.btnOpenGithub).setOnClickListener {
            AppTooling.openUrl(this, ProjectInfo.repositoryUrl())
        }
        findViewById<MaterialButton>(R.id.btnOpenReleases).setOnClickListener {
            AppTooling.openUrl(this, ProjectInfo.releasesPageUrl())
        }
        findViewById<MaterialButton>(R.id.btnOpenSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.btnCheckForUpdates).setOnClickListener {
            checkForUpdatesWithUi()
        }
        findViewById<MaterialButton>(R.id.btnTetheringSettings).setOnClickListener {
            AppTooling.openTetheringSettingsIfPossible(this)
        }
        findViewById<MaterialButton>(R.id.btnOpenBluetooth).setOnClickListener {
            AppTooling.openBluetoothSettings(this)
        }
        findViewById<MaterialButton>(R.id.btnBatteryOpt).setOnClickListener {
            AppTooling.openAppBatterySettings(this)
        }
        findViewById<MaterialButton>(R.id.btnAppInfo).setOnClickListener {
            AppTooling.openInstallPage(this)
        }
        findViewById<MaterialButton>(R.id.btnCopyDebugLog).setOnClickListener {
            AppTooling.copyText(
                this,
                getString(R.string.verbose_debug_title),
                DebugLog.read(this),
            )
        }
        findViewById<MaterialButton>(R.id.btnOpenRemoteTools)?.setOnClickListener {
            showProjectToolsDialog()
        }
        findViewById<MaterialButton>(R.id.btnUseSimpleHome)?.let { b ->
            b.visibility = if (currentMode() == MODE_CONTROLLER) View.VISIBLE else View.GONE
            b.setOnClickListener {
                AppPrefs.setUseSimpleHome(this, true)
                recreate()
            }
        }
        findViewById<MaterialButton>(R.id.btnRerunOnboardingTools)?.setOnClickListener {
            OnboardingV2.resetFlow(this)
            recreate()
        }
    }

    private fun wireHostMainConsole() {
        applyVersionToHeader()
        hostInstallCompatText = findViewById(R.id.hostInstallCompatText)
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val modeGroup = findViewById<RadioGroup>(R.id.modeGroup)!!
        val host = findViewById<RadioButton>(R.id.radioHost)!!
        val controller = findViewById<RadioButton>(R.id.radioController)!!
        hostPendingCodeText = findViewById(R.id.hostPendingCodeText)
        hostPairingStatusText = findViewById(R.id.hostPairingStatusText)
        hostRootStatusText = findViewById(R.id.hostRootStatusText)
        hostPairedDeviceText = findViewById(R.id.hostPairedDeviceText)
        hostPairedControllersList = findViewById(R.id.hostPairedControllersList)
        btnHostApproveCode = findViewById(R.id.btnHostApproveCode)
        btnHostApproveOnPair = findViewById(R.id.btnHostApproveOnPair)
        btnTogglePairingMode = findViewById(R.id.btnTogglePairingMode)
        btnHostPairingModeToggle = findViewById(R.id.btnHostPairingModeToggle)
        btnHostReAdvertise = findViewById(R.id.btnHostReAdvertise)
        hostDebugLogText = findViewById(R.id.hostDebugLogText)
        btnHostClearLogs = findViewById(R.id.btnHostClearLogs)
        btnUnpairHost = findViewById(R.id.btnUnpairHost)
        switchHostBondAllowlist = findViewById(R.id.switchHostBondAllowlist)
        switchHostBondAllowlist?.isChecked = AppPrefs.isHostBondAllowlistEnabled(this)
        switchHostBondAllowlist?.setOnCheckedChangeListener { _, checked ->
            AppPrefs.setHostBondAllowlistEnabled(this, checked)
            DebugLog.append(this, "HOST_UI", "Bond allowlist: $checked")
        }

        renderCurrentSecret()
        host.isChecked = currentMode() == MODE_HOST
        controller.isChecked = currentMode() == MODE_CONTROLLER
        applyVerboseUi(AppPrefs.isVerboseDebugEnabled(this))
        applyModeUi(MODE_HOST)
        ensureHostSecretInitialized()
        startService(Intent(this, HostBleService::class.java))

        btnHostApproveCode?.setOnClickListener { approveHostPendingCode() }
        btnHostApproveOnPair?.setOnClickListener { approveHostPendingCode() }
        btnTogglePairingMode?.setOnClickListener { toggleHostPairingMode() }
        btnHostPairingModeToggle?.setOnClickListener { toggleHostPairingMode() }
        btnHostReAdvertise?.setOnClickListener { forceHostReAdvertise() }
        btnHostClearLogs?.setOnClickListener { clearLogs() }
        btnUnpairHost?.setOnClickListener { unpairAsHost() }
        wireProjectAndToolsButtons()

        modeGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = if (checkedId == R.id.radioHost) MODE_HOST else MODE_CONTROLLER
            val prev = prefs.getString(KEY_MODE, MODE_CONTROLLER) ?: MODE_CONTROLLER
            if (mode == prev) return@setOnCheckedChangeListener
            prefs.edit().putString(KEY_MODE, mode).apply()
            if (mode == MODE_HOST) {
                ensureHostSecretInitialized()
                startService(Intent(this, HostBleService::class.java))
            } else {
                stopService(Intent(this, HostBleService::class.java))
            }
            recreate()
        }
        mainConsoleWired = true
        playMainEntrance()
    }

    private fun wireControllerMainConsole() {
        applyVersionToHeader()
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val modeGroup = findViewById<RadioGroup>(R.id.modeGroup)!!
        val host = findViewById<RadioButton>(R.id.radioHost)!!
        val controller = findViewById<RadioButton>(R.id.radioController)!!
        btnStartPairing = findViewById(R.id.btnStartPairing)
        btnConfirmPairing = findViewById(R.id.btnConfirmPairing)
        controllerPairCodeText = findViewById(R.id.controllerPairCodeText)
        controllerPairedDeviceText = findViewById(R.id.controllerPairedDeviceText)
        clientConnectionStatusText = findViewById(R.id.clientConnectionStatusText)
        clientHotspotConfigText = findViewById(R.id.clientHotspotConfigText)
        controllerDebugLogText = findViewById(R.id.controllerDebugLogText)
        btnClientHotspotOn = findViewById(R.id.btnClientHotspotOn)
        btnClientHotspotOff = findViewById(R.id.btnClientHotspotOff)
        btnClientSyncConfig = findViewById(R.id.btnClientSyncConfig)
        btnScanNearbyHosts = findViewById(R.id.btnScanNearbyHosts)
        controllerSavedHostsList = findViewById(R.id.controllerSavedHostsList)
        btnOpenWifiWithManual = findViewById(R.id.btnOpenWifiWithManual)
        controllerSelectedHostText = findViewById(R.id.controllerSelectedHostText)
        manualSsidInput = findViewById(R.id.manualSsidInput)
        manualPasswordInput = findViewById(R.id.manualPasswordInput)
        btnControllerClearLogs = findViewById(R.id.btnControllerClearLogs)
        btnUnpairController = findViewById(R.id.btnUnpairController)

        renderCurrentSecret()
        host.isChecked = currentMode() == MODE_HOST
        controller.isChecked = currentMode() == MODE_CONTROLLER
        applyVerboseUi(AppPrefs.isVerboseDebugEnabled(this))
        applyModeUi(MODE_CONTROLLER)

        btnStartPairing?.setOnClickListener { startPairingHandshake() }
        btnConfirmPairing?.setOnClickListener { confirmPairingHandshake() }
        btnClientHotspotOn?.setOnClickListener {
            sendClientHotspotCommand(HotspotCommand.HOTSPOT_ON, "HOTSPOT_ON", R.string.toast_client_hotspot_on_sent)
        }
        btnClientHotspotOff?.setOnClickListener {
            sendClientHotspotCommand(HotspotCommand.HOTSPOT_OFF, "HOTSPOT_OFF", R.string.toast_client_hotspot_off_sent)
        }
        btnClientSyncConfig?.setOnClickListener { syncHotspotConfigToClient() }
        btnScanNearbyHosts?.setOnClickListener { scanAndSelectNearbyHosts() }
        btnOpenWifiWithManual?.setOnClickListener { openWifiWithManualCredentials() }
        btnControllerClearLogs?.setOnClickListener { clearLogs() }
        btnUnpairController?.setOnClickListener { unpairAsController() }
        wireProjectAndToolsButtons()

        modeGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = if (checkedId == R.id.radioHost) MODE_HOST else MODE_CONTROLLER
            val prev = prefs.getString(KEY_MODE, MODE_CONTROLLER) ?: MODE_CONTROLLER
            if (mode == prev) return@setOnCheckedChangeListener
            prefs.edit().putString(KEY_MODE, mode).apply()
            if (mode == MODE_HOST) {
                ensureHostSecretInitialized()
                startService(Intent(this, HostBleService::class.java))
            } else {
                stopService(Intent(this, HostBleService::class.java))
            }
            recreate()
        }
        manualSsidInput?.setText(AppPrefs.manualHotspotSsid(this) ?: "")
        manualPasswordInput?.setText(AppPrefs.manualHotspotPassword(this) ?: "")
        renderSelectedHostTarget()
        mainConsoleWired = true
        playMainEntrance()
    }

    private fun playMainEntrance() {
        val main = findViewById<View>(R.id.mainRootContent) ?: return
        val density = resources.displayMetrics.density
        val lift = 18f * density
        main.translationY = lift
        val ease = PathInterpolator(0.2f, 0f, 0f, 1f)
        main.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(400L)
            .setInterpolator(ease)
            .withLayer()
            .start()
    }

    override fun onResume() {
        super.onResume()
        if (inOnboarding) {
            refreshOnboardingNextState()
            return
        }
        if (useSimpleLayout) {
            renderSimpleHome()
            uiHandler.removeCallbacks(statusRefreshRunnable)
            uiHandler.post(statusRefreshRunnable)
            return
        }
        applyVerboseUi(AppPrefs.isVerboseDebugEnabled(this))
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
        wireMainFullConsoleReschedule?.let { uiHandler.removeCallbacks(it) }
        wireMainFullConsoleReschedule = null
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
        findViewById<ViewGroup>(R.id.mainRootContent)?.let { content ->
            TransitionManager.beginDelayedTransition(
                content,
                MaterialFade().setDuration(240L),
            )
        }
        if (mode == MODE_HOST) {
            refreshHostInstallCompat()
        }
        renderCurrentSecret()
        uiHandler.removeCallbacks(statusRefreshRunnable)
        uiHandler.post(statusRefreshRunnable)
    }

    private fun refreshHostInstallCompat() {
        val probe = AppPrefs.lastApStateLine(this)
        val apLine = if (probe.isNullOrBlank()) "Last AP probe: none" else "Last AP probe: $probe"
        hostInstallCompatText?.text = HostCompatSummary.build(this) + "\n" + apLine
    }

    private fun renderCurrentSecret() {
        if (useSimpleLayout) {
            renderSimpleHome()
            return
        }
        if (currentMode() == MODE_HOST) {
            renderHostMainStatus()
        } else {
            renderControllerMainStatus()
        }
    }

    private fun renderHostMainStatus() {
        refreshHostInstallCompat()
        val pending = AppPrefs.pendingPairCode(this)
        hostPendingCodeText?.text = if (pending.isNullOrBlank()) {
            getString(R.string.pending_code_none)
        } else {
            "Pending code: $pending"
        }
        val approved = AppPrefs.approvedPairCode(this)
        val canApprove = !pending.isNullOrBlank() && pending != approved
        btnHostApproveCode?.isEnabled = canApprove
        btnHostApproveOnPair?.isEnabled = canApprove
        val serviceActive = AppPrefs.isHostServiceActive(this)
        val pairingModeEnabled = AppPrefs.isPairingModeEnabled(this)
        hostPairingStatusText?.text = when {
            !serviceActive -> getString(R.string.host_status_off)
            pairingModeEnabled -> getString(R.string.host_status_on_pairing_on)
            else -> getString(R.string.host_status_on_pairing_off)
        }
        hostRootStatusText?.text = when (latestRootGranted) {
            true -> getString(R.string.root_status_granted)
            false -> getString(R.string.root_status_denied)
            null -> getString(R.string.root_status_unknown)
        }
        val pairingBtnText = if (pairingModeEnabled) {
            getString(R.string.disable_pairing_mode)
        } else {
            getString(R.string.enable_pairing_mode)
        }
        btnTogglePairingMode?.text = pairingBtnText
        btnHostPairingModeToggle?.text = pairingBtnText
        val registryCount = PairedControllerRegistry.all(this).size
        val pairedController = AppPrefs.lastPairedController(this)
        hostPairedDeviceText?.text = when {
            registryCount == 0 && pairedController.isNullOrBlank() -> getString(R.string.host_paired_device_none)
            registryCount > 0 -> getString(R.string.host_paired_controllers_count_line, registryCount)
            else -> getString(R.string.host_paired_device_value, pairedController!!)
        }
        rebuildPairedControllerRows()
        val debug = DebugLog.read(this)
        hostDebugLogText?.text = debug
    }

    private fun renderControllerMainStatus() {
        btnStartPairing?.isEnabled = !pairingStartInProgress
        btnConfirmPairing?.isEnabled = activePairingSession != null && !pairingConfirmInProgress
        val paired = AppPrefs.isClientPaired(this)
        val pairedHost = AppPrefs.lastPairedHost(this)
        val connected = AppPrefs.isHostReachableRecently(this)
        clientConnectionStatusText?.text = when {
            !paired -> getString(R.string.client_status_not_paired)
            connected -> getString(R.string.client_status_paired_connected)
            else -> getString(R.string.client_status_paired_disconnected)
        }
        btnClientHotspotOn?.isEnabled = paired && !pairingStartInProgress && !pairingConfirmInProgress
        btnClientHotspotOff?.isEnabled = paired && !pairingStartInProgress && !pairingConfirmInProgress
        btnClientSyncConfig?.isEnabled = paired && !pairingStartInProgress && !pairingConfirmInProgress
        val savedConfig = AppPrefs.lastSyncedHotspotConfig(this)
        clientHotspotConfigText?.text = if (savedConfig.isNullOrBlank()) {
            getString(R.string.client_hotspot_config_empty)
        } else {
            formatHotspotConfigForDisplay(savedConfig)
        }
        controllerPairedDeviceText?.text = if (pairedHost.isNullOrBlank()) {
            getString(R.string.controller_paired_device_none)
        } else {
            val label = AppPrefs.pairedHostDisplayName(this)
            if (!label.isNullOrBlank()) {
                getString(R.string.controller_paired_device_friendly, label, pairedHost)
            } else {
                getString(R.string.controller_paired_device_value, pairedHost)
            }
        }
        renderSelectedHostTarget()
        when {
            activePairingSession != null -> Unit
            paired -> controllerPairCodeText?.text = getString(R.string.controller_pair_add_another_hint)
            else -> controllerPairCodeText?.text = getString(R.string.pair_code_none)
        }
        val debug = DebugLog.read(this)
        controllerDebugLogText?.text = debug
        rebuildPairedHostRows()
    }

    private fun applyActivePairedHost(entry: PairedHostEntry) {
        AppPrefs.setPreferredHostAddress(this, entry.address)
        AppPrefs.setLastPairedHost(this, entry.address)
        AppPrefs.setPairedHostDisplayName(this, entry.displayName)
        AppPrefs.setSharedSecret(this, entry.secret)
        renderSelectedHostTarget()
        renderCurrentSecret()
        HotspotWidgetProvider.requestUpdateAll(this)
    }

    private fun removePairedHostEntry(address: String) {
        val preferred = AppPrefs.preferredHostAddress(this)
        val last = AppPrefs.lastPairedHost(this)
        PairedHostRegistry.remove(this, address)
        val rest = PairedHostRegistry.all(this)
        if (rest.isEmpty()) {
            AppPrefs.unpairAsController(this)
        } else if (address.equals(preferred, ignoreCase = true) || address.equals(last, ignoreCase = true)) {
            applyActivePairedHost(rest.maxBy { it.pairedAtMs })
        } else {
            renderCurrentSecret()
        }
        HotspotWidgetProvider.requestUpdateAll(this)
    }

    @Suppress("DEPRECATION")
    private fun rebuildPairedControllerRows() {
        val container = hostPairedControllersList ?: return
        container.removeAllViews()
        val infl = layoutInflater
        for (e in PairedControllerRegistry.all(this)) {
            val row = infl.inflate(R.layout.item_paired_controller_row, container, false)
            val name = runCatching {
                getSystemService(BluetoothManager::class.java)?.adapter?.getRemoteDevice(e.address)?.name
            }.getOrNull()?.trim()
            val line = if (name.isNullOrBlank()) e.address else "$name · ${e.address}"
            row.findViewById<TextView>(R.id.pairedControllerLine).text = line
            row.findViewById<MaterialButton>(R.id.btnControllerRowRemove).setOnClickListener {
                removePairedControllerEntry(e.address)
            }
            container.addView(row)
        }
    }

    private fun removePairedControllerEntry(address: String) {
        PairedControllerRegistry.remove(this, address)
        if (!PairedControllerRegistry.hasAny(this)) {
            AppPrefs.setLastPairedController(this, null)
        }
        renderCurrentSecret()
    }

    private fun rebuildPairedHostRows() {
        val container = controllerSavedHostsList ?: return
        container.removeAllViews()
        val entries = PairedHostRegistry.all(this)
        if (entries.isEmpty()) return
        val infl = layoutInflater
        val activePref = AppPrefs.preferredHostAddress(this)
        val activeLast = AppPrefs.lastPairedHost(this)
        for (e in entries) {
            val row = infl.inflate(R.layout.item_paired_host_row, container, false)
            row.findViewById<TextView>(R.id.pairedHostTitle).text = e.displayName ?: e.address
            row.findViewById<TextView>(R.id.pairedHostAddress).text = e.address
            val isActive = (activePref != null && e.address.equals(activePref, ignoreCase = true)) ||
                (activeLast != null && e.address.equals(activeLast, ignoreCase = true) && activePref == null)
            val useBtn = row.findViewById<MaterialButton>(R.id.btnHostRowUse)
            useBtn.isEnabled = !isActive
            useBtn.setOnClickListener { applyActivePairedHost(e) }
            row.findViewById<MaterialButton>(R.id.btnHostRowRemove).setOnClickListener {
                removePairedHostEntry(e.address)
            }
            container.addView(row)
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
        btnStartPairing?.isEnabled = false
        btnConfirmPairing?.isEnabled = false
        btnSimpleStartPairing?.isEnabled = false
        btnSimpleConfirmPairing?.isEnabled = false
        backgroundExecutor.execute {
            val result = BlePairingClient.startPairing(this)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                pairingStartInProgress = false
                btnStartPairing?.isEnabled = true
                btnSimpleStartPairing?.isEnabled = true
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
                val line = "Controller code: ${session.code}"
                controllerPairCodeText?.text = line
                simplePairCodeText?.text = line
                btnConfirmPairing?.isEnabled = true
                btnSimpleConfirmPairing?.isEnabled = true
                Toast.makeText(this, getString(R.string.pairing_started), Toast.LENGTH_SHORT).show()
            }
        }
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
        btnConfirmPairing?.isEnabled = false
        btnSimpleConfirmPairing?.isEnabled = false
        backgroundExecutor.execute {
            val ok = BlePairingClient.confirmPairing(this, session)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                pairingConfirmInProgress = false
                if (!ok) {
                    DebugLog.append(this, "CTRL_UI", "Pairing confirm failed at host")
                    btnConfirmPairing?.isEnabled = activePairingSession != null
                    btnSimpleConfirmPairing?.isEnabled = activePairingSession != null
                    Toast.makeText(this, getString(R.string.pairing_confirm_failed), Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                activePairingSession = null
                renderCurrentSecret()
                HotspotWidgetProvider.requestUpdateAll(this)
                DebugLog.append(this, "CTRL_UI", "Pairing confirmed successfully")
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
        btnClientSyncConfig?.isEnabled = false
        DebugLog.append(this, "CTRL_UI", "Sync hotspot config requested")
        AppPrefs.setManualHotspotSsid(this, manualSsidInput?.text?.toString())
        AppPrefs.setManualHotspotPassword(this, manualPasswordInput?.text?.toString())
        backgroundExecutor.execute {
            val config = BlePairingClient.fetchHotspotConfig(this)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (config == null) {
                    DebugLog.append(this, "CTRL_UI", "Hotspot config sync failed")
                    Toast.makeText(this, getString(R.string.client_sync_failed), Toast.LENGTH_SHORT).show()
                } else {
                    DebugLog.append(this, "CTRL_UI", "Hotspot config sync success: ${config.take(80)}")
                    clientHotspotConfigText?.text = formatHotspotConfigForDisplay(config)
                    val creds = HotspotConfigParser.parseSsidPassword(config)
                    if (creds != null) {
                        manualSsidInput?.setText(creds.ssid)
                        manualPasswordInput?.setText(creds.password)
                        AppPrefs.setManualHotspotSsid(this, creds.ssid)
                        AppPrefs.setManualHotspotPassword(this, creds.password)
                        Toast.makeText(
                            this,
                            getString(R.string.client_sync_connect_hint, creds.ssid, creds.password),
                            Toast.LENGTH_LONG,
                        ).show()
                        runCatching {
                            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                        }
                    } else {
                        Toast.makeText(this, getString(R.string.client_sync_missing_credentials), Toast.LENGTH_LONG).show()
                    }
                }
                renderCurrentSecret()
            }
        }
    }

    private fun applyVerboseUi(enabled: Boolean) {
        if (useSimpleLayout) return
        findViewById<ViewGroup>(R.id.mainRootContent)?.let { content ->
            TransitionManager.beginDelayedTransition(
                content,
                MaterialFade().setDuration(220L),
            )
        }
        val vis = if (enabled) View.VISIBLE else View.GONE
        val off = if (enabled) View.GONE else View.VISIBLE
        findViewById<View>(R.id.hostInstallVerboseGroup)?.visibility = vis
        findViewById<View>(R.id.hostLogVerboseGroup)?.visibility = vis
        findViewById<View>(R.id.hostLogVerboseOffHint)?.visibility = off
        findViewById<View>(R.id.hostPairVerboseGroup)?.visibility = vis
        findViewById<View>(R.id.hostPairCompactHint)?.visibility = off
        findViewById<View>(R.id.controllerLogVerboseGroup)?.visibility = vis
        findViewById<View>(R.id.controllerLogVerboseOffHint)?.visibility = off
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

    private fun openWifiWithManualCredentials() {
        val ssid = manualSsidInput?.text?.toString()?.trim().orEmpty()
        val pass = manualPasswordInput?.text?.toString()?.trim().orEmpty()
        if (ssid.isBlank() || pass.isBlank()) {
            Toast.makeText(this, getString(R.string.manual_hotspot_missing), Toast.LENGTH_SHORT).show()
            return
        }
        AppPrefs.setManualHotspotSsid(this, ssid)
        AppPrefs.setManualHotspotPassword(this, pass)
        Toast.makeText(this, getString(R.string.client_sync_connect_hint, ssid, pass), Toast.LENGTH_LONG).show()
        runCatching {
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        }
    }

    private fun renderSelectedHostTarget() {
        val selected = AppPrefs.preferredHostAddress(this)
        val line = if (selected.isNullOrBlank()) {
            getString(R.string.preferred_host_none)
        } else {
            getString(R.string.preferred_host_value, selected)
        }
        controllerSelectedHostText?.text = line
        simpleSelectedHostText?.text = line
    }

    private fun scanAndSelectNearbyHosts() {
        if (PairedHostRegistry.hasAny(this)) {
            showSavedHostPickerDialog()
            return
        }
        btnScanNearbyHosts?.isEnabled = false
        btnSimpleScanHosts?.isEnabled = false
        Toast.makeText(this, getString(R.string.scan_hosts_working), Toast.LENGTH_SHORT).show()
        backgroundExecutor.execute {
            val hosts = ControllerCommandSender.scanNearbyHosts(this)
            runOnUiThread {
                btnScanNearbyHosts?.isEnabled = true
                btnSimpleScanHosts?.isEnabled = true
                if (hosts.isEmpty()) {
                    Toast.makeText(this, getString(R.string.scan_hosts_none_found), Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                showHostPickerDialog(hosts)
            }
        }
    }

    private fun showSavedHostPickerDialog() {
        val entries = PairedHostRegistry.all(this)
        if (entries.isEmpty()) return
        val labels = entries.map { e ->
            val n = e.displayName?.takeIf { it.isNotBlank() } ?: e.address
            "$n\n${e.address}"
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.scan_hosts_pick_title)
            .setItems(labels) { _, which ->
                val chosen = entries.getOrNull(which) ?: return@setItems
                applyActivePairedHost(chosen)
                Toast.makeText(
                    this,
                    getString(R.string.scan_hosts_selected, chosen.address),
                    Toast.LENGTH_SHORT,
                ).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showHostPickerDialog(hosts: List<HostDeviceSummary>) {
        val labels = hosts.mapIndexed { idx, d ->
            val n = d.name?.takeIf { it.isNotBlank() } ?: "Host ${idx + 1}"
            "$n (${d.address})"
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.scan_hosts_pick_title)
            .setItems(labels) { _, which ->
                val chosen = hosts.getOrNull(which) ?: return@setItems
                AppPrefs.setPreferredHostAddress(this, chosen.address)
                AppPrefs.setPairedHostDisplayName(
                    this,
                    chosen.name?.trim()?.takeIf { it.isNotBlank() },
                )
                renderSelectedHostTarget()
                HotspotWidgetProvider.requestUpdateAll(this)
                Toast.makeText(this, getString(R.string.scan_hosts_selected, chosen.address), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun checkForUpdatesWithUi() {
        Toast.makeText(this, R.string.update_checking, Toast.LENGTH_SHORT).show()
        UpdateChecker.checkInBackground { r ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (r.success && r.isNewerThanInstalled) {
                    val rel = r.releaseUrl
                    val msg = buildString {
                        append(r.userMessage)
                        r.bodyPreview?.let { pre ->
                            append("\n\n")
                            append(pre)
                        }
                    }
                    if (rel != null) {
                        MaterialAlertDialogBuilder(this)
                            .setTitle(R.string.dialog_update_title)
                            .setMessage(msg)
                            .setPositiveButton(R.string.update_view_release) { _, _ ->
                                AppTooling.openUrl(this, rel)
                            }
                            .setNegativeButton(R.string.update_dismiss, null)
                            .show()
                    } else {
                        Toast.makeText(this, r.userMessage, Toast.LENGTH_LONG).show()
                    }
                } else {
                    Toast.makeText(this, r.userMessage, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showProjectToolsDialog() {
        val labels = arrayOf(
            getString(R.string.btn_open_github),
            getString(R.string.btn_open_releases_module),
            getString(R.string.btn_check_updates),
            getString(R.string.btn_tethering_settings),
            getString(R.string.btn_bluetooth_settings),
            getString(R.string.btn_battery_optimization),
            getString(R.string.btn_app_info),
            getString(R.string.btn_copy_debug_log),
            getString(R.string.btn_tools_5g_only),
            getString(R.string.btn_tools_revert_network),
            getString(R.string.btn_tools_ring_here),
            getString(R.string.btn_tools_ring_host),
            getString(R.string.btn_tools_ping),
            getString(R.string.btn_tools_wifi_details),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.project_tools_title)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> AppTooling.openUrl(this, ProjectInfo.repositoryUrl())
                    1 -> AppTooling.openUrl(this, ProjectInfo.releasesPageUrl())
                    2 -> checkForUpdatesWithUi()
                    3 -> AppTooling.openTetheringSettingsIfPossible(this)
                    4 -> AppTooling.openBluetoothSettings(this)
                    5 -> AppTooling.openAppBatterySettings(this)
                    6 -> AppTooling.openInstallPage(this)
                    7 -> AppTooling.copyText(
                        this,
                        getString(R.string.verbose_debug_title),
                        DebugLog.read(this),
                    )
                    8 -> runTools5gOnly()
                    9 -> runToolsRevertNetworkMode()
                    10 -> {
                        LocalAlertPlayer.playAttention(this)
                        Toast.makeText(this, R.string.btn_tools_ring_here, Toast.LENGTH_SHORT).show()
                    }
                    11 -> runToolsRingHost()
                    12 -> runToolsPing()
                    13 -> runToolsWifiDetails()
                }
            }
            .setNegativeButton(R.string.update_dismiss, null)
            .show()
    }

    private fun runTools5gOnly() {
        if (currentMode() == MODE_HOST) {
            if (!HotspotController.hasRootPermission()) {
                Toast.makeText(this, R.string.tools_5g_root, Toast.LENGTH_LONG).show()
                return
            }
            if (NetworkRadioTuning.apply5gOnly(this)) {
                Toast.makeText(
                    this,
                    getString(R.string.tools_5g_applied) + "\n" + getString(R.string.tools_5g_note),
                    Toast.LENGTH_LONG,
                ).show()
            } else {
                Toast.makeText(this, R.string.tools_5g_failed, Toast.LENGTH_LONG).show()
            }
            return
        }
        if (!AppPrefs.isClientPaired(this) || currentMode() != MODE_CONTROLLER) {
            Toast.makeText(this, R.string.tools_paired_host_required, Toast.LENGTH_LONG).show()
            return
        }
        ControllerCommandSender.sendAsync(this, HotspotCommand.NET_5G_ONLY) { s ->
            when (s) {
                CommandSendStatus.SUCCESS -> {
                    AppPrefs.markHostReachableNow(this@MainActivity)
                    Toast.makeText(
                        this@MainActivity,
                        R.string.tools_5g_applied,
                        Toast.LENGTH_LONG,
                    ).show()
                }
                CommandSendStatus.NOT_PAIRED ->
                    Toast.makeText(this@MainActivity, R.string.tools_paired_host_required, Toast.LENGTH_LONG).show()
                CommandSendStatus.BLUETOOTH_OFF -> Toast.makeText(this@MainActivity, R.string.tile_toast_bt_off, Toast.LENGTH_LONG).show()
                CommandSendStatus.HOST_NOT_FOUND -> Toast.makeText(this@MainActivity, R.string.tile_toast_host_missing, Toast.LENGTH_LONG).show()
                CommandSendStatus.SEND_FAILED -> Toast.makeText(this@MainActivity, R.string.tools_5g_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun runToolsRevertNetworkMode() {
        if (currentMode() == MODE_HOST) {
            if (!HotspotController.hasRootPermission()) {
                Toast.makeText(this, R.string.tools_5g_root, Toast.LENGTH_LONG).show()
                return
            }
            if (NetworkRadioTuning.revertPreferredMode(this)) {
                Toast.makeText(this, R.string.tools_revert_ok, Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, R.string.tools_revert_nothing, Toast.LENGTH_LONG).show()
            }
            return
        }
        if (!AppPrefs.isClientPaired(this) || currentMode() != MODE_CONTROLLER) {
            Toast.makeText(this, R.string.tools_paired_host_required, Toast.LENGTH_LONG).show()
            return
        }
        ControllerCommandSender.sendAsync(this, HotspotCommand.NET_REVERT_MODE) { s ->
            when (s) {
                CommandSendStatus.SUCCESS -> {
                    AppPrefs.markHostReachableNow(this@MainActivity)
                    Toast.makeText(this@MainActivity, R.string.tools_revert_ok, Toast.LENGTH_LONG).show()
                }
                CommandSendStatus.NOT_PAIRED ->
                    Toast.makeText(this@MainActivity, R.string.tools_paired_host_required, Toast.LENGTH_LONG).show()
                CommandSendStatus.BLUETOOTH_OFF -> Toast.makeText(this@MainActivity, R.string.tile_toast_bt_off, Toast.LENGTH_LONG).show()
                CommandSendStatus.HOST_NOT_FOUND -> Toast.makeText(this@MainActivity, R.string.tile_toast_host_missing, Toast.LENGTH_LONG).show()
                CommandSendStatus.SEND_FAILED -> Toast.makeText(this@MainActivity, R.string.tools_revert_nothing, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun runToolsRingHost() {
        if (!AppPrefs.isClientPaired(this) || currentMode() != MODE_CONTROLLER) {
            Toast.makeText(this, R.string.tools_paired_host_required, Toast.LENGTH_LONG).show()
            return
        }
        ControllerCommandSender.sendAsync(this, HotspotCommand.RING_REMOTE) { s ->
            when (s) {
                CommandSendStatus.SUCCESS -> {
                    AppPrefs.markHostReachableNow(this@MainActivity)
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle(R.string.tools_ring_host_title)
                        .setMessage(R.string.tools_ring_host_ok)
                        .setPositiveButton(R.string.update_ok, null)
                        .show()
                }
                CommandSendStatus.NOT_PAIRED ->
                    Toast.makeText(this@MainActivity, R.string.tools_paired_host_required, Toast.LENGTH_LONG).show()
                CommandSendStatus.BLUETOOTH_OFF -> Toast.makeText(this@MainActivity, R.string.tile_toast_bt_off, Toast.LENGTH_LONG).show()
                CommandSendStatus.HOST_NOT_FOUND -> Toast.makeText(this@MainActivity, R.string.tile_toast_host_missing, Toast.LENGTH_LONG).show()
                CommandSendStatus.SEND_FAILED -> Toast.makeText(this@MainActivity, R.string.tile_toast_send_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun runToolsPing() {
        backgroundExecutor.execute {
            val out = NetworkPing.runPing4("1.1.1.1")
            runOnUiThread {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.tools_ping_title)
                    .setMessage(out.ifBlank { "—" })
                    .setPositiveButton(R.string.update_ok, null)
                    .show()
            }
        }
    }

    private fun runToolsWifiDetails() {
        val msg = WifiStatusHelper.detailsBlock(this)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.tools_wifi_title)
            .setMessage(msg)
            .setPositiveButton(R.string.update_ok, null)
            .show()
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
