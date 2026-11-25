package com.example.glassrack.service

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.example.glassrack.MainActivity
import com.example.glassrack.core.database.GlassrackRepository
import com.example.glassrack.core.model.TriggerSource
import com.example.glassrack.core.ssh.HomelabController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class GlassrackQuickTileService : TileService(), KoinComponent {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repository: GlassrackRepository by inject()
    private val controller: HomelabController by inject()

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = "Glassrack WoL"
            subtitle = "Wake Homelab"
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        scope.launch {
            val servers = repository.getServersOnce()
            val wolTarget = servers.firstOrNull { it.macAddress.isNotBlank() }
            if (wolTarget != null) {
                controller.sendWakeOnLan(wolTarget, TriggerSource.QUICK_TILE)
                qsTile?.apply {
                    state = Tile.STATE_ACTIVE
                    subtitle = "Sent to ${wolTarget.name.substringBefore('.')}"
                    updateTile()
                }
            } else {
                val intent = Intent(this@GlassrackQuickTileService, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivityAndCollapse(intent)
            }
        }
    }
}
