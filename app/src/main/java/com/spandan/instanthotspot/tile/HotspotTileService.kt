package com.spandan.instanthotspot.tile

import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.spandan.instanthotspot.R
import com.spandan.instanthotspot.core.AppPrefs
import com.spandan.instanthotspot.core.HotspotCommand
import com.spandan.instanthotspot.controller.CommandSendStatus
import com.spandan.instanthotspot.controller.ControllerCommandSender

class HotspotTileService : TileService() {
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        // Lets BLE run while the quick settings shade is up; also avoids "dead" tiles on
        // some HyperOS / MIUI builds. Runnable returns immediately; work continues async.
        unlockAndRun { dispatchToggleFromTile() }
    }

    private fun dispatchToggleFromTile() {
        ControllerCommandSender.sendAsync(
            this,
            HotspotCommand.HOTSPOT_TOGGLE,
        ) { status ->
            if (status == CommandSendStatus.SUCCESS) {
                AppPrefs.markHostReachableNow(this@HotspotTileService)
            }
            mainHandler.post {
                val msg = when (status) {
                    CommandSendStatus.SUCCESS -> getString(R.string.tile_toast_toggled)
                    CommandSendStatus.NOT_PAIRED -> getString(R.string.tile_toast_not_paired)
                    CommandSendStatus.BLUETOOTH_OFF -> getString(R.string.tile_toast_bt_off)
                    CommandSendStatus.HOST_NOT_FOUND -> getString(R.string.tile_toast_host_missing)
                    CommandSendStatus.SEND_FAILED -> getString(R.string.tile_toast_send_failed)
                }
                Toast.makeText(this@HotspotTileService, msg, Toast.LENGTH_SHORT).show()
                refreshTile()
            }
        }
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        val paired = AppPrefs.isClientPaired(this)
        val connected = AppPrefs.isHostReachableRecently(this)
        // INACTIVE/ACTIVE stay clickable; UNAVAILABLE is ignored by many launchers.
        if (!paired) {
            tile.state = Tile.STATE_INACTIVE
        } else if (connected) {
            tile.state = Tile.STATE_ACTIVE
        } else {
            tile.state = Tile.STATE_INACTIVE
        }
        tile.label = getString(R.string.tile_label)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                !paired -> getString(R.string.tile_subtitle_open_app_pair)
                connected -> getString(R.string.tile_subtitle_tap_to_toggle)
                else -> getString(R.string.tile_subtitle_paired_offline)
            }
        }
        tile.updateTile()
    }
}
