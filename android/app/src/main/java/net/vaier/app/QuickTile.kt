package net.vaier.app

/** What a tap on the Quick Settings tile does. */
enum class TileTap { CONNECT, DISCONNECT, OPEN_APP }

/** The tile in the pull-down shade: connect and disconnect without opening the app. */
object QuickTile {

    /**
     * Opens the app only for what the shade cannot do: joining a fleet, and Android's VPN consent,
     * which only an activity can ask for.
     */
    fun onTap(member: Boolean, up: Boolean, needsConsent: Boolean): TileTap = when {
        up -> TileTap.DISCONNECT
        !member || needsConsent -> TileTap.OPEN_APP
        else -> TileTap.CONNECT
    }
}
