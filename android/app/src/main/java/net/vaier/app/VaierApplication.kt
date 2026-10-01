package net.vaier.app

import android.app.Application

/**
 * The few things that outlive every screen. The tunnel runs in this process and keeps it alive with
 * no activity on show, so what watches the tunnel has to be held here rather than by an activity —
 * and there is exactly one WireGuard backend for the same reason.
 */
class VaierApplication : Application() {

    val store: VaierStore by lazy { VaierStore(this) }
    val tunnels: TunnelController by lazy { TunnelController(this) }
    val vaier = VaierClient()
    val watchdog: StandingWatchdog by lazy {
        StandingWatchdog(store, tunnels, vaier, RemovalNotification(this))
    }
    val connection: Connection by lazy { Connection(this, store, tunnels, watchdog) }
}
