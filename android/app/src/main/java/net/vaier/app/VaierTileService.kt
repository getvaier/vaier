package net.vaier.app

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlin.concurrent.thread

/** The Vaier toggle in the pull-down shade. Reads the tunnel each time the shade opens; never polls. */
class VaierTileService : TileService() {

    private val app get() = application as VaierApplication

    override fun onStartListening() {
        super.onStartListening()
        show()
    }

    override fun onClick() {
        super.onClick()
        val up = isUp()
        val needsConsent = VpnService.prepare(this) != null
        when (QuickTile.onTap(app.store.membership != null, up, needsConsent)) {
            TileTap.OPEN_APP -> openApp()
            TileTap.CONNECT -> act { app.connection.connect() }
            TileTap.DISCONNECT -> act { app.connection.disconnect() }
        }
    }

    // The tunnel calls block, so they run off the main thread; a failure leaves the tile showing the truth.
    private fun act(block: () -> Unit) = thread {
        runCatching(block)
        show()
    }

    private fun isUp() = runCatching { app.tunnels.status().up }.getOrDefault(false)

    private fun show() {
        val tile = qsTile ?: return
        tile.state = if (isUp()) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
