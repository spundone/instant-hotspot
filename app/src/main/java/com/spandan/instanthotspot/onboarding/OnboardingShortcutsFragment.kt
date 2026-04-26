package com.spandan.instanthotspot.onboarding

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.spandan.instanthotspot.MainActivity
import com.spandan.instanthotspot.R
import com.spandan.instanthotspot.widget.HotspotWidgetProvider

class OnboardingShortcutsFragment : Fragment(R.layout.fragment_onboarding_shortcuts) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<MaterialButton>(R.id.obBtnAddTile).setOnClickListener { requestTile() }
        view.findViewById<MaterialButton>(R.id.obBtnAddWidget).setOnClickListener { requestWidget() }
    }

    private fun requestTile() {
        @Suppress("DEPRECATION", "InlinedApi")
        runCatching {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivity(Intent("android.service.quicksettings.action.QS_PREFERENCES"))
            }
        }
        Toast.makeText(
            requireContext(),
            R.string.ob_tile_added_or_prompt,
            Toast.LENGTH_LONG,
        ).show()
    }

    @Suppress("DEPRECATION", "InlinedApi")
    private fun requestWidget() {
        val ctx = requireContext()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            showWidgetHelp()
            return
        }
        val awm = AppWidgetManager.getInstance(ctx)
        if (!awm.isRequestPinAppWidgetSupported) {
            showWidgetHelp()
            return
        }
        val c = ComponentName(ctx, HotspotWidgetProvider::class.java)
        val successPi = PendingIntent.getActivity(
            ctx,
            0,
            Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        @Suppress("WrongConstant", "InlinedApi")
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            awm.requestPinAppWidget(c, null, successPi)
        } else {
            awm.requestPinAppWidget(c, null, null)
        }
        if (!ok) {
            showWidgetHelp()
        }
    }

    private fun showWidgetHelp() {
        Toast.makeText(
            requireContext(),
            R.string.ob_widget_pin_prompt,
            Toast.LENGTH_LONG,
        ).show()
    }
}
