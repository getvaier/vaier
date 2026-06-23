# Product Requirements Document — Vaier

**Last updated:** 2026-04-10
**Status:** Living document
**Issues:** https://github.com/getvaier/vaier/issues — GitHub issues are part of the spec and represent confirmed requirements and bugs.

---

## 1. Overview

Vaier is a self-hosted infrastructure management tool for developers running a homelab. It eliminates the manual work of maintaining a WireGuard VPN server, reverse proxy, and DNS records by providing a single interface that wires everything together automatically.

The core value proposition: add a new Docker service anywhere on your VPN, select a subdomain, and Vaier handles DNS, reverse proxy, and HTTPS — end to end.

Vaier is a personal tool that will be open-sourced. It is not intended to compete with general-purpose infrastructure platforms (Portainer, Coolify, Rancher, etc.). It is opinionated about its stack: WireGuard + Traefik + Authelia + AWS Route53.

---

## 1.1 Design Philosophy: Automation First, Zero Config

The defining characteristic of Vaier is that **things should just work**. The user should never need to set an environment variable or edit a config file to enable a feature that Vaier can detect or infer automatically.

Principles:
- **Detect, don't configure.** If a capability (Docker socket location) can be discovered at runtime, it must be. Env vars are a last resort, not a first instinct.
- **Sensible defaults everywhere.** Every option has a default that is correct for the common case.
- **Progressive disclosure.** Advanced options exist but are never required to get started.
- **The happy path is the only path.** If a user has to read documentation to do the standard workflow, that is a bug.

---

## 2. Problem Statement

Running a homelab with multiple Docker hosts behind a VPN involves repetitive, error-prone manual steps every time a new service is added:

1. Create a WireGuard peer config and distribute it
2. Add a DNS A/CNAME record in Route53
3. Write a Traefik dynamic config file with the right router, service, and middleware
4. Optionally wire in Authelia forward-auth
5. Verify everything propagated correctly

Each step is done in a different tool, with no feedback loop. Mistakes are silent (wrong IP, missing middleware, typo in DNS name). Vaier removes all of this.

---

## 3. Target User

**Primary persona: homelab developer**

- Runs one or more Linux servers (VPS, home server, Raspberry Pi, etc.)
- Uses Docker for most services
- Manages a personal domain with subdomains per service
- Values automation over flexibility — happy to adopt Vaier's opinionated stack
- Not interested in learning Traefik TOML or WireGuard config syntax

---

## 4. Goals

| # | Goal |
|---|------|
| G1 | Add a new service in one action: pick a container, pick a subdomain, done |
| G2 | Manage WireGuard peers with zero manual config file editing |
| G3 | Provide a launchpad dashboard linking to all running services |
| G4 | Keep the operator aware of infrastructure health (DNS, reachability, update availability) |
| G5 | Be installable by any homelab developer in under 30 minutes |

---

## 5. Non-Goals

- Not a general-purpose container orchestrator (no Portainer replacement)
- Not a multi-cloud DNS manager (Route53 is the only automated provider; manual DNS mode is supported for everything else, see §6.4)
- Not a monitoring platform
- No multi-server WireGuard topology (single VPN server, multiple peers)
- No management of the Docker host OS (no package installs, kernel config, etc.)
- No mobile app

---

## 6. Feature Areas

### 6.1 VPN Peer Management ✅ (exists)

Manage WireGuard peers through the UI without touching config files.

**Current capabilities:**
- Create / delete peers
- Generate peer config (file download, QR code, docker-compose template, bash setup script)
- View peer connection status (latest handshake, transfer stats)
- View per-peer container list via Docker API
- Detect peers running an older wireguard image than the server — surface a "wireguard out of date" badge on the peer card so the operator knows to re-download the client compose and redeploy on the peer
- **Connected-peer world map ✅** — Machines page exposes a **List / Map** tab switcher. The Map tab renders a Leaflet/OpenStreetMap world map (Leaflet and the markercluster plugin are self-hosted under `static/vendor/` so the page works without unpkg/CDN access). Coordinates come from a local DB-IP City Lite MMDB lookup; the database is downloaded by a `geoip-init` container into a shared `./geoip/` volume on first boot and refreshed monthly. Lookups happen via a `ForGeolocatingIps` port (`DbIpGeolocationAdapter`) and are folded into the existing `GET /vpn/peers` payload so the page makes a single `wg show dump` call per refresh. Private/RFC1918/CGNAT/IPv6-ULA endpoints are filtered out, and the map gracefully shows no markers if the DB is missing.
  - **Server marker** ✅ — A distinct larger marker for the Vaier server itself. The server's public IP is resolved via a new `ForResolvingPublicHost.resolvePublicIp()` method that uses the EC2 IMDS `public-ipv4` endpoint (avoiding AWS split-horizon DNS, which inside the VPC resolves the EC2 hostname to a private RFC1918 IP), with fallbacks to `VAIER_PUBLIC_IP`, DNS-resolving `VAIER_PUBLIC_HOST`, then DNS-resolving `vaier.<domain>`. Exposed at `GET /vpn/peers/server-location`.
  - **Mobile/client dual marker** ✅ — `MOBILE_CLIENT` and `WINDOWS_CLIENT` peers plot twice: a dotted/low-opacity weak marker at the carrier-IP geolocation ("connecting from") plus a firm marker stacked at the Vaier server's location ("internet via Vaier"). This communicates both the device's actual ingress and how it appears on the public internet given `AllowedIPs = 0.0.0.0/0` full-tunnel routing. Server-type peers keep a single marker at their own endpoint.
  - **Clustering** ✅ — Co-located markers cluster with a count; clicking expands them, zooming in spiderfies. Cluster bubbles are styled in the theme green (`--green`) with high-contrast dark text and white border so they're legible against any tile colour.
  - **Live updates** ✅ — Both `peers-updated` and `peers-stats` SSE events refresh the map. `peers-stats` re-renders only when a peer's connection state actually flipped (avoiding churn).
  - **Hover popups** ✅ — Popups open on `mouseover` and close on `mouseout`, with no close button.
  - **Attribution** ✅ — DB-IP credit is rendered under the map and in the README, satisfying the CC BY 4.0 license.

**Config distribution options (all currently implemented):**
- **WireGuard config file** — download as `.conf` for native clients
- **QR code** — server-rendered PNG via ZXing; scannable by WireGuard mobile apps
- **docker-compose template** — ready-to-run compose file for peers running Docker
- **Bash setup script** — automated peer setup for Linux hosts

