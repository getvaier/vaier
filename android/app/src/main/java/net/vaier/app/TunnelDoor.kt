package net.vaier.app

/** Vaier's door inside the tunnel: Traefik's tunnel-only entrypoint, unreachable from the internet. */
object TunnelDoor {
    const val ADDRESS = "http://172.20.0.251:8090"
}
