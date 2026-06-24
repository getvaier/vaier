package net.vaier.domain;

import java.util.List;

/**
 * The single source of truth for the operator-facing <b>Concepts page</b>: a trimmed, plain-language
 * glossary of the terms an operator meets in the Vaier UI, grouped and ordered the way the page
 * shows them.
 *
 * <p>This is deliberately a pure domain class with no Spring dependencies — the copy here is part of
 * the product's ubiquitous language, not an infrastructure detail. Every term named here must also
 * exist verbatim as a bold entry in {@code UBIQUITOUS_LANGUAGE.md}; a drift test enforces that, so
 * the in-app glossary can never name a term the canonical document doesn't define.
 */
public final class OperatorGlossary {

    private OperatorGlossary() {
    }

    public static List<ConceptGroup> groups() {
        return List.of(
            new ConceptGroup("Machines", List.of(
                Concept.of("Machine",
                    "Any computer Vaier knows about — a VPN peer or a LAN server.",
                    "It's the thing you publish services from and watch the status of."),
                Concept.of("VPN peer",
                    "A machine that joins your network over the WireGuard VPN tunnel.",
                    "Use it when the machine isn't on your local network and needs to reach in securely."),
                Concept.of("Client peer",
                    "A VPN peer that connects out to the server but doesn't route traffic for others.",
                    "This is the normal kind of peer — a laptop or host you just want on the VPN."),
                Concept.of("Server peer",
                    "A VPN peer that runs as an always-on server (Linux or Windows) and can host Docker services reachable over the tunnel.",
                    "Pick this kind for a host you'll publish services from or turn into a relay; it stays on the VPN subnet (split tunnel) rather than routing all its traffic through Vaier."),
                Concept.of("LAN server",
                    "A machine on your local network that Vaier reaches directly, without the VPN.",
                    "Register it when the host already shares your LAN and a tunnel would be overkill."),
                Concept.of("Relay peer",
                    "A server peer with a LAN CIDR set, so Vaier routes that whole local network through its tunnel to reach the machines behind it.",
                    "Set one up to reach LAN servers — a NAS, printer or IPMI card — that sit on the relay's network but aren't on the VPN themselves."),
                Concept.of("Gateway peer",
                    "A peer that exposes a whole subnet behind it to the rest of the VPN.",
                    "Use it to reach a site's internal network through a single VPN entry point."),
                Concept.of("LAN address",
                    "The local IP address Vaier uses to reach a LAN server.",
                    "If it's wrong, Vaier can't reach the machine or its services."),
                Concept.of("LAN CIDR",
                    "The address range of your local network, written as an IP/prefix (e.g. 192.168.1.0/24).",
                    "Vaier uses it to tell which discovered machines are genuinely on your LAN."),
                Concept.of("Reissue",
                    "Generate a fresh VPN config for a peer, keeping its identity.",
                    "Do this when a peer lost its config file and you need to hand it a new one."),
                Concept.of("Regenerate",
                    "Replace a peer's keys with brand-new ones, invalidating the old config.",
                    "Use it if a peer's keys may be compromised — the old config stops working."),
                Concept.of("Out-of-date config",
                    "A peer whose downloaded config no longer matches what the server expects.",
                    "It flags a peer that will fail to connect until you reissue its config."))),

            new ConceptGroup("Services", List.of(
                Concept.of("Service",
                    "Something running on a machine that Vaier can route traffic to.",
                    "It's the unit you publish and that ends up as a tile on the launchpad."),
                Concept.of("Publishable service",
                    "A discovered service that Vaier can publish but hasn't yet.",
                    "It's the shortlist you pick from when exposing something new."),
                Concept.of("Published service",
                    "A service Vaier is actively routing to through the reverse proxy.",
                    "These are the ones reachable by their public address right now."),
                Concept.of("LAN service",
                    "A published service that runs on a LAN server rather than a VPN peer.",
                    "Same idea as any published service, just hosted on your local network."),
                Concept.of("Subdomain",
                    "The host label a service answers on (e.g. the 'git' in git.example.com).",
                    "It's the address visitors type to reach a published service."),
                Concept.of("Path prefix",
                    "A URL path under which a service is served (e.g. /grafana).",
                    "Use it to put several services under one subdomain instead of one each."),
                Concept.of("Root redirect path",
                    "Where the bare domain root sends visitors who don't ask for a specific path.",
                    "Set it so the front page lands somewhere useful instead of a blank 404."),
                Concept.of("Direct URL",
                    "The full address you'd type to reach a service yourself.",
                    "Handy for copy-pasting or sharing a working link to a service."),
                Concept.of("Hidden from launchpad",
                    "A published service that's reachable but not shown as a tile.",
                    "Use it for internal endpoints you want routed but not advertised."),
                Concept.of("Ignored service",
                    "A discovered service you've told Vaier to stop offering for publishing.",
                    "Ignore the noise — system containers you'll never expose stay out of your way."))),

            new ConceptGroup("DNS & access", List.of(
                Concept.of("DNS provider",
                    "The service that hosts your domain's DNS records (e.g. AWS Route53).",
                    "It's where Vaier creates the records that point names at your server."),
                Concept.of("Manual DNS mode",
                    "A mode where Vaier doesn't touch DNS and you manage records yourself.",
                    "Pick it when your DNS lives somewhere Vaier can't automate."),
                Concept.of("Forward-auth",
                    "Routing requests through Authelia for a login check before they reach a service.",
                    "It's how a service gets put behind Vaier's single sign-on."),
                Concept.of("Auth toggle",
                    "The switch that turns forward-auth on or off for a published service.",
                    "Flip it to decide whether a service requires a login or is open."),
                Concept.of("Group",
                    "A named set of users used to control who may reach protected services.",
                    "Assign groups to gate access to sensitive services by audience."),
                Concept.of("ACME",
                    "The protocol Traefik uses to get and renew Let's Encrypt TLS certificates.",
                    "It's what gives your published services valid HTTPS automatically."),
                Concept.of("Public host",
                    "The internet-facing hostname that resolves to your Vaier server.",
                    "It's the anchor every published subdomain hangs off of."))),

            new ConceptGroup("Status & discovery", List.of(
                Concept.of("Connected",
                    "A VPN peer with a live, recent tunnel to the server.",
                    "It tells you at a glance the peer is reachable right now."),
                Concept.of("Latest handshake",
                    "The time of the most recent successful WireGuard key exchange with a peer.",
                    "A recent handshake means the tunnel is alive; a stale one means it dropped."),
                Concept.of("Last seen",
                    "The most recent time Vaier had any contact with a machine.",
                    "Use it to spot machines that have gone quiet."),
                Concept.of("Four-state machine-icon colour",
                    "The colour of a machine's icon, showing its connection state at a glance.",
                    "It's the quickest read on whether a machine is healthy, idle, or down."),
                Concept.of("Reachability check",
                    "A probe Vaier runs to see whether a machine or service actually responds.",
                    "It backs the status you see, so a tile reflects reality rather than hope."),
                Concept.of("LAN scanner",
                    "A feature that sweeps your local network to find machines automatically.",
                    "Saves you typing addresses by hand when onboarding LAN servers."),
                Concept.of("Discovered LAN machine",
                    "A host the LAN scanner found that you haven't registered yet.",
                    "It's a candidate you can promote into a LAN server with one step."))),

            new ConceptGroup("Launchpad", List.of(
                Concept.of("Launchpad",
                    "The dashboard page listing all your services as clickable tiles.",
                    "It's the everyday landing page for getting to everything you host."),
                Concept.of("Tile",
                    "A single clickable card on the launchpad representing one service.",
                    "It's what you click to open a service, with its live status shown."),
                Concept.of("Version endpoint",
                    "A service URL Vaier polls to read the version a service reports.",
                    "Configure it to see at a glance which version each service is running."),
                Concept.of("Launchpad display name",
                    "The friendly label shown on a service's launchpad tile.",
                    "Set it so tiles read clearly instead of showing raw container names."))));
    }
}
