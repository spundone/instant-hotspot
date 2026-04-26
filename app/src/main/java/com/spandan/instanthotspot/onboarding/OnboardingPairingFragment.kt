package com.spandan.instanthotspot.onboarding

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.spandan.instanthotspot.MainActivity
import com.spandan.instanthotspot.R
import com.spandan.instanthotspot.controller.BlePairingClient
import com.spandan.instanthotspot.controller.PairingStartError
import com.spandan.instanthotspot.controller.PairingSession
import com.spandan.instanthotspot.core.AppPrefs
import com.spandan.instanthotspot.core.DebugLog
import com.spandan.instanthotspot.core.RandomSecret
import com.spandan.instanthotspot.host.HostBleService
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService

class OnboardingPairingFragment : Fragment(R.layout.fragment_onboarding_pairing) {
    private val prefsName = "instant_hotspot_prefs"
    private val keyMode = "mode"
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        backgroundExecutor = Executors.newSingleThreadExecutor()
    }

    override fun onDestroy() {
        backgroundExecutor?.shutdown()
        super.onDestroy()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val hostCard = view.findViewById<View>(R.id.obHostPairCard)
        val ctrlCard = view.findViewById<View>(R.id.obControllerPairCard)
        val hint = view.findViewById<TextView>(R.id.obPairingHint)
        if (isHost()) {
            hostCard.visibility = View.VISIBLE
            ctrlCard.visibility = View.GONE
            hint.setText(R.string.ob_pairing_hint_host)
            val toggle = view.findViewById<MaterialButton>(R.id.obhBtnTogglePair)
            val readv = view.findViewById<MaterialButton>(R.id.obhBtnReadvertise)
            val approve = view.findViewById<MaterialButton>(R.id.obhBtnApprove)
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
            hostCard.visibility = View.GONE
            ctrlCard.visibility = View.VISIBLE
            hint.setText(R.string.ob_pairing_hint_controller)
            val save = view.findViewById<MaterialButton>(R.id.obcSaveSecret)
            val start = view.findViewById<MaterialButton>(R.id.obcStart)
            val confirm = view.findViewById<MaterialButton>(R.id.obcConfirm)
            val sec = view.findViewById<TextInputEditText>(R.id.obcSecret)
            save.setOnClickListener { saveManual(sec) }
            start.setOnClickListener { startPairing(confirm) }
            confirm.setOnClickListener { confirmPairing(confirm) }
            sec.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    saveManual(sec)
                    true
                } else {
                    false
                }
            }
        }
    }

    private fun isHost(): Boolean = requireContext().getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        .getString(keyMode, MainActivity.MODE_CONTROLLER) == MainActivity.MODE_HOST

    override fun onResume() {
        super.onResume()
        if (isHost()) {
            if (!AppPrefs.hasNonDefaultSecret(requireContext())) {
                AppPrefs.setSharedSecret(requireContext(), RandomSecret.newReadable())
            }
            requireContext().startService(Intent(requireContext(), HostBleService::class.java))
            handler.removeCallbacks(hostRefresh)
            handler.post(hostRefresh)
            refreshHost()
        }
    }

    override fun onPause() {
        handler.removeCallbacks(hostRefresh)
        super.onPause()
    }

    private fun refreshHost() {
        val v = requireView()
        v.findViewById<TextView>(R.id.obhHostSecret).text =
            if (AppPrefs.hasNonDefaultSecret(requireContext())) {
                AppPrefs.sharedSecret(requireContext())
            } else {
                getString(R.string.pairing_not_set)
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
        v.findViewById<TextView>(R.id.obhPairedC).text = AppPrefs.lastPairedController(requireContext())?.let {
            getString(R.string.host_paired_device_value, it)
        } ?: getString(R.string.host_paired_device_none)
        val on = AppPrefs.isPairingModeEnabled(requireContext())
        v.findViewById<MaterialButton>(R.id.obhBtnTogglePair).text = if (on) {
            getString(R.string.disable_pairing_mode)
        } else {
            getString(R.string.enable_pairing_mode)
        }
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

    private fun saveManual(sec: TextInputEditText) {
        val t = sec.text?.toString()?.trim().orEmpty()
        if (t.length < 12) {
            Toast.makeText(requireContext(), R.string.secret_empty, Toast.LENGTH_SHORT).show()
            return
        }
        AppPrefs.setSharedSecret(requireContext(), t)
        AppPrefs.setClientPaired(requireContext(), true)
        AppPrefs.setLastPairedHost(requireContext(), "manual-secret")
        DebugLog.append(requireContext(), "OB", "Manual pair in onboarding")
        Toast.makeText(requireContext(), R.string.manual_pair_saved, Toast.LENGTH_SHORT).show()
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
                Toast.makeText(requireContext(), R.string.pairing_confirmed, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
