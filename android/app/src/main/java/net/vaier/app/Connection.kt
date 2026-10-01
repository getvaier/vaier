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
    private val vaier: VaierClient,
) {

    fun connect() {
        val membership = store.membership ?: return
        try {
            tunnels.setUp(tunnels.configOf(membership.configText))
            watchdog.watch()
        } finally {
            refreshTile()
        }
        // Only a tunnel that has handshaken can carry the hello.
        val deadline = System.currentTimeMillis() + HANDSHAKE_WAIT_MILLIS
        while (tunnels.status().latestHandshakeEpochMillis == 0L && System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_MILLIS)
        }
        vaier.hello(store.publicKey.orEmpty(), EnrolmentPayload.presharedKeyIn(membership.configText))
    }

    fun disconnect() {
        val membership = store.membership ?: return
        try {
            watchdog.rest()
            // Through the tunnel, so it has to go before the tunnel does.
            if (tunnels.status().up) {
                vaier.goodbye(store.publicKey.orEmpty(), EnrolmentPayload.presharedKeyIn(membership.configText))
            }
            tunnels.setDown(tunnels.configOf(membership.configText))
        } finally {
            refreshTile()
        }
    }

    private companion object {
        const val HANDSHAKE_WAIT_MILLIS = 5_000L
        const val POLL_MILLIS = 250L
    }

    private fun refreshTile() {
        TileService.requestListeningState(context, ComponentName(context, VaierTileService::class.java))
    }
}
