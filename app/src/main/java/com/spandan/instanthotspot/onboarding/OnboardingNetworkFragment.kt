package com.spandan.instanthotspot.onboarding

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.spandan.instanthotspot.R
import com.spandan.instanthotspot.core.AppPrefs
import com.spandan.instanthotspot.core.HostCompatSummary
import com.spandan.instanthotspot.core.HotspotConfigParser
import com.spandan.instanthotspot.core.HotspotController
import com.spandan.instanthotspot.core.OnboardingV2
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService

class OnboardingNetworkFragment : Fragment(R.layout.fragment_onboarding_network) {
    private val handler = Handler(Looper.getMainLooper())
    private var backgroundExecutor: ExecutorService? = null
    @Volatile
    private var snapshotGeneration: Long = 0L

    companion object {
        private const val TAG = "OnboardingNetwork"
        /** Avoid TransactionTooLargeException when sharing via Intent. */
        private const val MAX_SHARE_TEXT_CHARS = 100_000
    }
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

    private fun isHost() = OnboardingV2.isHostModeForOnboarding(requireContext())

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
            view.findViewById<MaterialButton>(R.id.obHostShareNetwork)?.setOnClickListener { shareHostSnapshot() }
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

    private fun truncateForIntentShare(text: String): String {
        if (text.length <= MAX_SHARE_TEXT_CHARS) return text
        return text.substring(0, MAX_SHARE_TEXT_CHARS) +
            "\n\n…(truncated for share; " + (text.length - MAX_SHARE_TEXT_CHARS) + " chars omitted)"
    }

    private fun refreshHostNetworkSnapshot() {
        val v = view ?: return
        val out = v.findViewById<TextView>(R.id.obHostNetworkDetail) ?: return
        // Use the host Activity context: package features (install source, su, Bluetooth) are
        // not guaranteed safe with applicationContext on all OEMs/threads.
        val snapshotCtx: Context = requireContext()
        val gen = ++snapshotGeneration
        backgroundExecutor?.execute {
            val text = runCatching { buildHostHotspotSnapshotText(snapshotCtx) }
                .onFailure { t -> Log.w(TAG, "refreshHostNetworkSnapshot", t) }
                .getOrElse { t ->
                    snapshotCtx.getString(
                        R.string.ob_network_snapshot_error,
                        t.message?.take(200) ?: t.javaClass.simpleName,
                    )
                }
            handler.post {
                if (!isResumed) return@post
                if (gen != snapshotGeneration) return@post
                out.text = text
            }
        }
    }

    private fun shareHostSnapshot() {
        if (!isAdded) return
        // Snapshot and share must not hold an Activity across rotation; re-read on main in handler.
        val snapshotCtx: Context = requireContext()
        val chooserTitle = getString(R.string.ob_network_page_title_host)
        backgroundExecutor?.execute {
            val text = runCatching { buildHostHotspotSnapshotText(snapshotCtx) }
                .onFailure { t -> Log.w(TAG, "shareHostSnapshot build", t) }
                .getOrElse { t ->
                    snapshotCtx.getString(
                        R.string.ob_network_snapshot_error,
                        t.message?.take(200) ?: t.javaClass.simpleName,
                    )
                }
            val toShare = truncateForIntentShare(text)
            if (toShare.isBlank()) return@execute
            handler.post {
                if (!isResumed || !isAdded) return@post
                val act: Activity? = activity
                if (act == null || act.isFinishing) return@post
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, toShare)
                }
                val chooser = Intent.createChooser(send, chooserTitle)
                runCatching {
                    act.startActivity(chooser)
                }.onFailure { t ->
                    Log.e(TAG, "startActivity(share)", t)
                    val msg = when (t) {
                        is ActivityNotFoundException -> getString(R.string.ob_network_share_no_handler)
                        else -> t.message ?: t.javaClass.simpleName
                    }
                    Toast.makeText(act, msg, Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
