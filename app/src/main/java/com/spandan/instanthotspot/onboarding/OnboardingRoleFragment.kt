package com.spandan.instanthotspot.onboarding

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RadioGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.spandan.instanthotspot.MainActivity
import com.spandan.instanthotspot.R
import com.spandan.instanthotspot.core.AppPrefs
import com.spandan.instanthotspot.core.AppTooling
import com.spandan.instanthotspot.core.HostOnboarding
import com.spandan.instanthotspot.core.ProjectInfo
import com.spandan.instanthotspot.core.RandomSecret
import com.spandan.instanthotspot.host.HostBleService

class OnboardingRoleFragment : Fragment(R.layout.fragment_onboarding_role) {
    private val prefsName = "instant_hotspot_prefs"
    private val keyMode = "mode"

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val p = requireContext().getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        var mode = p.getString(keyMode, MainActivity.MODE_CONTROLLER) ?: MainActivity.MODE_CONTROLLER
        if (mode == MainActivity.MODE_HOST && !HostOnboarding.isHostSetupSatisfied(requireContext())) {
            p.edit().putString(keyMode, MainActivity.MODE_CONTROLLER).apply()
            mode = MainActivity.MODE_CONTROLLER
            AppPrefs.setOnboardingWantsHost(requireContext(), true)
        }
        val g = view.findViewById<RadioGroup>(R.id.obRoleGroup)
        when {
            mode == MainActivity.MODE_HOST -> g.check(R.id.obRadioHost)
            AppPrefs.isOnboardingWantsHost(requireContext()) -> g.check(R.id.obRadioHost)
            else -> g.check(R.id.obRadioController)
        }
        g.setOnCheckedChangeListener { _, _ ->
            applyFromRadio()
        }
        view.findViewById<MaterialButton>(R.id.obDownloadMagiskReleases).setOnClickListener {
            AppTooling.openUrl(requireContext(), ProjectInfo.releasesPageUrl())
        }
        val prereq = view.findViewById<MaterialCardView>(R.id.obHostPrereqCard)
        val verify = view.findViewById<MaterialButton>(R.id.obVerifyHostSetup)
        val status = view.findViewById<TextView>(R.id.obHostPrereqStatus)
        verify.setOnClickListener { verifyHostOnboarding(status) }
        applyFromRadio()
        refreshPrereqUi(prereq, status)
    }

    private fun refreshPrereqUi(prereq: MaterialCardView, status: TextView) {
        val g = requireView().findViewById<RadioGroup>(R.id.obRoleGroup)
        val isHost = g.checkedRadioButtonId == R.id.obRadioHost
        if (!isHost) {
            prereq.visibility = View.GONE
            return
        }
        prereq.visibility = View.VISIBLE
        val ok = HostOnboarding.isHostSetupSatisfied(requireContext())
        if (ok) {
            status.text = getString(R.string.ob_host_prereq_status_ok)
        } else {
            status.setText(R.string.ob_host_prereq_status_fail)
        }
    }

    private fun verifyHostOnboarding(status: TextView) {
        val btn = requireView().findViewById<MaterialButton>(R.id.obVerifyHostSetup)
        btn.isEnabled = false
        Thread {
            val ok = HostOnboarding.isHostSetupSatisfied(requireContext().applicationContext)
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                btn.isEnabled = true
                if (ok) {
                    AppPrefs.setOnboardingWantsHost(requireContext(), false)
                    commitHostMode()
                    val prereq = requireView().findViewById<MaterialCardView>(R.id.obHostPrereqCard)
                    refreshPrereqUi(prereq, status)
                    (activity as? MainActivity)?.onOnboardingModeChanged()
                } else {
                    status.setText(R.string.ob_host_prereq_status_fail)
                }
            }
        }.start()
    }

    private fun applyFromRadio() {
        val g = requireView().findViewById<RadioGroup>(R.id.obRoleGroup)
        val host = g.checkedRadioButtonId == R.id.obRadioHost
        val ctx = requireContext()
        val prereq = requireView().findViewById<MaterialCardView>(R.id.obHostPrereqCard)
        val status = requireView().findViewById<TextView>(R.id.obHostPrereqStatus)
        if (host) {
            if (HostOnboarding.isHostSetupSatisfied(ctx)) {
                AppPrefs.setOnboardingWantsHost(ctx, false)
                commitHostMode()
            } else {
                requireContext().getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                    .edit()
                    .putString(keyMode, MainActivity.MODE_CONTROLLER)
                    .apply()
                AppPrefs.setOnboardingWantsHost(ctx, true)
                ctx.stopService(Intent(ctx, HostBleService::class.java))
            }
        } else {
            AppPrefs.setOnboardingWantsHost(ctx, false)
            requireContext().getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                .edit()
                .putString(keyMode, MainActivity.MODE_CONTROLLER)
                .apply()
            ctx.stopService(Intent(ctx, HostBleService::class.java))
        }
        refreshPrereqUi(prereq, status)
        (activity as? MainActivity)?.onOnboardingModeChanged()
    }

    private fun commitHostMode() {
        val ctx = requireContext()
        // commit() so a fast tap on Finish (markFlowComplete) never reads stale "controller" mode.
        requireContext().getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit()
            .putString(keyMode, MainActivity.MODE_HOST)
            .commit()
        if (!AppPrefs.hasNonDefaultSecret(ctx)) {
            AppPrefs.setSharedSecret(ctx, RandomSecret.newReadable())
        }
        if (!AppPrefs.isPairingModeEnabled(ctx)) {
            AppPrefs.setPairingModeEnabled(ctx, true)
        }
        ctx.startService(Intent(ctx, HostBleService::class.java))
    }
}
