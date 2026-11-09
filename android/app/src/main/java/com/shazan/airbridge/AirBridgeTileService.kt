package com.shazan.airbridge

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.shazan.airbridge.net.ClipboardSync
import com.shazan.airbridge.net.Hub

/** Quick Settings tile: push the clipboard to every connected device. */
class AirBridgeTileService : TileService() {

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val intent = Intent(this, SyncActivity::class.java)
            .putExtra(ClipboardSync.EXTRA_REASON, "tile")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        val peers = Hub.peers()
        when (peers.size) {
            0 -> { tile.label = getString(R.string.app_brand); tile.state = Tile.STATE_INACTIVE }
            1 -> { tile.label = peers[0].name; tile.state = Tile.STATE_ACTIVE }
            else -> { tile.label = resources.getQuantityString(R.plurals.tile_sync_n, peers.size, peers.size); tile.state = Tile.STATE_ACTIVE }
        }
        tile.updateTile()
    }
}
