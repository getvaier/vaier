package net.vaier.app

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService

/**
 * Connecting and disconnecting, the same from the app and from the Quick Settings tile. Blocking:
 * call it off the main thread. Either way the tile is told to look again.
 */
class Connection(
    private val context: Context,
    private val store: VaierStore,
    private val tunnels: TunnelController,
    private val watchdog: StandingWatchdog,
) {

    fun connect() {
        val membership = store.membership ?: return
        try {
            tunnels.setUp(tunnels.configOf(membership.configText))
            watchdog.watch()
        } finally {
            refreshTile()
        }
    }

    fun disconnect() {
        val membership = store.membership ?: return
        try {
            watchdog.rest()
            tunnels.setDown(tunnels.configOf(membership.configText))
        } finally {
            refreshTile()
        }
    }

    private fun refreshTile() {
        TileService.requestListeningState(context, ComponentName(context, VaierTileService::class.java))
    }
}
