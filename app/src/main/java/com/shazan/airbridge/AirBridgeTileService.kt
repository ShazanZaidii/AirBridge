package com.shazan.airbridge

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class AirBridgeTileService : TileService() {

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()

        val intent = Intent(this, SyncActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        if (Build.VERSION.SDK_INT >= 34) {
            val pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    override fun onStartListening() {
        super.onStartListening()

        val prefs = getSharedPreferences("AirBridgePrefs", Context.MODE_PRIVATE)
        val url = prefs.getString("WS_URL", null)

        if (url != null) {
            val uri = Uri.parse(url)
            val macName = uri.getQueryParameter("name") ?: "Mac"
            qsTile?.label = "Send to $macName"
            qsTile?.state = Tile.STATE_ACTIVE
        } else {
            qsTile?.label = "AirBridge"
            qsTile?.state = Tile.STATE_INACTIVE
        }

        qsTile?.updateTile()
    }
}