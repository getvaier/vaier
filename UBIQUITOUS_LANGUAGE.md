# Ubiquitous Language

This is the shared vocabulary for Vaier. Use these terms exactly — in code, commit messages, issues, PRD entries, UI copy, and conversation. When two words could mean the same thing, only the term listed here is correct.

Terms are drawn from the domain model (`src/main/java/net/vaier/domain/`), the use-case ports, REST APIs, the PRD, the README, and active GitHub issues. When the codebase and the docs disagree, the codebase wins and this document gets updated.

---

## 1. Product

| Term | Definition |
|------|------------|
| **Vaier** | The product. Also the name of the Spring Boot application container in the Docker Compose stack. |
| **Vaier server** | The single Linux host that runs the full Compose stack (WireGuard, Traefik, oauth2-proxy, Vaier). Has one public IP and is the WireGuard gateway. There is exactly one. Also appears in Infrastructure as a singleton machine under its reserved name `"Vaier server"` (`LanAnchor.VAIER_SERVER_NAME`) — no operator peer or LAN server may reuse it — carrying the same **SSH access** toggle and **host credential** control as any other machine. It is infrastructure, so its card offers no delete/regenerate/publish. Its SSH-access override is stored in the **Vaier config** (it is neither a peer nor a LAN server). |
| **Operator** | The human running Vaier — typically a homelab developer. Not "user" (which means an access entry). |
| **Stack** | The long-running containers in `docker-compose.yml`: `wireguard`, `traefik`, `docker-proxy`, `oauth2-proxy`, `dex`, `vaier`, and `vaier-offline`, alongside sidecar/init helpers. `authelia` and `redis` were removed when social login replaced Authelia as the running auth gateway. Stack components are upstream and pinned by tag — never floating `:latest`. |
| **Configured administrator** | The email (`VAIER_ADMIN_EMAIL`) Vaier seeds as the first **admin** **access entry** when the access store is empty, and restores to admin on startup whenever no admin remains. The way the operator first reaches the admin-only console under social login. |
| **Concepts page** | The in-app, operator-facing glossary shown inside the admin shell: a trimmed, plain-language subset of this document's terms, grouped, each with a short definition and a one-line "why it matters". Its terms are a curated subset of the ones defined here. |
| **Infrastructure** | The single admin-shell section that manages machines and the services published on them. It carries two inner views — **List** and **Map** tabs — and is where peers and LAN servers are added, monitored, and have their published services managed inline on each machine card. Replaces the former separate **Machines** and **Services** pages, which no longer exist as distinct sections. |

---

## 2. Machines and peers

The unified term is **machine**. A machine is anything Vaier knows about that can host services or connect to the VPN.

