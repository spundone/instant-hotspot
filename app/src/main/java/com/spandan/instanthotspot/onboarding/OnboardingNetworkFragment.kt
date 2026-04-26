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
import com.spandan.instanthotspot.core.HotspotConfigParser
import com.spandan.instanthotspot.core.HotspotController
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService

class OnboardingNetworkFragment : Fragment(R.layout.fragment_onboarding_network) {
    private val prefsName = "instant_hotspot_prefs"
    private val keyMode = "mode"
    private val handler = Handler(Looper.getMainLooper())
    private var backgroundExecutor: ExecutorService? = null
    @Volatile
    private var snapshotGeneration: Long = 0L
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        backgroundExecutor = Executors.newSingleThreadExecutor()
    }

    override fun onDestroy() {
        backgroundExecutor?.shutdown()
        super.onDestroy()
    }

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

    /**
     * Hotspot + device install details (not the phone’s *connected* Wi‑Fi network).
     */
    private fun buildHostHotspotSnapshotText(ctx: Context): String = buildString {
        val apProbe = AppPrefs.lastApStateLine(ctx)
        if (apProbe.isNullOrBlank()) {
            appendLine(ctx.getString(R.string.ob_network_host_ap_none))
        } else {
            appendLine(ctx.getString(R.string.ob_network_ap_probe, apProbe))
        }
        val raw = HotspotController.hotspotConfigSummary()
        val creds = HotspotConfigParser.parseSsidPassword(raw)
        if (creds != null) {
            appendLine(ctx.getString(R.string.ob_network_hotspot_ssid, creds.ssid))
            appendLine(ctx.getString(R.string.ob_network_hotspot_password, creds.password))
        } else {
            val label = ctx.getString(R.string.ob_network_hotspot_config_label)
            appendLine(
                if (raw.isNotBlank()) {
                    "$label:\n$raw"
                } else {
                    label + ": (empty)"
                },
            )
        }
        appendLine()
        append(HostCompatSummary.build(ctx))
    }

    private fun refreshHostNetworkSnapshot() {
        val v = view ?: return
        val out = v.findViewById<TextView>(R.id.obHostNetworkDetail) ?: return
        val appCtx = requireContext().applicationContext
        val gen = ++snapshotGeneration
        backgroundExecutor?.execute {
            val text = buildHostHotspotSnapshotText(appCtx)
            handler.post {
                if (!isResumed) return@post
                if (gen != snapshotGeneration) return@post
                out.text = text
            }
        }
    }

    private fun shareHostSnapshot() {
        val appCtx = requireContext().applicationContext
        backgroundExecutor?.execute {
            val text = buildHostHotspotSnapshotText(appCtx)
            if (text.isBlank()) return@execute
            handler.post {
                if (!isResumed) return@post
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
    }
}
