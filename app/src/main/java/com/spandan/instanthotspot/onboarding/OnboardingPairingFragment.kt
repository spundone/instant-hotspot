package com.spandan.instanthotspot.onboarding

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.spandan.instanthotspot.R
import com.spandan.instanthotspot.controller.BlePairingClient
import com.spandan.instanthotspot.controller.PairingStartError
import com.spandan.instanthotspot.controller.PairingSession
import com.spandan.instanthotspot.core.AppPrefs
import com.spandan.instanthotspot.core.DebugLog
import com.spandan.instanthotspot.core.OnboardingV2
import com.spandan.instanthotspot.core.RandomSecret
import com.spandan.instanthotspot.host.HostBleService
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService

class OnboardingPairingFragment : Fragment(R.layout.fragment_onboarding_pairing) {
    private val handler = Handler(Looper.getMainLooper())
    private var backgroundExecutor: ExecutorService? = null
    @Volatile
    private var activeSession: PairingSession? = null
    @Volatile
    private var startInProgress = false
    @Volatile
    private var confirmInProgress = false
    private val hostRefresh = object : Runnable {
        override fun run() {
            if (!isResumed) return
            if (isHost()) refreshHost()
            handler.postDelayed(this, 2500L)
        }
    }
    private val controllerRefresh = object : Runnable {
        override fun run() {
            if (!isResumed) return
            if (!isHost()) updatePairedBanner()
            handler.postDelayed(this, 2500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        backgroundExecutor = Executors.newSingleThreadExecutor()
    }

    override fun onDestroy() {
        backgroundExecutor?.shutdown()
        super.onDestroy()
    }

    private var lastAppliedHostMode: Boolean? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        applyHostVsControllerPairingUi(view, force = true)
    }

    private fun isHost(): Boolean = OnboardingV2.isHostModeForOnboarding(requireContext())

    /**
     * Host vs controller UI is decided from prefs. If the fragment is created offscreen before the
     * role step saves [AppPrefs] / mode, re-apply when [onResume] runs.
     */
    private fun applyHostVsControllerPairingUi(view: View, force: Boolean) {
        val hostNow = isHost()
        if (!force && lastAppliedHostMode == hostNow) return
        lastAppliedHostMode = hostNow

        val pageTitle = view.findViewById<TextView>(R.id.obPairingPageTitle)
        val hint = view.findViewById<TextView>(R.id.obPairingHint)
        val hostCard = view.findViewById<View>(R.id.obHostPairCard)
        val ctrlCard = view.findViewById<View>(R.id.obControllerPairCard)
        if (hostNow) {
            pageTitle.setText(R.string.ob_pairing_page_title_host)
            hint.setText(R.string.ob_pairing_hint_host)
            hostCard.visibility = View.VISIBLE
            ctrlCard.visibility = View.GONE
            val hostSecret = view.findViewById<TextInputEditText>(R.id.obhSecret)
            val toggle = view.findViewById<MaterialButton>(R.id.obhBtnTogglePair)
            val readv = view.findViewById<MaterialButton>(R.id.obhBtnReadvertise)
            val approve = view.findViewById<MaterialButton>(R.id.obhBtnApprove)
            view.findViewById<MaterialButton>(R.id.obhSaveSecret).setOnClickListener { saveHostSecret(hostSecret) }
            view.findViewById<MaterialButton>(R.id.obhShareSecret).setOnClickListener { shareHostPassphrase() }
            toggle.setOnClickListener { toggleHostPairing() }
            readv.setOnClickListener {
                val ctx = requireContext()
                ctx.startService(
                    Intent(ctx, HostBleService::class.java).apply {
                        action = HostBleService.ACTION_RESTART_ADVERTISING
                    },
                )
                Toast.makeText(ctx, R.string.host_re_advertise_done, Toast.LENGTH_SHORT).show()
            }
            approve.setOnClickListener { approvePending() }
        } else {
            pageTitle.setText(R.string.ob_pairing_page_title_controller)
            hint.setText(R.string.ob_pairing_hint_controller)
            hostCard.visibility = View.GONE
            ctrlCard.visibility = View.VISIBLE
            val start = view.findViewById<MaterialButton>(R.id.obcStart)
            val confirm = view.findViewById<MaterialButton>(R.id.obcConfirm)
            start.setOnClickListener { startPairing(confirm) }
            confirm.setOnClickListener { confirmPairing(confirm) }
        }
        updatePairedBanner()
    }

    override fun onResume() {
        super.onResume()
        view?.let { applyHostVsControllerPairingUi(it, force = false) }
        if (isHost()) {
            if (!AppPrefs.hasNonDefaultSecret(requireContext())) {
                AppPrefs.setSharedSecret(requireContext(), RandomSecret.newReadable())
            }
            requireContext().startService(Intent(requireContext(), HostBleService::class.java))
            handler.removeCallbacks(hostRefresh)
            handler.removeCallbacks(controllerRefresh)
            handler.post(hostRefresh)
            refreshHost()
        } else {
            handler.removeCallbacks(hostRefresh)
            handler.removeCallbacks(controllerRefresh)
            updatePairedBanner()
            handler.post(controllerRefresh)
        }
    }

    override fun onPause() {
        handler.removeCallbacks(hostRefresh)
        handler.removeCallbacks(controllerRefresh)
        super.onPause()
    }

    private fun updatePairedBanner() {
        val b = requireView().findViewById<TextView>(R.id.obPairedStatusBanner)
        val ctx = requireContext()
        if (isHost()) {
            val name = AppPrefs.lastPairedController(ctx)
            b.text = if (name.isNullOrBlank()) {
                getString(R.string.host_paired_device_none)
            } else {
                getString(R.string.host_paired_device_value, name)
            }
        } else {
            val paired = AppPrefs.isClientPaired(ctx)
            val connected = AppPrefs.isHostReachableRecently(ctx)
            b.text = when {
                !paired -> getString(R.string.client_status_not_paired)
                connected -> getString(R.string.client_status_paired_connected)
                else -> getString(R.string.client_status_paired_disconnected)
            }
        }
    }

    private fun refreshHost() {
        val v = requireView()
        val secretField = v.findViewById<TextInputEditText>(R.id.obhSecret)
        val ctx = requireContext()
        if (secretField != null && !secretField.isFocused) {
            secretField.setText(
                if (AppPrefs.hasNonDefaultSecret(ctx)) {
                    AppPrefs.sharedSecret(ctx)
                } else {
                    ""
                },
            )
        }
        val code = AppPrefs.pendingPairCode(requireContext())
        v.findViewById<TextView>(R.id.obhPending).text = if (code.isNullOrBlank()) {
            getString(R.string.pending_code_none)
        } else {
            "Pending: $code"
        }
        val p = AppPrefs.pendingPairCode(requireContext())
        v.findViewById<MaterialButton>(R.id.obhBtnApprove).isEnabled = !p.isNullOrBlank() && p != AppPrefs.approvedPairCode(
            requireContext(),
        )
        val on = AppPrefs.isPairingModeEnabled(requireContext())
        v.findViewById<MaterialButton>(R.id.obhBtnTogglePair).text = if (on) {
            getString(R.string.disable_pairing_mode)
        } else {
            getString(R.string.enable_pairing_mode)
        }
        updatePairedBanner()
    }

    private fun toggleHostPairing() {
        val n = !AppPrefs.isPairingModeEnabled(requireContext())
        AppPrefs.setPairingModeEnabled(requireContext(), n)
        DebugLog.append(requireContext(), "OB_HOST", "Pairing mode $n")
        refreshHost()
    }

    private fun approvePending() {
        val p = AppPrefs.pendingPairCode(requireContext())
        if (p.isNullOrBlank()) {
            Toast.makeText(requireContext(), R.string.host_code_missing, Toast.LENGTH_SHORT).show()
            return
        }
        AppPrefs.setApprovedPairCode(requireContext(), p)
        refreshHost()
        Toast.makeText(requireContext(), R.string.host_code_approved, Toast.LENGTH_SHORT).show()
    }

    private fun saveHostSecret(sec: TextInputEditText) {
        val t = sec.text?.toString()?.trim().orEmpty()
        if (t.isEmpty()) {
            Toast.makeText(requireContext(), R.string.secret_empty, Toast.LENGTH_SHORT).show()
            return
        }
        AppPrefs.setSharedSecret(requireContext(), t)
        DebugLog.append(requireContext(), "OB", "Host secret saved in onboarding")
        Toast.makeText(requireContext(), R.string.secret_saved, Toast.LENGTH_SHORT).show()
        requireContext().startService(Intent(requireContext(), HostBleService::class.java))
        refreshHost()
    }

    private fun shareHostPassphrase() {
        val t = AppPrefs.sharedSecret(requireContext())
        if (t.isEmpty()) {
            Toast.makeText(requireContext(), R.string.secret_empty, Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, t)
                },
                getString(R.string.host_pairing_title),
            ),
        )
    }

    private fun startPairing(confirmBtn: MaterialButton) {
        if (startInProgress || confirmInProgress) return
        startInProgress = true
        backgroundExecutor?.execute {
            val r = BlePairingClient.startPairing(requireContext())
            requireActivity().runOnUiThread {
                if (!isResumed) return@runOnUiThread
                startInProgress = false
                val session = r.session
                if (session == null) {
                    val msg = when (r.error) {
                        PairingStartError.BLUETOOTH_OFF -> getString(R.string.pairing_failed_bluetooth_off)
                        PairingStartError.HOST_NOT_FOUND -> getString(R.string.pairing_failed_host_not_found)
                        PairingStartError.HOST_PAIRING_MODE_OFF -> getString(R.string.pairing_failed_pair_mode_off)
                        PairingStartError.HANDSHAKE_FAILED -> getString(R.string.pairing_failed_handshake)
                        null -> getString(R.string.pairing_failed)
                    }
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                activeSession = session
                requireView().findViewById<TextView>(R.id.obcPairCode).text = "Controller code: ${session.code}"
                confirmBtn.isEnabled = true
                Toast.makeText(requireContext(), R.string.pairing_started, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun confirmPairing(confirmBtn: MaterialButton) {
        val s = activeSession
        if (s == null) {
            Toast.makeText(requireContext(), R.string.pairing_failed, Toast.LENGTH_SHORT).show()
            return
        }
        if (confirmInProgress) return
        confirmInProgress = true
        confirmBtn.isEnabled = false
        backgroundExecutor?.execute {
            val ok = BlePairingClient.confirmPairing(requireContext(), s)
            requireActivity().runOnUiThread {
                if (!isResumed) return@runOnUiThread
                confirmInProgress = false
                if (!ok) {
                    Toast.makeText(requireContext(), R.string.pairing_confirm_failed, Toast.LENGTH_SHORT).show()
                    if (activeSession != null) confirmBtn.isEnabled = true
                    return@runOnUiThread
                }
                AppPrefs.setSharedSecret(requireContext(), s.candidateSecret)
                AppPrefs.setClientPaired(requireContext(), true)
                activeSession = null
                requireView().findViewById<TextView>(R.id.obcPairCode).text = getString(R.string.pair_code_none)
                updatePairedBanner()
                Toast.makeText(requireContext(), R.string.pairing_confirmed, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
