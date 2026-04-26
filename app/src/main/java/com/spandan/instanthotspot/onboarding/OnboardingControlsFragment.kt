package com.spandan.instanthotspot.onboarding

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.spandan.instanthotspot.R
import com.spandan.instanthotspot.controller.CommandSendStatus
import com.spandan.instanthotspot.controller.ControllerCommandSender
import com.spandan.instanthotspot.core.AppPrefs
import com.spandan.instanthotspot.core.HotspotCommand
import com.spandan.instanthotspot.MainActivity

class OnboardingControlsFragment : Fragment(R.layout.fragment_onboarding_controls) {
    private val prefsName = "instant_hotspot_prefs"
    private val keyMode = "mode"

    private fun isHost() = requireContext().getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        .getString(keyMode, MainActivity.MODE_CONTROLLER) == MainActivity.MODE_HOST

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val hostC = view.findViewById<MaterialCardView>(R.id.obControlHostTextCard)
        val clC = view.findViewById<MaterialCardView>(R.id.obControlClientCard)
        if (isHost()) {
            hostC.visibility = View.VISIBLE
            clC.visibility = View.GONE
        } else {
            hostC.visibility = View.GONE
            clC.visibility = View.VISIBLE
            val onB = view.findViewById<MaterialButton>(R.id.obBtnHotOn)
            val offB = view.findViewById<MaterialButton>(R.id.obBtnHotOff)
            onB.setOnClickListener { send(HotspotCommand.HOTSPOT_ON, R.string.toast_client_hotspot_on_sent) }
            offB.setOnClickListener { send(HotspotCommand.HOTSPOT_OFF, R.string.toast_client_hotspot_off_sent) }
        }
    }

    private fun send(cmd: HotspotCommand, okRes: Int) {
        ControllerCommandSender.sendAsync(requireContext(), cmd) { s ->
            if (s == CommandSendStatus.SUCCESS) {
                AppPrefs.markHostReachableNow(requireContext())
            }
            val msg = when (s) {
                CommandSendStatus.SUCCESS -> getString(okRes)
                CommandSendStatus.NOT_PAIRED -> getString(R.string.client_status_not_paired)
                CommandSendStatus.BLUETOOTH_OFF -> "Bluetooth is off"
                CommandSendStatus.HOST_NOT_FOUND -> "Host not found"
                CommandSendStatus.SEND_FAILED -> "Failed to send"
            }
            requireActivity().runOnUiThread {
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
