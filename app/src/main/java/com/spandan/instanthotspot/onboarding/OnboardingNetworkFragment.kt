package com.spandan.instanthotspot.onboarding

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.spandan.instanthotspot.MainActivity
import com.spandan.instanthotspot.R
import com.spandan.instanthotspot.core.AppPrefs
import com.spandan.instanthotspot.core.HostCompatSummary
import com.spandan.instanthotspot.core.WifiStatusHelper

class OnboardingNetworkFragment : Fragment(R.layout.fragment_onboarding_network) {
    private val prefsName = "instant_hotspot_prefs"
    private val keyMode = "mode"
    private val handler = Handler(Looper.getMainLooper())
    private val hostRefresh = object : Runnable {
        override fun run() {
            if (isResumed && isHost()) {
                refreshHostNetworkSnapshot()
            }
            if (isResumed && isHost()) {
                handler.postDelayed(this, 3000L)
            }
        }
    }

    private fun isHost() = requireContext().getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        .getString(keyMode, MainActivity.MODE_CONTROLLER) == MainActivity.MODE_HOST

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val title = view.findViewById<TextView>(R.id.obNetPageTitle)
        val hostCard = view.findViewById<MaterialCardView>(R.id.obNetHostCard)
        val controllerNote = view.findViewById<TextView>(R.id.obControllerNetworkNote)
        if (isHost()) {
            title.setText(R.string.ob_network_page_title_host)
            hostCard.visibility = View.VISIBLE
            controllerNote.visibility = View.GONE
            view.findViewById<MaterialButton>(R.id.obHostShareNetwork).setOnClickListener { shareHostSnapshot() }
            refreshHostNetworkSnapshot()
        } else {
            title.setText(R.string.ob_network_page_title_controller)
            hostCard.visibility = View.GONE
            controllerNote.visibility = View.VISIBLE
            controllerNote.setText(R.string.ob_network_controller_note)
        }
    }

    override fun onResume() {
        super.onResume()
        if (isHost()) {
            handler.removeCallbacks(hostRefresh)
            handler.post(hostRefresh)
        }
    }

    override fun onPause() {
        handler.removeCallbacks(hostRefresh)
        super.onPause()
    }

    private fun refreshHostNetworkSnapshot() {
        val v = view ?: return
        val out = v.findViewById<TextView>(R.id.obHostNetworkDetail) ?: return
        val ctx = requireContext()
        val apProbe = AppPrefs.lastApStateLine(ctx)
        val apLine = if (apProbe.isNullOrBlank()) {
            getString(R.string.ob_network_host_ap_none)
        } else {
            getString(R.string.ob_network_ap_probe, apProbe)
        }
        out.text = buildString {
            appendLine(WifiStatusHelper.line(ctx))
            appendLine(apLine)
            appendLine()
            append(HostCompatSummary.build(ctx))
        }
    }

    private fun shareHostSnapshot() {
        refreshHostNetworkSnapshot()
        val text = view?.findViewById<TextView>(R.id.obHostNetworkDetail)?.text?.toString().orEmpty()
        if (text.isBlank()) return
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                },
                getString(R.string.ob_network_page_title_host),
            ),
        )
    }
}
