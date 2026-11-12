package com.controid.clipboard

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import com.controid.service.EcosystemForegroundService

class ControidTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_ACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val triggerSync = {
            val intent = Intent(this, EcosystemForegroundService::class.java).apply {
                action = ACTION_SYNC_CLIPBOARD
            }
            runCatching {
                ContextCompat.startForegroundService(this, intent)
            }.onFailure {
                runCatching { startService(intent) }
            }
        }

        if (isLocked) {
            unlockAndRun { triggerSync() }
        } else {
            triggerSync()
        }
    }

    companion object {
        const val ACTION_SYNC_CLIPBOARD = "com.controid.ACTION_SYNC_CLIPBOARD"
    }
}
