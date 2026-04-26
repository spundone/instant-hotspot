package com.spandan.instanthotspot.onboarding

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.spandan.instanthotspot.R
import com.spandan.instanthotspot.controller.BlePairingClient
import com.spandan.instanthotspot.core.AppPrefs
import com.spandan.instanthotspot.core.HotspotConfigParser
import com.spandan.instanthotspot.core.WifiStatusHelper
import com.spandan.instanthotspot.MainActivity
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService

class OnboardingNetworkFragment : Fragment(R.layout.fragment_onboarding_network) {
    private val prefsName = "instant_hotspot_prefs"
    private val keyMode = "mode"
    private val handler = Handler(Looper.getMainLooper())
    private var exec: ExecutorService? = null
    private val tick = object : Runnable {
        override fun run() {
            if (isResumed && !isHost()) {
                updateWifiLine()
            }
            handler.postDelayed(this, 3000L)
        }
    }

    private fun isHost() = requireContext().getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        .getString(keyMode, MainActivity.MODE_CONTROLLER) == MainActivity.MODE_HOST

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        exec = Executors.newSingleThreadExecutor()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (isHost()) {
            view.findViewById<TextView>(R.id.obNetHostInfo).visibility = View.VISIBLE
            view.findViewById<MaterialCardView>(R.id.obNetClientCard).visibility = View.GONE
        } else {
            view.findViewById<TextView>(R.id.obNetHostInfo).visibility = View.GONE
            val card = view.findViewById<MaterialCardView>(R.id.obNetClientCard)
            card.visibility = View.VISIBLE
            val sync = view.findViewById<MaterialButton>(R.id.obSync)
            sync.setOnClickListener { doSync(view) }
            updateConfig(view)
            updateWifiLine()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isHost()) {
            handler.removeCallbacks(tick)
            handler.post(tick)
        }
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    override fun onDestroy() {
        exec?.shutdown()
        super.onDestroy()
    }

    private fun updateWifiLine() {
        if (!isHost()) {
            val v = view ?: return
            v.findViewById<TextView>(R.id.obWifiLine).text = WifiStatusHelper.line(requireContext())
        }
    }

    private fun doSync(root: View) {
        exec?.execute {
            val c = BlePairingClient.fetchHotspotConfig(requireContext())
            requireActivity().runOnUiThread {
                if (c == null) {
                    Toast.makeText(requireContext(), R.string.client_sync_failed, Toast.LENGTH_SHORT).show()
                } else {
                    AppPrefs.setLastSyncedHotspotConfig(requireContext(), c)
                    val creds = HotspotConfigParser.parseSsidPassword(c)
                    if (creds != null) {
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.client_sync_connect_hint, creds.ssid, creds.password),
                            Toast.LENGTH_LONG,
                        ).show()
                        runCatching {
                            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                        }
                    } else {
                        Toast.makeText(requireContext(), R.string.client_sync_missing_credentials, Toast.LENGTH_LONG).show()
                    }
                }
                updateConfig(root)
            }
        }
    }

    private fun updateConfig(v: View) {
        val raw = AppPrefs.lastSyncedHotspotConfig(requireContext())
        v.findViewById<TextView>(R.id.obSyncedConfig).text = if (raw.isNullOrBlank()) {
            getString(R.string.client_hotspot_config_empty)
        } else {
            formatConfig(raw)
        }
    }

    private fun formatConfig(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return getString(R.string.client_hotspot_config_empty)
        return t
            .replace(" ; ", "\n")
            .replace(" | ", "\n")
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .take(8_000)
    }

}