All four artefacts are delivered **show-once** — see [Show-once peer config](#show-once-peer-config) below.

**Implemented:**

#### Peer types ✅

When creating a peer, the user selects a **peer type**. The type drives the WireGuard config defaults and which download options are shown.

| Type | Use case | AllowedIPs | Downloads shown |
|------|----------|------------|-----------------|
| **Mobile client** | Phone or tablet | `0.0.0.0/0` | QR code, `.conf` file |
| **Windows client** | Laptop or desktop | `0.0.0.0/0` | `.conf` file |
| **Ubuntu server with Docker** | Self-hosted services on Linux | `10.13.13.0/24` | docker-compose, bash setup script |
| **Windows server with Docker** | Self-hosted services on Windows | `10.13.13.0/24` | docker-compose |

- Server types expose containers in the peer view; client types hide that section.
- The type is persisted in a `# VAIER: {"peerType":"..."}` JSON comment at the top of the client config file. Legacy peers with no comment default to `UBUNTU_SERVER`.
- Ubuntu server peers can optionally specify a **LAN CIDR** (e.g. `192.168.1.0/24`). When set, the CIDR is appended to the **server-side** `wg0.conf` `[Peer]` entry's `AllowedIPs` so the VPN server routes LAN-bound traffic into the relay peer's tunnel. The CIDR is **not** added to the relay's own client-side `AllowedIPs` — doing so would make `wg-quick` install a route for the LAN via `wg0` on the relay host itself, hijacking the relay's local LAN connectivity. The LAN CIDR is recorded in the VAIER metadata comment so it survives across re-installs. The generated peer install script additionally enables `net.ipv4.ip_forward` and installs idempotent `iptables` MASQUERADE + FORWARD rules (vpn → lan, lan → vpn for established/related), so the relay actually forwards decrypted VPN packets out its own LAN NIC. The relay rules are persisted across reboots via a generated systemd oneshot (`vaier-wg-relay-iptables.service`) that re-applies the same idempotent block on every boot (closes [#191](https://github.com/getvaier/vaier/issues/191)). On the server side, hot-edits to `AllowedIPs` (via `wg set`) also reconcile the wireguard container's kernel routes (`ip route replace`/`ip route del`) to match — `wg set` itself is silent on routing and would otherwise leave VPN→LAN traffic falling through to the docker bridge until the next container restart (closes [#192](https://github.com/getvaier/vaier/issues/192)).
- Ubuntu/Windows server peers can additionally specify a **LAN address** (e.g. `192.168.1.50`) — the server's reachable host/IP on that LAN. Used by the launchpad to return direct, proxy-bypassing URLs when the caller is on the same LAN. Editable inline on the expanded server card via `PATCH /vpn/peers/{id}/lan-address`, so existing peers can be annotated without recreation.
- Any machine — VPN peer or LAN server — can carry an optional **description** ✅ ([#54](https://github.com/getvaier/vaier/issues/54)) — operator-supplied free text (e.g. "Home media server (NUC, Ubuntu 22.04)") to record what it is for. Set on the Add Machine form, shown as a muted subtitle under the machine name, and editable inline on the expanded card. For VPN peers it is stored in the `# VAIER:` metadata comment (JSON-escaped so the single-line comment stays valid) and updated via `PATCH /vpn/peers/{id}/description`; for LAN servers it is a field in `lan-servers.yml`, updated via `PATCH /lan-servers/{name}/description`.
- A peer has a stable **id** and a separate, editable display **name** ✅ ([#209](https://github.com/getvaier/vaier/issues/209)). The id is the slug Vaier derives from the operator-typed name at creation (`domain.PeerId` — non-slug characters folded to `-`, runs collapsed; deduplicated with a numeric suffix `-2`, `-3`, … when it would collide). The id is the peer's `peers/<id>/` config directory and the segment in every `/vpn/peers/{id}` REST path, and is frozen for the life of the peer. The name is free text the operator edits at will, stored in the `# VAIER:` metadata comment; a peer with no stored name falls back to its id rendered with dashes as spaces (so an auto-slugged `media-server` reads as `media server` in the UI and on the launchpad). Existing peers need no migration — their config directory name becomes the id verbatim.
- VPN peers and LAN servers can be **renamed** in place ✅ (peer half of [#55](https://github.com/getvaier/vaier/issues/55)) — edit the **Name** field on the expanded card. A peer rename (`PATCH /vpn/peers/{id}`) only rewrites the `name` in the `# VAIER:` metadata comment — the peer's id, config directory, and published services never move (the running tunnel is keyed by public key server-side, and routes/launchpad resolve from IP at runtime). Display names must be unique across every machine (see #284 below). A LAN server rename (`PATCH /lan-servers/{name}`) rewrites the `lan-servers.yml` entry; published LAN routes keep working (keyed by address). **Still open in #55:** renaming a published *service* (its DNS name) — heavier because it rewrites the live Route53 record + Traefik route, so it is tracked separately.
- Machine names are **unique across all of Vaier** ✅ (closes [#284](https://github.com/getvaier/vaier/issues/284)) — registering a new VPN peer or LAN server, or renaming either onto a name already used by *any* machine (peer or LAN server), is rejected with `409` `ApiError(code=CONFLICT)`. The check is a single domain predicate, `Machine.nameIsTaken(candidate, existingNames)`, called by both `VpnService` (create + rename) and `LanServerService` (register + rename) before any key/IP/file is touched; each service gathers the names of every *other* machine from driven ports — peer names via `ForGettingPeerConfigurations` and LAN-server names raw via `ForPersistingLanServers` (deliberately not `ForGettingLanServers#getAll`, which resolves relay anchors and re-reads peer configs just to surface names) — and passes them in, so the domain decides and the hex boundary holds. Comparison is case-insensitive and trims surrounding whitespace (`"nas"` == `"NAS"` == `" nas "`); LAN-server registration also persists the trimmed name and address so the stored identity matches the comparison rule. The peer name the check sees is the *effective* display label (`ForGettingPeerConfigurations` always supplies the stored name, or the humanised id as a fallback), so an unnamed peer still reserves its displayed label. *Clearing* a peer's name (a blank rename) reverts it to that fallback label, which is itself subject to the uniqueness rule — so a clear is rejected when the humanised-id label is already used by another machine. The peer **id** remains the immutable identity — only the display-name uniqueness constraint is new. Previously this guard existed for LAN-server-vs-LAN-server only; it now spans peers too, closing the data-loss path where a duplicate LAN-server name would silently upsert over the existing entry. The Add Machine and rename UI now surface the envelope's message instead of a body-less error.

#### Show-once peer config ✅ (closes [#202](https://github.com/getvaier/vaier/issues/202))

The WireGuard config artefacts (`.conf`, QR PNG, docker-compose, setup-script — anything embedding the peer's private key) are delivered **exactly once**. The threat model: WireGuard has no session concept, no server-side revocation, and the same config works on any number of devices, so a screenshot of the QR or a copied `.conf` would otherwise be a permanent backdoor.

- A filesystem marker (`<wireguardConfigPath>/<peerName>/<peerName>.conf.viewed`) lives next to the peer's `.conf` and is created atomically on the first GET to any of the five secret-bearing endpoints (`/config`, `/config-file`, `/qr-code`, `/docker-compose`, `/setup-script`). Subsequent GETs on any of those five return `410 Gone` with `{"reason":"already-viewed","action":"delete-and-recreate"}`. Driven by the `ForTrackingPeerConfigRetrieval` port (`FilePeerConfigRetrievalTracker` adapter); the existing peer-delete flow removes the whole peer directory, which also clears the marker for free.
- The create response (`POST /vpn/peers`) inlines every artefact — config text, base64 QR PNG, docker-compose, setup-script (when applicable) — so the UI's create-success modal renders all of them without a follow-up GET. The marker is **not** set on create, so a one-shot raw curl GET after create still works for tooling that prefers to fetch the artefact out-of-band.
- The create-success modal opens with a **Getting Started** panel (closes [#51](https://github.com/getvaier/vaier/issues/51)) — one sentence of per-peer-type guidance pointing at the 80/20 next step: mobile = "scan the QR code with the WireGuard app", `UBUNTU_SERVER` = "copy `setup-<name>.sh` to the host and run `bash setup-<name>.sh`", Windows = "import `<name>.conf` into the WireGuard Windows client". The matching download button gets primary styling; alternatives stay secondary so the eye lands on the recommended action without the others being hidden. Recommendation is decided by `recommendedArtifactFor(peerType)` in the browser.
- Pre-existing peers (created before this change) have no marker file and are therefore treated as **not yet viewed**: the first GET after upgrade is allowed, then the peer is locked.
- The Services-page row no longer surfaces per-artefact buttons (config/compose/script/QR). To recover a fresh config for an existing peer, the operator uses **Regenerate** — a confirmation modal followed by a `DELETE /vpn/peers/{id}` + `POST /vpn/peers` with the same name/peerType/lanCidr/lanAddress/description, which rotates the WireGuard keypair as a side effect of the recreate.
- Authelia 2FA on the secret-bearing endpoints is a complementary follow-up (tracked in [#203](https://github.com/getvaier/vaier/issues/203)).

#### Reissue config + out-of-date detection ✅ (closes [#247](https://github.com/getvaier/vaier/issues/247))

When the config-generation logic changes after a peer was created (e.g. [#204](https://github.com/getvaier/vaier/issues/204) started appending the **server LAN CIDR** to server peers' client-side `AllowedIPs`), existing on-disk configs are not retroactively rewritten and **Regenerate** is overkill — it rotates the keypair and disrupts the tunnel. **Reissue** is the keys-preserving fix:

- **Reissue** (`POST /vpn/peers/{id}/reissue`) re-renders the peer's config from current logic while preserving its keypair, preshared key and tunnel IP (`WireGuardPeerConfig.reissue` reads them back out of the on-disk config), persists it (`ForUpdatingPeerConfigurations.rewriteConfig`), and re-opens the show-once budget (`ForTrackingPeerConfigRetrieval.resetViewed`). The response inlines the same artefacts as create, so the UI reuses the create-success modal. No `wg` call and no server-side `[Peer]` change — the live tunnel is untouched; the operator reinstalls the reissued config on the peer machine to apply it. Distinct from **Regenerate** (delete + recreate, rotates keys).
- **Out-of-date detection** — `GetVpnPeersUseCase` flags each peer's `configOutOfDate` by comparing its on-disk config against its **rendered config** (`WireGuardPeerConfig.isOutOfDate`, server render inputs resolved once per refresh). The Services-page card shows a **Config out of date** badge and styles the **Reissue config** button as primary.

#### Show-once / reissue follow-ups (backlog)
- Startup drift scan: surface out-of-date peers proactively (a summary banner / count) rather than only per-card, so the operator notices without expanding each card.

---

### 6.2 Service Publishing ✅ (exists, core workflow)

The primary workflow: expose a Docker container as a public HTTPS subdomain.

**Current capabilities:**
- Discover containers with exposed ports on the Vaier server and VPN peers
- Publish a service: creates DNS CNAME record (pointing to the VPN server) + Traefik route + optional Authelia middleware
- Toggle authentication on/off per service
- Check publish status (DNS propagated, Traefik active)
- Delete published service (removes DNS + Traefik route)
- Edit root path redirect on published services
- Auto-delete published services when a VPN peer is deleted

**Publish flow (confirmed UX):**

1. User sees two lists on the published services page:
   - **Discovered** — containers with exposed TCP ports not yet published, found on the Vaier server and reachable VPN peers
   - **Active** — published services with their DNS/reachability state
2. Clicking **+ Add** on a discovered service opens a modal: subdomain input + auth toggle
3. On submit, the modal closes immediately and the service moves into a **Processing** list that sits between the discovered and active lists
4. The processing card shows live progress steps: DNS record created → DNS propagated → Traefik route active
5. When the Traefik route is confirmed active, the processing card disappears and the service appears in the active list
6. The discovered list hides the service as soon as it enters processing (server-side, not client-side)
7. Both active and processing lists are driven by SSE — no polling from the browser
8. Processing state survives page refresh (backed by in-memory server state, not persisted to disk)
9. Duplicate submissions are rejected: attempting to add a service already in active or processing shows an error

**Also implemented:**
- **Root redirect path UI** — collapsible "Advanced" section in the publish modal with an optional root path redirect input, wired to the `rootRedirectPath` API field. Redirect path is also editable on published services via a modal.
- **Service cleanup on peer deletion** — when a VPN peer is deleted, all published services routing to that peer's IP are automatically removed (DNS + Traefik routes)
- **Published services page cleanup** — consolidated host/status rows, hide discovered section when empty, replaced fragile optimistic auth toggle with server-side refresh
- **Publish rollback on failure** — if DNS propagation times out, Traefik route creation throws, or Traefik never picks up the new route, Vaier removes the CNAME (and, where applicable, the Traefik route) so no orphan records remain in Route53. Emits `publish-rolled-back` on the `published-services` SSE topic.
- **Contextual help + error explanations in the publish flow** ✅ (closes [#56](https://github.com/getvaier/vaier/issues/56)) — UX/observability pass over the existing publish flow; no new endpoints or concepts.
  - **Progress-step tooltips** — each Processing-card step ("DNS record created", "Waiting for DNS propagation…", "Activating reverse proxy route…") carries a native hover tooltip explaining what is happening and why, including the ~30–60s ETA for DNS propagation and that a stuck record rolls the publish back.
  - **Rejection reason surfaced on 400** — `POST /published-services/publish` and `POST /published-services/lan` return the rejection reason in the response body (the `400` body was previously empty). Since [#275](https://github.com/getvaier/vaier/issues/275) this is the shared `ApiError` envelope (the validation exception propagates to `GlobalExceptionHandler`); originally it was a bespoke `PublishError(String message)` record. The browser's `explainPublishError()` helper reads `.message` and shows that human message plus a suggested next step instead of a raw `HTTP <code>`, with status-keyed fallbacks for 409, 5xx, and network errors.
  - **Rollback surfaced to the operator** — the browser now handles the `publish-rolled-back` SSE event (which the backend already emitted but the UI ignored), telling the operator the DNS record + Traefik route were removed so they can safely retry.
- **LAN service publishing** ✅ (closes [#175](https://github.com/getvaier/vaier/issues/175)) — expose a LAN host (NAS, IPMI, printer, IoT) reachable through a relay peer's `lanCidr` *or in the Vaier server's own subnet* (see "server LAN CIDR" below), no Docker container required. The publish flow validates that the target IP falls inside some relay peer's `lanCidr` or the server LAN CIDR (`LanAnchor`), writes a DNS CNAME and a Traefik route whose backend is `http(s)://<lan-ip>:<port>`. For a relay-anchored target, cryptokey routing on `wg0` plus the relay's #170 forwarding deliver packets; for a server-anchored target, the Traefik container reaches it directly out the host's LAN/VPC NIC. Surfaces with a small "LAN" badge in the published-services list; relay-anchored routes use the target host as the launchpad direct-URL shortcut for on-LAN callers and a server-anchored route's host state is always OK.
- **LAN server registration (Docker optional)** ✅ (closes [#177](https://github.com/getvaier/vaier/issues/177), [#184](https://github.com/getvaier/vaier/issues/184), [#181](https://github.com/getvaier/vaier/issues/181)) — register any machine on a relay peer's LAN *or in the Vaier server's own subnet* (see "server LAN CIDR" below) as a `LAN_SERVER` machine, with optional Docker. With Docker on, Vaier scrapes its remote Docker socket through the relay (same `tcp://<host>:<port>` pattern as VPN peers) — or, for a server-anchored LAN server, directly from the Vaier container. With Docker off, the LAN server still appears on the Machines page and is publishable through the manual LAN-service flow. Registration validates that `lanAddress` falls inside some relay peer's `lanCidr` or the server LAN CIDR; the Add Machine modal asks only for the address. Persisted as YAML at `${VAIER_CONFIG_PATH}/lan-servers.yml` (legacy `lan-docker-hosts.yml` is auto-migrated on startup). V1 scope: insecure tcp 2375 only; no TLS/SSH yet. Backed by the unified `MachineType` taxonomy: `MOBILE_CLIENT`, `WINDOWS_CLIENT`, `UBUNTU_SERVER`, `WINDOWS_SERVER`, `LAN_SERVER`. A unified `GET /machines` endpoint returns all five in one list. Registration validates the address only (Docker need not be reachable yet); the operator then runs the host's **unified per-host setup script** from its card — see [Per-host LAN setup script](#per-host-lan-setup-script--closes-249) below.
- **Per-host LAN setup script** ✅ (closes [#249](https://github.com/getvaier/vaier/issues/249)) — one script the operator runs on a registered LAN server, `GET /lan-servers/{name}/setup.sh`, that **adapts to that host**: it opens the Docker engine API (native + snap, the same daemon.json/systemd-drop-in logic as before) when the host runs Docker, **and** installs static routes via its relay peer when it is relay-anchored. Routes cover the server LAN CIDR, the VPN subnet, and every *other* relay peer's `lanCidr` (so a host behind one relay can reach the Vaier VPC and — once #250 lands — other sites' LANs), persisted across reboots by a `vaier-lan-routes.service` systemd oneshot (`ip route replace … via <relay lanAddress>`, distro-agnostic, idempotent — mirrors the relay's `vaier-wg-relay-iptables.service`). Strict hex: the decision of what the script must do (effective Docker port, relay-anchor → gateway, routed-CIDR set, "relay has no LAN address" → 409, "nothing to do" → 404) lives in the pure domain `LanServerSetupScript.forHost(...)` / `routedDestinations(...)`; `LanServerService` only reads the `LanServer`, peer configs, server LAN CIDR and VPN subnet from driven ports and hands them over. This **replaces** the old generic `docker-setup.sh` (static classpath script + `--port`) — register-first, since registration never needed Docker reachable; the old endpoint and `scripts/lan-docker-setup.sh` are retired. Sibling-site LAN routes are installed but only carry traffic once the site-to-site mesh follow-up ([#250](https://github.com/getvaier/vaier/issues/250) — sibling-relay LANs in peer `AllowedIPs` + broadened relay forwarding) ships; the script header says so. The endpoint is on Authelia's **bypass** list (`AutheliaConfigAdapter`) so `curl … | sudo bash` works on a fresh LAN host with no Vaier session — the script carries no secrets (the bypass regex is single-quoted YAML; a `generatesValidYaml` test guards against the invalid-escape crash that double quotes caused).
- **Server LAN CIDR — Vaier server as its own LAN router** ✅ — the Vaier server knows the CIDR of the network it sits on, so machines on it can be registered as LAN servers and have their services published *without a relay peer*. The value is **discovered**, not hand-configured by default: `ForResolvingServerLanCidr` reads the instance's own **subnet** CIDR from EC2 IMDSv2 (`network/interfaces/macs/<mac>/subnet-ipv4-cidr-block` — a default-VPC subnet is a `/20`, one per AZ). `VAIER_SERVER_LAN_CIDR` is a general **override** (it short-circuits IMDS, on EC2 too) — set it to widen the routed range, typically to the whole VPC CIDR (`172.31.0.0/16`) so machines in any AZ/subnet qualify, or to supply the value off EC2; anything that doesn't parse as a strict IPv4 CIDR (`Cidr.validateLanCidr`) is ignored, and the resolved value is memoized. `docker-compose.yml`'s `vaier` service passes the env var through (closes [#204](https://github.com/getvaier/vaier/issues/204)). Such a machine is anchored at `"Vaier server"` (`LanAnchor.VAIER_SERVER_NAME`) — it surfaces on the Machines page (`Machine.lanCidr` = the resolved CIDR), shows "via Vaier server", plots on the Map tab at the Vaier-server location with a "Behind Vaier server" label, and the Add Machine modal validates a typed LAN address by asking the domain (`GET /lan-servers/lan-anchor`, `ResolveLanAnchorUseCase`) rather than reimplementing CIDR containment in the browser. It is reachability-probed and Docker-scraped straight from the Vaier-side containers (vaier / traefik → docker bridge → host → the host's LAN/VPC NIC, which already works because Docker enables `ip_forward` on the host and masquerades the bridge network out the host NIC), and publishes a normal `isLanService` Traefik route. When an address is covered by both a relay peer's `lanCidr` and the server LAN CIDR, the relay peer wins. **Split-tunnel server peers can also initiate connections into the subnet** (closes [#204](https://github.com/getvaier/vaier/issues/204)): `WireGuardPeerConfig.generate` appends the resolved server LAN CIDR to `UBUNTU_SERVER`/`WINDOWS_SERVER` peers' client-side `AllowedIPs` (e.g. `AllowedIPs = 10.13.13.0/24,172.31.0.0/16`), so `wg-quick` installs a route for the server's subnet via `wg0` on the peer. The `wireguard-masquerade` sidecar installs an interface-name-agnostic `iptables -t nat -A POSTROUTING ! -o wg0 -j MASQUERADE` rule inside the wireguard container's netns (closes [#248](https://github.com/getvaier/vaier/issues/248)), so VPN-sourced packets exit the Vaier host with the host's LAN IP and replies come back — regardless of the host NIC's name. (The linuxserver `wg0.conf` `PostUp` only matches `-o eth+`, which is a silent no-op on hosts whose NIC isn't named `eth*`, e.g. AWS EC2's `ens5`.) Full-tunnel client peers already reach it via their default `0.0.0.0/0`. Mobile/client peers' `AllowedIPs` is unchanged (adding the CIDR would be redundant and risks confusing wg-quick's route table). Existing peers don't pick up the new `AllowedIPs` automatically — **Reissue** the peer (Services-page **Reissue config**, which preserves the keypair), or **Regenerate** to also rotate keys. See [Reissue config + out-of-date detection](#reissue-config--out-of-date-detection--closes-247). No Settings-UI field and no `vaier-config.yml` entry — env + IMDS cover the supported cases.
- **LAN server reachability check** ✅ (closes [#186](https://github.com/getvaier/vaier/issues/186), [#201](https://github.com/getvaier/vaier/issues/201)) — every registered LAN server is probed every 30s with a TCP connect to a small set of common ports (80, 443, 22). Any TCP response (handshake or RST) marks the host pingable; if every TCP probe times out, an ICMP echo (`/bin/ping -c 1`) fires as a fallback so printers, IoT devices and IPMI cards that don't expose any of those ports don't get falsely shown as red. A clean timeout plus no ICMP reply marks the host down. The Machines page combines that signal with the Docker socket scrape to colour the machine icon four ways: grey (not yet probed), green (host pingable; if Docker-enabled, scrape also OK), yellow (Docker host pingable but scrape failed), red (host not pingable). Cache changes publish a `lan-servers-updated` SSE event on the existing `vpn-peers` topic so the page updates without a manual refresh.
- **Peer-config lifecycle clarity** ✅ (closes [#271](https://github.com/getvaier/vaier/issues/271)) — the show-once / reissue / regenerate / out-of-date distinctions are now self-explanatory in the UI, with no behavioural change to the show-once security model. The create-success modal explains *why* the config vanishes ("For your security, this config is delivered exactly once and won't be shown again on its own") and points to **Reissue** to recover a lost config (keys preserved) vs **Regenerate** to rotate keys. The Reissue/Regenerate button tooltips now state what each does *and when to use it*; the regenerate-confirm modal points to Reissue as the non-destructive alternative; and the ⚠ out-of-date-config badge spells out what changed and what to do. Glossary reconciled — **Regenerate** is for replacing a compromised config, **Reissue** is the non-destructive way to recover one. Pure frontend copy.
- **Inline field help** ✅ (closes [#269](https://github.com/getvaier/vaier/issues/269)) — advanced form fields carry a small visible "?" affordance (shared `.help-tip` in `styles.css`) whose hover text gives a one-line plain-language explanation, so an operator who hasn't read the dev docs can tell what each field does. Covers **LAN CIDR** (Add Machine modal), and on the published-services page **path prefix**, **require Authelia auth**, **direct LAN URL**, **root path redirect**, **version endpoint**, and **hide-from-launchpad** — both in the Publish modal and the expanded service-detail rows (rendered via a `helpTip()` helper in `published-services.js`). Pure frontend; no API changes.
  - **Backlog:** the #269 inline tooltips could deep-link into the matching anchor on the Concepts page (e.g. the LAN CIDR "?" → `concepts.html#lan-cidr`). This PR adds the anchor support (each concept carries a stable `id` slug) and the Concepts tab; wiring each individual tooltip to its anchor is a follow-up.
- **In-app operator glossary (Concepts page)** ✅ (closes [#274](https://github.com/getvaier/vaier/issues/274)) — a new **Concepts** tab in the admin shell renders a trimmed, plain-language glossary of the terms an operator meets in the UI, grouped by area, each with a short definition and a one-line "why it matters". The copy is curated in the pure domain `OperatorGlossary.groups()` (the single source of operator-facing concept copy) over `Concept`/`ConceptGroup` records; `Concept.of(term, …)` derives a stable URL-safe slug from the term so each entry is deep-linkable via its `id` anchor (e.g. `concepts.html#lan-cidr`). Served by `GET /concepts` (`ConceptsController` → `GetConceptsUseCase` → `ConceptsService`). A drift test (`OperatorGlossaryTest`) asserts every concept term appears verbatim as a `**Term**` entry in `UBIQUITOUS_LANGUAGE.md`, plus no duplicate slugs and non-blank definition/why for every entry — so the in-app glossary can never name a term the canonical doc doesn't define. Loaded inside the already-authenticated admin shell; no Authelia bypass.
- **Machine-status tooltip** ✅ (closes [#270](https://github.com/getvaier/vaier/issues/270)) — each machine's type icon carries a hover **tooltip** stating the current state in plain language plus its evidence (e.g. "Green — connected, last handshake 12s ago", "Red — unreachable, last handshake 4m ago", "Grey — not yet probed"), prefixed with the machine type, surfacing the four-state machine-icon colour without a separate legend. The tooltip's relative handshake age stays live via the `peers-stats` SSE stream. Pure frontend — reuses the existing reachability / last-handshake data; no behavioural change.
- **LAN server Docker scrape scheduler** ✅ (closes [#188](https://github.com/getvaier/vaier/issues/188), [#200](https://github.com/getvaier/vaier/issues/200)) — every Docker-enabled LAN server is scraped every 30s through its relay peer, mirroring the reachability scheduler. Status (`OK` / `UNREACHABLE`) is debounced with the same 3-consecutive-cycle rule used for reachability, so a single Docker-socket blip never flips the machine icon green→yellow. The cached scrape result is what `GET /docker-services/lan-servers` returns, so the UI also reads the dampened value rather than a fresh-but-flickering scrape. On a confirmed status change the scheduler republishes the existing `lan-servers-updated` SSE event on the `vpn-peers` topic, so a host coming up after its setup script finishes turns green without a page refresh. First observation of a server commits immediately — no 90s warmup blackout. The live-scrape `DiscoverLanServerContainersUseCase` is unchanged and still serves the publishable-services flow, which needs current state.
- **Last seen for LAN servers** ✅ (closes [#194](https://github.com/getvaier/vaier/issues/194)) — every successful reachability probe stamps an in-memory `lastSeen` epoch second on the LAN server, mirroring what VPN peers get from their WireGuard handshake. Surfaced in the "Last Seen" detail row inside the expanded card. A later DOWN probe never erases `lastSeen` — the whole point is to remember when the host last responded. `lastSeen` is exposed as a Long epoch second on `GET /lan-servers`.
- **Unified machine UI** ✅ (closes [#185](https://github.com/getvaier/vaier/issues/185), [#182](https://github.com/getvaier/vaier/issues/182)) — single **Add Machine** modal on the Machines page covers all five machine types with conditional fields driven by the type dropdown (LAN address required for `LAN_SERVER`, Docker checkbox + port shown only for LAN servers). The Servers section now combines the Vaier server (always pinned to the top, rendered with the Vaier brand icon and a green/red status colour) + VPN server peers + LAN servers sorted by name; the dedicated LAN-Docker-hosts section is removed. The manual **Publish LAN service** dialog picks a machine from a dropdown of registered LAN servers — including Docker-enabled ones, since a Docker host can still expose native (non-container) services that auto-discovery doesn't cover. The map tab places `LAN_SERVER` markers anchored at the relay's geo location with a "Behind &lt;relay&gt;" label.
- **Exposed-port range collapsing** ✅ (closes [#189](https://github.com/getvaier/vaier/issues/189)) — host-network containers that declare large contiguous `EXPOSE` ranges (e.g. RoonServer ships `9100-9339/tcp`, 240 ports) used to surface as one row per port in the publishable list. The Docker discovery adapter now collapses runs of consecutive `(port, type, ip)` tuples into a single range `PortMapping` carrying `firstPort`/`lastPort`. Range mappings are filtered out of the publishable list (a range can't be auto-published as one route) so one container no longer drowns the page.
- **Services page card layout** ✅ (closes [#234](https://github.com/getvaier/vaier/issues/234), [#235](https://github.com/getvaier/vaier/issues/235)) — the admin published-services list now groups cards under one section per peer, mirroring how the launchpad presents tiles: section heading is the host (`hostName`, "Vaier" for the Vaier server's own services, the relay peer for a LAN route); peers and services sort alphabetically within their group. Each card's bold heading is the operator-facing Display Name (`launchpadAlias` if set, else the route's `shortName`) — pathPrefix no longer concatenates into the heading. The dim sub-line carries (a) the LAN host's display name as `@ <name>` for LAN services — surfaced via `ReverseProxyRoute.lanServerName(List<LanServer>)` and a new nullable `lanServerName` field on `PublishedServiceUco`, resolved by `lanAddress` against `lan-servers.yml` — so an operator reads "DSM @ NAS" even though the section names the relay, and (b) the pathPrefix when set. Discovered and Processing sections stay flat.
- **Services page edit reconciliation** ✅ (closes [#239](https://github.com/getvaier/vaier/issues/239)) — toggling a checkbox or saving a field on the Services page used to visibly snap back for a frame: the PATCH fired a server-side `service-updated` SSE event, and the resulting re-render could race the PATCH's own `fetchServices()` response. The page now tracks an in-flight edits set keyed by `(dnsAddress, pathPrefix, field)`; while non-empty, `displayServices` defers swaps, then flushes once the set drains. Mirrors the existing focus-defer for text inputs.
- **Services card polish** ✅ (closes [#237](https://github.com/getvaier/vaier/issues/237), [#243](https://github.com/getvaier/vaier/issues/243)) — the expanded Services card drops the redundant status pill on the Host row (the card-header machine icon already carries that state), and the two negative-framed toggles flip to positive: `Direct LAN URL` checked now means "link directly" (uncheck to disable), `Launchpad` checked means "show tile" (uncheck to hide). API fields (`directUrlDisabled`, `hiddenFromLaunchpad`) stay the same; only the UI presentation inverts, matching the Auth-row convention where checked = the named thing is on. Publish + Publish-LAN modals use the longer `Link directly to LAN URL` label since modal layout isn't constrained by the card's label column.
- **Services card — Advanced disclosure** ✅ (closes [#236](https://github.com/getvaier/vaier/issues/236)) — the rare-touch settings (Redirect, Version endpoint, Direct LAN URL, Launchpad) live behind an `<details>` "Advanced" disclosure on each expanded Services card; URL, DNS, Host, Auth, and Display name stay above the fold. Auto-opens when any of its fields is non-default so existing customisations are visible without a click; explicit operator toggles are remembered in `advancedExpanded` and override the auto-open rule for the lifetime of the page.
- **Services card — auto-save text fields** ✅ (closes [#238](https://github.com/getvaier/vaier/issues/238)) — Display name, Redirect, and Version endpoint inputs lost their `Save` buttons and dirty-tracking helpers; each saves on blur (and on Enter, which just blurs the field) when the value differs from `data-original`, then briefly flashes a green border to confirm. As a belt-and-suspenders against any timing edge case in the existing SSE/in-flight defer logic, `renderServicesContainer` now captures dirty input values and the focused field's caret position before the innerHTML swap and restores them after — an unsaved edit can't be wiped out by a poll or SSE re-render even if the focus guard misses a frame.
- **Discovered services — filter + grouping** ✅ (closes [#244](https://github.com/getvaier/vaier/issues/244)) — the Discovered section header gains a free-text filter (matches container name, source/host, address) and a `Group by host` toggle that renders the list under one `.peer-heading` per source (same styling the published list uses). Filter, grouping, and the existing `Show ignored` toggle all persist in `localStorage` under `vaier.discovered.*` keys, so a reload doesn't reset the operator's view. Filtered-empty surfaces a "No discovered services match …" message; the `@ source` sub-label on each card is dropped when grouping is on since the heading already names the source.
- **Unified published-service PATCH endpoint** ✅ (closes [#241](https://github.com/getvaier/vaier/issues/241)) — the six per-field PATCH endpoints (`/auth`, `/direct-url-disabled`, `/hidden-from-launchpad`, `/redirect`, `/launchpad-alias`, `/version-endpoint`) collapse into one `PATCH /published-services/{dnsName}?pathPrefix=...` accepting a partial body (`requiresAuth`, `directUrlDisabled`, `hiddenFromLaunchpad`, `rootRedirectPath`, `launchpadAlias`, `versionEndpoint`, `versionProperty`). Field semantics: `null` (or omitted) = leave unchanged; for the string fields, an empty string = clear. Backed by one `UpdatePublishedServiceUseCase` and one `PublishingService.updateService(...)` method; the six narrow `*UseCase` interfaces are deleted. Frontend collapses six handlers' fetch logic into one `patchService(dnsAddress, pathPrefix, patch)` helper.
- **Unified publish modal** ✅ (closes [#240](https://github.com/getvaier/vaier/issues/240)) — the two near-duplicate publish modals collapse into one `#publishModal` that switches via a `data-mode="container|lan"` attribute. CSS hides the LAN-only rows (machine picker, target port, protocol) in container mode and hides the discovered-source caption in LAN mode. One `submitPublish()` branches on mode to hit `/published-services/publish` vs `/published-services/lan`. Dropped: `publishLanModal`, all `publishLan*` field IDs, `hidePublishLanModal`, and `submitPublishLan`.
- **Path-based routing — multiple services per subdomain** ✅ — every published service gains a nullable, normalised `pathPrefix` (e.g. `/auth`). When set, the Traefik rule becomes `Host(\`fqdn\`) && PathPrefix(\`path\`)`, the router/service/redirect-middleware names get a path-derived slug so siblings on one FQDN don't collide, and the read-back parser extracts the prefix back onto `ReverseProxyRoute`. Route uniqueness moves from FQDN-only to `(fqdn, pathPrefix)`. The publish flow becomes sibling-aware: the first publish on a host creates the DNS CNAME, later siblings skip the create; on delete, the CNAME is only removed when the last sibling on the FQDN is gone. The publish modal exposes Path prefix next to the Subdomain input on both the peer and LAN flows; the launchpad emits one tile per path-based service whose landing URL is decided by `ReverseProxyRoute.landingPath()`: when a `rootRedirectPath` is set it wins over the path prefix; otherwise the path prefix is used verbatim, including any trailing slash the operator typed (so an SPA at `bmp/builder/ui/` can be expressed either by typing the slash on the prefix or by registering a redirect to `/builder/ui/`). Per-row toggles (auth, redirect, Disable direct LAN URL) target the specific `(fqdn, pathPrefix)` route — flipping auth on `bmp/auth` no longer affects `bmp/CorpoWebserver`. The Direct-URL-disabled set on disk migrates transparently: legacy bare-FQDN entries keep working for host-only routes; new writes use the unique router name. Decisions like duplicate detection and sibling lookup live on `ReverseProxyRoute` (static helpers); services orchestrate, the domain decides.

---

### 6.3 Service Dashboard ✅ (`launchpad.html`)

A read-only launchpad page listing all published services as a clean grid of tiles.

**Current state:**
- Separate page at `/launchpad.html`
- Each tile: service name, peer name, favicon (with letter-avatar fallback), clickable link opening service in a new tab
- No management controls — purely presentational
- Suitable for use as a browser home page or new-tab page
- Launchpad page and its API (`/launchpad/services`, `/favicon`) are public (no auth required)
- Admin pages remain protected by Authelia
- When the caller's public IP matches a VPN peer's WireGuard endpoint IP (i.e. they share a NAT gateway with that peer), and the service is hosted on that peer, the tile links to `http://lanAddress:port` directly — bypassing Traefik and Authelia. Falls back to the public HTTPS URL otherwise. The caller IP is taken from `X-Forwarded-For` only when the direct peer (`RemoteAddr`) is inside the trusted proxy CIDR (`launchpad.trusted-proxy-cidr`, default `172.20.0.0/16`).
- **Auth-mediated tile URL** — when the service requires Authelia auth and no direct-LAN bypass applies, the tile links to `https://login.<domain>/?rd=<encoded-service-url>` instead of the service URL itself. This forces the browser to navigate to a different origin first, defeating PWA service workers (e.g. openHAB) that would otherwise serve a cached SPA from their own origin and trap the user in the app's own login screen because XHRs to `/rest/*` get answered with `401` rather than a cross-origin `302` redirect to login.
- **Per-service direct LAN URL opt-out** — the reverse-proxy route carries a `directUrlDisabled` flag (persisted in the Traefik YAML as `x-vaier-direct-url-disabled`). When set, the launchpad always serves the public HTTPS hostname for that service, skipping the direct LAN URL shortcut. This is required for services whose public origin differs from `http://lan:port` — Vaultwarden is the canonical case: its `DOMAIN` env is `https://vaultwarden.<domain>`, so opening the LAN URL yields a near-blank page because the Vue app won't initialise against a mismatched origin. Available as a checkbox both in the Publish service modal (so it can be set on creation) and on the expanded service details row. Also togglable via the unified `PATCH /published-services/{dnsName}` partial-update endpoint (`{"directUrlDisabled": ...}`); accepted on `POST /published-services/publish` as a `directUrlDisabled` body field.
- **Per-service hide-from-launchpad toggle** ✅ — the reverse-proxy route carries a `hiddenFromLaunchpad` flag (persisted in the Traefik YAML as `x-vaier-hidden-from-launchpad`). When on, the route stays reachable but the launchpad never renders a tile for it. Use case: internal APIs that back another service and don't need an operator-clickable tile. Togglable from the expanded service details row in the admin published-services page, or via the unified `PATCH /published-services/{dnsName}` partial-update endpoint (`{"hiddenFromLaunchpad": ...}`).
- **Domain-owned tri-state launchpad visibility** ✅ — `ReverseProxyRoute.launchpadVisibility(dnsState, hostState, callerAuthenticated)` returns `LaunchpadVisibility.{NOT_VISIBLE, VISIBLE_INACTIVE, VISIBLE_ACTIVE}`, consolidating every reason a route might be hidden, dimmed, or active. The launchpad use case is a thin pass-through that filters `NOT_VISIBLE` and forwards the rest with `visibility` on `LaunchpadServiceUco`; the launchpad client only renders the value and never has to understand individual reasons. New visibility rules accrete inside the domain method, not in the application layer.
- **Branded offline page for unreachable backends** ✅ — when a published service's backend is down, Traefik no longer serves its bare black "Bad Gateway" page; visitors get Vaier's branded **offline page**. A shared Traefik `errors` middleware (`vaier-errors`) catches 502/503/504 and forwards to an http service (`vaier-error-pages`) pointing at the Vaier container, which serves `GET /error-pages/{status}` — a self-contained HTML page (inline CSS, no cross-origin asset links) that names the unavailable service from `X-Forwarded-Host`, shows a friendly message, and offers Retry and Back-to-launchpad links. The status→title/message mapping is the domain `GatewayError` record. The middleware is attached to every router `addReverseProxyRoute`/`addLanReverseProxyRoute` creates, and an idempotent startup backfill (`TraefikReverseProxyAdapter.backfillErrorPages`, on `ApplicationReadyEvent`) appends `vaier-errors` to every pre-existing router and ensures the shared infra exists — without touching existing auth/redirect middlewares, load-balancer servers, or `x-vaier-*` metadata. Web layer mirrors the favicon shape: `GetOfflinePageUseCase` → `OfflinePageService` → `OfflinePageController`.
- **Branded offline page when Vaier itself is down** ✅ — the offline page above is served *by Vaier*, so it can't help when the Vaier container itself is down (Traefik then falls back to its bare "Bad gateway", and Vaier's own routers — defined on its container's labels — vanish while the self-published file-provider router still points at the dead backend). A separate, always-up `vaier-offline` service (tiny pinned nginx, `offline/html` + `offline/default.conf` bind-mounted) stands in: it serves one self-contained branded page (matching the offline-page styling, HTTP 503, 15s auto-refresh) for any path. A **low-priority Traefik fallback router** (`priority=50`) for the Vaier host sits above the lingering file router (30) but below Vaier's real docker routers (100/200), so while Vaier is up its own routers win and when the container stops this becomes the top match. A `vaier-down` `errors` middleware on the same container also covers the transient "container up but returning 5xx mid-restart" window. Infra-only (docker-compose + static assets); no Java change. A `.gitignore` exception keeps `offline/default.conf` tracked despite the blanket `*.conf` ignore.
- **Version visible under Settings → About** ✅ — the running Vaier build version is surfaced so the operator always knows which build is deployed. The Maven `build-info` goal bakes `project.version` into the jar as Spring `BuildProperties`; `ForReadingAppVersion` / `BuildPropertiesVersionAdapter` reads it (falling back to `dev` when absent), exposed via `GetAppVersionUseCase` on `SettingsService` and `GET /settings/version`. The Settings page renders an **About** card showing `v<version> · <Edition>` (edition from `GET /license`). Replaced an earlier always-on corner badge on the machines page that was too intrusive.
- **Host-down indicator on the launchpad** ✅ (closes [#208](https://github.com/getvaier/vaier/issues/208)) — `Server.State` is now tri-state: `OK`, `UNREACHABLE`, `UNKNOWN`. A `VISIBLE_INACTIVE` launchpad tile (host confirmed unreachable) shows an 11px red status dot in its top-right corner with a "Host offline" tooltip *and* drops its `href` so a click can't follow the dead link. An `UNKNOWN` host stays `VISIBLE_ACTIVE` (we don't know it's down) — no dot, normal link — but the services card icon goes grey (`icon-unknown`) instead of misleadingly green. `ReverseProxyRoute.hostState(...)` consumes the LAN-reachability snapshot (`Map<String, Reachability>`): `DOWN` → `UNREACHABLE` (regardless of relay), `UNKNOWN` → `UNKNOWN` when the route is otherwise routable, `OK` → existing relay / server-LAN-CIDR check. A dead relay tunnel still beats `UNKNOWN`. The snapshot comes from a new `ForCheckingLanReachability` driven port, implemented by `InMemoryLanReachabilityCache` (`adapter/driven/`) — LAN-reachability state was moved out of `LanServerReachabilityService` into that cache adapter so the cross-service read goes through a domain port rather than a use-case back-channel; the orchestrator service writes through a sibling `ForRecordingLanReachability` port. A confirmed UP↔DOWN transition invalidates the published-services cache and publishes `service-updated` on the `published-services` SSE topic, so the launchpad and services pages re-fetch immediately. The `Reachability` enum lives in the domain.
- **Hide auth-protected services from anonymous viewers** ✅ (closes [#207](https://github.com/getvaier/vaier/issues/207)) — the launchpad page is public, but it now consumes two sibling endpoints. `GET /launchpad/services` stays in Authelia's `bypass` set and always returns the public-only subset (passes `callerAuthenticated=false` into `ReverseProxyRoute.launchpadVisibility`). `GET /launchpad/services-authenticated` falls through to Authelia's `one_factor` policy, so reaching the controller is itself proof of a valid session — the endpoint always passes `callerAuthenticated=true` and returns the full list including auth-protected tiles. The launchpad page tries the authenticated endpoint first and falls back to the public one when Authelia 302s the anonymous load to the login portal. Auth-protected routes (`authInfo != null`) collapse to `NOT_VISIBLE` for anonymous viewers, so the names and URLs of internal services stop leaking to anyone who can reach the launchpad URL. Per the V1 auth model (Traefik forward-auth only, all logged-in users are admin), any auth on a service is effectively "internal — log in first"; per-user/per-group visibility waits for V2.
- **Domain-owned launchpad tile name + alias** ✅ — `ReverseProxyRoute.launchpadDisplayName(baseDomain)` decides the tile label: operator-supplied `launchpadAlias` wins (persisted as `x-vaier-launchpad-alias`), otherwise the final segment of `pathPrefix` for path-based routes (so `services.example.com/grafana` displays as `grafana`, not `services/grafana`), otherwise the first DNS label. The subdomain moves into the tile's sub-line beside the peer when it differs from the display name. Editable via the Display name input in the admin published-services details panel and the unified `PATCH /published-services/{dnsName}` partial-update endpoint (`{"launchpadAlias": ...}`).
- **Domain-owned favicon lookup identity** ✅ — `ReverseProxyRoute.launchpadFaviconQuery()` decides what the launchpad sends to `/favicon`: host-only routes resolve a single icon per FQDN; path-based routes carry both host and `pathPrefix` so siblings on one subdomain (e.g. `services.example.com/grafana` and `…/jenkins`) cache separately. The favicon fetcher accepts a `pathPrefix` query param and probes `https://host{pathPrefix}/` first for HTML/`favicon.ico` discovery before falling back to the FQDN root; the CDN-by-name fallback uses the final path segment when present. Closes the regression where every path-based sibling shared a single cache key and fell back to the letter avatar.
- **Backing container image on the launchpad tile** ✅ (closes [#210](https://github.com/getvaier/vaier/issues/210)) — hovering a tile reveals the Docker image and version of the container behind the service, so an operator can see what's running without inspecting the host by hand. `ReverseProxyRoute.backingContainer(...)` resolves a route to its container among the discovered peer / Vaier-server / LAN-server containers: a peer route matches by VPN IP, a Vaier-server route by container name (or port), a LAN-service route by LAN address. The image/version ride along on `LaunchpadServiceUco`; the launchpad renders them in a small styled tooltip. `PublishingService` caches the discovered-container snapshot for 60s (`containerImageSnapshotTtlMillis`) so the launchpad's aggressive reloads (tab focus, SSE) don't re-query every Docker daemon over the VPN. A service published as a bare LAN host:port has no container — its tile simply omits the version.
- **LAN-native service version** ✅ (the [#210](https://github.com/getvaier/vaier/issues/210) follow-up) — a published service can carry an operator-configured **version endpoint**: a URL (a path on the service, or an absolute URL) plus a property name. The version is read via the `ForProbingServiceVersion` driven port — invoked from `ReverseProxyRoute.probeVersion(...)` so the domain owns the port call — by GETting the endpoint and extracting a `property="value"` label from the response (Prometheus text-exposition style). The probed version takes precedence over a backing container's image tag and rides along on `LaunchpadServiceUco`, so a service running natively on a LAN machine (no discoverable container) still reports its version in the tile tooltip. Probes run concurrently and `PublishingService` caches them for 60s (`versionProbeSnapshotTtlMillis`). Configured via the Version endpoint inputs on the published-services details panel and the unified `PATCH /published-services/{dnsName}` partial-update endpoint (`{"versionEndpoint": "...", "versionProperty": "..."}`); persisted in the Traefik YAML as `x-vaier-version-endpoint`.
- **Backing image + version on the Services-page card** ✅ (closes [#245](https://github.com/getvaier/vaier/issues/245)) — the same image/version that the launchpad tile shows on hover is also surfaced inline on the Services-page service card as a `Version` detail row (image as a monospace code chip, dim `version <x>` beside it). Same resolution as the launchpad: `ReverseProxyRoute.backingContainer(...)` against the discovered container snapshot, with a configured `versionEndpoint`'s probed value taking precedence. Threaded through `PublishedServiceUco.image` / `version` so the browser doesn't re-derive either. The row is omitted when neither value is set (e.g. bare LAN host:port).

---

### 6.4 DNS Management

Vaier supports two DNS modes, inferred from the presence of AWS credentials. There is no `VAIER_DNS_PROVIDER` env var: `ConfigResolver.getDnsProvider()` derives `ROUTE53` when both `awsKey` and `awsSecret` are present and `MANUAL` otherwise.

**Route53 mode ✅ (default when AWS keys present).** Vaier automates DNS through the AWS Route53 API: it auto-creates the bootstrap records (`vaier.<domain>`, `login.<domain>`) on first boot and a CNAME per published service. Backed by `Route53DnsAdapter` and the `ForPersistingDnsRecords` / `ForValidatingAwsCredentials` ports. There is no UI page for general-purpose record CRUD — the REST endpoints exist (`/dns/*`) but the navigation page was never built. Service publishing is the primary path; advanced records are managed in the AWS console.

**Manual DNS mode ✅ (closes [#198](https://github.com/getvaier/vaier/issues/198), [#199](https://github.com/getvaier/vaier/issues/199)).** Omit `VAIER_AWS_KEY` / `VAIER_AWS_SECRET` (and don't save them via the Settings UI) and Vaier runs without Route53. The `ManualDnsAdapter` no-ops every DNS write and synthesizes the bootstrap records as already-present so `Lifecycle.initDns()` is silent. The publish flow is unchanged: `addDnsRecord` no-ops, then `waitForDnsThenActivate` polls real DNS via `ForResolvingDns` and activates Traefik once the operator's record propagates. If the record never appears, the existing 2-minute timeout + rollback handles it. The Settings UI hides the AWS Credentials card whenever the active provider is MANUAL, since saving keys via that form does not flip the runtime mode (it only takes effect on the next restart with env vars set) and the field was just clutter in manual installs. To opt into Route53, set `VAIER_AWS_KEY` / `VAIER_AWS_SECRET` and restart. For the launchpad, `PublishingService.toUco()` reports `DnsState.OK` for every route in MANUAL mode — the operator owns DNS and Vaier has no authoritative view, so synthesising "OK" matches the trust-the-operator semantics rather than rendering every published service as missing.

**V2** — Cloudflare as a first-class alternative provider, tracked in [#154](https://github.com/getvaier/vaier/issues/154).

---

### 6.5 Reverse Proxy Management ✅ (exists)

Direct CRUD for Traefik routes (escape hatch for non-Docker services).

**Current capabilities:**
- List / create / delete routes
- Per-route authentication toggle

No planned changes beyond what service publishing drives automatically.

---

### 6.6 User Management ✅ (exists)

Manage Authelia users from the Vaier UI.

**Current capabilities:**
- List / create / delete users
- Change passwords (Argon2 hashing)
- Update display name and email per user
- Choose group(s) when creating a user (no more hardcoded `admins`); edit a user's groups inline; delete a group across all users in one action ([#84](https://github.com/getvaier/vaier/issues/84))
- Authelia login / 2FA pages inherit Vaier's dark theme and logo via `theme: dark` + `asset_path: /config/assets`; the Vaier container publishes `logo.png` into the Authelia assets directory at startup so the hand-off between `vaier.<domain>` and `login.<domain>` reads as one product.

**Planned next:**
- Wire groups into Authelia `access_control` rules (per-service group gating, two-factor escalation per group) — see [#84](https://github.com/getvaier/vaier/issues/84) follow-up. Today every logged-in user reaches every published service.

---

### 6.7 Backup / Restore 🔲 (deferred to V2, tracked in [#153](https://github.com/getvaier/vaier/issues/153))

Export and import the full Vaier configuration as a snapshot.

**V1 decision:** removed from scope. The earlier V1 implementation shipped a plaintext JSON export containing every peer's WireGuard private key and an import path with shell-injection ([#141](https://github.com/getvaier/vaier/issues/141)) and path-traversal ([#142](https://github.com/getvaier/vaier/issues/142)) risk. Rather than patch those in V1, the REST endpoints and UI have been removed and the feature is re-planned for V2 with encryption-at-rest and hardened restore.

**V2 goals (see #153):**
- Passphrase-encrypted export (AES-256-GCM, scrypt KDF) by default
- Hardened restore: argv-style exec throughout, strict input validation at the import boundary
- Round-trip integration test in the new format
- UI rework with re-auth / 2FA gate on import

---

### 6.8 Container Update Notifications 🔲 (planned, tracked in [#57](https://github.com/getvaier/vaier/issues/57))

Keep the operator aware when Docker images have newer versions available.

**Requirements:**
- For each container running on any VPN peer (and the Vaier server), check whether the current image digest has a newer version available on Docker Hub
- Surface outdated containers in the UI: badge count on the nav item, per-container badge in the peer's container list and in service cards
- No automatic updates — notification only
- Check interval: every 24 hours (not configurable in v1) *(OQ4 resolved: UI-only in v1)*
- Containers running the `latest` tag display a warning that version tracking is unreliable for that tag (digest comparison is still attempted but flagged as approximate)
- Only Docker Hub is supported in v1 *(OQ2 resolved: Docker Hub only for v1)*

**Implementation sketch:**
- Background scheduled task (`@Scheduled`) queries each peer's Docker API for running image refs
- For each image ref, call Docker Hub Registry API v2 to compare remote digest vs. local image digest
- Cache results in-memory (TTL: 24 h) to avoid hammering the registry
- Expose via existing `/docker-services/peers` response — add `updateAvailable: boolean` field per container
- Frontend receives updates via SSE (Server-Sent Events) for immediate feedback without polling

**Out of scope for v1:** GHCR, self-hosted registries, push notifications (webhook/email).

---

### 6.9 Email Notifications ✅ (implemented)

Vaier ships an SMTP notifier that powers Authelia password-reset emails today and is intended to carry other Vaier notifications in future. Settings are stored in `vaier-config.yml`; the password lives in Authelia's `secrets.properties`.

**What's implemented:**
- Settings → *Email notifications* form with host, port, username, password, sender, and a "Send test email to …" recipient field.
- **Send test email** button does a full AUTH + roundtrip send via Jakarta Mail so misconfigurations surface without touching the auth layer.
- **Save** verifies credentials against the SMTP server *before* writing the Authelia notifier block or restarting Authelia. On failure the REST endpoint returns HTTP 400 with the upstream SMTP error; Authelia is left untouched.
- Password field can be left blank on save/test to reuse the stored value, so host/sender/etc. can be edited without retyping the secret.
- `AutheliaConfigAdapter` generates `notifier: smtp` when a password is stored, otherwise falls back to `notifier: filesystem` so Authelia always has a valid notifier to start.
- SMTP UI copy is provider-neutral — Authelia is one consumer; future Vaier emails will reuse the same settings.
- **Server machine up/down alerts** ([#173](https://github.com/getvaier/vaier/issues/173)): two 30s schedulers — one watching WireGuard handshake age for `UBUNTU_SERVER`/`WINDOWS_SERVER` peers, one watching the LAN reachability TCP probe for `LAN_SERVER` machines. Mobile/Windows clients are excluded — their disconnects are routine user behaviour. On a state change either watcher emails every user in the `admins` group with subject `[Vaier] <name> is now <connected|disconnected>` and a body containing the machine's name, type, last handshake (or last-seen timestamp for LAN servers), LAN address, and a link back to `vaier.<domain>/vpn-peers.html`. Per-machine state is in-memory; the first observation after Vaier startup is treated as a baseline so a restart never produces a notification storm. No quiet-hours setting — alerts fire 24/7.
- **Reachability debounce for LAN servers**: a probe result must hold for 3 consecutive 30s cycles (≈60s of consistency) before the published cache flips and an email goes out. Dampens both the WireGuard tunnel warmup window after a Vaier restart (no false-down email when it takes one cycle for the relay handshake to complete) and ordinary network flapping (a single transient timeout never propagates). The UI shows the icon as grey ("warming up") until the first state confirms.
- **Last-seen timestamp inside the card** ([#173](https://github.com/getvaier/vaier/issues/173)): every machine's expanded card has a "Last Seen" detail row derived from the latest handshake (or the latest successful LAN reachability probe), updated live by the `peers-stats` SSE stream so the value stays current without a manual refresh. The header row itself signals liveness through the machine-icon colour rather than a separate widget.
- **Host disk-pressure alerts** ✅ — Vaier monitors free space on its own host root filesystem and emails the `admins` group when it fills past a configurable threshold, reusing the same SMTP path as the machine up/down alerts. A `DiskUsageWatcher` (`@Scheduled(fixedDelay = 60000)`) reads host disk usage through the `ForReadingDiskUsage` driven port (`HostDiskUsageAdapter`, backed by `Files.getFileStore`), which reads the host root bind-mounted read-only into the Vaier container at `VAIER_HOST_ROOT_PATH` (default `/host`; `docker-compose.yml`'s `vaier` service mounts `- /:/host:ro` and passes the env var). All fullness decisions live on the `domain.DiskUsage` entity (`usedPercent`, `isAbove`, and its own email subject/body rendering); `HostMonitoringService` (`GetHostDiskUsageUseCase`) only orchestrates the read. An in-memory `domain.DiskPressureTracker` (mirroring `PeerConnectivityTracker`) emits a transition only on a boundary crossing — `CROSSED_ABOVE` sends the pressure email, `CROSSED_BELOW` the recovery email — so a poll that doesn't change state stays quiet, and the first reading after startup is a silent baseline. The emails go out via `NotifyAdminsOfDiskPressureUseCase` on `NotificationService`. The threshold is `diskMonitorThresholdPercent` in `vaier-config.yml` (default 85, valid 1–99, validated in `domain.VaierConfig`), exposed via `ConfigResolver.getDiskMonitorThresholdPercent()` and editable through `PUT /settings/disk-monitor` (`UpdateDiskMonitorSettingsUseCase` on `SettingsService`). With SMTP unconfigured or the host root not mounted the watcher is inert; the latter case is swallowed and logged at debug.

**Known gotcha:** Gmail requires an **App Password** (not the account password) when 2FA is on. The pre-save verification catches this cleanly — save is rejected with the Gmail `534 5.7.9 Application-specific password required` message.

**Host-monitoring follow-ups (backlog):**
- Monitor host CPU and memory pressure alongside disk, with their own thresholds and alerts.
- Per-mount monitoring (not just the host root) — e.g. a separate data volume — each with its own threshold.
- An in-UI disk widget (a usage gauge on the Machines page / Settings) so the operator sees current host disk usage without waiting for an alert email.
- Extend disk monitoring to server peers and Docker-enabled LAN servers, not just the Vaier server itself.

---

### 6.10 First-Run Setup Wizard — removed ✅ (closes [#48](https://github.com/getvaier/vaier/issues/48), [#145](https://github.com/getvaier/vaier/issues/145), [#161](https://github.com/getvaier/vaier/issues/161))

The in-app wizard at `/setup.html` was deprecated on 2026-04-23 (a tester walking through the README found it unreachable when the four required env vars were populated, and not documented when they weren't). It was deleted on 2026-05-04 along with `SetupRedirectFilter`, `SetupRestController`, the three setup use case interfaces, and `SetupService`. Removal also retires the unauthenticated `/api/setup/*` surface that #145 flagged as a race-condition admin-claim window. First-run is now exclusively the env-var path documented in `README.md`.

### 6.11 Zero-touch first-run DNS + Authelia boot ✅ (implemented 2026-04-23, closes [#163](https://github.com/getvaier/vaier/issues/163), [#164](https://github.com/getvaier/vaier/issues/164))

- **Auto-creates `vaier.<domain>` on first boot.** Vaier resolves the server's public address in order: `VAIER_PUBLIC_HOST` (CNAME target) → `VAIER_PUBLIC_IP` (A target) → EC2 IMDSv2 `public-hostname` (CNAME). If none resolve and the record is already missing, Vaier logs a clear instruction and exits the lifecycle step without crash-looping — the rest of the stack stays up so the operator can fix .env and restart.
- **`authelia-init` one-shot container** mirrors the `redis-init` pattern: writes a minimum-viable `configuration.yml` + `placeholder_users.yml` into `./authelia/config` before Authelia starts, so the Authelia container no longer crash-loops against its own default template on the first `docker compose up -d`. Vaier overwrites the placeholder config on its own first start and restarts Authelia.

### 6.12 Docker socket hardening ✅ (closes [#147](https://github.com/getvaier/vaier/issues/147))

The Docker socket is no longer bind-mounted into Vaier or Traefik. A pinned `tecnativa/docker-socket-proxy:v0.4.2` sidecar holds the real socket and exposes a restricted HTTP API on `tcp://docker-proxy:2375` over `vaier-network`. Tecnativa's stock allowlist (`CONTAINERS`, `EVENTS`, `EXEC`, `IMAGES`, `PING`, `POST`, `ALLOW_RESTARTS`) covers GET access cleanly, but `CONTAINERS=1 + POST=1` would also permit `/containers/create` and `/containers/{id}/start` — leaving the privesc chain open. To close it, the `haproxy_template` Compose `configs:` entry overrides the upstream haproxy template with explicit `http-request deny` rules for `/containers/create`, `/containers/{id}/start`, `/images/create`, `/images/load`, and `/images/*/push` *before* the broad `CONTAINERS` allow. The template is embedded inline in `docker-compose.yml` so the stack ships as a single file — no separate config download. A smoke test confirms each denied path returns `HTTP/1.0 403` while `/containers/json`, `/_ping`, `/events`, `/images/{id}/json`, and `/containers/{id}/restart` still return 200/204. Net result: an attacker with RCE in Vaier cannot launch a `--privileged` container, pull a fresh malicious image, or alter swarm/network/volume state.

The Vaier container's PID 1 (the Java process) runs as UID 1000. The `Dockerfile` ENTRYPOINT is `setpriv --reuid=1000 --regid=1000 --init-groups --inh-caps=+net_admin --ambient-caps=+net_admin -- java …` — `setpriv` (from `util-linux`) starts as root so it can manage capabilities, raises `CAP_NET_ADMIN` to ambient (so it transfers to `ip` invoked by `ProcessBuilder`), then drops to UID 1000 before exec'ing Java. A one-shot `vaier-init` container (busybox, mirroring `redis-init`/`authelia-init`) `chown`s the four bind-mounted config dirs (`vaier`, `traefik`, `authelia`, `wireguard`) to `1000:1000` on every start so the non-root process can read and write its own state. The `authelia` service runs with `PUID=1000`/`PGID=1000` (its image entrypoint does `chown -R "${PUID}:${PGID}" /config` on every start and PUID/PGID default to `0`) — without it, Authelia would re-root `./authelia/config` on each (re)start, locking the UID-1000 Vaier process out of the dir it shares with Authelia (bootstrap password file, users database, branding assets). `cap_add: NET_ADMIN` is retained at the container level so `VpnNetworkSetupAdapter` and `LanRouteAdapter` can install routes inside the Vaier container — file caps alone don't transfer reliably under Docker overlayfs, hence the ambient-cap path. (#151's keep-as-hedge rationale therefore stands.)

### 6.13 Argv-style sinks for user-supplied lanCidr ✅ (closes [#195](https://github.com/getvaier/vaier/issues/195))

The three live, authenticated `wg`/`ip` sinks that consumed user-supplied `lanCidr` (`WireGuardVpnAdapter.setPeerAllowedIps`, `WireGuardVpnAdapter.reconcileKernelRoutes`'s `ip route del`, `VpnService.addPeerToServer`) no longer use `sh -c` + `String.format`. They invoke the underlying binaries directly via argv, so shell metacharacters in the input cannot escape `allowed-ips` or `dev` arguments. The `2>/dev/null || true` shell idiom on `ip route del` is replaced by relying on `executeInContainer`'s existing exit-code-discarding behaviour.

A new strict validator `domain.Cidr.validateLanCidr(String)` is applied at the boundary in `VpnService.updateLanCidr` and `VpnService.createPeer` before any state change. It accepts only `A.B.C.D/N` with octet 0-255 and prefix 0-32, rejecting hostnames, IPv6, leading zeros, and any input containing whitespace, `;`, `|`, backticks, `$()`, `&`, quotes or newlines. This is intentionally stricter than `Cidr.parse()`, which uses `InetAddress.getByName()` and silently accepts hostnames — that method stays for trusted internal CIDR strings.

One residual `sh -c "echo '$psk' > $pskFile"` remains in `VpnService.addPeerToServer`. The PSK is generated by `wg genpsk` (base64, no shell metacharacters); the file path is Java-controlled. User-supplied `lanCidr` does not flow through it. Documented in the source comment as a known sh-c invocation that's not user-input-reachable.

### 6.14 Editions and Enterprise licensing ✅ (implemented)

Vaier ships as a single binary with two **editions** — **Community** (free, default) and **Enterprise** (paid) — resolved at runtime from an installed **licence**, never a separate build (open-core model). A licence is an offline, Ed25519-signed **licence token**: the issuer mints it with a private key via `LicenseMintingTool`, the operator installs it through the `VAIER_LICENSE` env var, and Vaier verifies the signature locally against a public key baked into the binary (`Ed25519LicenseVerifierAdapter`) — no phone-home, suiting the self-hosted, no-database ethos. `domain.License` owns the validity decisions (expiry, edition, feature grants); an authentic-but-expired licence transparently falls back to Community. `GET /license` exposes the current edition and licence status so the UI can decide what to render.

Enterprise features are gated by the **Enterprise gate**: any controller/handler annotated `@RequiresEnterprise` is reachable only while the edition is Enterprise, enforced by `EnterpriseLicenseInterceptor`, which otherwise returns `402 Payment Required`. The UI hides gated features entirely by reading `GET /license` first, so a Community instance never teases paid functionality.

### 6.15 LAN scanner 🟡 (Enterprise, first slice — [#246](https://github.com/getvaier/vaier/issues/246))

The first Enterprise-only feature. An on-demand **LAN scanner** sweeps every **relay peer's** `lanCidr` and the **server LAN CIDR**, surfacing responsive hosts not yet registered ("Discovered LAN machines"). The probe is a narrow TCP-connect sweep over common service ports (each connect bounded by `timeout 1`), run from the Vaier WireGuard container (which already routes to every relay LAN over the tunnel) via `ForScanningLan` / `LanScanAdapter`; `LanScannerService` orchestrates the relay/server CIDRs **concurrently**, maps each hit to a `domain.DiscoveredLanMachine` with a **guessed role** (Docker host / web UI / SSH / printer / unknown), and drops any host whose address is **already claimed by a registered machine** — both LAN servers *and* VPN peers (relays/Ubuntu servers carry a LAN address). The "already registered" check is a domain decision on `DiscoveredLanMachine`, fed the union of claimed addresses by the service.

Because a sweep is slow (~20s per /24), it runs **on demand and asynchronously**: `POST /lan-scan` (`ScanLanUseCase`) kicks off a background scan and returns `202 Accepted`; `GET /lan-scan` (`GetDiscoveredLanMachinesUseCase`) returns the latest snapshot — `status` (`IDLE`/`SCANNING`), the discovered machines, and `lastScanCompleted`. On completion the service publishes a `lan-scan-updated` SSE event on the `vpn-peers` topic. Both endpoints are gated `@RequiresEnterprise`.

The scanner is surfaced **inside the Add Machine modal**, above the LAN address field, and only for the **LAN server** machine type (the one type where you discover a remote host behind a relay — an Ubuntu server's "LAN address" is its own IP, so scanning there makes no sense). It is shown on Enterprise instances only — the control is hidden on Community, detected via `GET /license` on page load. The operator clicks **Scan LAN for machines**, the control shows a scanning indicator and polls until the sweep finishes, then lists the discovered hosts in a picker grouped by relay anchor (`IP — role :ports`). Choosing one fills the LAN address (and toggles **Runs Docker** for a detected Docker host). Re-opening the modal shows the last cached snapshot without re-scanning. This replaced an earlier standalone "Discovered LAN machines" page section — answering "what's on this LAN to register?" exactly where the operator types the address is more useful than a flat list. (The modal's fields were also reordered so **Type** leads the form, since it drives which fields appear; and the backdrop-click close was hardened so a click only dismisses the modal when it both starts and ends on the backdrop, so a text-selection drag can't wipe a half-filled form.)

Delivered in this slice: domain + role-guessing, scanning port + relay-exec adapter (with a unit-tested output parser), async stateful orchestration service with already-registered filtering and SSE completion event, async POST-trigger / GET-snapshot REST endpoints, and the in-modal pick-to-fill UI. **Backlog for #246:** per-host ignore list (the discovered-services ignore pattern), scheduled background scans on a slow cadence, MAC-vendor / mDNS hostname enrichment, per-relay enable/disable, and CIDRs larger than `/24` (the current sweep covers the `.1–.254` range of the network's first three octets).

### 6.16 Uniform API error envelope ✅ (closes [#268](https://github.com/getvaier/vaier/issues/268))

A step toward operator-friendly error feedback under the V2 usability theme: every uncaught exception from any controller is now translated into one consistent JSON shape so the web UI can always show the operator *what went wrong* instead of a bare status code or a leaked stack trace.

- A `@RestControllerAdvice` (`net.vaier.rest.GlobalExceptionHandler`) maps uncaught exceptions to the **API error envelope** — `net.vaier.rest.ApiError(code, message, detail)`, where `code` is a stable machine-readable token, `message` is an operator-safe human-readable explanation, and `detail` is optional/nullable.
- `IllegalArgumentException` — the convention domain validation throughout Vaier already uses to signal bad input — maps to `400` with `code=BAD_REQUEST` and the exception's message surfaced **verbatim**, so operator-readable validation messages reach the UI.
- Any other exception maps to `500` with `code=INTERNAL_ERROR` and a safe generic message; the real exception is logged in full server-side and its details (which may include hostnames, IPs, or credentials) are **not** leaked to the client.
- **Migrated the two remaining bespoke error flows onto the shared envelope ✅ (closes [#275](https://github.com/getvaier/vaier/issues/275)).** `PublishedServiceRestController` no longer catches `IllegalArgumentException` to return a `PublishError{message}` for publish / LAN-publish / delete — those validation failures now propagate to the `GlobalExceptionHandler` and render as the uniform `400` `ApiError`; the `PublishError` record is deleted. `SettingsRestController` keeps its deliberate `Exception`→`400` mapping (bad AWS creds / SMTP auth failures are client errors that should be `400`, not the generic `500` a raw SDK exception would otherwise yield) but now emits the shared `ApiError` instead of its own `ErrorResponse{error}`, which is deleted. The frontend is standardised on the envelope's `.message` field (`settings.html` now reads `err.message`; the publish flow already did).
- **Extended the envelope to not-found and conflict across the rest layer ✅ (closes [#282](https://github.com/getvaier/vaier/issues/282)).** Two domain exceptions — `net.vaier.domain.NotFoundException` and `net.vaier.domain.ConflictException` — give the handler typed signals for the two missing categories (`PeerNotFoundException` now extends `NotFoundException`). `GlobalExceptionHandler` maps `NotFoundException` → `404` `ApiError(code=NOT_FOUND)` and `ConflictException` → `409` `ApiError(code=CONFLICT)`, alongside the existing `IllegalArgumentException`→`400`/`BAD_REQUEST` and catch-all→`500`/`INTERNAL_ERROR`. The conflict/not-found throw sites were retyped from raw `IllegalStateException` / `NoSuchElementException` / `RuntimeException` to the new typed exceptions: `VpnService` (LAN-CIDR already owned → `ConflictException`), `LanServerService` (not-found → `NotFoundException`, name-taken → `ConflictException`), `LanServerSetupScript` (relay without a LAN address → `ConflictException`), and `AutheliaUserAdapter` (user already exists → `ConflictException`, user-not-found → `NotFoundException`). Genuine server faults (invalid licence key, SMTP provider unavailable, file-write failures) deliberately stay `IllegalStateException`/`RuntimeException` → `500`. Four controllers were migrated to let these exceptions propagate to the handler instead of hand-rolling responses, removing: `VpnPeerRestController`'s body-less `4xx`/`5xx` on rename / delete / lan-address / lan-cidr / description / reissue; `LanServerRestController`'s body-less `4xx` and its `Map.of("error", …)` `409`; `AuthRestController`'s bare-string error bodies (7 handlers); and `DockerServiceRestController`'s body-less `500`s on discovery. Net result: validation, not-found, conflict, and `500` all render as `ApiError`.
- **Caveat:** `ApiError` is now the shape for every error response *except* two intentional cases: the enterprise-gate `402` (`EnterpriseLicenseInterceptor`), and the deliberately body-less `404`s for a missing optional GET artifact (the favicon, and an already-retrieved one-shot peer config).

---

## 7. End-to-End Workflows

### 7.1 New service on a peer (primary workflow)

1. Peer is already connected to VPN (created via Vaier)
2. Developer starts a Docker container on the peer
3. In Vaier → Services, the container appears in the **Discovered** list automatically
4. Developer clicks **+ Add**, enters a subdomain, toggles auth if needed, clicks **Add Service**
5. Modal closes immediately; service moves to the **Processing** list with live progress steps
6. Vaier creates: DNS CNAME → waits for propagation → Traefik route → (optional) Authelia middleware
7. Processing card disappears; service appears in the **Active** list with live status
8. All updates arrive via SSE — no page reload or manual polling required

**Success:** zero manual DNS/Traefik/Authelia steps. The user always knows where their service is in the pipeline.

### 7.2 Add a new VPN peer

1. Developer clicks "Add peer" → enters name
2. Vaier generates WireGuard keys, assigns IP from subnet, writes config
3. Developer downloads QR code or docker-compose file
4. Peer is running; developer can see handshake status in Vaier

### 7.3 Check for stale containers

1. Developer opens VPN Peers view
2. Containers with available updates show an "update available" badge
3. Developer updates container manually on the peer host

### 7.5 First-time setup

1. User creates `.env` with `VAIER_DOMAIN`, `ACME_EMAIL`, `VAIER_AWS_KEY`, `VAIER_AWS_SECRET` (and optionally `VAIER_PUBLIC_HOST` / `VAIER_PUBLIC_IP` when not on EC2)
2. `docker compose up -d` — `authelia-init` writes placeholder Authelia config, the stack comes up without Authelia crash-looping
3. Vaier auto-creates `vaier.<domain>` (and `login.<domain>`) records in Route53, writes the real Authelia config, and bootstraps an admin user whose one-time password is written to `authelia/config/.bootstrap-admin-password`
4. User reads the bootstrap password, logs in at `https://vaier.<domain>`, changes it, and deletes the file

## 8. Technical Constraints

- **Stack is fixed:** WireGuard (linuxserver), Traefik, Authelia, Redis, AWS Route53
- **Sub-image versions are pinned** in `docker-compose.yml`; bumps are deliberate, tested, and released with a new Vaier version (no floating `:latest` tags for upstream images)
- **No database:** all state is file-based (WireGuard/Traefik/Authelia configs) or cloud-based (Route53)
- **Single WireGuard server:** multi-server mesh is out of scope
- **Java 21 / Spring Boot 3.5.5:** backend language and framework are fixed
- **Docker socket required:** container discovery requires access to `/var/run/docker.sock` or TCP Docker API on peers

---

## 9. Out-of-Scope Integrations

The following are explicitly out of scope to avoid feature creep and overlap with dedicated tools:

- Cloudflare / other DNS providers
- nginx / Caddy as reverse proxy alternatives
- Keycloak / other OIDC providers
- Kubernetes
- Secrets management (Vault, etc.)

---

## 10. Success Criteria

Vaier is "done enough" when:

1. A developer can add a new Docker container on any VPN peer and have it publicly accessible via HTTPS subdomain in under 2 minutes with no manual steps outside Vaier
2. All VPN peers can be managed (create, configure, delete) without editing any WireGuard config file
3. A launchpad page exists that works as a browser home page showing all services and their status
4. The operator is notified when container images have updates available
5. The full stack can be installed from scratch with `docker compose up -d` and a minimal `.env` file, with no further manual config file editing required

---

## 11. Open Questions

All original open questions have been resolved:

| # | Question | Decision |
|---|----------|----------|
| OQ1 | Should the launchpad be unauthenticated or protected? | Launchpad is public; admin UI is protected by Authelia. A dedicated `/launchpad/services` endpoint returns only DNS address and host address (no ports, auth state, or internal details). |
| OQ2 | Non-Docker Hub registries in v1? | No — Docker Hub only. GHCR / self-hosted are stretch goals for v2. |
| OQ3 | Pi-hole detection: automatic or env var? | N/A — Pi-hole removed from the project. |
| OQ4 | Update notifications: push or UI only? | UI only in v1. Webhook/email is a v2 consideration. |

---

## 12. Backlog

The backlog is tracked in [GitHub Issues](https://github.com/getvaier/vaier/issues). Feature specs for planned items are in the relevant section above (6.8–6.10). Bugs and smaller improvements are described directly in the issue.

"Server LAN CIDR" remaining follow-ups are closed by [#204](https://github.com/getvaier/vaier/issues/204) — `VAIER_SERVER_LAN_CIDR` passes through `docker-compose.yml`, and split-tunnel `UBUNTU_SERVER`/`WINDOWS_SERVER` peers' client-side `AllowedIPs` includes the resolved CIDR. The `wireguard-masquerade` sidecar's interface-name-agnostic `! -o wg0 -j MASQUERADE` rule covers the source-NAT step on the Vaier server (closes [#248](https://github.com/getvaier/vaier/issues/248) — the linuxserver `PostUp`'s `-o eth+` is a no-op on AWS's `ens5`).

