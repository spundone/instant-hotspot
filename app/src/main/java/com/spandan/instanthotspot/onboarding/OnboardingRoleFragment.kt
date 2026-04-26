package com.spandan.instanthotspot.onboarding

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RadioGroup
import androidx.fragment.app.Fragment
import com.spandan.instanthotspot.MainActivity
import com.spandan.instanthotspot.R
import com.spandan.instanthotspot.core.AppPrefs
import com.spandan.instanthotspot.core.RandomSecret
import com.spandan.instanthotspot.host.HostBleService

class OnboardingRoleFragment : Fragment(R.layout.fragment_onboarding_role) {
    private val prefsName = "instant_hotspot_prefs"
    private val keyMode = "mode"

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val g = view.findViewById<RadioGroup>(R.id.obRoleGroup)
        val p = requireContext().getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        when (p.getString(keyMode, MainActivity.MODE_CONTROLLER)) {
            MainActivity.MODE_HOST -> g.check(R.id.obRadioHost)
            else -> g.check(R.id.obRadioController)
        }
        g.setOnCheckedChangeListener { _, _ ->
            applyMode()
        }
    }

    private fun applyMode() {
        val g = requireView().findViewById<RadioGroup>(R.id.obRoleGroup)
        val host = g.checkedRadioButtonId == R.id.obRadioHost
        val mode = if (host) MainActivity.MODE_HOST else MainActivity.MODE_CONTROLLER
        requireContext().getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit()
            .putString(keyMode, mode)
            .apply()
        val ctx = requireContext()
        if (host) {
            if (!AppPrefs.hasNonDefaultSecret(ctx)) {
                AppPrefs.setSharedSecret(ctx, RandomSecret.newReadable())
            }
            if (!AppPrefs.isPairingModeEnabled(ctx)) {
                AppPrefs.setPairingModeEnabled(ctx, true)
            }
            ctx.startService(Intent(ctx, HostBleService::class.java))
        } else {
            ctx.stopService(Intent(ctx, HostBleService::class.java))
        }
    }
}