| Term | Definition |
|------|------------|
| **Machine** | Read projection (`domain.Machine`) covering every host Vaier manages. Has a `MachineType` and a **name** that is unique across all of Vaier (case-insensitive). Returned by `GET /machines`. |
| **MachineType** | Enum with five values: `MOBILE_CLIENT`, `WINDOWS_CLIENT`, `UBUNTU_SERVER`, `WINDOWS_SERVER`, `LAN_SERVER`. The first four are **VPN peers**; the fifth is not. The **routing** concept — it drives WireGuard client/server config. Distinct from **device category**, which is icon-only. |
| **Device category** | The kind of device a machine is (`domain.DeviceCategory`: `PHONE`, `LAPTOP`, `DESKTOP`, `SERVER`, `NAS`, `PRINTER`, `ROUTER`, `GATEWAY`, `IOT`, `CAMERA`, `MEDIA`, `GENERIC`). A purely presentational, orthogonal attribute: it picks the icon shown for a machine and never affects routing, keys, or how a service is exposed. It also *seeds* a machine's **SSH access** default (a printer/phone starts off, a server/NAS starts on) — a presentational convenience only; the SSH-access flag is authoritative and, once set, the category never affects it. `GENERIC` is the fallback when nothing else signals. Distinct from **machine type**, which is the routing concept — a NAS and a desktop may both be `UBUNTU_SERVER` peers yet carry different device categories. Within the enum, `ROUTER` (a LAN router or Wi-Fi access point) and `GATEWAY` (an internet-edge or IoT-hub bridge, e.g. a Zigbee/Z-Wave or Home Assistant hub) are separate categories, not synonyms; the device-category `GATEWAY` is also unrelated to the routing-level **gateway peer**. |
| **Effective device category** | The **device category** actually shown for a machine: its **device category override** when one is set, otherwise the auto-detected category. Never blank — falls back to `GENERIC`. |
| **Device category override** | An explicit **device category** an operator pins on a machine, taking precedence over auto-detection. Optional; when absent the machine uses its detected category, and clearing it reverts to auto-detection. |
| **SSH access** | A per-machine flag for whether Vaier offers SSH — the host-credential control now, the web terminal later — for that machine. Authoritative and independent of routing. The persisted value is an explicit operator override; when unset, the **effective** value falls back to a smart default seeded from the machine's device type (a server/NAS/desktop/laptop starts on; an appliance such as a printer, phone, router, or camera starts off). Stored per machine where that machine lives — peer `# VAIER:` metadata, `lan-servers.yml`, or the **Vaier config** for the Vaier server itself. |
| **VPN peer** / **peer** | A machine connected to Vaier over WireGuard. Equivalent to "MachineType where `isVpnPeer()` is true". Use "peer" in UI and casual reference; "VPN peer" when disambiguating from a LAN server. |
| **Client peer** | Mobile or Windows client; default `AllowedIPs = 0.0.0.0/0` (full tunnel). Cannot host containers in the Vaier model. |
| **Server peer** | `UBUNTU_SERVER` or `WINDOWS_SERVER`; default `AllowedIPs` is the VPN subnet only (split tunnel). Can host Docker containers. `MachineType.isServerType()` also returns true for `LAN_SERVER`. |
| **LAN server** | A non-WireGuard machine that Vaier knows about — NAS, IPMI host, printer, extra Docker host — sitting either on a **relay peer's** LAN or in the **Vaier server's own LAN** (server LAN CIDR). Persisted in `lan-servers.yml`. Docker is optional; when on, Vaier scrapes its remote socket *through the relay*, or — for a server-anchored LAN server — directly from the Vaier-side containers. Registration requires the `lanAddress` to fall inside some relay peer's `lanCidr` or the server LAN CIDR; the UI asks only for the address. |
| **Relay peer** | A server peer with a `lanCidr` set. Vaier routes traffic for that CIDR into the relay's tunnel, and the relay's install script enables `ip_forward` and idempotent iptables NAT/FORWARD rules so packets reach LAN hosts behind it. The rules are re-applied on every boot by a generated `vaier-wg-relay-iptables.service` systemd oneshot. A machine in the Vaier server's *own* subnet does not need a relay peer — see **server LAN CIDR**. |
| **LAN anchor** | What routes packets to a LAN address: a relay peer whose `lanCidr` contains it, or — failing that — the Vaier server itself, when the address is inside the **server LAN CIDR**. When both match, the relay peer wins. Modelled by `domain.LanAnchor`; the Vaier-server anchor surfaces under the canonical name `"Vaier server"` (`LanAnchor.VAIER_SERVER_NAME`) wherever a relay peer name would otherwise appear. Resolved via `ResolveLanAnchorUseCase` and exposed at `GET /lan-servers/lan-anchor?address=…` (`{routable, routedVia, cidr}`), so the Add Machine modal asks the domain whether a typed address is routable instead of doing CIDR math in the browser. |
| **LAN setup script** | The single shell script an operator runs on a registered **LAN server** to prepare it for Vaier. It adapts to the host: it exposes the host's Docker engine API when the LAN server runs Docker, and installs persistent routes into the Vaier network (the **server LAN CIDR**, the **VPN subnet**, and other **relay peers'** LANs) via the host's relay peer when the host is relay-anchored. Idempotent. Supersedes the earlier Docker-only setup script. |
| **Gateway peer** | (Planned, #174) A single peer designated to carry all internet traffic for opted-in clients. Distinct from a relay peer. |
| **LAN address** | The reachable IP (or hostname) of a server peer or LAN server on its local LAN — e.g. `192.168.1.50`. Used by the launchpad to hand out direct URLs. Editable inline via `PATCH /vpn/peers/{id}/lan-address`. Not the WireGuard tunnel IP. |
| **Peer id** | A peer's immutable identifier — a slug (letters, digits, `_`, `-`) derived from the name the operator typed when creating the peer, deduplicated with a numeric suffix (`-2`, `-3`, …) when it would collide with an existing id. It is the WireGuard config directory name and the path segment of every `/vpn/peers/{id}` REST call, and never changes once assigned — not even on rename. Modelled by `domain.PeerId`. |
| **Name** | A machine's human-readable display label — what the UI shows. Unique across all of Vaier: no two machines (peer or LAN server) may share a name, compared case-insensitively and ignoring surrounding whitespace ("nas", "NAS" and " nas " are the same name). For a peer it is operator-edited text; editing it changes only this label, never the **peer id**. A peer with no stored name falls back to its **peer id** rendered with dashes as spaces, and that effective label still participates in uniqueness; clearing a peer's name reverts it to that fallback label, which must itself be free across machines. Distinct from the **peer id** (a peer's identity) and the **description** (informational). |
| **Description** | Optional free-text label an operator attaches to a machine — a VPN peer or a LAN server — to record what it is, e.g. "Home media server (NUC, Ubuntu 22.04)". Purely informational, and distinct from the machine **name** (its display label). |
| **Rename** | Changing a machine's **name**. For a peer this edits the display **name** only — the **peer id**, its configuration, and its published services are untouched. The machine keeps its identity; only the label changes. Because Vaier keys a machine's **host credential** and **host-key pin** by its **name**, both are carried over to the new name as part of the rename, so a renamed machine keeps its terminal access rather than orphaning them. Distinct from deleting and recreating. |
| **Peer config retrieval** | The act of downloading a peer's secret-bearing artefact (`.conf`, QR PNG, docker-compose, setup script). One-shot: the create response is the canonical delivery, and the five GET endpoints (`/config`, `/config-file`, `/qr-code`, `/docker-compose`, `/setup-script`) burn a shared budget — the first GET on any of them returns 200, every subsequent GET returns `410 Gone`. Tracked via a filesystem marker (`<peerDir>/<name>.conf.viewed`) and the `ForTrackingPeerConfigRetrieval` port. |
| **Regenerate** | Operator action on a machine card that deletes the peer and recreates it with the same **name**/**peer type**/**LAN CIDR**/**LAN address**/**description**. Rotates the WireGuard keypair as a side effect of the recreate, which simultaneously clears the **peer config retrieval** marker and re-enables a one-shot delivery via the create-success modal. Used to replace a compromised config — the old config stops working immediately. Distinct from **Reissue**, which recovers a config without rotating the keypair (the non-destructive way to get a lost config back). |
| **Reissue** | Operator action that re-renders a peer's installable config from the *current* generation logic while **preserving its keypair and preshared key** — so the live tunnel and the server-side `[Peer]` entry are untouched — then persists the new config and re-opens the one-shot **peer config retrieval** budget so it can be delivered once more via the create-success modal. Used when generation logic has changed since the peer was created (e.g. the **server LAN CIDR** is now appended to server peers' client-side `AllowedIPs`) and an existing peer's config has fallen **out of date**. Unlike **Regenerate**, the operator must reinstall the reissued config on the peer machine for it to take effect. |
| **Rendered config** | The config the current generation logic would produce for a peer, with its keypair, preshared key, tunnel IP, and identity preserved — i.e. the output of a **Reissue**. Compared against the on-disk config to decide whether a peer is **out of date**. |
| **Out-of-date config** | A peer whose on-disk config differs from its **rendered config**. Surfaced as a badge in the peer list; cleared by a **Reissue**. |
| **LAN CIDR** | The IPv4 CIDR of the LAN sitting behind a relay peer — e.g. `192.168.1.0/24`. Recorded in the `# VAIER:` JSON header of the peer's client config and appended to the **server-side** `[Peer] AllowedIPs` only. Never appended to the client-side `AllowedIPs` (would hijack the relay's own LAN). |
| **Server LAN CIDR** | The IPv4 CIDR Vaier treats as "the network the **Vaier server itself** sits on". By default it's the server's own **subnet** CIDR, auto-**discovered** via EC2 IMDSv2 (`network/interfaces/macs/<mac>/subnet-ipv4-cidr-block` — a default-VPC subnet is a `/20`, one per AZ). `VAIER_SERVER_LAN_CIDR` is a general override (short-circuits IMDS, on EC2 too) — set it to widen the range, typically to the whole VPC CIDR (e.g. `172.31.0.0/16`) so machines in any AZ qualify, or to supply the value off EC2. Resolved by `ForResolvingServerLanCidr`; non-CIDR values are ignored; the result is memoized. A LAN server or LAN service whose address falls inside it is registered, scraped, and published directly from the Vaier-side containers (vaier / traefik → docker bridge → host → the host's LAN/VPC NIC) — no relay peer needed. Also appended to split-tunnel server peers' client-side `AllowedIPs` so they can *initiate* connections back into the server's subnet through the tunnel. Empty when undeterminable (the feature is then inert). |
| **VPN subnet** | The WireGuard /24 used for tunnel addresses (default `10.13.13.0/24`). |
| **Tunnel IP** / **WG address** | A peer's address inside the VPN subnet (e.g. `10.13.13.7`). Distinct from LAN address and endpoint IP. |
| **Endpoint IP** | The peer's public IP as seen by the WireGuard server (`wg show dump`). The carrier IP for mobile/client peers. Used for geolocation. |
| **Latest handshake** | Unix epoch seconds of the most recent successful WireGuard handshake. Source of truth for connection state. |
| **Connected** | A peer is connected when `latestHandshake > 0` and `(now - latestHandshake) < 180s`. See `VpnClient.isConnected()`. Anything older is **disconnected** (which on the UI may render as "stale"). |
| **AllowedIPs** | WireGuard config field. Has both a **server-side** form (in `wg0.conf`'s `[Peer]` block on the Vaier server) and a **client-side** form (in the peer's own config). They are not interchangeable — see the LAN CIDR rules above. |
| **VAIER metadata** | The `# VAIER: {...}` JSON comment at the top of every client WireGuard config. Carries `peerType`, `lanCidr`, `lanAddress`, `description`, `name`. Legacy peers without it default to `UBUNTU_SERVER`; a peer with no `name` falls back to its **peer id** shown with dashes as spaces. |

Avoid: "node", "host" (ambiguous), "device", "client" by itself, "VPN node".

---

## 3. Service publishing

The primary workflow. Always use these terms — the UI is built around them.

| Term | Definition |
|------|------------|
| **Service** | (Without qualifier) a Docker container with at least one exposed port, running on the Vaier server or a server peer or a Docker-enabled LAN server. Discovered automatically. Backed by `domain.DockerService`. |
| **Publishable service** | A discovered service eligible for publishing. Has a `source` indicating where it was discovered (`VAIER_SERVER`, `PEER`, `LAN_SERVER`). Surfaced as a **+ Publish** candidate row in its host machine card's Services section on the **Infrastructure** page. |
| **Published service** | A service that has been wired through the publish flow: a DNS CNAME (or A) plus a Traefik route plus the middleware chain for its **auth mode** (public or social login). Backed by a `ReverseProxyRoute`. Shown as a managed route in its host machine card's Services section on the **Infrastructure** page. |
| **LAN service** | A published service whose backend is a host:port on a relay peer's LAN or in the Vaier server's own subnet (server LAN CIDR), not a container. `ReverseProxyRoute.isLanService == true`. Routed via the relay peer, or — when anchored at the Vaier server — straight from the Traefik container out the host's LAN/VPC NIC. Surfaces with a "LAN" badge; its host state follows the relay peer's tunnel, or is always OK for a server-anchored route. |
| **Publish flow** | The end-to-end pipeline: validate → write CNAME → wait for DNS propagation → write Traefik route → confirm Traefik picked it up. UI shows it as a **Processing** card between Discovered and Active. Survives page refresh because state lives server-side. |
| **Pending publication** | A publish in flight, tracked in-memory by `PendingPublicationsService`. Used to hide the source service from the discovered list and to reject duplicate submits. |
| **Publish rollback** | If any step fails (DNS timeout, Traefik error, Traefik never picks up the route), Vaier removes the CNAME (and the route where applicable) and emits a `publish-rolled-back` SSE event on the `published-services` topic. |
| **Cleanup on machine deletion** | When a machine is deleted — a VPN peer or a LAN server — every published service whose backend address routes to that machine is also removed (DNS + Traefik). |
| **Subdomain** | The label the operator picks at publish time. Combined with `VAIER_DOMAIN` to form the published service's DNS name. |
| **Mandatory subdomains** | `vaier` — the only subdomain reserved as Vaier infrastructure and barred from reuse as a published-service subdomain. Defined in `PublishingConstants.MANDATORY_SUBDOMAINS`. (`login`, the former Authelia portal host, is no longer mandatory.) |
| **Path prefix** | Optional `pathPrefix` on a published service (e.g. `/auth`). When set, the Traefik rule becomes `Host(...) && PathPrefix(...)` so multiple services can share one subdomain, each routed by path. Normalised by `ReverseProxyRoute.normalisePathPrefix` (blank/"`/`" → null; an operator-typed trailing slash is preserved verbatim — type `/auth` to match both `/auth` and `/auth/`, type `/auth/` to match only the slash variant). Route uniqueness is the `(fqdn, pathPrefix)` pair. |
| **Landing path** | The path segment a launchpad-emitted URL lands on. Decided by `ReverseProxyRoute` (`landingPath`, used by both `launchpadUrl` and `directUrl`): a non-blank `rootRedirectPath` wins; otherwise the `pathPrefix` is used verbatim; otherwise empty. So the operator's intended landing path is encoded either in `rootRedirectPath` (overrides everything) or in the slash they typed on the `pathPrefix`. |
| **Sibling routes** | Two or more `ReverseProxyRoute`s with the same FQDN but different path prefixes. They share one DNS CNAME — the first publish on a host creates it, subsequent siblings reuse it, and DNS is only deleted when the last sibling is removed. Decided by `ReverseProxyRoute.hasSiblingOnHost`. |
| **Root redirect path** | Optional `rootRedirectPath` on a published service — appended to the direct URL and (where applicable) used for a Traefik redirect at root. Distinct from path prefix: path prefix decides *what requests reach this route*; root redirect decides *where bare `/` lands after routing*. |
| **Direct URL** | The `http://lanAddress:port` URL the launchpad hands out *to callers on the same LAN* as the hosting peer, bypassing Traefik and its auth. |
| **Direct URL disabled** | Per-route opt-out (`directUrlDisabled`, persisted as `x-vaier-direct-url-disabled`). For services whose public origin differs from `http://lan:port` — Vaultwarden is the canonical case. |
| **Auth-mediated tile URL** | The URL the launchpad hands out for auth-protected services when no direct-LAN bypass applies, routing the browser through the auth gateway first so a PWA's service worker on the service origin can't serve a cached SPA that bypasses sign-in. |
| **Auth toggle** | Retired. The per-service on/off auth switch, superseded by **auth mode** (public or social login) — see §13. |
| **Hidden from launchpad** | Per-route operator switch (`hiddenFromLaunchpad`, persisted as `x-vaier-hidden-from-launchpad`). When on, the route is kept reachable but the launchpad never shows a tile for it. Use for internal APIs that back another service and don't need their own tile. Settable via `PATCH /published-services/{dnsName}` with `{"hiddenFromLaunchpad": ...}`. |
| **Offline page** | The branded, self-contained page Vaier serves in place of Traefik's default gateway error when a published service's backend is unreachable (502/503/504). Names the unavailable service, shows a friendly message, and offers a retry and a back-to-launchpad link. Backed by the domain `GatewayError` mapping (status → title + message). |
| **Offline fallback** | The always-up stand-in that shows a branded offline page for the Vaier host when the Vaier container *itself* is down — served by a separate tiny static server, not by Vaier, so the control panel degrades gracefully instead of showing "Bad gateway". |
| **Host-offline indicator** | Red dot on a launchpad tile (and red machine-type icon on a machine card) signalling that the machine hosting the service is confirmed unreachable. An *unknown* host (no probe data yet) gets a grey machine-card icon and a grey **pending** dot on its launchpad tile — distinct from "confirmed down". Fed by `ReverseProxyRoute.hostState(...)` → `Server.State.{OK,UNREACHABLE,UNKNOWN}`, which considers VPN handshake age (peer routes), relay-tunnel connectivity (LAN routes), and the LAN reachability probe (LAN routes). Never raised for DNS issues — DNS-not-propagated routes are filtered out entirely. |
| **Launchpad alias** | Optional operator-supplied tile label (`launchpadAlias`, persisted as `x-vaier-launchpad-alias` map). Overrides the default label when non-blank. Settable via `PATCH /published-services/{dnsName}` with `{"launchpadAlias": ...}` (empty string clears). |
| **Launchpad display name** | The label the launchpad tile shows. Computed by `ReverseProxyRoute.launchpadDisplayName(baseDomain)`: alias if set, else the final segment of `pathPrefix` for path-based routes (e.g. `/grafana` → `grafana`), else the first DNS label (e.g. `grafana.example.com` → `grafana`). The path prefix no longer leaks into the tile label as `subdomain/path` — the host appears on the tile's sub-line beside the peer when it differs from the display name. |
| **Icon lookup identity** | What the launchpad uses to ask `/icon` for an icon. Computed by `ReverseProxyRoute.launchpadIconQuery()`: host-only routes use just the FQDN; path-based routes carry `pathPrefix` too, so siblings under one host don't collide on the icon cache and the CDN-by-name fallback uses the path segment instead of the shared subdomain. The launchpad client renders `service.iconQuery` opaquely — it never decides what's in it. Doubles as the **icon cache key**, so the same identity addresses both the in-memory and on-disk caches. |
| **Icon** | A resolved service icon (`domain.Icon`): the image bytes plus the content-type the launchpad should report for them. |
| **Icon cache** | Where a resolved icon is remembered so it's fetched online at most once. Two tiers behind the same key: an in-memory map and a filesystem store (`ForStoringIcons`, default `/icons`) that survives restarts. Positives are persisted to disk; a "no icon found" result is remembered only in memory so a once-dead host can recover. |
| **Ignored service** | A publishable service the operator has chosen to hide from the discovered list. Persisted via `ForManagingIgnoredServices`. |

Avoid: "expose", "deploy", "route" (as a verb for the user-facing publish action), "site", "endpoint" (ambiguous with WireGuard's endpoint).

---

## 4. DNS, reverse proxy, auth

| Term | Definition |
|------|------------|
| **DNS provider** | `domain.DnsProvider` — `ROUTE53` or `MANUAL`. **Inferred** from configuration by `ConfigResolver.getDnsProvider()`: `ROUTE53` when both AWS key and secret are present, `MANUAL` otherwise. There is no `VAIER_DNS_PROVIDER` env var. Picks which `ForPersistingDnsRecords` adapter wires up via `DnsAdapterConfig`. |
| **Manual DNS mode** | The default when no AWS credentials are configured. Vaier owns no DNS — the operator maintains records by hand in whatever provider they like. Backed by `ManualDnsAdapter`, which no-ops every write and synthesizes the bootstrap records as already-present so `Lifecycle.initDns()` is silent. To switch into Route53 mode, save AWS credentials (env or Settings UI) and restart. |
| **DNS zone** | A DNS hosted zone (`domain.DnsZone`). In Route53 mode this is a real Route53 hosted zone; in manual mode it's the synthesized zone for `VAIER_DOMAIN`. Vaier owns at most one. |
| **DNS record** | `domain.DnsRecord` with a `DnsRecordType` (A, CNAME, MX, etc.). In Route53 mode written to Route53; in manual mode the operator maintains them externally and Vaier's writes are no-ops. |
| **DNS state** | `OK` (record exists matching expectation) or `NON_EXISTING` (`domain.DnsState`). Used in published-service status. |
| **Public host** | The Vaier server's public address as a `(value, type)` pair — either A (IPv4) or CNAME (hostname). Resolved by `ForResolvingPublicHost` in order: `VAIER_PUBLIC_HOST` → `VAIER_PUBLIC_IP` → EC2 IMDSv2 `public-hostname` → DNS lookup of `vaier.<domain>`. |
| **Reverse proxy route** | `domain.ReverseProxyRoute`. The Traefik-side half of a published service. Some routes are created by service publishing; others are added directly through the reverse-proxy CRUD endpoints (the escape hatch). |
| **Entry point** | Traefik concept: the named port a route binds to (`web`, `websecure`). |
| **TLS config** | Per-route TLS settings; `certResolver` names the ACME resolver in Traefik static config. |
| **Middleware** | Traefik chain element. A social-gated route carries the oauth2-proxy → Vaier authorization chain (`oauth2-signin`, `oauth2-authn`, `vaier-authz`); a public route carries none. |
| **Forward-auth** | The auth pattern where Traefik forwards each request to an external authenticator before passing it upstream. Under social login the chain is oauth2-proxy (Google sign-in) followed by Vaier's own `/authz/verify` authorization check. There is no in-process Spring Security. |
| **Group** | A named set of identities used to gate who may reach protected services. In Vaier this is realised as an **access group** on an **access entry** (see §13); **access group** is the canonical term for the concept. |
| **ACME** | Let's Encrypt protocol for issuing TLS certs. `ACME_EMAIL` env var feeds the Traefik resolver. |

Avoid: "vhost", "site", "auth provider".

---

## 5. Discovery, reachability, status

| Term | Definition |
|------|------------|
| **Discovery** | The process of listing Docker containers on a host. `DiscoverVaierServerContainersUseCase` (Vaier server), `DiscoverPeerContainersUseCase` (server peer via `tcp://<peer>:<port>`), `DiscoverLanServerContainersUseCase` (LAN server, scraped through the relay — or directly from the Vaier container when the LAN server is in the server LAN CIDR). |
| **Reachability check** | A TCP probe (`ForProbingTcp`) used for LAN servers. Every 30s, hits ports 80/443/22; *any* response (handshake or RST) means pingable. If every TCP probe times out, an ICMP echo (`ForPingingHost`, backed by `/bin/ping`) fires as a fallback so printers / IoT / IPMI cards that don't expose any of those ports aren't falsely marked down. Drives the **four-state machine-icon colour**. |
| **LAN server scrape** | The 30s scheduled Docker-socket scrape of every Docker-enabled LAN server, owned by `LanServerScrapeService`. Status (`OK` / `UNREACHABLE`) is debounced with the same 3-cycle dampening rule as reachability so the icon doesn't flicker green↔yellow on a transient socket blip. The cached result is what `GET /docker-services/lan-servers` returns; on a confirmed status change it republishes the existing `lan-servers-updated` SSE event. The live `DiscoverLanServerContainersUseCase` is still used by the publishable-services flow, which needs current state. |
| **LAN scanner** | (Enterprise, #246) An on-demand, **asynchronous** sweep of every **relay peer's** `lanCidr` and the **server LAN CIDR** that surfaces responsive hosts not yet registered as **LAN servers**. The probe is a narrow TCP-connect sweep over common service ports, run from the Vaier WireGuard container (which already routes to every relay LAN) via `ForScanningLan`; `LanScannerService` (`ScanLanUseCase` to start, `GetDiscoveredLanMachinesUseCase` to read) orchestrates it. `POST /lan-scan` starts a background scan (`202`); `GET /lan-scan` returns the snapshot (status + machines + last-completed); completion fires the `lan-scan-updated` SSE event. Gated `@RequiresEnterprise`. |
| **Discovered LAN machine** | One responsive host the **LAN scanner** found that is **not already registered** as any machine (LAN server or VPN peer): its address, an optional hostname, the open ports it answered on, and the **LAN anchor** it sits behind. Modelled by `domain.DiscoveredLanMachine`. Surfaced in the Add Machine modal's scan picker — shown only for the **LAN server** type — so the operator picks it to fill the LAN address. Not persisted — recomputed each scan. |
| **Guessed role** | An advisory classification of a **discovered LAN machine** from its open ports (`domain.LanMachineRole`: `DOCKER_HOST`, `WEB_UI`, `SSH_HOST`, `PRINTER`, `UNKNOWN`). Pre-fills the Add Machine hint only — never a hard fact. |
| **Probe result** | `CONNECTED` (open), `REFUSED` (host alive, port closed — still pingable), `UNREACHABLE` (timeout or low-level error). |
| **Four-state machine-icon colour** | The machine-type icon on each card carries the status colour: **grey** (not yet probed / unknown), **green** (host pingable; if Docker-enabled, scrape also OK), **yellow** (Docker host pingable but scrape failed), **red** (host not pingable). Replaces the older standalone status dot. |
| **Status tooltip** | The per-machine hover text on the machine-type icon stating the current state in plain language plus its evidence (e.g. "Green — connected, last handshake 12s ago"), alongside the machine type. |
| **Field help** | The small visible "?" affordance next to an advanced form field whose hover text gives a one-line plain-language explanation (and example) of what the field does. |
| **Last seen** | Absolute timestamp shown in the **expanded** card's detail row. Sourced from the latest WireGuard handshake for VPN peers; for LAN servers, the most recent successful TCP probe (CONNECTED or REFUSED). Preserved across later DOWN probes — once a host has been seen, the timestamp sticks until the LAN server is removed or the app restarts. |
| **Disk alert threshold** | The percentage of a machine's filesystem capacity in use above which Vaier raises **remote disk pressure** (default 85%). Configurable in Settings. |
| **Remote disk usage** | A point-in-time reading of a machine's root-filesystem fullness, gathered by running `df` over SSH — for any machine in the fleet, including the Vaier host itself, reached via SSH-to-self like any other machine. Carries the machine name and the used percentage. Modelled by `domain.RemoteDiskUsage`. |
| **Remote disk pressure** | The condition where a machine's **remote disk usage** is above the **disk alert threshold**. Applies uniformly across the fleet, including the Vaier host. |
| **Remote disk recovery** | The condition where a machine's **remote disk usage** has dropped back below the **disk alert threshold** after having been in **remote disk pressure**. |
| **Runway** | The projected time until a machine's monitored filesystem reaches 100%, extrapolated from the recent fill trend of its **remote disk usage** readings. A flat or draining disk has no finite runway. Distinct from **remote disk pressure**, which is about the disk already being too full rather than about when it will be. |
| **Disk-fill forecast** | A projection of a machine's disk-fill trend: its current fullness, its fill rate (percent of capacity per hour), and its **runway**. Trend-based ("will be full"), as opposed to the level-based **remote disk pressure** ("is full"). |
| **Forecast horizon** | The fixed 24-hour **runway** threshold below which a **disk-fill forecast** warrants an early warning. Unlike the **disk alert threshold**, it is a constant, not configurable. |
| **Capability strip** | Fixed-column row of capability icons (relay, docker) on the right side of each machine card header. Empty slots render as placeholders so the same capability lines up vertically across every card. |
| **Geolocation** | `GeoLocation(latitude, longitude, city, country)` resolved by `DbIpGeolocationAdapter` against a DB-IP City Lite MMDB downloaded by the `geoip-init` container. Used for the Map tab. |
| **Map tab** / **List tab** | The two views on the **Infrastructure** page. The List tab shows machine cards; the Map tab renders a self-hosted Leaflet/OpenStreetMap world map with markers and clustering. |
| **Server marker** | The single distinct marker for the Vaier server itself on the Map tab. |
| **Mobile/client dual marker** | Mobile/Windows-client peers plot twice: a dotted "approx. ISP" marker at the carrier IP plus a firm marker stacked at the Vaier server. Reflects `AllowedIPs = 0.0.0.0/0` full-tunnel routing. |

---

## 6. Launchpad

| Term | Definition |
|------|------------|
| **Launchpad** | The public, **viewer-adaptive** dashboard at `/launchpad.html` showing published services as a tile grid. Suitable as a browser home page. An anonymous visitor sees only public services; a signed-in **viewer** additionally sees the social services it may reach. Admin pages remain admin-only. Reads are pure — the launchpad never creates or mutates an access entry. |
| **Tile** | A single service card on the launchpad — service name, peer name, icon, link, and, on hover, the Docker image and running version of the service behind it. |
| **Backing container** | The Docker container that serves a published service. Its image and version are surfaced on the launchpad tile and on the machine card's service row (`Version`), resolved by `ReverseProxyRoute.backingContainer(...)` against discovered peer / Vaier-server / LAN-server containers. A service published as a bare LAN host:port has no backing container. |
| **Version endpoint** | An operator-configured URL and property name on a published service. Vaier reads the service's running version from that URL and shows it on the tile and on the machine card's service row — used for a service that has no backing container, such as one running natively on a LAN machine. |
| **Launchpad visibility** | Tri-state outcome the domain computes for each route (`domain.LaunchpadVisibility`): `NOT_VISIBLE` (operator hid it, or DNS not propagated, or a social-gated route the viewer may not reach — anonymous, pending, or a user outside the allowed groups — no tile rendered), `VISIBLE_INACTIVE` (tile shown but de-emphasised — backend currently unreachable), `VISIBLE_ACTIVE` (tile shown normally). Owned by `ReverseProxyRoute.launchpadVisibility(dnsState, hostState, AccessEntry viewer, ForResolvingServiceGroup serviceGroups)` (with `isVisibleToLaunchpadViewer(viewer, serviceGroups)` deciding whether a tile appears at all); new launchpad-visibility rules accrete there rather than in the application layer. |
| **Launchpad tile liveness** | Presentation tri-state (`domain.LaunchpadLiveness`) for a launchpad tile's status dot, reporting host reachability alone and separate from launchpad visibility (which governs the tile's link and dim): `LIVE` (green — host confirmed reachable), `PENDING` (grey — reachability not yet confirmed, e.g. at startup before the first probe), `OFFLINE` (red — host confirmed unreachable). A service counts as "online"/"reachable" only when `LIVE`. |
| **Caller IP** | The public IP of the launchpad visitor, used to decide whether to hand out the direct URL. Taken from `X-Forwarded-For` only when the immediate connection is from inside `launchpad.trusted-proxy-cidr` (default `172.20.0.0/16`); otherwise from `RemoteAddr`. |
| **Viewer** | The identity looking at the launchpad — either anonymous (`null`) or a resolved **access entry** — that the launchpad adapts its tiles to. An anonymous viewer sees only public services; a signed-in viewer additionally sees the social services it may reach. Resolved read-only from the signed-in email (`ResolveViewerUseCase`); a pending or unknown identity resolves to no extra access. |
| **Identity-optional router** | The middle launchpad routing tier that injects a signed-in identity's headers when a session exists but never forces sign-in and never gates on admin — so a signed-in non-admin can read its own identity and its own reachable tiles, while an anonymous request is refused and the page falls back to the public feed. Distinct from the public tier (no auth at all) and the admin catch-all (full sign-in + admin gate). |
| **Trusted proxy CIDR** | The CIDR Vaier trusts to set `X-Forwarded-For`. Default is the Docker bridge subnet. |

---

## 7. Lifecycle, setup, notifications

| Term | Definition |
|------|------------|
| **Lifecycle** | `domain.Lifecycle`. The boot sequence: ensure the `vaier.<domain>` DNS record exists. In manual DNS mode this step is a silent no-op since the operator owns that record. |
| **First-run** | The first `docker compose up -d` on a host. Triggers the `geoip-init`, `vaier-init`, `oauth2-proxy-init`, and `dex-init` one-shot containers, seeds the **configured administrator** into the access store, and (in Route53 mode) the auto-create of `vaier.<domain>`. |
| **Docker socket proxy** | The `tecnativa/docker-socket-proxy` sidecar (`docker-proxy` container) that holds the real `/var/run/docker.sock` and exposes a restricted HTTP API on `tcp://docker-proxy:2375` over `vaier-network`. Vaier and Traefik talk to it instead of mounting the host socket. Allowlist: `CONTAINERS, EVENTS, EXEC, IMAGES, PING, POST, ALLOW_RESTARTS`. Default-deny on `/containers/create`, `/containers/{id}/start`, image pulls, swarm/network/volume management. |
| **vaier-init** | One-shot busybox container that `chown`s the bind-mounted config dirs to UID 1000 on every start, so the non-root Vaier process can read and write its own state. |
| **SMTP notifier** | The Jakarta Mail-based outbound mail integration. Powers Vaier's admin alert emails. Settings and the password live in `vaier-config.yml`. |
| **Test email** | A full AUTH + roundtrip Jakarta Mail send triggered from Settings, used to verify SMTP config independently of the auth layer. |
| **Machine transition** / **up/down alert** | Email sent to every **admin**-role **access entry** when a server-type machine (VPN server peer or LAN server) flips connected/disconnected. Mobile/Windows clients are excluded — their disconnects are routine. The first observation after Vaier startup is treated as a baseline so restarts don't generate noise. |
| **Remote disk-pressure alert** | Email sent to every **admin**-role **access entry** when a machine crosses into **remote disk pressure**, with a paired recovery email when it drops back below. This is the sole disk-alert path — it covers every SSH-accessible machine Vaier holds a **host credential** for, including the Vaier host itself (reached via SSH-to-self, same as any other machine). Alerts only on a boundary crossing per machine, and a machine that is unreachable or whose `df` fails is skipped rather than alarmed. Superseded an earlier, separate local-host-only disk-pressure alert, which was retired once this alert could reach the Vaier host itself, to stop double-notifying admins for the same host. |
| **Disk-fill early-warning alert** | Email sent to every **admin**-role **access entry** when a machine's **runway** first drops below the **forecast horizon** while the disk is still below the **disk alert threshold**, with a paired all-clear on a genuine recovery — the disk drained, or its fill slowed so the runway rose back past the horizon, all while still below the threshold. Trend-based counterpart to the level-based **remote disk-pressure alert**: a filling disk raises this once, then hands off to the pressure alert when it crosses the threshold — that hand-off raises no all-clear (the pressure alert speaks for it), so admins are never paged twice for the same disk. |
| **Access-request alert** | Email sent to every **admin** when a new **pending** **access entry** is created — i.e. when an identity signs in for the first time and lands awaiting approval. The mail names the email and points the admin to the access overview to approve or deny it. Fires only on the first sighting, never on repeat sign-ins by the same pending identity. |
| **Update available** | (Planned, #57) Indicator on a container when its image has a newer digest on Docker Hub. UI-only in V1; no auto-update. |
| **Wireguard out of date** | Badge on a peer card whose running wireguard image differs from `WireguardClientImage.EXPECTED`. Operator action: re-download the client compose and redeploy. |

---

## 8. Persistence and config

Vaier has **no database**. State lives in files, in Route53, or in memory.

| Term | Definition |
|------|------------|
| **Vaier config** | `vaier/config/vaier-config.yml`. Holds Route53 credentials (`awsKey`, `awsSecret`), `domain`, `acmeEmail`, SMTP settings, and the **disk alert threshold**. Loaded as `domain.VaierConfig`. The reversible secrets (`awsSecret`, SMTP password) are encrypted at rest via the same cipher as the credential vault. |
| **Host credentials file** | `vaier/config/host-credentials.yml`. One encrypted host credential per machine (the credential vault on disk). |
| **Vault key** | `vaier/config/vault.key`. The credential vault's symmetric master key, self-generated on first use (mode `600`) unless supplied via `VAIER_VAULT_KEY`. Never committed. |
| **WireGuard config** | The set of files under `WIREGUARD_CONFIG_PATH`. `wg0.conf` plus per-peer client configs. |
| **Traefik dynamic config** | YAML files under `TRAEFIK_CONFIG_PATH`, generated by `TraefikReverseProxyAdapter`. Picked up live by Traefik. |
| **lan-servers.yml** | Persists registered LAN servers. `lan-docker-hosts.yml` is the legacy filename and is auto-migrated on startup. |
| **Secrets on disk** | New secret files are written at mode `600`; surrounding directories should be `go-rwx`. |

---

## 9. Architecture

Hexagonal architecture (ports & adapters), four layers. See `CLAUDE.md` for the rules.

| Term | Convention | Example |
|------|-----------|---------|
| **Domain** | Pure Java, no Spring. Entities, value objects, port interfaces. | `domain.Machine`, `domain.ReverseProxyRoute` |
| **Port (driving)** | `*UseCase` interface — one per use case, narrow. Controllers depend on these. | `PublishPeerServiceUseCase`, `DeletePeerUseCase` |
| **Port (driven)** | `For*` interface — one per outbound capability. | `ForPersistingDnsRecords`, `ForProbingTcp`, `ForResolvingPublicHost` |
| **Application service** | `*Service` — one per *domain concept*, not per use case. Implements every use case in its domain. | `VpnService`, `PublishingService`, `UserService`, `MachineService`, `LanServerService`, `LanServerReachabilityService`, `LanServerScrapeService`, `DnsService`, `ReverseProxyService`, `SettingsService`, `ContainerService`, `LifecycleService`, `NotificationService`, `HostMonitoringService` |
| **Adapter** | `*Adapter` — driven adapter, implements `For*` ports. Lives in `adapter/driven/`. | `Route53DnsAdapter`, `WireGuardVpnAdapter`, `TraefikReverseProxyAdapter`, `LanServerFileAdapter`, `JavaSocketTcpProbeAdapter` |
| **REST controller** | `*RestController`, in `rest/`. DTOs are inner `record` classes. | `MachineRestController`, `PublishedServiceRestController` |
| **API error envelope** | The uniform JSON shape returned when a request fails: `ApiError(code, message, detail)`. `code` is a stable machine-readable token (`BAD_REQUEST`, `NOT_FOUND`, `CONFLICT`, `INTERNAL_ERROR`), `message` is an operator-safe human-readable explanation, `detail` is optional context (nullable). | `ApiError` |

**Cross-domain orchestration goes through use-case interfaces, never class-to-class.** E.g. `VpnService.deletePeer` cascading into published-service cleanup is wired via `DeletePublishedServiceUseCase`, not a direct dependency on `PublishingService`.

**Strict layer isolation.** Two unrelated services that happen to need the same string literal keep their own copies. Don't import an unrelated `*UseCase` interface just to share a constant.

---

## 10. Events (SSE)

The browser receives live updates via Server-Sent Events. Topics and event names are part of the API.

| Topic | Events | Triggered by |
|-------|--------|--------------|
| `published-services` | `services-updated`, `publish-rolled-back` | Publishing flow, rollback, peer deletion, route changes |
| `vpn-peers` | `peers-updated`, `peers-stats`, `lan-servers-updated` | `VpnService` mutations, `PeerStatsScheduler`, `LanServerReachabilityScheduler`, `LanServerScrapeScheduler` |

Do not poll from the browser when an SSE topic exists.

---

## 11. Naming choices to avoid drift

These pairs come up often. Use the left, never the right.

| Use | Don't use |
|-----|-----------|
| machine | node, device, host (when ambiguous) |
| machine type | peer type (in routing context), device category (means the icon, not routing) |
| device category | device type, icon kind, machine type (means routing, not the icon) |
| peer / VPN peer | client (alone), VPN node |
| relay peer | bridge peer, gateway (means #174) |
| LAN server | LAN docker host (legacy, code path migrated) |
| LAN service | host route, raw route |
| published service | site, deployed service, route (when meaning the publish flow) |
| publish | expose, deploy, route (verb) |
| publishable service | candidate, available service |
| direct URL | local URL, lan URL (when written as one word) |
| Vaier server | local, local host, this host (when referring to the machine running the stack) |
| operator | admin (means a role), user (means an access entry) |
| configured administrator | initial admin, default user, bootstrap admin |
| latest handshake | last seen (UI label only — derived from this) |
| endpoint IP | public IP (peer's public IP, not the Vaier server's) |
| public host | server hostname, our IP |

---

## 12. Licensing and editions

| Term | Definition |
|------|------------|
| **Edition** | The product tier a running instance operates as: **Community** or **Enterprise** (`domain.Edition`). There is a single binary — the edition is resolved at runtime from the installed **licence**, never a separate build. |
| **Community** | The free edition. The default when no valid licence is installed. Every non-Enterprise feature works exactly as before. |
| **Enterprise** | The paid edition. Unlocks Enterprise-only features (the first being the **LAN scanner**, #246). Granted only by a valid **licence**. |
| **Licence** | An offline, Ed25519-signed token that grants the Enterprise edition. Carries the customer, edition, issue/expiry instants, and unlocked features. Verified locally against a public key baked into the binary (`ForVerifyingLicense` → `Ed25519LicenseVerifierAdapter`) — no network call. Installed via the `VAIER_LICENSE` env var (`ForReadingLicenseToken`). Modelled by `domain.License`; an authentic-but-expired licence falls back to **Community**. |
| **Licence token** | The wire form of a **licence**: `base64url(payload) "." base64url(signature)`. Minted by the issuer with the matching private key via `LicenseMintingTool`; the private key never ships. |
| **Enterprise gate** | The rule that an `@RequiresEnterprise`-annotated controller or handler is reachable only while the **edition** is Enterprise. Enforced by `EnterpriseLicenseInterceptor`, which answers `402 Payment Required` otherwise. The UI hides gated features by reading `GET /license` first. |

---

## 13. Social login and access

Vocabulary for the social-login authorization model — now the sole runtime auth mechanism. Authentication
is delegated to an external **identity provider**; Vaier owns **authorization** through a file-based access store.

| Term | Definition |
|------|------------|
| **Social login** | Signing in with an external identity provider (Google or GitHub) instead of a Vaier-local password. Vaier no longer authenticates the user itself; it authorizes an already-authenticated identity. |
| **Identity provider** | The external service that authenticates a user and asserts their email to Vaier — Google or GitHub, reached through **Dex**. Abbreviated IdP. |
| **Dex** | The identity broker that sits behind **oauth2-proxy** and federates the **identity providers**: oauth2-proxy speaks one OIDC dialect to Dex, and Dex fans out to a **connector** per provider. Lets Vaier offer several sign-in choices while identity stays keyed on email. |
| **Connector** | One upstream provider wired into **Dex** — currently a Google connector and a GitHub connector. The sign-in page's provider choice selects which connector Dex hands the user to. |
| **Access entry** | One known identity in the access store: its email, its **role**, its **access groups**, its **display name**, and the **last sign-in provider** it most recently signed in with (`domain.AccessEntry`). The unit the access overview lists and an admin actions. |
| **Display name** (access entry) | The identity's human name as reported by the **identity provider** (the provider's `name` claim), stored on the **access entry** and shown beside the email in the access overview. Also greets the identity in the Vaier console when the console runs on Social **auth mode**. Null until a sign-in fills it in (e.g. a pre-approved entry); a sign-in without one never clears it. Distinct from the *launchpad display name* (a service tile's label). |
| **Last sign-in provider** | The **identity provider** (its **Dex connector** id — `google` or `github`) an **access entry** most recently signed in with, stored on the entry and surfaced as a small provider glyph on the corner of the person's **avatar** in the access overview. Null until a sign-in fills it in (e.g. a pre-approved entry that has never authenticated); a sign-in whose provider is blank or unrecognised never clears it. |
| **Provider user id** | The identity's stable id at its **last sign-in provider** (the Dex `federated_claims.user_id` — GitHub's numeric account id, Google's `sub`), stored on the **access entry**. Used to build the provider **avatar** photo (the GitHub account picture). Null until a sign-in fills it in. |
| **Avatar** | The person's provider photo when available — the GitHub account picture (via the **provider user id**), otherwise a Gravatar keyed on the email. Shown beside each **access entry** in the access overview (falling back to a **monogram** — initials on an email-derived colour) and in place of the signed-in user's name in the console topbar (falling back to the name text). |
| **Role** | The access level granted to an **access entry** (`domain.Role`): **pending**, **user**, or **admin**. Exactly one role per entry, and the sole authority for admin-vs-user — orthogonal to **access groups**, which never decide it. |
| **Pending** | The role a freshly seen identity lands in: authenticated by the identity provider but not yet approved, so blocked from everything until an admin promotes it. "Awaiting approval." |
| **User** (role) | An approved, non-administrative identity — an **access entry** with the user role. Reaches the services whose required **access group** it holds; cannot administer Vaier. This is the only user concept an operator manages (on the **Users** page). |
| **Admin** (role) | An approved identity that may administer the Vaier console and reach every service, regardless of access groups. |
| **Last-admin protection** | The invariant that the access store always retains at least one **admin**, so the admin-only console can never be locked out for everyone. Revoking or demoting the sole remaining admin is refused, and a configured administrator is restored on startup when no admin exists. |
| **Access group** | A free-form, per-service access tag on an **access entry** (e.g. `devs`, `family`): an **access rule** names the groups a service allows, and a user reaches it only if their entry carries at least one of them. Admins bypass the requirement. Purely per-service — the names `admins` and `users` are never access groups (they mirror the **role**, which is the sole authority for admin-vs-user). |
| **Access rule** | The per-service authorization rule for one **published service**, keyed by its host: the **allowed groups** an identity may satisfy to reach it. Any-of — a **user** passes if it holds at least one allowed group; an empty rule (no groups) admits any approved user; an **admin** always passes and a **pending** identity never does. Because a rule keys on host, path-scoped services sharing a host share one rule. |
| **Allowed groups** | The set of **access groups** an **access rule** holds — the any-of list a **user** may satisfy to reach the service. Empty means no group restriction (any approved user). |
| **Auth mode** | How a gated surface signs a user in (`domain.AuthMode`): **none** (public) or **social** (the oauth2-proxy → Vaier authorization chain). Set per published service (one mode per route); the Vaier console is always social. Every gated route runs on **social**. Replaces the earlier per-route "requires auth" on/off toggle. |
| **oauth2-proxy** | The external component that performs **social login** authentication (the sign-in dance and the domain-wide SSO session) and asserts the signed-in email to Vaier. It does not talk to the **identity providers** directly — it federates them through **Dex**. Mandatory, always-on stack infrastructure — the sole runtime auth gateway since Authelia was decommissioned. |

---

## 14. Web terminal

| Term | Definition |
|------|------------|
| **Host credential** | The single SSH login Vaier holds for a machine: a username, an **auth method** (password or private key), the secret material, and an optional key passphrase. Exactly one per machine. |
| **Credential vault** | The encrypted-at-rest store for host credentials. Secret material is sealed with a symmetric cipher before it is written to disk and never leaves the process in the clear. |
| **Web terminal** | A live, in-browser SSH shell to a machine, opened from its Infrastructure card. Vaier authenticates server-side from the credential vault; the browser only exchanges keystrokes and screen output, never the secret. |
| **Remote command** | One non-interactive command Vaier runs on a machine over SSH to read a result back in code — as opposed to the **web terminal**, which streams an interactive shell for a human. It uses the same host credential and **host-key pin**, opens a short-lived exec channel (not a PTY), and is bounded by a run timeout and an output cap so it can neither hang nor exhaust memory. The mechanism the alerting pipeline uses to sense hosts behind the VPN. |
| **Command result** | The outcome of a **remote command**: the process exit code (or unknown when it timed out or the server sent none), the captured stdout and stderr, whether it timed out, and the host-key fingerprint the server presented (so a remote command can pin an unpinned host on first use, like the **web terminal**). A non-zero exit code is a normal result the caller inspects, not an error. |
| **SSH address** | Where Vaier opens the SSH connection for a machine: a VPN peer's tunnel IP, a LAN server's LAN address, or — for the Vaier server host — the container's gateway host IP (or `VAIER_HOST_SSH_ADDRESS`). |
| **Host-key pin** | The SSH host-key fingerprint Vaier records for a machine on first connect (trust-on-first-use). A later connect that presents a different fingerprint is refused as a **host-key mismatch**; clearing the pin lets a legitimately rebuilt host be re-pinned. |

---

## 15. Out-of-language

Terms that look like they belong here but don't — these are explicitly **not** Vaier vocabulary because the underlying concept is out of scope:

- Cloudflare, nginx, Caddy, Keycloak, Vault, Kubernetes, Portainer, Coolify, Pi-hole — listed only to record that they were considered and rejected.
- "Backup snapshot" / "export" — V1 has no backup feature; V2 will (see #153).
- "Multi-server", "WireGuard mesh" — single Vaier server, period.
