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
                Concept.of("Stream",
                    "A published service whose port is not a website — MQTT, a database — carried over "
                    + "TLS on the same HTTPS port and passed through byte for byte.",
                    "It cannot sit behind a login, so publish one only where the service's own "
                    + "password is good enough."),
                Concept.of("Connect address",
                    "Where a client dials a stream: its name and the HTTPS port, like mqtt.example.com:443.",
                    "A stream has no link to click, so this is the address you give the app."),
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
                Concept.of("Wildcard DNS",
                    "One *.yourdomain record, made once, that answers for every name under your domain.",
                    "Make it and DNS is done — Vaier never has to touch it again."),
                Concept.of("Wildcard DNS report",
                    "What Vaier found when it checked that wildcard record at startup.",
                    "It tells you in plain words whether DNS is right, and what to fix if it isn't."),
                Concept.of("Forward-auth",
                    "Routing requests through a login check before they reach a service.",
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

            new ConceptGroup("Credentials", List.of(
                Concept.of("Host credential",
                    "The one SSH login Vaier holds for a machine — a username with either a password or a private key.",
                    "It's how Vaier opens a shell, reads a disk or runs a backup there; without one, those controls don't appear at all."),
                Concept.of("Fleet credential",
                    "One secret that has to exist on every machine that runs a shell, kept in step for you.",
                    "It's the mirror of a host credential: that one is how Vaier reaches a machine, this one is something the machine itself needs."),
                Concept.of("Credential vault",
                    "Where Vaier seals every secret it holds, encrypted at rest.",
                    "Vaier only ever tells you whether a secret exists, never what it is — so you can't leak one by looking at it."),
                Concept.of("Distribute",
                    "Write a fleet credential out to every machine that can hold it.",
                    "Saving a credential reaches no machine on its own; this is the step that puts it where it's needed, and it reports where it landed."),
                Concept.of("Withdraw",
                    "Take a fleet credential back off the machines holding it.",
                    "This is how you revoke one. Deleting only makes Vaier forget its own copy, so withdraw first — otherwise the secret stays out on the fleet with nothing left to recall it."),
                Concept.of("Coverage strip",
                    "The row of cells beside a fleet credential — one per machine that can hold it, coloured by where it stands there.",
                    "It answers \"is my fleet in step?\" at a glance. Machines that can't run a shell are left out on purpose, so all-green stays reachable."),
                Concept.of("Claude sign-in",
                    "Signing the Claude CLI on one machine in to your own Anthropic account, without opening a terminal on that machine. It signs in the user Vaier acts as there, since the CLI keeps its sign-in in one user's home.",
                    "It's the one credential Vaier never holds: Anthropic mints it, the CLI on the machine keeps it, and Vaier only carries a link out to your browser and a code back. Signing out asks that CLI to let go of it — Vaier never deletes anything. And because a sign-in belongs to a user, a machine can be signed in as one login and signed out as another — the row says which one it is talking about."),
                Concept.of("Authorization URL",
                    "The link Claude prints when a sign-in starts, for you to open in your own browser.",
                    "Approving happens on Anthropic's own pages, never inside Vaier. Open it where you're already signed in to the right account — that's the account the machine ends up on."),
                Concept.of("Authorization code",
                    "What Anthropic shows you once you've approved, and what you paste back into Vaier.",
                    "Vaier hands it straight to the Claude CLI waiting on that machine and keeps no copy. Codes expire within a few minutes, so paste it while the sign-in is still open."),
                Concept.of("Claude account",
                    "Who a machine's Claude CLI is signed in as for the user Vaier acts as there — the email, organisation and plan it reports.",
                    "Half a fleet signed in to the wrong account looks perfectly healthy until something fails oddly, so Vaier says once, quietly, when your machines aren't all on the same one."))),

            new ConceptGroup("Backups", List.of(
                Concept.of("Backup job",
                    "Vaier's standing instruction to back up one machine's chosen folders every night.",
                    "It's what actually protects a machine — no job, no copies of anything."),
                Concept.of("Backup run",
                    "One execution of a backup job, with its outcome recorded.",
                    "It's the record that tells you whether last night's copy really happened."),
                Concept.of("Incomplete backup",
                    "A run that finished and wrote an archive, but could not read some of the files it was "
                        + "meant to copy — so those files are not in it.",
                    "It's the failure that looks like success: green on screen, holes in the data, and you "
                        + "only find out on the day you need the file."),
                Concept.of("Back up as root",
                    "Reading every file on a machine when Vaier copies it, including files that belong to "
                        + "other users — container volumes, databases, other people's home folders. Vaier "
                        + "normally reads as its own login there, which silently skips whatever that login "
                        + "may not open.",
                    "Turn it on when a backup came back incomplete: it's the one setting that decides "
                        + "whether the archive holds all of your data or most of it. The price is real and "
                        + "worth knowing — the machine has to let Vaier's login run the backup program as "
                        + "root without a password, which makes that login as powerful as root there. Vaier "
                        + "grants it for the backup program alone, and only when you say yes."),
                Concept.of("Backup repository",
                    "The store on the backup server that holds one machine's archives. Vaier makes exactly "
                        + "one per machine, names it after the machine and generates the passphrase that "
                        + "unlocks it — you never create or name one.",
                    "You won't meet this word anywhere else in Vaier: every screen says whose backups a "
                        + "store holds instead. It is here for the day you read it in a borg command or a "
                        + "passphrase prompt and want to know what it is."),
                Concept.of("Archive",
                    "One dated snapshot of a machine's protected folders inside its backup store.",
                    "It's what you actually restore from, and there's one per successful run."))),

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
                    "Set it so tiles read clearly instead of showing raw container names."))),

            new ConceptGroup("Chat", List.of(
                Concept.of("Chat",
                    "The Explorer pane where you talk to Marvin in sentences and are answered from the "
                        + "fleet's own facts. It can look, and it can propose; it changes nothing until "
                        + "you click a confirmation.",
                    "It is the quickest way to a question that would otherwise mean opening three panes — "
                        + "which machine is red and why, who is waiting to join, how last night's backups "
                        + "went. It appears in the menu only once an Anthropic API key is stored."),
                Concept.of("Marvin",
                    "Who answers in Chat: the Paranoid Android from The Hitchhiker's Guide to the Galaxy, "
                        + "kept by Vaier to answer questions about a fleet of small computers with a brain "
                        + "the size of a planet.",
                    "Gloomy, weary, dryly sardonic, and never wrong. The mood is a garnish on an accurate "
                        + "answer, never a reason to withhold one, and never aimed at you; and he is never in "
                        + "charge of anything, since every change still waits for your click."),
                Concept.of("Anthropic API key",
                    "Your own key for the Claude API, stored encrypted in Settings like the SMTP password "
                        + "and never shown again.",
                    "It is what makes Chat available, and it is billed to you. It never leaves this server "
                        + "except to the Claude API, and clearing it removes Chat from the menu."),
                Concept.of("Chat tool",
                    "One read of the fleet Vaier may make while answering you — the machines, who is "
                        + "waiting to join, the published services, backups, disks, container updates, "
                        + "security decisions, or one read-only command on a machine.",
                    "Everything Chat says comes from one of these reads and nothing else; when none of them "
                        + "has the answer, Chat says it does not know rather than guess. No tool ever carries "
                        + "a key, a password or a credential."),
                Concept.of("Read-only command",
                    "A single command line Chat runs on a machine over SSH, as Vaier's login user there and "
                        + "without sudo, and reads back what it printed.",
                    "It is how Chat answers what Vaier does not already track — pending OS updates, uptime, "
                        + "a log, a process list. Only looking commands are allowed: anything that could "
                        + "change the machine, chain commands or read where secrets live is refused, which "
                        + "is what keeps \"Chat can look, never change\" true."),
                Concept.of("Chat action",
                    "One thing Vaier may propose while answering you: let a phone in or refuse it, back up "
                        + "a machine, update a container, lift a block, trust an address.",
                    "Each is a button the Explorer already has, so Chat can never do more than you could "
                        + "by hand. Proposing runs nothing; it puts a confirmation in front of you."),
                Concept.of("Confirmation",
                    "The card a proposed action becomes in the Chat pane: one sentence saying exactly what "
                        + "will happen, and a button that says the same.",
                    "Your click is the only thing that runs it. A card lives ten minutes and runs once, "
                        + "and \"Not now\" runs nothing — so nothing on this fleet ever changes on the "
                        + "model's say-so."),
                Concept.of("Bundle",
                    "Files on one machine that Chat offers you as a download card: one zip, named for what "
                        + "it holds, built while it downloads.",
                    "It is how Chat hands over the files it found — the pictures from last year today, say. "
                        + "Nothing is copied or written anywhere, and the card's link lives an hour."),
                Concept.of("Conversation",
                    "What you have asked in Chat and what Vaier answered, in order, kept by Vaier under "
                        + "your login the way everything else is kept: a file.",
                    "A follow-up like \"and Colina?\" works next week too, because the thread is still "
                        + "there. Once it is long, the older turns are shortened into a summary that stands "
                        + "in for them; Start over forgets the whole thread."),
                Concept.of("Memory",
                    "What Marvin remembers across conversations, kept by Vaier for the whole fleet: short facts such as where "
                        + "the photos live or which machine is the relay, whether you said them or Chat found "
                        + "them by looking.",
                    "It is why you only have to explain something once. Every memory is listed on the Chat "
                        + "pane, folded under \"What Vaier remembers\", and you can remove any of them "
                        + "there; nothing can be planted in it unseen."),
                Concept.of("Spend",
                    "What Chat has cost this month on your own Anthropic API key: the tokens every answer "
                        + "used, counted by Vaier and priced at Anthropic's list price.",
                    "It is the figure under the Chat pane's description, so a chatty afternoon never comes as "
                        + "a surprise on the bill. Vaier's own count, not Anthropic's invoice: the two "
                        + "should agree to the cent, and the invoice wins if they do not."))));
    }
}
