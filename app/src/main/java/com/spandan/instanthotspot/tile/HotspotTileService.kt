package com.spandan.instanthotspot.tile

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.spandan.instanthotspot.core.AppPrefs
import com.spandan.instanthotspot.core.HotspotCommand
import com.spandan.instanthotspot.controller.CommandSendStatus
import com.spandan.instanthotspot.controller.ControllerCommandSender

class HotspotTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        ControllerCommandSender.sendAsync(this, HotspotCommand.HOTSPOT_ON) { status ->
            if (status == CommandSendStatus.SUCCESS) {
                AppPrefs.markHostReachableNow(this)
            }
            refreshTile()
        }
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        val paired = AppPrefs.isClientPaired(this)
        val connected = AppPrefs.isHostReachableRecently(this)
        tile.state = when {
            !paired -> Tile.STATE_UNAVAILABLE
            connected -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.label = getString(com.spandan.instanthotspot.R.string.tile_label)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                !paired -> "Not paired"
                connected -> "Connected"
                else -> "Paired, offline"
            }
        }
        tile.updateTile()
    }
}
