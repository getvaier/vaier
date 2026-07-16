# Product Requirements Document — Vaier

**Last updated:** 2026-07-01
**Status:** Living document
**Issues:** https://github.com/getvaier/vaier/issues — GitHub issues are part of the spec and represent confirmed requirements and bugs.

---

## 1. Overview

Vaier is a self-hosted infrastructure management tool for developers running a homelab. It eliminates the manual work of maintaining a WireGuard VPN server, reverse proxy, and DNS records by providing a single interface that wires everything together automatically.

The core value proposition: add a new Docker service anywhere on your VPN, select a subdomain, and Vaier handles DNS, reverse proxy, and HTTPS — end to end.

Vaier is a personal tool that will be open-sourced. It is not intended to compete with general-purpose infrastructure platforms (Portainer, Coolify, Rancher, etc.). It is opinionated about its stack: WireGuard + Traefik + Google sign-in (via oauth2-proxy) + AWS Route53.

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
4. Optionally wire in forward-auth (Google sign-in via oauth2-proxy)
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
  - **Reachability map — built then removed.** A third Infrastructure tab once lived alongside List and Map: first a decorative Cytoscape/cola force-directed network graph, then reimagined as a deterministic per-service request-path tracer ("why is service X unreachable") rendering each published service's chain of hops. It was removed entirely — it never addressed a real operator need, and the machine-icon / host-offline signals already cover health at a glance. Gone with it: the third tab, the `GET /published-services/topology` endpoint, the `GetServicePathsUseCase` / `ServicePath` / hop / hop-state domain model, and the vendored Cytoscape/cola libraries (`static/vendor/cytoscape/`). The Infrastructure page is back to **List · Map**.

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

#### Device category ✅

Every machine carries a **device category** (`domain.DeviceCategory`: `PHONE`, `LAPTOP`, `DESKTOP`, `SERVER`, `NAS`, `PRINTER`, `ROUTER`, `GATEWAY`, `IOT`, `CAMERA`, `MEDIA`, `GENERIC`) that decides which icon represents it. It is an **orthogonal, icon-only** attribute, explicitly distinct from `MachineType` — `MachineType` remains the routing concept (drives WireGuard client/server config), while device category never affects routing, keys, or exposure. `GENERIC` is the fallback.

- **Auto-detection** — `DeviceCategory.detect(name, machineType, lanRole)` resolves the category from signals in priority order: machine-name keyword (`fromName`, e.g. "synology" → NAS, "iphone" → PHONE), then (for scanned hosts) the guessed LAN role (`fromLanRole`), then the machine type (`fromMachineType`: `MOBILE_CLIENT` → PHONE, `WINDOWS_CLIENT` → LAPTOP, `UBUNTU_SERVER`/`WINDOWS_SERVER` → SERVER, `LAN_SERVER` → GENERIC), else `GENERIC`. The first non-null signal wins; never returns null.
- **Override + effective category** — an operator can pin an explicit **device category override**; the **effective device category** = override if set, else the detected one. Clearing the override reverts to auto-detection, and renaming a machine re-detects when there is no override. The domain pure-decides all of this; the per-domain service only reads/writes through driven ports.
- **Persistence (backward-compatible)** — for VPN peers the override is a new optional `deviceCategory` field in the per-peer `# VAIER:` metadata JSON (absent = no override); for LAN servers it is a new optional `deviceCategory` key in `lan-servers.yml`. Legacy configs without it simply have no override.
- **REST surface:**
  - `VpnPeerResponse` (`GET /vpn/peers`) gains `deviceCategory` (string, the **effective** category name, never null) and `deviceCategoryOverridden` (boolean).
  - `PATCH /vpn/peers/{name}/device-category` with body `{"deviceCategory": "<NAME>"}` sets the override; a blank/null value clears it; an invalid value → `400` (`DeviceCategory.fromString` throws, surfaced via the `ApiError` handler). Backed by `UpdatePeerDeviceCategoryUseCase` on `VpnService`.
  - `LanServerResponse` (`GET /lan-servers`) gains `deviceCategory` (effective, never null) and `deviceCategoryOverridden` (boolean).
  - The create-LAN-server request (`POST /lan-servers`) gains an optional `deviceCategory` field, letting the Add Machine modal pre-fill from a scan pick.
  - `PATCH /lan-servers/{name}/device-category` mirrors the peer endpoint (`UpdateLanServerDeviceCategoryUseCase` on `LanServerService`).
  - `MachineResponse` (`GET /machines`) gains `deviceCategory` (effective, never null).
  - The discovered-host DTO from the scan endpoint (`GET /lan-scan`, Enterprise) gains `deviceCategory` (string, derived read-only via `DeviceCategory.detect(hostname, null, guessedRole)` — never persisted).
- **UI** — the per-category icon (`deviceCategoryIconKind` in `vpn-peers.html`) drives the glyph for every machine on the List and Map tabs, replacing the old machine-type icon. The expanded machine card carries a **Device type** selector (`deviceCategorySelectHtml`): an "Auto-detect" option plus the twelve categories, with an "Auto-detected: …" caption when no override is set; choosing a category `PATCH`es the override, choosing "Auto-detect" clears it. The LAN-scan results picker shows each discovered host's device icon, and picking a host carries its derived category into the new LAN server via the `POST /lan-servers` `deviceCategory` field.

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
- **Out-of-date detection** — `GetVpnPeersUseCase` flags each peer's `configOutOfDate` by comparing its on-disk config against its **rendered config** (`WireGuardPeerConfig.isOutOfDate`, server render inputs resolved once per refresh). The Services-page card shows a **Config out of date** badge and styles the **Reissue config** button as primary. The comparison strips the `# VAIER:` metadata comment from both sides first (`stripVaierMetadata`) — that comment is Vaier-side metadata never installed into the tunnel, and it may carry a `deviceCategory` key that `generate()` omits — so editing a peer's device category, name, or description no longer falsely trips the out-of-date badge. A Reissue threads the stored device-category override through `WireGuardPeerConfig.reissue` so the override survives the re-render.

#### Show-once / reissue follow-ups (backlog)
- Startup drift scan: surface out-of-date peers proactively (a summary banner / count) rather than only per-card, so the operator notices without expanding each card.

---

### 6.2 Service Publishing ✅ (exists, core workflow)

The primary workflow: expose a Docker container as a public HTTPS subdomain.

**Current capabilities:**
- Discover containers with exposed ports on the Vaier server and VPN peers
- Publish a service: creates DNS CNAME record (pointing to the VPN server) + Traefik route + optional social-login middleware chain
- Toggle authentication on/off per service
- Check publish status (DNS propagated, Traefik active)
- Delete published service (removes DNS + Traefik route)
- Edit root path redirect on published services
- Auto-delete published services when a VPN peer or LAN server is deleted

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
- **Service cleanup on LAN-server deletion** — when a LAN server is deleted, all published services whose backend address equals that LAN server's `lanAddress` are automatically removed (DNS + Traefik routes). Mirrors the peer cascade: `LanServerService.delete` finds the matching reverse-proxy routes and removes each via `DeletePublishedServiceUseCase` before deleting the LAN-server record
- **Published services page cleanup** — consolidated host/status rows, hide discovered section when empty, replaced fragile optimistic auth toggle with server-side refresh
- **Publish rollback on failure** — if DNS propagation times out, Traefik route creation throws, or Traefik never picks up the new route, Vaier removes the CNAME (and, where applicable, the Traefik route) so no orphan records remain in Route53. Emits `publish-rolled-back` on the `published-services` SSE topic.
- **Contextual help + error explanations in the publish flow** ✅ (closes [#56](https://github.com/getvaier/vaier/issues/56)) — UX/observability pass over the existing publish flow; no new endpoints or concepts.
  - **Progress-step tooltips** — each Processing-card step ("DNS record created", "Waiting for DNS propagation…", "Activating reverse proxy route…") carries a native hover tooltip explaining what is happening and why, including the ~30–60s ETA for DNS propagation and that a stuck record rolls the publish back.
  - **Rejection reason surfaced on 400** — `POST /published-services/publish` and `POST /published-services/lan` return the rejection reason in the response body (the `400` body was previously empty). Since [#275](https://github.com/getvaier/vaier/issues/275) this is the shared `ApiError` envelope (the validation exception propagates to `GlobalExceptionHandler`); originally it was a bespoke `PublishError(String message)` record. The browser's `explainPublishError()` helper reads `.message` and shows that human message plus a suggested next step instead of a raw `HTTP <code>`, with status-keyed fallbacks for 409, 5xx, and network errors.
  - **Rollback surfaced to the operator** — the browser now handles the `publish-rolled-back` SSE event (which the backend already emitted but the UI ignored), telling the operator the DNS record + Traefik route were removed so they can safely retry.
- **LAN service publishing** ✅ (closes [#175](https://github.com/getvaier/vaier/issues/175)) — expose a LAN host (NAS, IPMI, printer, IoT) reachable through a relay peer's `lanCidr` *or in the Vaier server's own subnet* (see "server LAN CIDR" below), no Docker container required. The publish flow validates that the target IP falls inside some relay peer's `lanCidr` or the server LAN CIDR (`LanAnchor`), writes a DNS CNAME and a Traefik route whose backend is `http(s)://<lan-ip>:<port>`. For a relay-anchored target, cryptokey routing on `wg0` plus the relay's #170 forwarding deliver packets; for a server-anchored target, the Traefik container reaches it directly out the host's LAN/VPC NIC. Surfaces with a small "LAN" badge in the published-services list; relay-anchored routes use the target host as the launchpad direct-URL shortcut for on-LAN callers and a server-anchored route's host state is always OK.
- **LAN server registration (Docker optional)** ✅ (closes [#177](https://github.com/getvaier/vaier/issues/177), [#184](https://github.com/getvaier/vaier/issues/184), [#181](https://github.com/getvaier/vaier/issues/181)) — register any machine on a relay peer's LAN *or in the Vaier server's own subnet* (see "server LAN CIDR" below) as a `LAN_SERVER` machine, with optional Docker. With Docker on, Vaier scrapes its remote Docker socket through the relay (same `tcp://<host>:<port>` pattern as VPN peers) — or, for a server-anchored LAN server, directly from the Vaier container. With Docker off, the LAN server still appears on the Machines page and is publishable through the manual LAN-service flow. Registration validates that `lanAddress` falls inside some relay peer's `lanCidr` or the server LAN CIDR; the Add Machine modal asks only for the address. Persisted as YAML at `${VAIER_CONFIG_PATH}/lan-servers.yml` (legacy `lan-docker-hosts.yml` is auto-migrated on startup). V1 scope: insecure tcp 2375 only; no TLS/SSH yet. Backed by the unified `MachineType` taxonomy: `MOBILE_CLIENT`, `WINDOWS_CLIENT`, `UBUNTU_SERVER`, `WINDOWS_SERVER`, `LAN_SERVER`. A unified `GET /machines` endpoint returns all five in one list. Registration validates the address only (Docker need not be reachable yet); the operator then runs the host's **unified per-host setup script** from its card — see [Per-host LAN setup script](#per-host-lan-setup-script--closes-249) below.
- **Per-host LAN setup script** ✅ (closes [#249](https://github.com/getvaier/vaier/issues/249)) — one script the operator runs on a registered LAN server, `GET /lan-servers/{name}/setup.sh`, that **adapts to that host**: it opens the Docker engine API (native + snap, the same daemon.json/systemd-drop-in logic as before) when the host runs Docker, **and** installs static routes via its relay peer when it is relay-anchored. Routes cover the server LAN CIDR, the VPN subnet, and every *other* relay peer's `lanCidr` (so a host behind one relay can reach the Vaier VPC and — once #250 lands — other sites' LANs), persisted across reboots by a `vaier-lan-routes.service` systemd oneshot (`ip route replace … via <relay lanAddress>`, distro-agnostic, idempotent — mirrors the relay's `vaier-wg-relay-iptables.service`). Strict hex: the decision of what the script must do (effective Docker port, relay-anchor → gateway, routed-CIDR set, "relay has no LAN address" → 409, "nothing to do" → 404) lives in the pure domain `LanServerSetupScript.forHost(...)` / `routedDestinations(...)`; `LanServerService` only reads the `LanServer`, peer configs, server LAN CIDR and VPN subnet from driven ports and hands them over. This **replaces** the old generic `docker-setup.sh` (static classpath script + `--port`) — register-first, since registration never needed Docker reachable; the old endpoint and `scripts/lan-docker-setup.sh` are retired. Sibling-site LAN routes are installed but only carry traffic once the site-to-site mesh follow-up ([#250](https://github.com/getvaier/vaier/issues/250) — sibling-relay LANs in peer `AllowedIPs` + broadened relay forwarding) ships; the script header says so. The endpoint is on oauth2-proxy's **public** path (skip-auth) so `curl … | sudo bash` works on a fresh LAN host with no Vaier session — the script carries no secrets.
- **Server LAN CIDR — Vaier server as its own LAN router** ✅ — the Vaier server knows the CIDR of the network it sits on, so machines on it can be registered as LAN servers and have their services published *without a relay peer*. The value is **discovered**, not hand-configured by default: `ForResolvingServerLanCidr` reads the instance's own **subnet** CIDR from EC2 IMDSv2 (`network/interfaces/macs/<mac>/subnet-ipv4-cidr-block` — a default-VPC subnet is a `/20`, one per AZ). `VAIER_SERVER_LAN_CIDR` is a general **override** (it short-circuits IMDS, on EC2 too) — set it to widen the routed range, typically to the whole VPC CIDR (`172.31.0.0/16`) so machines in any AZ/subnet qualify, or to supply the value off EC2; anything that doesn't parse as a strict IPv4 CIDR (`Cidr.validateLanCidr`) is ignored, and the resolved value is memoized. `docker-compose.yml`'s `vaier` service passes the env var through (closes [#204](https://github.com/getvaier/vaier/issues/204)). Such a machine is anchored at `"Vaier server"` (`LanAnchor.VAIER_SERVER_NAME`) — it surfaces on the Machines page (`Machine.lanCidr` = the resolved CIDR), shows "via Vaier server", plots on the Map tab at the Vaier-server location with a "Behind Vaier server" label, and the Add Machine modal validates a typed LAN address by asking the domain (`GET /lan-servers/lan-anchor`, `ResolveLanAnchorUseCase`) rather than reimplementing CIDR containment in the browser. It is reachability-probed and Docker-scraped straight from the Vaier-side containers (vaier / traefik → docker bridge → host → the host's LAN/VPC NIC, which already works because Docker enables `ip_forward` on the host and masquerades the bridge network out the host NIC), and publishes a normal `isLanService` Traefik route. When an address is covered by both a relay peer's `lanCidr` and the server LAN CIDR, the relay peer wins. **Split-tunnel server peers can also initiate connections into the subnet** (closes [#204](https://github.com/getvaier/vaier/issues/204)): `WireGuardPeerConfig.generate` appends the resolved server LAN CIDR to `UBUNTU_SERVER`/`WINDOWS_SERVER` peers' client-side `AllowedIPs` (e.g. `AllowedIPs = 10.13.13.0/24,172.31.0.0/16`), so `wg-quick` installs a route for the server's subnet via `wg0` on the peer. The `wireguard-masquerade` sidecar installs an interface-name-agnostic `iptables -t nat -A POSTROUTING ! -o wg0 -j MASQUERADE` rule inside the wireguard container's netns (closes [#248](https://github.com/getvaier/vaier/issues/248)), so VPN-sourced packets exit the Vaier host with the host's LAN IP and replies come back — regardless of the host NIC's name. (The linuxserver `wg0.conf` `PostUp` only matches `-o eth+`, which is a silent no-op on hosts whose NIC isn't named `eth*`, e.g. AWS EC2's `ens5`.) Full-tunnel client peers already reach it via their default `0.0.0.0/0`. Mobile/client peers' `AllowedIPs` is unchanged (adding the CIDR would be redundant and risks confusing wg-quick's route table). Existing peers don't pick up the new `AllowedIPs` automatically — **Reissue** the peer (Services-page **Reissue config**, which preserves the keypair), or **Regenerate** to also rotate keys. See [Reissue config + out-of-date detection](#reissue-config--out-of-date-detection--closes-247). No Settings-UI field and no `vaier-config.yml` entry — env + IMDS cover the supported cases.
- **LAN server reachability check** ✅ (closes [#186](https://github.com/getvaier/vaier/issues/186), [#201](https://github.com/getvaier/vaier/issues/201)) — every registered LAN server is probed every 30s with a TCP connect to a small set of common ports (80, 443, 22). Any TCP response (handshake or RST) marks the host pingable; if every TCP probe times out, an ICMP echo (`/bin/ping -c 1`) fires as a fallback so printers, IoT devices and IPMI cards that don't expose any of those ports don't get falsely shown as red. A clean timeout plus no ICMP reply marks the host down. The Machines page combines that signal with the Docker socket scrape to colour the machine icon four ways: grey (not yet probed), green (host pingable; if Docker-enabled, scrape also OK), yellow (Docker host pingable but scrape failed), red (host not pingable). Cache changes publish a `lan-servers-updated` SSE event on the existing `vpn-peers` topic so the page updates without a manual refresh.
- **Peer-config lifecycle clarity** ✅ (closes [#271](https://github.com/getvaier/vaier/issues/271)) — the show-once / reissue / regenerate / out-of-date distinctions are now self-explanatory in the UI, with no behavioural change to the show-once security model. The create-success modal explains *why* the config vanishes ("For your security, this config is delivered exactly once and won't be shown again on its own") and points to **Reissue** to recover a lost config (keys preserved) vs **Regenerate** to rotate keys. The Reissue/Regenerate button tooltips now state what each does *and when to use it*; the regenerate-confirm modal points to Reissue as the non-destructive alternative; and the ⚠ out-of-date-config badge spells out what changed and what to do. Glossary reconciled — **Regenerate** is for replacing a compromised config, **Reissue** is the non-destructive way to recover one. Pure frontend copy.
- **Inline field help** ✅ (closes [#269](https://github.com/getvaier/vaier/issues/269)) — advanced form fields carry a small visible "?" affordance (shared `.help-tip` in `styles.css`) whose hover text gives a one-line plain-language explanation, so an operator who hasn't read the dev docs can tell what each field does. Covers **LAN CIDR** (Add Machine modal), and on the published-services page **path prefix**, **require sign-in (Google)**, **direct LAN URL**, **root path redirect**, **version endpoint**, and **hide-from-launchpad** — both in the Publish modal and the expanded service-detail rows (rendered via a `helpTip()` helper in `published-services.js`). Pure frontend; no API changes.
  - **Backlog:** the #269 inline tooltips could deep-link into the matching anchor on the Concepts page (e.g. the LAN CIDR "?" → `concepts.html#lan-cidr`). This PR adds the anchor support (each concept carries a stable `id` slug) and the Concepts tab; wiring each individual tooltip to its anchor is a follow-up.
- **In-app operator glossary (Concepts page)** ✅ (closes [#274](https://github.com/getvaier/vaier/issues/274)) — a new **Concepts** tab in the admin shell renders a trimmed, plain-language glossary of the terms an operator meets in the UI, grouped by area, each with a short definition and a one-line "why it matters". The copy is curated in the pure domain `OperatorGlossary.groups()` (the single source of operator-facing concept copy) over `Concept`/`ConceptGroup` records; `Concept.of(term, …)` derives a stable URL-safe slug from the term so each entry is deep-linkable via its `id` anchor (e.g. `concepts.html#lan-cidr`). Served by `GET /concepts` (`ConceptsController` → `GetConceptsUseCase` → `ConceptsService`). A drift test (`OperatorGlossaryTest`) asserts every concept term appears verbatim as a `**Term**` entry in `UBIQUITOUS_LANGUAGE.md`, plus no duplicate slugs and non-blank definition/why for every entry — so the in-app glossary can never name a term the canonical doc doesn't define. Loaded inside the already-authenticated admin shell; not a public endpoint.
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

A public, **viewer-adaptive** launchpad page listing published services as a clean grid of tiles — the visible set adapts to who is looking.

**Current state:**
- Separate page at `/launchpad.html`
- Each tile: service name, peer name, icon (with letter-avatar fallback), clickable link opening service in a new tab
- No management controls — purely presentational
- Suitable for use as a browser home page or new-tab page
- The launchpad shell (`/`, `/launchpad.html`, `/styles.css`, `/icon`, `/favicon`) and the anonymous data feed `/launchpad/services` are public (no auth required)
- Admin pages (`/admin.html`) and all admin APIs remain protected by social login (Google via oauth2-proxy → Vaier `/authz/verify`, which enforces admin)
- When the caller's public IP matches a VPN peer's WireGuard endpoint IP (i.e. they share a NAT gateway with that peer), and the service is hosted on that peer, the tile links to `http://lanAddress:port` directly — bypassing Traefik and its auth. Falls back to the public HTTPS URL otherwise. The caller IP is taken from `X-Forwarded-For` only when the direct peer (`RemoteAddr`) is inside the trusted proxy CIDR (`launchpad.trusted-proxy-cidr`, default `172.20.0.0/16`).
- **Auth-mediated tile URL** — when an auth-protected service has no direct-LAN bypass, the tile routes the browser through the auth gateway first (rather than the service URL itself), defeating PWA service workers (e.g. openHAB) that would otherwise serve a cached SPA from their own origin and trap the user in the app's own login screen because XHRs to `/rest/*` get answered with `401` rather than a cross-origin `302` redirect to sign-in. _(Originally an Authelia `https://login.<domain>/?rd=…` redirect; under social login the domain-wide oauth2-proxy SSO cookie carries the signed-in session across origins.)_
- **Per-service direct LAN URL opt-out** — the reverse-proxy route carries a `directUrlDisabled` flag (persisted in the Traefik YAML as `x-vaier-direct-url-disabled`). When set, the launchpad always serves the public HTTPS hostname for that service, skipping the direct LAN URL shortcut. This is required for services whose public origin differs from `http://lan:port` — Vaultwarden is the canonical case: its `DOMAIN` env is `https://vaultwarden.<domain>`, so opening the LAN URL yields a near-blank page because the Vue app won't initialise against a mismatched origin. Available as a checkbox both in the Publish service modal (so it can be set on creation) and on the expanded service details row. Also togglable via the unified `PATCH /published-services/{dnsName}` partial-update endpoint (`{"directUrlDisabled": ...}`); accepted on `POST /published-services/publish` as a `directUrlDisabled` body field.
- **Per-service hide-from-launchpad toggle** ✅ — the reverse-proxy route carries a `hiddenFromLaunchpad` flag (persisted in the Traefik YAML as `x-vaier-hidden-from-launchpad`). When on, the route stays reachable but the launchpad never renders a tile for it. Use case: internal APIs that back another service and don't need an operator-clickable tile. Togglable from the expanded service details row in the admin published-services page, or via the unified `PATCH /published-services/{dnsName}` partial-update endpoint (`{"hiddenFromLaunchpad": ...}`).
- **Domain-owned tri-state launchpad visibility** ✅ — `ReverseProxyRoute.launchpadVisibility(dnsState, hostState, AccessEntry viewer, ForResolvingServiceGroup serviceGroups)` returns `LaunchpadVisibility.{NOT_VISIBLE, VISIBLE_INACTIVE, VISIBLE_ACTIVE}`, consolidating every reason a route might be hidden, dimmed, or active — including per-viewer reachability via `isVisibleToLaunchpadViewer(viewer, serviceGroups)` (a NONE route is always shown; a SOCIAL route only when the viewer may reach it). The launchpad use case is a thin pass-through that filters `NOT_VISIBLE` and forwards the rest with `visibility` on `LaunchpadServiceUco`; the launchpad client only renders the value and never has to understand individual reasons. New visibility rules accrete inside the domain method, not in the application layer.
- **Domain-owned launchpad tile liveness dot** ✅ (issue [#208](https://github.com/getvaier/vaier/issues/208) follow-up) — the launchpad tile's status dot is driven by a **separate** presentation tri-state, `ReverseProxyRoute.launchpadLiveness(hostState)` → `LaunchpadLiveness.{LIVE, PENDING, OFFLINE}`, so it can read honestly during startup. `LaunchpadVisibility` still maps an `UNKNOWN` host to `VISIBLE_ACTIVE` (the tile stays clickable), but the dot no longer flashes green before reachability is confirmed: `OK` → LIVE (green), `UNKNOWN` → PENDING (grey — no probe yet), `UNREACHABLE` → OFFLINE (red). `PublishingService` populates `liveness` on `LaunchpadServiceUco` (serialized straight to JSON), and the launchpad client keys the dot class off `liveness` while keeping the link/dim logic keyed off `visibility`; the per-section and hero "online" counts now mean *confirmed reachable* (`liveness === 'LIVE'`).
- **Branded offline page for unreachable backends** ✅ — when a published service's backend is down, Traefik no longer serves its bare black "Bad Gateway" page; visitors get Vaier's branded **offline page**. A shared Traefik `errors` middleware (`vaier-errors`) catches 502/503/504 and forwards to an http service (`vaier-error-pages`) pointing at the Vaier container, which serves `GET /error-pages/{status}` — a self-contained HTML page (inline CSS, no cross-origin asset links) that names the unavailable service from `X-Forwarded-Host`, shows a friendly message, and offers Retry and Back-to-launchpad links. The status→title/message mapping is the domain `GatewayError` record. The middleware is attached to every router `addReverseProxyRoute`/`addLanReverseProxyRoute` creates, and an idempotent startup backfill (`TraefikReverseProxyAdapter.backfillErrorPages`, on `ApplicationReadyEvent`) appends `vaier-errors` to every pre-existing router and ensures the shared infra exists — without touching existing auth/redirect middlewares, load-balancer servers, or `x-vaier-*` metadata. Web layer mirrors the icon shape: `GetOfflinePageUseCase` → `OfflinePageService` → `OfflinePageController`.
- **Branded offline page when Vaier itself is down** ✅ — the offline page above is served *by Vaier*, so it can't help when the Vaier container itself is down (Traefik then falls back to its bare "Bad gateway", and Vaier's own routers — defined on its container's labels — vanish while the self-published file-provider router still points at the dead backend). A separate, always-up `vaier-offline` service (tiny pinned nginx, `offline/html` + `offline/default.conf` bind-mounted) stands in: it serves one self-contained branded page (matching the offline-page styling, HTTP 503, 15s auto-refresh) for any path. A **low-priority Traefik fallback router** (`priority=50`) for the Vaier host sits above the lingering file router (30) but below Vaier's real docker routers (100/200), so while Vaier is up its own routers win and when the container stops this becomes the top match. A `vaier-down` `errors` middleware on the same container also covers the transient "container up but returning 5xx mid-restart" window. Infra-only (docker-compose + static assets); no Java change. A `.gitignore` exception keeps `offline/default.conf` tracked despite the blanket `*.conf` ignore.
- **Version visible under Settings → About** ✅ — the running Vaier build version is surfaced so the operator always knows which build is deployed. The Maven `build-info` goal bakes `project.version` into the jar as Spring `BuildProperties`; `ForReadingAppVersion` / `BuildPropertiesVersionAdapter` reads it (falling back to `dev` when absent), exposed via `GetAppVersionUseCase` on `SettingsService` and `GET /settings/version`. The Settings page renders an **About** card showing `v<version> · <Edition>` (edition from `GET /license`). Replaced an earlier always-on corner badge on the machines page that was too intrusive.
- **Host-down indicator on the launchpad** ✅ (closes [#208](https://github.com/getvaier/vaier/issues/208)) — `Server.State` is now tri-state: `OK`, `UNREACHABLE`, `UNKNOWN`. A `VISIBLE_INACTIVE` launchpad tile (host confirmed unreachable) shows an 11px red status dot in its top-right corner with a "Host offline" tooltip *and* drops its `href` so a click can't follow the dead link. An `UNKNOWN` host stays `VISIBLE_ACTIVE` (we don't know it's down) — no dot, normal link — but the services card icon goes grey (`icon-unknown`) instead of misleadingly green. `ReverseProxyRoute.hostState(...)` consumes the LAN-reachability snapshot (`Map<String, Reachability>`): `DOWN` → `UNREACHABLE` (regardless of relay), `UNKNOWN` → `UNKNOWN` when the route is otherwise routable, `OK` → existing relay / server-LAN-CIDR check. A dead relay tunnel still beats `UNKNOWN`. The snapshot comes from a new `ForCheckingLanReachability` driven port, implemented by `InMemoryLanReachabilityCache` (`adapter/driven/`) — LAN-reachability state was moved out of `LanServerReachabilityService` into that cache adapter so the cross-service read goes through a domain port rather than a use-case back-channel; the orchestrator service writes through a sibling `ForRecordingLanReachability` port. A confirmed UP↔DOWN transition invalidates the published-services cache and publishes `service-updated` on the `published-services` SSE topic, so the launchpad and services pages re-fetch immediately. The `Reachability` enum lives in the domain.
- **Public, viewer-adaptive launchpad** ✅ (closes [#207](https://github.com/getvaier/vaier/issues/207)) — the launchpad is public but adapts its tile set to the **viewer**, replacing the earlier binary "authenticated?" flag with the actual resolved identity. It consumes two sibling endpoints. `GET /launchpad/services` stays anonymously reachable and returns the public-only subset (auth mode `NONE`). `GET /launchpad/services-authenticated` sits on the **identity-optional router** (oauth2-authn only — no forced sign-in, no admin gate): a valid session gets its `X-Auth-Request-*` headers injected and the endpoint resolves the caller's **access entry** and returns the public tiles *plus* every social service that identity may reach; an anonymous request gets `401` and the page falls back to the public feed. Visibility is decided per-viewer by `ReverseProxyRoute.isVisibleToLaunchpadViewer(viewer, serviceGroups)` — admins see all social services, a user sees a social service iff its **access rule**'s allowed groups intersect the user's **access groups**, and anonymous/pending/unknown identities get public only. The launchpad path is a pure read: it resolves the viewer via `ResolveViewerUseCase` (read-only; unknown/blank → empty) and never creates a pending entry (only `/authz/verify` does). Under social login, auth on a service means "internal — sign in first"; a social service's tile now surfaces to exactly the users allowed to reach it. Live updates come from a third, public endpoint: `GET /launchpad/events` (also anonymously reachable) is a **signal-only** SSE stream — it fires the same event names as the admin's `/published-services/events` but with an empty payload, so a logged-out visitor's tiles refresh on change without the private service **subdomain** the full stream carries in its data ever leaking. `SseEventPublisher` fans each publish out to full subscribers (with data) and signal-only subscribers (data stripped); the detailed `/published-services/events` stays on the identity router for the admin publish-progress toasts that need the subdomain.
- **`GET /users/me` for the viewer-adaptive topbar** ✅ — `AuthRestController` serves `/users/me` behind the identity-optional router (oauth2-authn only), reads the caller identity from oauth2-proxy's `X-Auth-Request-User`/`-Name`/`-Email`/`-Connector`/`-Connector-Uid` headers, and returns `MeResponse(username, displayname, email, isAdmin, logoutUrl, loginUrl, provider, providerUserId)`. The launchpad renders its topbar from this — greeting the signed-in identity by photo (else name) and showing the admin nav only when `isAdmin`. An anonymous caller is stopped at the router with `401`, which the page treats as logged-out. Because the launchpad is a viewer's main authenticated touch-point and never crosses `/authz/verify`, `/users/me` also **captures** the presented display name and last-used provider onto the caller's existing access entry (`CaptureViewerIdentityUseCase`) — so a user who only ever uses the launchpad still has a named, provider-badged **Users** card. Capture is a no-op write when nothing changed, never wipes a stored value on a blank/absent header, and (unlike `/authz/verify`) never creates a pending entry for an unknown email — first-sighting stays on the forward-auth path.
- **Domain-owned launchpad tile name + alias** ✅ — `ReverseProxyRoute.launchpadDisplayName(baseDomain)` decides the tile label: operator-supplied `launchpadAlias` wins (persisted as `x-vaier-launchpad-alias`), otherwise the final segment of `pathPrefix` for path-based routes (so `services.example.com/grafana` displays as `grafana`, not `services/grafana`), otherwise the first DNS label. The subdomain moves into the tile's sub-line beside the peer when it differs from the display name. Editable via the Display name input in the admin published-services details panel and the unified `PATCH /published-services/{dnsName}` partial-update endpoint (`{"launchpadAlias": ...}`).
- **Domain-owned icon lookup identity** ✅ — `ReverseProxyRoute.launchpadIconQuery()` decides what the launchpad sends to `/icon`: host-only routes resolve a single icon per FQDN; path-based routes carry both host and `pathPrefix` so siblings on one subdomain (e.g. `services.example.com/grafana` and `…/jenkins`) cache separately. The icon fetcher accepts a `pathPrefix` query param and probes `https://host{pathPrefix}/` first for HTML/`favicon.ico` discovery before falling back to the FQDN root; the CDN-by-name fallback uses the final path segment when present. Closes the regression where every path-based sibling shared a single cache key and fell back to the letter avatar.
- **Filesystem-backed icon cache** ✅ — a resolved service icon is now fetched online at most once, then served from disk across restarts. `IconService` checks the in-memory map, then a new `ForStoringIcons` driven port (filesystem store at `icon.cache.path`, default `/icons`, mounted read-write in `docker-compose.yml`), then resolves online and persists the positive result to disk (negatives are remembered only in memory so a once-dead host can recover). The shared `IconResolution.cacheKey(host, pathPrefix)` addresses both tiers so memory and disk never drift. `FilesystemIconStoreAdapter` stores each icon as two files named by the SHA-256 of the key (`<hash>` bytes + `<hash>.ct` content-type), written via temp-file-then-atomic-move, and degrades gracefully (disables itself, never throws) when the directory can't be created/written — mirroring the geoip DB. The `Icon` value object moved to the domain (`net.vaier.domain.Icon`).
- **Backing container image on the launchpad tile** ✅ (closes [#210](https://github.com/getvaier/vaier/issues/210)) — hovering a tile reveals the Docker image and version of the container behind the service, so an operator can see what's running without inspecting the host by hand. `ReverseProxyRoute.backingContainer(...)` resolves a route to its container among the discovered peer / Vaier-server / LAN-server containers: a peer route matches by VPN IP, a Vaier-server route by container name (or port), a LAN-service route by LAN address. The image/version ride along on `LaunchpadServiceUco`; the launchpad renders them in a small styled tooltip. `PublishingService` caches the discovered-container snapshot for 60s (`containerImageSnapshotTtlMillis`) so the launchpad's aggressive reloads (tab focus, SSE) don't re-query every Docker daemon over the VPN. A service published as a bare LAN host:port has no container — its tile simply omits the version.
- **LAN-native service version** ✅ (the [#210](https://github.com/getvaier/vaier/issues/210) follow-up) — a published service can carry an operator-configured **version endpoint**: a URL (a path on the service, or an absolute URL) plus a property name. The version is read via the `ForProbingServiceVersion` driven port — invoked from `ReverseProxyRoute.probeVersion(...)` so the domain owns the port call — by GETting the endpoint and extracting a `property="value"` label from the response (Prometheus text-exposition style). The probed version takes precedence over a backing container's image tag and rides along on `LaunchpadServiceUco`, so a service running natively on a LAN machine (no discoverable container) still reports its version in the tile tooltip. Probes run concurrently and `PublishingService` caches them for 60s (`versionProbeSnapshotTtlMillis`). Configured via the Version endpoint inputs on the published-services details panel and the unified `PATCH /published-services/{dnsName}` partial-update endpoint (`{"versionEndpoint": "...", "versionProperty": "..."}`); persisted in the Traefik YAML as `x-vaier-version-endpoint`.
- **Backing image + version on the Services-page card** ✅ (closes [#245](https://github.com/getvaier/vaier/issues/245)) — the same image/version that the launchpad tile shows on hover is also surfaced inline on the Services-page service card as a `Version` detail row (image as a monospace code chip, dim `version <x>` beside it). Same resolution as the launchpad: `ReverseProxyRoute.backingContainer(...)` against the discovered container snapshot, with a configured `versionEndpoint`'s probed value taking precedence. Threaded through `PublishedServiceUco.image` / `version` so the browser doesn't re-derive either. The row is omitted when neither value is set (e.g. bare LAN host:port).

**Launchpad backlog:**
- **Live updates for the public launchpad** — the launchpad's SSE stream (`/published-services/events`) is not on the public or identity-optional router, so it falls through to the admin catch-all. An anonymous (or non-admin) launchpad therefore renders once and won't receive push updates when a service goes up/down or is (un)published; it only refreshes on reload / tab focus. Exposing a viewer-scoped SSE feed on the identity-optional (or public) tier would let anonymous and non-admin launchpads live-update without leaking admin-only detail.

---

### 6.4 DNS Management

Vaier supports two DNS modes, inferred from the presence of AWS credentials. There is no `VAIER_DNS_PROVIDER` env var: `ConfigResolver.getDnsProvider()` derives `ROUTE53` when both `awsKey` and `awsSecret` are present and `MANUAL` otherwise.

**Route53 mode ✅ (default when AWS keys present).** Vaier automates DNS through the AWS Route53 API: it auto-creates `vaier.<domain>` on first boot and a CNAME per published service. Backed by `Route53DnsAdapter` and the `ForPersistingDnsRecords` / `ForValidatingAwsCredentials` ports. There is no UI page for general-purpose record CRUD — the REST endpoints exist (`/dns/*`) but the navigation page was never built. Service publishing is the primary path; advanced records are managed in the AWS console.

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

### 6.6 Access Management ✅ (exists)

Manage who can sign in and what they can reach, from the **Users** page — a single list of social identities (access entries). With Authelia removed from the running stack, this is the live identity/access surface — see §6.17 for the full social-login model (roles, access groups, pending approvals, last-admin protection). Per-service group gating is implemented via the access store rather than Authelia `access_control` rules.

**Legacy Authelia user management** — the local-password user-management UI (list / create / delete / edit-email / edit-display-name / edit-groups + the group manager), its `/users*` and `/groups*` REST endpoints, and the `AddUser`/`DeleteUser`/`UpdateUserEmail`/`UpdateUserDisplayName`/`GetUsers`/`GetGroups`/`UpdateUserGroups`/`DeleteGroup` use cases were removed once names and email became provider-owned (Google). The `AutheliaUserAdapter` / `ForPersistingUsers` / `ForGettingUsers` ports and the `User` entity have since been deleted along with the rest of the dead Authelia Java code (see §6.17). The self-service **My Page** profile screen (`mypage.html`) was removed with it — the topbar shows the name read-only. In-UI password change was already removed with #305 step 3b (social-login users have no Vaier password).

---

### 6.7 Backup / Restore 🔲 (deferred to V2, tracked in [#153](https://github.com/getvaier/vaier/issues/153))

Export and import the full Vaier configuration as a snapshot.

> **Not the same as Fleet Backup (§6.19).** This section is the **Backup snapshot** — an export of *Vaier's own configuration*. It is a separate feature from **Fleet backup** (§6.19), which backs up the *fleet's machines* to a NAS borg repository. The two share no vocabulary; don't conflate them.

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

Vaier ships an SMTP notifier that carries Vaier's admin alert emails (machine up/down, disk pressure, new access requests). Settings and the password are stored in `vaier-config.yml` (the password owner-only, alongside `awsSecret`; read back via `ForReadingStoredSmtpPassword`, implemented by `VaierConfigFileAdapter`). (It previously also powered Authelia's password-reset mail; Authelia has been removed entirely.)

**What's implemented:**
- Settings → *Email notifications* form with host, port, username, password, sender, and a "Send test email to …" recipient field.
- **Send test email** button does a full AUTH + roundtrip send via Jakarta Mail so misconfigurations surface without touching the auth layer.
- **Save** verifies credentials against the SMTP server *before* storing them. On failure the REST endpoint returns HTTP 400 with the upstream SMTP error. The password is persisted to `vaier-config.yml` (owner-only); saving no longer writes any Authelia notifier block or restarts a container — the Authelia integration is gone entirely.
- Password field can be left blank on save/test to reuse the stored value, so host/sender/etc. can be edited without retyping the secret.
- **Server machine up/down alerts** ([#173](https://github.com/getvaier/vaier/issues/173)): two 30s schedulers — one watching WireGuard handshake age for `UBUNTU_SERVER`/`WINDOWS_SERVER` peers, one watching the LAN reachability TCP probe for `LAN_SERVER` machines. Mobile/Windows clients are excluded — their disconnects are routine user behaviour. On a state change either watcher emails every **admin**-role **access entry** with subject `[Vaier] <name> is now <connected|disconnected>` and a body containing the machine's name, type, last handshake (or last-seen timestamp for LAN servers), LAN address, and a link back to `vaier.<domain>/vpn-peers.html`. Per-machine state is in-memory; the first observation after Vaier startup is treated as a baseline so a restart never produces a notification storm. No quiet-hours setting — alerts fire 24/7.
- **Reachability debounce for LAN servers**: a probe result must hold for 3 consecutive 30s cycles (≈60s of consistency) before the published cache flips and an email goes out. Dampens both the WireGuard tunnel warmup window after a Vaier restart (no false-down email when it takes one cycle for the relay handshake to complete) and ordinary network flapping (a single transient timeout never propagates). The UI shows the icon as grey ("warming up") until the first state confirms.
- **Last-seen timestamp inside the card** ([#173](https://github.com/getvaier/vaier/issues/173)): every machine's expanded card has a "Last Seen" detail row derived from the latest handshake (or the latest successful LAN reachability probe), updated live by the `peers-stats` SSE stream so the value stays current without a manual refresh. The header row itself signals liveness through the machine-icon colour rather than a separate widget.
- **Host disk-pressure alerts** ✅ — originally a dedicated local watcher reading the Vaier host's own filesystem directly; fully retired in favour of a single alerting path for the whole fleet. See **#316** below: the Vaier host is now covered by `RemoteDiskWatcher` over SSH-to-self exactly like any other machine, so it no longer double-notifies alongside a local watcher. The entire local host-disk-reading stack was removed once the watcher was gone and nothing else consumed it — `DiskUsageWatcher`, `NotifyAdminsOfDiskPressureUseCase`, `GetHostDiskUsageUseCase`, `HostMonitoringService`, the `ForReadingDiskUsage` port and its `HostDiskUsageAdapter` (which read the host root via `Files.getFileStore` over the `VAIER_HOST_ROOT_PATH` bind mount), and the `domain.DiskUsage` value object are all deleted. What survives is the threshold config — `diskMonitorThresholdPercent` in `vaier-config.yml` (default 85, valid 1–99, validated in `domain.VaierConfig`), exposed via `ConfigResolver.getDiskMonitorThresholdPercent()` and editable through `PUT /settings/disk-monitor` (`UpdateDiskMonitorSettingsUseCase` on `SettingsService`) — which now governs remote disk pressure across the fleet. Since **#325** it is the **fleet-wide fallback**: it judges every filesystem whose **disk watch** carries no threshold of its own.
- **Every filesystem, watched or muted, with its size** ✅ (closes [#325](https://github.com/getvaier/vaier/issues/325)) — the watcher read `df -P /`: the root filesystem and only the root filesystem. On the NAS `/` is the fixed-size 2.3 GB DSM system partition, 88% full by design and never moving, while `/volume1` (11.6 TB, holding every borg backup) was invisible to Vaier and could have filled to 100% without a word. Both the alert email and the Explorer's `disk` entry showed the useless 88%.
  - **`RemoteDiskUsage` is now one record per filesystem** — `(machineName, device, mountPoint, sizeKb, usedKb, availableKb, usedPercent)` — and `DF_COMMAND` is the unscoped `df -P`. `parseList(machineName, dfOutput)` replaces `parse`: **total**, like `Archive.parseList` — bad input, a header-only run, a `df` that failed all yield an **empty list**, never an exception, and one unparseable row is skipped while its siblings are kept. The mount point is taken as the rest of the line after the `Capacity` column, so a mount point with spaces survives.
  - **Which rows are disks at all is a domain decision** on the entity: `RemoteDiskUsage.isPseudoFilesystem(device, mountPoint)` drops kernel/in-memory mounts (tmpfs, devtmpfs, proc, sysfs, cgroup, squashfs, overlay, aufs…), mounts under kernel/container plumbing (`/proc`, `/sys`, `/dev`, `/run`, `/snap`, `/var/lib/docker/`…), and the `none` bind-mounts Docker's aufs driver creates — eight on the NAS, every one an alias of `/volume1`. Without it the NAS reports sixteen filesystems, nine of them the same volume, and one full volume raises nine alerts.
  - **Readings carry SIZE, not just a percentage.** `sizeHuman()`/`availableHuman()` render 1024-byte blocks as IEC binary units (`10.8 TiB`, `6.6 TiB free`) — the same divisors and honest unit names `df -h` prints, so the operator can reproduce the number on the host. The alert subject became `[Vaier] NAS /volume1 is at 91% full (10.8 TiB, 1.0 TiB free)`.
  - **Disk watch** — `domain.DiskWatch(machineName, mountPoint, watched, thresholdPercent)` and the `domain.DiskWatches` collection: per machine, per filesystem, watched or **muted**, optionally at its own threshold. Keyed on machine **and** mount point (joined on NUL, a byte neither a machine name nor a POSIX path can hold), because `/` on the NAS and `/` on Apalveien 5 are two different disks with two different verdicts. **The default is watched, at the global threshold** (`DiskWatch.watchedByDefault`, `DiskWatches.forFilesystem` never returns empty) — deliberately not invertible: the failure this issue fixes is *silence about the disk that matters*, so an unconfigured filesystem nags rather than hides, and muting is a decision someone takes rather than inherits.
  - **The breach verdict is a domain decision**: `RemoteDiskUsage.breaches(DiskWatch, globalThreshold)` resolves mute → per-mount threshold → global fallback. The alert email and the Explorer both call it, so they cannot disagree about whether a disk is in trouble.
  - **Persistence** — new driven port `ForPersistingDiskWatches` (`getAll` / `save`) with `DiskWatchFileAdapter` writing `${VAIER_CONFIG_PATH}/disk-watches.yml` under a root `watches:` key. No secrets, so a plain tolerant SnakeYAML round-trip like the backup adapters. **Only the exceptions are stored**; an absent file is the healthy first-boot state. A malformed entry is skipped with a warning rather than aborting the load, and an absent `watched` key reads as *watched* — not even a truncated file can silently unwatch a disk.
  - **Trackers are keyed on machine AND mount point.** `RemoteDiskPressureTracker` and `RemoteDiskForecastTracker` were machine-keyed, which was defensible only while a machine had one disk. It is not now: the NAS's `/` sits permanently above the threshold, so a machine-keyed tracker would already read "in pressure" and `/volume1` crossing would be swallowed as "no change" — the exact silence this issue is about. Likewise a machine-keyed `DiskFillHistory` would fit its least-squares slope through a sawtooth of unrelated disks. `DiskFillForecast` and `DiskFillForecastCleared` gained a `mountPoint`, and the forecast hands off to the pressure alert at *this filesystem's* resolved threshold.
  - **Application + REST** — `GetMachineDiskUsageUseCase.getDiskUsage` returns `List<MachineFilesystemUco>` (device, mount point, raw `*Kb` block counts, human `size`/`available`, `usedPercent`, the **effective** `thresholdPercent`, `watched`, `aboveThreshold`), so `GET /machines/{machine}/disk` returns a **list**. New `SetDiskWatchUseCase` + `GetDiskWatchesUseCase`, both on the existing `MachineService` (one service per domain concept), behind `PUT /machines/{machine}/disk/watch` with `{mountPoint, watched, thresholdPercent}` — the **mount point travels in the body**, because a mount point is full of slashes and a path variable carrying them is a routing/encoding bug waiting to happen. Both endpoints are non-whitelisted paths under `/machines`, so they sit behind the admin auth chain; nothing anonymous. A `df` that yields no real filesystem is still a `DiskUnreadableException` → **502**, never an empty list ("this machine has nothing to watch" would be the same silence again).
  - **Explorer** — the `disk` Inspector lists every filesystem: mount point + device (mono, they're coordinates), size and free space, a usage meter with a **threshold tick**, and a watch control (mute switch + threshold input). A **muted filesystem** keeps its meter but loses its tick — nothing is being judged. Saving a watch re-reads the machine's disks rather than recomputing locally, because the verdict is the server's.
- **Disk-fill forecast (early-warning alert)** ✅ — a second, forward-looking consumer of the same `df -P` readings the disk-pressure watcher already takes (no extra SSH round-trip). Per **filesystem** (per machine until #325), `RemoteDiskWatcher` feeds each `usedPercent` and `clock.instant()` into a `RemoteDiskForecastTracker` (sibling of `RemoteDiskPressureTracker`), which holds a `DiskFillHistory` ring buffer (K=12 samples) and a `DiskPressureTracker` keyed on the early-warning boolean. `DiskFillHistory.forecast(machineName, mountPoint)` fits a **least-squares** line through the retained samples and returns a `DiskFillForecast` (mount point, current %, fill rate %/h, **runway** as a `Duration`) — empty when there is too little signal (< 3 samples, span < 15 min, or slope ≤ 0 = flat/draining/infinite runway). `DiskFillForecast.warrantsEarlyWarning(levelThreshold)` is the gate: `runway < FORECAST_HORIZON (fixed 24h) && currentPercent <= levelThreshold` — where `levelThreshold` is the threshold *this filesystem* is judged against (its **disk watch**'s own, or the global one), already resolved by the caller, so the forecast hands off at exactly the level the pressure alert fires at. It only warns while below that threshold, pages once, and then hands off to the level alert (never both at once). The tracker reports `CROSSED_ABOVE`/`CROSSED_BELOW` on the gate boolean, and — keeping the decision in the domain — splits a `CROSSED_BELOW` by *why* it flipped: still `<= levelThreshold` (drained, or fill slowed so runway rose back over the horizon) is a **genuine recovery** and yields a runway-free `DiskFillForecastCleared` (machine + mount point + current percent, "no longer trending toward full, now at N%"); `> levelThreshold` is a **hand-off** to the disk-pressure alert and yields nothing, so admins never get a contradictory "cleared" and "is 86% full" at the same poll. The `Observation` carries `earlyWarning` and `cleared` payloads; the watcher just sends whichever is present. Notifies via a new `NotifyAdminsOfDiskFillForecastUseCase` (`notifyAdminsOfDiskFillForecast(DiskFillForecast)` / `notifyAdminsOfDiskFillForecastCleared(DiskFillForecastCleared)`) implemented on the existing `NotificationService`, reusing the `sendToAdmins` SMTP path. `Clock` is injected (bean `ClockConfig.systemClock()`, `Clock.systemUTC()`) so tests feed a stepped clock a rising series deterministically. Baseline-quiet on first observation, and the failed-/unparseable-`df` paths `return` before the forecast feed, so a transient failure records no sample. Distinct from **remote disk pressure**: **runway**/trend ("will be full") vs level ("is full"). Follows the strict-TDD, domain-owns-the-decisions discipline: slope, runway and the gate all live in the domain; the watcher only orchestrates.
- **New pending access-request alert** ✅ — when `UserService.verify()` sees a Google identity for the first time, it auto-creates a `PENDING` `AccessEntry` and, only in that new-pending branch, notifies admins via the `ForNotifyingAdmins` driven port (`notifyNewPendingIdentity(email)`), implemented by `NotificationService`. The email content is rendered by the `domain.PendingIdentity` value object (subject `[Vaier] New access request awaiting approval`; body names the email and links to `vaier.<domain>/admin.html#users`), mirroring `PeerSnapshot`. Recipients are the **admin**-role **access entries** (`accessStore.getEntries()` filtered by `AccessEntry::isAdmin`), reusing the same SMTP path as the other alerts, so it stays silent when SMTP is unconfigured or there are no admins. Because `verify()` runs on the Traefik forward-auth hot path for every request to a social-gated service, the send is non-blocking and exception-safe: the notifier method is `@Async` (`@EnableAsync` on `VaierApplication`) and the call site in `UserService` swallows/logs any failure, so a misbehaving notifier can never add latency to or throw into the access decision. It does not fire for existing entries, repeat sign-ins by the same pending user, or allowed decisions. The cross-service cycle (NotificationService reads admins via the access store, UserService notifies via `ForNotifyingAdmins`) is broken with `@Lazy` on the UserService dependency.

**Known gotcha:** Gmail requires an **App Password** (not the account password) when 2FA is on. The pre-save verification catches this cleanly — save is rejected with the Gmail `534 5.7.9 Application-specific password required` message.

**Host-monitoring follow-ups (backlog):**
- Monitor host CPU and memory pressure alongside disk, with their own thresholds and alerts.
- ~~Per-mount monitoring (not just the host root) — e.g. a separate data volume — each with its own threshold.~~ **Shipped** in [#325](https://github.com/getvaier/vaier/issues/325): every real **filesystem** is read, watched and alerted on, each with its own **disk watch** (muted or watched, at its own threshold or the fleet-wide fallback). See the entry above.
- ~~An in-UI disk widget so the operator sees current disk usage without waiting for an alert email~~ — the **level** half shipped in [#323](https://github.com/getvaier/vaier/issues/323) slice C and grew into the full picture in [#325](https://github.com/getvaier/vaier/issues/325): a machine's **disk** entry in the **Explorer** reads `df` on demand (`GET /machines/{machine}/disk`) and lists every filesystem with its size, free space and usage against its resolved threshold, with the domain's own `breaches` verdict — and is where a filesystem is muted or given its own threshold (`PUT /machines/{machine}/disk/watch`). Still open: surfacing the **disk-fill forecast** (**runway** + fill rate) beside it — that needs a *history* of samples, which today is private state inside the scheduled `RemoteDiskWatcher`'s `RemoteDiskForecastTracker`, so a single on-demand reading cannot produce it.
- Make the **forecast horizon** configurable (it is a fixed 24h constant today), and/or expose the per-machine fill-rate history as a trend sparkline.

---

### 6.10 First-Run Setup Wizard — removed ✅ (closes [#48](https://github.com/getvaier/vaier/issues/48), [#145](https://github.com/getvaier/vaier/issues/145), [#161](https://github.com/getvaier/vaier/issues/161))

The in-app wizard at `/setup.html` was deprecated on 2026-04-23 (a tester walking through the README found it unreachable when the four required env vars were populated, and not documented when they weren't). It was deleted on 2026-05-04 along with `SetupRedirectFilter`, `SetupRestController`, the three setup use case interfaces, and `SetupService`. Removal also retires the unauthenticated `/api/setup/*` surface that #145 flagged as a race-condition admin-claim window. First-run is now exclusively the env-var path documented in `README.md`.

### 6.11 Zero-touch first-run DNS boot ✅ (implemented 2026-04-23, closes [#163](https://github.com/getvaier/vaier/issues/163), [#164](https://github.com/getvaier/vaier/issues/164))

- **Auto-creates `vaier.<domain>` on first boot.** Vaier resolves the server's public address in order: `VAIER_PUBLIC_HOST` (CNAME target) → `VAIER_PUBLIC_IP` (A target) → EC2 IMDSv2 `public-hostname` (CNAME). If none resolve and the record is already missing, Vaier logs a clear instruction and exits the lifecycle step without crash-looping — the rest of the stack stays up so the operator can fix .env and restart.
- **First-boot auth is now zero-touch via oauth2-proxy** — `oauth2-proxy-init` renders oauth2-proxy's config into `./oauth2/config` before oauth2-proxy starts, and the access store seeds the **configured administrator** (`VAIER_ADMIN_EMAIL`), so the operator signs in with Google on first boot. _(Superseded: the former `authelia-init`/`redis-init` one-shots that seeded a placeholder Authelia config were removed when Authelia and Redis left the stack.)_

### 6.12 Docker socket hardening ✅ (closes [#147](https://github.com/getvaier/vaier/issues/147))

The Docker socket is no longer bind-mounted into Vaier or Traefik. A pinned `tecnativa/docker-socket-proxy:v0.4.2` sidecar holds the real socket and exposes a restricted HTTP API on `tcp://docker-proxy:2375` over `vaier-network`. Tecnativa's stock allowlist (`CONTAINERS`, `EVENTS`, `EXEC`, `IMAGES`, `PING`, `POST`, `ALLOW_RESTARTS`) covers GET access cleanly, but `CONTAINERS=1 + POST=1` would also permit `/containers/create` and `/containers/{id}/start` — leaving the privesc chain open. To close it, the `haproxy_template` Compose `configs:` entry overrides the upstream haproxy template with explicit `http-request deny` rules for `/containers/create`, `/containers/{id}/start`, `/images/create`, `/images/load`, and `/images/*/push` *before* the broad `CONTAINERS` allow. The template is embedded inline in `docker-compose.yml` so the stack ships as a single file — no separate config download. A smoke test confirms each denied path returns `HTTP/1.0 403` while `/containers/json`, `/_ping`, `/events`, `/images/{id}/json`, and `/containers/{id}/restart` still return 200/204. Net result: an attacker with RCE in Vaier cannot launch a `--privileged` container, pull a fresh malicious image, or alter swarm/network/volume state.

The Vaier container's PID 1 (the Java process) runs as UID 1000. The `Dockerfile` ENTRYPOINT is `setpriv --reuid=1000 --regid=1000 --init-groups --inh-caps=+net_admin --ambient-caps=+net_admin -- java …` — `setpriv` (from `util-linux`) starts as root so it can manage capabilities, raises `CAP_NET_ADMIN` to ambient (so it transfers to `ip` invoked by `ProcessBuilder`), then drops to UID 1000 before exec'ing Java. A one-shot `vaier-init` container (busybox) `chown`s the bind-mounted config dirs (`vaier`, `traefik`, `wireguard`, `icons`) to `1000:1000` on every start so the non-root process can read and write its own state. (The `authelia`/`redis` services and their PUID/PGID re-root workaround are gone with Authelia's removal from the stack.) `cap_add: NET_ADMIN` is retained at the container level so `VpnNetworkSetupAdapter` and `LanRouteAdapter` can install routes inside the Vaier container — file caps alone don't transfer reliably under Docker overlayfs, hence the ambient-cap path. (#151's keep-as-hedge rationale therefore stands.)

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
- **Extended the envelope to not-found and conflict across the rest layer ✅ (closes [#282](https://github.com/getvaier/vaier/issues/282)).** Two domain exceptions — `net.vaier.domain.NotFoundException` and `net.vaier.domain.ConflictException` — give the handler typed signals for the two missing categories (`PeerNotFoundException` now extends `NotFoundException`). `GlobalExceptionHandler` maps `NotFoundException` → `404` `ApiError(code=NOT_FOUND)` and `ConflictException` → `409` `ApiError(code=CONFLICT)`, alongside the existing `IllegalArgumentException`→`400`/`BAD_REQUEST` and catch-all→`500`/`INTERNAL_ERROR`. The conflict/not-found throw sites were retyped from raw `IllegalStateException` / `NoSuchElementException` / `RuntimeException` to the new typed exceptions: `VpnService` (LAN-CIDR already owned → `ConflictException`), `LanServerService` (not-found → `NotFoundException`, name-taken → `ConflictException`), and `LanServerSetupScript` (relay without a LAN address → `ConflictException`). (`AutheliaUserAdapter` was similarly retyped at the time but has since been deleted with the rest of the Authelia code.) Genuine server faults (invalid licence key, SMTP provider unavailable, file-write failures) deliberately stay `IllegalStateException`/`RuntimeException` → `500`. Four controllers were migrated to let these exceptions propagate to the handler instead of hand-rolling responses, removing: `VpnPeerRestController`'s body-less `4xx`/`5xx` on rename / delete / lan-address / lan-cidr / description / reissue; `LanServerRestController`'s body-less `4xx` and its `Map.of("error", …)` `409`; `AuthRestController`'s bare-string error bodies (7 handlers); and `DockerServiceRestController`'s body-less `500`s on discovery. Net result: validation, not-found, conflict, and `500` all render as `ApiError`.
- **Caveat:** `ApiError` is now the shape for every error response *except* two intentional cases: the enterprise-gate `402` (`EnterpriseLicenseInterceptor`), and the deliberately body-less `404`s for a missing optional GET artifact (the icon, and an already-retrieved one-shot peer config).

### 6.17 Social login + Vaier-owned authorization 🟡 (V2, in progress — Option C from the spike)

Replacing Authelia's file/LDAP-only first factor with **social login** (Google first) while keeping
the no-database, file-based model. Per the spike (`docs/spikes/social-login-spike.md`), authentication
moves to an external **identity provider** via oauth2-proxy, and **Vaier owns authorization** through a
file-based **access store** — the part that carries the real product logic and is fully testable
without Google credentials.

**Authorization model:** a **role** ladder `pending → user → admin`. A freshly seen Google identity
lands as **pending** (authenticated but blocked, "awaiting approval"); an admin promotes it to **user**
(reaches the services whose **access group** it holds) or **admin** (administers Vaier and reaches every
service). The same store gates **both** the Vaier console (admin-only) and per-service access.

**Delivered in this slice (authorization core + admin UI, TDD-first):**
- Domain: `domain.Role` (`PENDING`/`USER`/`ADMIN`), `domain.AccessEntry` (email, role, groups) with the
  access decisions *on the entity* — `isPending`, `isAdmin`, `mayAccessConsole` (admin only), and
  `mayAccessService(requiredGroup)`; plus `domain.AccessDecision` carrying the downstream identity headers.
- Ports: `ForPersistingAccessEntries` (list/find/upsert/delete) and `ForResolvingServiceGroup`
  (host → required group).
- Use cases (narrow, one each) on the existing `UserService` (identities are its domain):
  `VerifyAccessUseCase`, `ListAccessEntriesUseCase`, `GrantRoleUseCase`, `AssignGroupsUseCase`,
  `RevokeAccessUseCase`. An unknown email is auto-created as **pending** and denied, so it surfaces for
  the admin.
- Adapter: `AccessFileAdapter` — SnakeYAML at `${VAIER_CONFIG_PATH}/access.yml` (mirrors
  `AutheliaUserAdapter`: atomic-style write, owner-only perms), with the schema's `entries:` and
  `serviceGroups:` maps. Seeds the first admin from `VAIER_ADMIN_EMAIL` when the store is empty so the
  owner isn't locked out as pending.
- Web: `AuthzRestController` — `GET /authz/verify` (the Traefik forward-auth endpoint, reading
  `X-Auth-Request-Email` / `X-Forwarded-Host`, emitting `Remote-User`/`Remote-Email`/`Remote-Groups`),
  plus authenticated admin endpoints `GET /access`, `PATCH /access/{email}/role`,
  `PATCH /access/{email}/groups`, `DELETE /access/{email}`.
- UI: an **Access overview on the Users page** (alongside the Authelia users — both are identity/access
  management) — one flat list, **pending rows highlighted at the top** with an "N awaiting approval"
  count, per-row identity monogram, email, role badge, group chips, and actions (Approve as user /
  Approve as admin, edit groups, Revoke). _(The Authelia list and the by-role filter tabs were later
  removed — see the Users-page convergence and redesign entries below.)_

**Delivered in step 3a (per-service social auth mode, TDD-first):**
- Domain: `domain.AuthMode` (originally `NONE`/`AUTHELIA`/`SOCIAL`) replaces the per-route "requires auth" boolean.
  The mode *owns which middleware chain a route needs* (`authMiddlewareNames`), reads back off a
  router's chain (`fromMiddlewareNames`), and `ReverseProxyRoute.authMode()` surfaces it. At the time `authelia` and
  `social` coexisted so services could migrate one at a time. _(The `AUTHELIA` value has since been retired — `AuthMode` now has only `NONE` and `SOCIAL`; an unknown/blank/null wire value reads as `SOCIAL`, and a leftover `auth-middleware`-only route reads as `NONE`.)_
- Traefik generation (`TraefikReverseProxyAdapter`): per route, the chain for its mode — `authelia` →
  today's `auth-middleware`; `social` → the proven step-1 trio (`oauth2-signin` errors page →
  `oauth2-authn` Google forward-auth → `vaier-authz` Vaier forward-auth) **plus** a higher-priority
  per-host `Host(...) && PathPrefix(/oauth2/)` router pointing at oauth2-proxy (without which the Google
  button loops); `none` → no auth middleware. The `/oauth2/` helper router is torn down with its last
  social route and never appears as a published service.
- Logout: `VaierHostnames.logoutUrl(AuthMode, target)` is mode-aware — Authelia portal logout vs
  oauth2-proxy `/oauth2/sign_out` (which clears the domain-wide cookie). The console itself stays
  Authelia-gated in this step (3b moves it).
- oauth2-proxy is promoted to a first-class `docker-compose.yml` service; the throwaway `whoami` is
  removed. It was originally gated behind a `social` Compose profile, but **now that Authelia is
  decommissioned oauth2-proxy is mandatory, always-on infrastructure** — the `social`/`COMPOSE_PROFILES`
  profile is gone and a plain `docker compose up -d` starts it. `ConfigResolver.isSocialAuthAvailable`
  (Google client id present, surfaced on `GET /settings/config`) still governs whether the auth-mode
  picker offers Social.
- UI: a per-service **auth-mode picker** (Public / Social) on the service card replaces the
  on/off auth toggle; a distinct badge names the gateway in front of each service.

**Delivered (display name capture, TDD-first):**
- oauth2-proxy is migrated from CLI flags to an env-driven, secret-safe **alpha config** so it can
  forward Google's `name` claim. An `oauth2-proxy-init` container
  renders `alpha.yaml` into a shared `./oauth2/config` volume — substituting only the client id, writing
  the broker client secret to a mode-0600 `client-secret` file referenced via `clientSecretFile`
  (never inlined) — and adds `X-Forwarded-Name` / `X-Auth-Request-Name` (claim `name`) to the header
  injection. oauth2-proxy keeps the flags alpha doesn't cover (cookie, whitelist, email-domain,
  reverse-proxy, redirect-url, custom-templates-dir) and adds `--alpha-config`.
- Traefik (`TraefikReverseProxyAdapter`): the `oauth2-authn` middleware's `authResponseHeaders` gains
  `X-Auth-Request-Name` so the name reaches `/authz/verify`.
- Domain: `AccessEntry` gains a nullable `name` and owns the capture decision — `resolvedName(incoming)`:
  a present, non-blank header (trimmed) refreshes the name; a blank/absent one never wipes a known one.
- Web/app: `AuthzRestController.verify` reads an optional `X-Auth-Request-Name` header and passes it to
  `VerifyAccessUseCase.verify`; `UserService` stores/refreshes the name on the entry (preserving it across
  `grantRole`/`assignGroups`). `AccessFileAdapter` persists `name` in `access.yml` (back-compat: entries
  with no `name` read as null). `GET /access` returns `name`.
- UI: the **Users** rows lead with the display name and demote the email to a caption, falling
  back to email-only when there's no name yet.

**Delivered (last sign-in provider glyph, #305 follow-up, TDD-first):**
- Config only (no Java) makes the Dex **connector** id reach Vaier: the provider requests the
  `federated:id` scope (`scope: openid email profile federated:id`) so Dex emits
  `federated_claims`, and oauth2-proxy's alpha config injects
  `X-Auth-Request-Connector` from the nested claim `federated_claims.connector_id` (rendered by
  `oauth2-proxy-init` and mirrored in `oauth2/config/alpha.yaml`). Dex needs no change.
- Traefik (`TraefikReverseProxyAdapter`): the `oauth2-authn` middleware's `authResponseHeaders`
  gains `X-Auth-Request-Connector` so the connector id reaches `/authz/verify`.
- Domain: `AccessEntry` gains a nullable `provider` (the last sign-in provider) and owns the capture
  decision — `resolvedProvider(incoming)`: a recognised connector (`google`/`github`,
  case-insensitive, trimmed) refreshes it; a blank, absent, or unknown value never wipes a known one
  and never affects the access decision (tolerant so unknown connectors can never break auth).
- Web/app: `AuthzRestController.verify` reads an optional `X-Auth-Request-Connector` header and passes
  it to `VerifyAccessUseCase.verify`; `UserService` stores/refreshes the provider on the entry
  (preserving it across `grantRole`/`assignGroups`). `AccessFileAdapter` persists `provider` in
  `access.yml` (back-compat: entries with no `provider` read as null). `GET /access` returns `provider`.
- UI: the **Users** rows show a small monochrome provider glyph (Google or GitHub) beside the person's
  role badge, with a "Signed in with …" tooltip; no glyph for a pre-approved entry that has never
  signed in.

**Delivered (provider photo avatars, #305 follow-up, TDD-first):**
- Config only extends the provider-glyph plumbing to also capture the Dex `federated_claims.user_id`:
  oauth2-proxy's alpha config injects `X-Auth-Request-Connector-Uid` from `federated_claims.user_id`
  (`docker-compose.yml` heredoc + mirrored `oauth2/config/alpha.yaml`); the `federated:id` scope was
  already requested. `TraefikReverseProxyAdapter` forwards `X-Auth-Request-Connector-Uid` on the
  `oauth2-authn` middleware.
- Domain/app/infra mirror `provider`: `AccessEntry` gains a nullable `providerUserId` +
  `resolvedProviderUserId` (present non-blank refreshes, blank/absent never wipes); `UserService.verify`
  widens to carry it and refreshes it in the same single upsert as name+provider (preserved across
  `grantRole`/`assignGroups`); `AccessFileAdapter` persists `providerUserId` in `access.yml` (missing →
  null); `AuthzRestController.verify` reads the optional header and `GET /access` returns `providerUserId`.
- UI: the **Users** avatar resolves a real photo per entry — a GitHub sign-in with a known
  `providerUserId` uses the GitHub account picture; otherwise a Gravatar keyed on the email's SHA-256
  (`d=404`); on any load failure the `<img>` removes itself and the existing initials monogram shows
  through. The provider glyph moves to a small corner badge on the avatar. Photos populate on next login.
- No CSP is set anywhere in Vaier (no Spring header, no meta, no Traefik middleware), so no img-src
  allow-list change was needed for the GitHub/Gravatar hosts.

**Delivered (topbar profile photo, #305 follow-up, TDD-first):**
- `GET /users/me` (`AuthRestController.MeResponse`) now also carries the viewer's `provider` +
  `providerUserId` (from the resolved **access entry**; null when the viewer is unknown or has never
  signed in with a recognised provider), so the topbar can build the same photo URL as the Users cards.
- The avatar resolution chain (GitHub picture → Gravatar `d=404` → placeholder) moved into a shared
  `static/avatar.js` (`VaierAvatar.photoUrl`) — `users.html` was refactored onto it (behaviour
  identical) and it is now included by `admin.html` and `launchpad.html` too.
- Both console topbars render a small round `.topbar-avatar` `<img>` in place of the name when a photo
  resolves, with the name as `title`/`alt`; on a load miss (GitHub 404 / Gravatar `d=404`) the script
  swaps it back for the existing `.display-name` text. The viewer's own captured data means their photo
  shows immediately; identities that haven't signed in since the provider work fall back to name.

**Delivered (hide infrastructure hosts from the service list, #305 follow-up, TDD-first):**
- `PublishingConstants.MANDATORY_SUBDOMAINS` expands from just `vaier` to `vaier`, `oauth2`, and `dex`
  (new `ServiceNames.DEX`), so `oauth2.<domain>` and `dex.<domain>` — discovered as Traefik
  docker-provider routers — are filtered out of the published-services list (and thus the launchpad and
  Infrastructure page) and can't be deleted/edited via the publish API. `isMandatory` is
  launchpad-filter + delete/edit-guard only; it never provisions DNS or routes, so no side effect.

**Delivered in step 3b (console social-login polish, TDD-first):**
- Display name plumbed to the console identity: `GET /authz/verify` emits a `Remote-Name` response header
  when the access entry has a known display name (pre-approved entries with no name yet omit it), and the
  `vaier-authz` Traefik middleware's `authResponseHeaders` now forwards `Remote-Name` alongside
  `Remote-User`/`Remote-Email`/`Remote-Groups` (self-healing onto older configs via the existing startup
  backfill in `TraefikReverseProxyAdapter`). `GET /users/me` therefore returns a social-login console
  user's Google display name as `displayname`, so the console topbar greets them by name
  (falling back to email when absent). *(The My Page profile screen that also showed the name was later
  removed — see the Users-convergence entry below.)*
- Console auth mode: step 3b added a `VAIER_CONSOLE_AUTH_MODE` env var to select how the Vaier console
  itself was gated and therefore its logout URL. _(Since retired: the console is now **always** social —
  the `VAIER_CONSOLE_AUTH_MODE` env var, `ConfigResolver.getConsoleAuthMode`, the mode-aware
  `VaierHostnames.logoutUrl(AuthMode, …)`, and the docker-compose env line are all gone. Console logout is
  always oauth2-proxy's `/oauth2/sign_out`, and the login link points at the console, which forces Google
  sign-in.)_
- Password-change surface removed: the "Change password" card on My Page (`mypage.html`), the per-user
  change-password action/modal on the Users page (`users.html`), the `PUT /users/{username}/password`
  endpoint, and `ChangePasswordUseCase` are all gone — social-login users have no Vaier password. The
  `ForPersistingUsers.changePassword` port and its `AutheliaUserAdapter` implementation have since been
  deleted with the rest of the Authelia code.

**Delivered (Users page convergence — one social-identity list, TDD-first):** ✅
- The legacy Authelia user-management surface was removed now that names and email are provider-owned
  (Google): the local-password user list, add-user form, change-groups modal, delete-user action, and the
  group manager are gone from `users.html`, and the **Users** page is now the single access-entry list
  (pending highlight, role control, per-service groups, pre-approve-by-email, revoke, last-admin
  guard). The chip picker's group suggestions are derived from the groups already assigned across entries
  (the removed `/groups` feed is gone).

**Delivered (Infrastructure card declutter — Connection details disclosure):** ✅
- An expanded VPN-peer card used to show a flat grid mixing editable settings (name, description,
  device category, LAN CIDR/address) with raw read-only WireGuard diagnostics (IP, public key, allowed
  IPs, endpoint, Rx/Tx, last seen). The diagnostics now collapse behind a quiet **Connection details**
  toggle (`toggleConnection`, state held in the `expandedConnection` set so it survives SSE/poll
  re-renders like the other expansion sets), so an expanded card leads with the settings and services
  the operator manages. Last-seen / Rx-Tx still update live via the `peers-stats` SSE stream when the
  block is open — the header machine-icon colour already signals liveness when it isn't. LAN-server and
  Vaier-server cards are unchanged (they carry no WireGuard internals). Static-resource-only change.
- The inline machine-field editors (peer name / description / LAN CIDR / LAN address, and LAN-server
  name / description) **lost their per-field Save buttons** — they now **save on blur** (or on Enter,
  which blurs), matching the published-service editor fields. A green flash + toast confirms; an
  unchanged field is a silent no-op; a blank or duplicate name reverts. Because a blur-save repaints
  the list to refresh the card header, the repaint is deferred while another card field is focused
  (tabbing between edits) and flushed on `focusout`, so it can't wipe an edit-in-progress.

**Delivered (Users page redesign — one calm roster):** ✅
- `users.html` was reworked from a control-panel layout into a single scannable roster. The by-role
  filter tabs (All / Pending / Users / Admins) and the per-role section headings were dropped — with a
  homelab's handful of users a flat list is clearer than filtering. Each row now leads with a per-identity
  **monogram** (a square tile whose hue is derived from the email; pending identities are forced amber),
  the wall-of-text description shrank to a one-line subtitle, and the pre-approve form collapses behind a
  header button so the roster stays the focus. A group row's **Save** button stays disabled until the
  group set actually changes. No API, endpoint, or use-case change — purely a static-resource redesign.
- REST + use cases removed: `GET/POST /users`, `DELETE /users/{username}`, `PUT /users/{username}/email`,
  `/displayname`, `/groups`, `GET /groups`, `DELETE /groups/{name}` and their `AddUser`/`DeleteUser`/
  `UpdateUserEmail`/`UpdateUserDisplayName`/`GetUsers`/`GetGroups`/`UpdateUserGroups`/`DeleteGroup` use
  cases. `AuthRestController` keeps only `GET /users/me` (topbar identity + logout URL). `UserService` no
  longer implements those use cases or injects `ForPersistingUsers`/`ForGettingUsers`; it now owns only the
  social-login authorization use cases.
- The self-service **My Page** profile screen (`mypage.html`) was deleted — nothing was left to edit once
  password change (3b) and name/email editing were gone. The topbar display name across `launchpad.html`
  and `admin.html` is now a non-interactive read-only element (no link).
- These were kept compiling for a later cleanup pass and **have since been deleted**: `ForPersistingUsers`,
  `ForGettingUsers`, `AutheliaUserAdapter`, the `User` entity, and the boot `Lifecycle` Authelia bootstrap.

**Delivered (all published services migrated to social on startup, TDD-first):** ✅
- `SocialAuthMigration`, an idempotent `ApplicationReadyEvent` component, flips every remaining
  `AuthMode.AUTHELIA` published route over to `AuthMode.SOCIAL` in one pass on boot — the mass move off
  Authelia forward-auth now that the console and `dozzle` proved the chain. It reads all routes via the
  `ForPersistingReverseProxyRoutes` port and calls `setRouteAuthMode(dnsName, pathPrefix, SOCIAL)` for
  each Authelia route, which swaps the middleware chain and stands up the per-host `/oauth2/` helper
  router. `NONE` and `SOCIAL` routes are left untouched, so a second run flips nothing (there are no
  Authelia routes left). Now that the migration is proven, **Authelia and Redis have been removed from
  the running stack** — social login is the sole runtime auth gateway. No new domain concept — the mode
  decision stays on `ReverseProxyRoute.authMode()`. _(The one-shot `SocialAuthMigration` component and the
  `AuthMode.AUTHELIA` value have since been deleted; every gated route is social.)_

**Delivered (role is the sole admin/user authority, TDD-first):** ✅
- **Role** and **access groups** are now cleanly orthogonal — the role (`pending`/`user`/`admin`) is the
  single authority for admin-vs-user, and groups are purely **per-service access tags**. The reserved
  names `admins`/`users` are no longer treated as, or generated as, access groups on an `AccessEntry`.
- Domain: `AccessEntry` owns which names are role-mirroring (`admins`/`users`) via
  `hasRoleMirroringGroups()` and `withoutRoleMirroringGroups()`; the reserved-name set lives on the
  entity, not scattered across adapters/services. `mayAccessService` semantics are unchanged.
- Adapter: `AccessFileAdapter` seeds the first admin with `role=admin` and **empty groups** (no longer
  mirroring the role into an `admins` group, dropping the cross-concept coupling to Authelia's `User`).
- Migration: `AccessGroupMigration`, an idempotent `ApplicationReadyEvent` component that strips
  `admins`/`users` from every existing entry's groups through the `ForPersistingAccessEntries` port (a
  second run is a no-op).
- UI: the **Users** section presents groups as free-form per-service tags only — the group
  picker no longer suggests or accepts `admins`/`users`; admin-vs-user is set solely by the role control.

**Delivered (last-admin protection, TDD-first):** ✅
- With the console admin-only and Authelia decommissioned (no fallback), the access store must
  always retain at least one admin — otherwise the console would be permanently locked out for everyone.
- Domain: `AccessRoster` (an immutable value object over the entries) owns the decision via
  `adminCount()` and `isOnlyAdmin(email)`; `LastAdminException` signals the refusal. The rule lives in
  the domain, not as private service logic.
- Service: `UserService` refuses to revoke the sole admin, or to demote the sole admin to a non-admin
  role, throwing `LastAdminException`; granting admin never trips the guard.
- Adapter: `AccessFileAdapter` self-heals on startup — whenever no admin exists, it restores the
  configured `VAIER_ADMIN_EMAIL` to `role=admin` (promoting an existing entry in place, preserving its
  groups and name, or creating one with empty groups), and warns if adminless with no configured email.
  Idempotent when an admin already exists.
- Web: `LastAdminException` maps to `409 Conflict` carrying the operator-safe message.
- UI: the **Users** section disables Revoke and the demote-to-user control for the sole
  remaining admin, with an inline note explaining why.

**Delivered (Authelia decommissioned from the running stack, this change):** ✅ `authelia`, `authelia-init`,
`redis`, and `redis-init` are removed from `docker-compose.yml`; oauth2-proxy(+init) run unconditionally
(no `social` profile); admin-notification recipients are now the **admin**-role **access entries**; and
`login.<domain>` is no longer a mandatory/undeletable subdomain (only `vaier.<domain>` is).

**Delivered (dead Authelia Java code removed, this change):** ✅ `AutheliaUserAdapter`, `AutheliaConfigAdapter`,
`AutheliaAssetsAdapter`, `BootstrapCredentialsFileAdapter`, `SocialAuthMigration`, the `User` entity, and the
ports `ForPersistingUsers`, `ForGettingUsers`, `ForPublishingAutheliaAssets`, `ForConfiguringSmtpNotifier`,
`ForWritingBootstrapCredentials`, and `ForInitialisingUserService` (with all their tests) are deleted. The
`AuthMode.AUTHELIA` enum value is retired (`AuthMode` is now `NONE`/`SOCIAL` only). `domain.Lifecycle` is trimmed
to just the `vaier.<domain>` DNS bootstrap — no user seeding, bootstrap-credentials file, Authelia asset
publishing, or `login.<domain>` CNAME/router creation. `VaierHostnames` lost `autheliaHost()` /
`autheliaLogoutUrl()` / `logoutUrl(AuthMode, …)`, and `mandatoryDnsRecords()` returns only the `vaier` CNAME.
The SMTP password moved off the legacy `authelia/config/secrets.properties` store into `vaier-config.yml`
(owner-only), and `docker-compose.yml` dropped the `./authelia/config` mounts, `AUTHELIA_CONFIG_PATH`, and
`VAIER_CONSOLE_AUTH_MODE`. **Deliberately retained:** `TraefikReverseProxyAdapter` keeps an idempotent startup
sweep that removes any leftover Authelia Traefik objects (login router / authelia service / auth-middleware)
from previously-deployed stacks, and the `ServiceNames.AUTHELIA`/`REDIS`/`AUTH`/`AUTH_MIDDLEWARE` constants
survive to feed that cleanup and the defensive `VaierServerCatalogue` infra-exclusion. No Authelia runtime, no
Authelia config written, and no dead Authelia Java classes remain.

**Delivered (per-service access rules — any-of allowed groups, TDD-first):** ✅ (part of #305)
- Generalised per-service gating from a single required group to an **access rule**: the *any-of* list of
  **allowed groups** an identity may satisfy to reach one service. Empty rule ⇒ any approved user; **admin**
  always passes; **pending** never does.
- Domain: `AccessEntry.mayAccessService` now takes a `Collection<String> allowedGroups` and allows an
  ordinary user iff its own groups intersect the allowed set on at least one group (was
  `mayAccessService(String requiredGroup)` with a single-group `contains`). Semantics for admin/pending and
  the empty case are unchanged.
- Ports: `ForResolvingServiceGroup.requiredGroupForHost(host): Optional<String>` becomes
  `allowedGroupsForHost(host): List<String>` (read on the forward-auth hot path). New write/list port
  `ForPersistingServiceAccessRules` (`setAllowedGroups(host, groups)`, `allServiceAccessRules(): Map<host,
  List<group>>`).
- Use cases on `UserService`: `SetServiceAccessRuleUseCase` (empty/all-blank list clears the rule) and
  `GetServiceAccessRulesUseCase` (the rules map). `host` must be non-blank; normalisation lives in the adapter.
- Adapter: `AccessFileAdapter` now implements `ForPersistingServiceAccessRules` too. `access.yml`'s
  `serviceGroups:` maps host → **list** of groups (was a scalar); the adapter trims/drops-blanks/dedupes,
  and an empty result removes the host key. **Back-compat:** a host whose value is a bare scalar (older files)
  is read as a one-element list. `allServiceAccessRules()` omits hosts with no groups.
- Web: `AuthzRestController` gains admin endpoints `GET /access/services` (host → `[groups]`) and
  `PUT /access/services/{host}/groups` with body `{ "groups": [...] }` (empty list clears the rule), behind
  the console's existing social-login gate.
- UI: on the **Infrastructure** page, a Social published service's row carries an **Allowed groups** chip
  multi-select (suggestions derived from groups already assigned to access entries; free-typing new groups
  allowed; helper text "Leave empty — any signed-in, approved user can reach this."). A **restricted** badge
  marks Social services with a non-empty rule. In Public auth mode no rule applies and the control is hidden.
- Known limitation: rules key on host (matching the forward-auth `X-Forwarded-Host`), so path-scoped
  services that share a host share one rule.

**Delivered (GitHub sign-in via the Dex identity broker, TDD-first):** ✅ (#305 follow-up)
- A user can now sign in with **Google or GitHub**. Rather than teach oauth2-proxy two providers, a **Dex**
  OIDC broker is inserted behind it: `Traefik → oauth2-proxy → Dex → Google / GitHub`. oauth2-proxy stays
  the single forward-auth gatekeeper and identity stays keyed on **email**, so `/authz/verify`, `access.yml`,
  `UserService`, and the Traefik middleware chain are unchanged.
- Any GitHub account is allowed (no org/team restriction) — the existing **pending → admin-approval** gate
  does the gating. The same person on both providers currently yields two separate **access entries**
  (identity linking is backlogged).
- Compose: new `dex` service (`ghcr.io/dexidp/dex:v2.45.1`, port 5556, Traefik router `dex.<domain>`) and a
  `dex-init` one-shot that renders `dex/config/config.yaml` and writes the three upstream secrets to
  mode-0600 files (mirrors `oauth2-proxy-init`). Both are mandatory infrastructure — no `social` profile.
  Dex has one **connector** per provider (`google`, `github`) and one static client for oauth2-proxy.
- oauth2-proxy's `alpha.yaml` render is repointed from `provider: google` to a generic `provider: oidc`
  brokered by Dex (`issuerURL: https://dex.<domain>`, `connector_id` login param), with the oauth2-proxy↔Dex
  shared secret in the mode-0600 `client-secret` file.
- Secrets: `VAIER_DEX_CLIENT_SECRET` (oauth2-proxy↔Dex) is auto-generated into `.env` like
  `VAIER_OAUTH2_COOKIE_SECRET`; `VAIER_OIDC_GITHUB_CLIENT_ID` / `VAIER_OIDC_GITHUB_CLIENT_SECRET` are
  operator-provided alongside the Google pair.
- UI: the branded oauth2-proxy sign-in page now offers two buttons — **Continue with Google** (primary) and
  **Continue with GitHub** (quieter outlined secondary) — each submitting its `connector_id`.

**Backlog (not in this slice):**
- **Cross-provider identity linking** — treat the same person signing in via Google and via GitHub as one
  **access entry** (today they are two, keyed on each provider's asserted email).
- The unauthenticated "awaiting approval" page and migration off Authelia for existing deployments. The
  **availability coupling** the spike flags — Vaier being in the request path for protected services —
  remains the open trade-off to accept or revisit.

### 6.18 Web terminal 🟡 (in progress — epic [#306](https://github.com/getvaier/vaier/issues/306))

A per-machine **web terminal**: open an SSH shell to any machine in the Vaier network straight from the
admin UI, with Vaier holding the credentials — enough to replace Termius. Per-host, keyed on a machine
(peer or LAN server), one **host credential** per machine, admin-only (behind the Tier-3 Social auth
chain). Address selection (tunnel IP for peers, `lanAddress` for LAN servers) is a domain decision.

**Slices:**
- **#307 — Credential vault + host-credential CRUD ✅ (this slice).** A `HostCredential` (username,
  `AuthMethod` = `PASSWORD` | `PRIVATE_KEY`, secret material, optional key passphrase) stored one-per-machine
  in an encrypted-at-rest **credential vault** (`host-credentials.yml`). A `SecretCipher` (AES-256-GCM,
  `enc:v1:` envelope, random IV per encryption) seals the secret/passphrase; its master key is either
  `VAIER_VAULT_KEY` (base64 32 bytes) or a self-generated `vault.key` (mode 600) — no operator-authored
  secret. The same cipher now also encrypts the existing reversible secrets in `vaier-config.yml`
  (`awsSecret`, SMTP password); legacy plaintext still loads and is re-encrypted on the next save. New
  `TerminalService` implements the narrow save/get/delete use cases; GET returns only a redacted
  `HostCredentialView` (`username`, `authMethod`, `hasSecret`) — secret bytes never leave the process.
  REST CRUD at `PUT|GET|DELETE /machines/{machine}/ssh-credential`, plus an **SSH credential** control on
  each machine card. A per-machine **SSH access** flag gates that control: `DeviceCategory` seeds a smart
  default (appliances like printers/phones off; servers/NAS/desktops/laptops on) via
  `Machine.defaultSshAccess`, an explicit nullable override is persisted (peer `# VAIER:` metadata /
  `lan-servers.yml`), and `PATCH /machines/{machine}/ssh-access` sets it. The SSH access flag is
  authoritative; the device category only seeds its default and never otherwise affects it.
- **#311 — Vaier-server host as an SSH target ✅ (credential surface only).** The Vaier server host
  itself now appears in Infrastructure as a singleton synthetic machine (`Machine.vaierServer`, reusing
  `MachineType.UBUNTU_SERVER` + device category `SERVER` so no new `MachineType` and no routing ripple),
  under the reserved name `"Vaier server"` (`LanAnchor.VAIER_SERVER_NAME`, rejected as an operator peer /
  LAN-server name). It carries the same **SSH access** toggle and **host credential** control as any
  machine — no delete/regenerate/publish. Its SSH-access override lives in `vaier-config.yml`
  (`vaierServerSshAccess`, default on); the host credential uses the existing name-keyed vault unchanged.
  `PATCH /machines/{name}/ssh-access` routes the Vaier-server write to the config store; a dedicated
  `GET /machines/vaier-server` feeds its card. The terminal connection (address = container→host gateway
  or `VAIER_HOST_SSH_ADDRESS`) is deferred to slice 2.
- **#308 — Web terminal ✅.** A live in-browser SSH shell per machine: a **Terminal** button on every
  SSH-capable card (peers, LAN servers, the Vaier server) opens a locally-vendored xterm.js
  (`static/vendor/xterm/5.3.0`) over a WebSocket at `/machines/{name}/terminal`. The `TerminalService`
  resolves the machine's **SSH address** (peer tunnel IP / LAN address / Vaier host gateway or
  `VAIER_HOST_SSH_ADDRESS`), loads its vault credential, and opens the session via the driven port
  `ForOpeningSshSessions` (adapter `MinaSshSessionAdapter`, Apache MINA sshd-core 2.15.0 — JSch is
  unmaintained). Host-key **trust-on-first-use**: `ForTrackingHostKeys` (`ssh-known-hosts.yml`) pins a
  fingerprint on first connect and the domain `HostKeyTrust` decision refuses a later mismatch
  (`HostKeyMismatchException`); `DELETE /machines/{name}/host-key` clears a pin. The WebSocket relays
  keystrokes (binary) and resize (JSON control) to the shell and streams output back, closing with a
  distinct code per failure (no credential / auth / host-key mismatch / not found / connect). The path
  is non-whitelisted, so the oauth2 forward-auth runs on the upgrade (Traefik passes WebSockets through
  by default). The terminal is its own **Terminal** tab in the admin shell (`admin.html` +
  `terminal-dock.js`), a sibling of the section iframe rather than content inside one — so its live SSH
  sessions survive navigating to Infrastructure/Users/Settings and back (a badge on the tab counts them).
  The tab is transient: it appears only while at least one shell is open and retires when the last one
  closes, dropping the user back to Infrastructure (a stale `#terminal` bookmark with no live shells
  lands there too). A machine card's Terminal button (in the Infrastructure iframe) reaches the shell via
  `window.parent.vaierOpenTerminal`, which opens a shell as a tab and switches to the Terminal section.
  Each tab is bound to its own WebSocket + SSH session, so several shells (even two to the same host)
  coexist and closing or erroring one leaves the others live; the adapter holds no shared session state.
  On desktop the pane area is a **2-D split grid** (rows of columns): clicking a tab focuses a shell alone;
  dragging a tab (pointer-based, not native DnD, which won't start from the tab's button) onto a pane's
  left/right edge adds a column or its top/bottom edge adds a row, with a drop-zone preview; draggable
  dividers on both axes resize adjacent rows/columns (flex-grow weights, min 140px wide / 90px tall).
  Dragging the sole on-screen shell splits it against the most-recently-focused other shell. On a phone
  the grid collapses to a single full-screen pane at a smaller font (10px vs 13px, re-fitting on rotate),
  touch scrolling is driven from the finger via `term.scrollLines` with `touch-action: none` so the
  scrollback scrolls instead of the page (xterm's viewport is a sibling of its text layer), and the shell
  binds its height to `visualViewport` so the soft keyboard never covers the prompt. On a phone Vaier also
  **holds the screen awake** while any shell is open — `navigator.wakeLock.request('screen')`, acquired when
  the first shell opens and released on the last close, re-acquired on `visibilitychange` because browsers
  drop the lock whenever the tab is backgrounded — so a long-running command you're watching isn't lost to a
  dimmed display; it degrades silently where the API is unsupported or denied. Only visible panes
  render and re-fit; hidden shells keep running. A dropped WebSocket **auto-reconnects** with exponential
  backoff (to 8s, up to 8 attempts, then a manual Reconnect action) unless the close is clean (`1000`) or
  permanent (no credential / auth / host-key mismatch / not found); term input is bound to the session's
  current socket so a reconnect is seamless. The panel fills the content area under the topbar — which is
  what made the earlier floating-window and in-page-dock designs unusable on mobile. A clean session
  end (`1000`) now **closes the pane** on its own instead of leaving a dead window — the last one closing
  retires the Terminal tab and drops the user back to Infrastructure as before.
- **On-screen control keys on a phone (terminal key bar) ✅.** A mobile soft keyboard has no Esc, Tab,
  Ctrl, Alt, or arrows — the keys a shell needs most. A **key bar** (`#terminalKeys` in `admin.html`,
  `.term-keybar`/`.term-key` in `styles.css`, built and driven in `terminal-dock.js`) shows **only on a
  phone** (`@media (max-width: 720px)`) and **only while a shell is open** (a `term-no-shells` class hides
  it on an empty panel), sending each key straight to the focused shell over the same WebSocket as a
  keystroke. **Esc** and **Tab** are literal bytes. **Ctrl** and **Alt** are **sticky** modifiers: tapping
  one arms it (it glows via `.is-armed`, `aria-pressed`) and it folds into the very next key — whether
  tapped on the bar or typed on the soft keyboard, because all typed input now routes through `sendTyped`
  — then disarms; Ctrl maps a printable to its control byte (`ctrlByte`, a–z → 0x01–0x1a plus the symbol
  controls), Alt is an ESC prefix. Focusing a different shell disarms first (`disarmMods` in `focus`) so a
  modifier armed for one pane never fires in another. The **arrow** keys honour the shell's
  application-cursor-keys mode (DECCKM: `ESC O <letter>` vs `ESC [ <letter>`, read from
  `term.modes.applicationCursorKeysMode`) so they navigate in vim/less instead of printing stray
  characters; with a modifier armed they send the parameterised CSI cursor form (`ESC [ 1 ; <mod> <letter>`,
  `mod = 1 + Alt·2 + Ctrl·4`) for word-jumps like Ctrl+Right. Bar buttons act on `pointerdown` with
  `preventDefault` so focus stays in the terminal textarea and the soft keyboard never drops between taps.
  Desktop is untouched — a hardware keyboard already has these keys.
- **Persistent shells survive a Vaier redeploy ✅.** A web-terminal shell used to be a bare login-shell
  PTY living inside the Vaier JVM, so running `docker compose up -d --force-recreate vaier` from a terminal
  on the Vaier host killed the JVM, the WebSocket, and the shell — losing cwd, history, scrollback, and the
  deploy's remaining output/exit code; the auto-reconnect then opened a brand-new shell while writing a
  `[reconnected]` banner that implied a continuity that didn't exist. Now each pane's shell runs inside a
  tmux session on the target machine (a **persistent shell**), so it outlives the drop and a reconnect
  **reattaches** to it. The command decision lives in the domain `PersistentShell` (mirroring `BorgCommand`):
  `attachOrCreateCommand` runs `tmux new-session -A -D -s <name>` — `-A` attach-or-create, `-D` detach any
  stale client so the live client alone drives the window size (no letterbox after a reattach) — and falls
  back to `exec "${SHELL:-/bin/sh}" -l` when `command -v tmux` finds no tmux, so a terminal never fails to
  open on a machine without it. The session name is `vaier-<paneId>`, where `paneId` is a stable per-pane id
  the browser generates (`crypto.randomUUID`) and sends on the WebSocket as `?pane=…`; the domain reduces it
  to a safe identifier (`[A-Za-z0-9_-]`, single-quoted) so a hostile pane id can't break out of the command
  line — stable across reconnects (reattaches), distinct between panes (two panes on one host never share a
  session). Because `new-session -A` is an atomic attach-or-create, the service first runs
  `PersistentShell.probeCommand` over the ordinary exec path (`ForRunningSshCommands`, the same host-key
  trust) and reads it with `PersistentShell.readProbe` into a `Continuity` (`REATTACHED` / `NEW` / `PLAIN`);
  the handler pushes it as a `shell-mode` control frame, and the browser writes a **truthful** reconnect
  banner — green "reattached — session resumed" only when it really was, amber "new shell" otherwise. The
  adapter now opens the shell over a PTY **exec** channel (`createExecChannel` + `setUsePty(true)`) running
  that command, rather than a bare login shell; resize still flows via `sendWindowChange`. tmux config is
  kept minimal and passed on the command line — the operator's prefix key is never rebound and no `tmux.conf`
  is written to their hosts.
- **Ending a persistent shell — closing a pane no longer strands a tmux session ✅.** Persistence had no
  counterpart: closing a pane only closed the WebSocket, and the pane id lived in browser memory alone. So
  every closed pane and every page reload abandoned a tmux session on the machine **forever**, still running
  whatever was inside it — 10 orphaned sessions were found on the Vaier server itself, including a `claude`
  detached for 3+ hours, on a 1.9 GB box whose earlyoom preferentially kills `java`/`mvn`/`claude`. The fix
  rests on one distinction: **ending** a shell (deliberate — the session dies) is not **disconnecting** from
  it (the session lives on, reattachable), and the two are indistinguishable from the socket alone, so the
  browser must say which it means. Domain: `PersistentShell.endCommand(paneId)` →
  `tmux kill-session -t 'vaier-<pane>' 2>/dev/null || true`, scoped to that pane's session, idempotent (a
  session already gone is success), and reduced through the same safe-identifier rule so a hostile pane id
  can't break out. Application: a new narrow `EndTerminalSessionUseCase.endTerminal(machineName, paneId)`,
  implemented by the existing `TerminalService` (no new service class) and **best-effort by contract** — it
  never throws, because it runs on a close path where an unreachable host or a missing credential is not
  something the operator, who has already dismissed the pane, can act on. Web: a `{"type":"end-shell"}`
  control frame on the terminal WebSocket; `afterConnectionClosed` still deliberately does **not** end the
  shell, since a dropped socket must leave the session alive to reattach to. Browser (`terminal-dock.js`):
  `closeShell` sends `end-shell` before closing the socket, and pane ids are now persisted in
  `localStorage` (`vaier.terminal.panes`, keyed by machine) and reused on open (`claimPaneId` picks the first
  id owned for that machine that isn't already on screen), so a page reload **reattaches** to the shells it
  already owns instead of minting fresh ids and stranding the old sessions unnamed; `releasePaneId` drops an
  id only when its shell has been ended.
- **Send stored password to a live prompt ✅.** A **Send password** action in each shell's tab actions menu
  asks Vaier to write that machine's stored password straight into the PTY, so it travels vault → SSH server without the
  browser ever holding it. The safety gate is a domain rule: `PasswordPrompt.isAwaitingPassword` matches a
  password/passphrase prompt **only at the tail** of the recent output
  (`(?is).*(?:password(?: for \S+)?|passphrase for .+):\s*\z` — bare `password:`, `user@host's password:`,
  sudo's `[sudo] password for <user>:`, and ssh's `Enter passphrase for key '...':`), so a `password:`
  already answered upthread can't trigger a send that would echo into the screen or shell history. The
  narrow `SendHostPasswordUseCase` (on `TerminalService`) returns `SENT` / `NOT_AT_PROMPT` /
  `NO_PASSWORD_CREDENTIAL` (absent, or key-auth) / `FAILED`; the secret is never returned, logged, or put
  on the wire. The handler keeps a bounded 512-byte rolling tail of PTY output per session for the check,
  handles a `{"type":"send-password"}` control frame, and replies with a status-only
  `{"type":"password-result","status":...}` frame the UI turns into a quiet confirmation or an advisory.
- **Send-password key enabled only at a prompt ✅.** The **backend** owns the detection: the WebSocket
  handler runs the same `PasswordPrompt.isAwaitingPassword` domain rule over the rolling tail after every
  PTY chunk and, **on each state change** (never per chunk), pushes a `{"type":"password-prompt",
  "showing":true|false}` frame. The tail makes a prompt split across two reads still register. No
  browser-side heuristic guessing.
- **Per-tab actions menu; actions never removed, enabled by the server ✅.** The **pane head**
  (`term-pane-head`) reverted to what it always was — the machine name and, only while the pane area is
  split, the ✕ that removes the pane from the grid — and carries **no actions**. The dock's one row of
  chrome is the tab strip (one `term-tab` per open shell). Each tab now carries a small **⋯** actions-menu
  button (`term-tab-menu-btn`) between its label and its ✕ close button; clicking it opens a fixed-position
  dropdown (`term-tab-menu`, mounted on `<body>` so the horizontally-scrolling strip can't clip it) hanging
  beneath that tab. The menu holds the shell's **Send password** action as a row (`term-menu-item`). The
  action is never removed when it can't be used — it renders **disabled with a tooltip reason** and enables
  the moment the server's control frames say it's usable. Send password enables only while the
  `password-prompt` frame's `showing` is true (and disables again the moment the prompt is answered).
  Because a disabled action sits hidden inside a closed menu, the ⋯ button itself takes the accent colour
  (`has-action`) while the shell's remote is at a live password prompt — the visible signal that an action
  just became usable. A menu open over a stale action is redrawn in place when a server frame flips its
  state, and it closes on outside click, Escape, resize, or a tab-strip scroll. Rationale: the action is
  rare — a password prompt — so a permanent button row would cost every shell a line of terminal output
  forever; hanging the menu off one named tab also means an action always belongs to a single shell, with no
  ambiguity about which pane a click meant.
- **#309 — Managed ed25519 keypair generation** (the `managed` flag). 🔲
- **#310 — Saved snippets.** 🔲
- **#313 — Remote SSH exec → host telemetry & alerts** (umbrella). Turn the SSH capability into a
  remote-sensing input for the alerting pipeline: run a command on a host, read the result in code, and
  feed threshold-crossing alerts. Reuses the credential vault, host-key TOFU, and SSH-address resolution.
  - **#314 — Non-interactive SSH exec port ✅ (keystone).** New driven port
    `ForRunningSshCommands.run(SshTarget, command) → CommandResult` (a domain record: `exitCode`,
    `stdout`, `stderr`, `timedOut`). Backed by the existing `MinaSshSessionAdapter`, which now also
    implements it via an exec channel (`createExecChannel`, not a PTY shell) — the shell and exec paths
    share one copy of the connect + host-key TOFU + auth logic (`establish`). Safety rails: a hard,
    constructor-injectable run **timeout** (default 20s; on expiry `timedOut=true`, everything closed,
    returns promptly — never hangs) and a **bounded** stdout/stderr cap (default 1 MiB) so a chatty
    command can't OOM Vaier. A non-zero exit is a normal result, not an exception; connect/auth/host-key
    failures surface as the same domain SSH exceptions as the terminal path. Internal capability only —
    no consumer wiring or UI yet (slices 3–5). Short-lived client per call, closed in a `finally`.
  - **#315 — Per-host SSH port** (retire hard-coded port 22). 🔲
  - **#316 — Remote disk-pressure alert** (`df` over SSH) ✅. The first alert built on the exec keystone:
    a `RemoteDiskWatcher` (`@Scheduled`) runs `df -P` over SSH on every `Machine` that both has
    `effectiveSshAccess()` and a stored **host credential**, parses each row, and feeds a
    per-filesystem `RemoteDiskPressureTracker` so it alerts only on threshold crossings (into pressure and
    back), never per poll. (It shipped scoped to `df -P /` — the root filesystem and only the root
    filesystem — and reading *every* filesystem, each with its own **disk watch** and its size, is
    [#325](https://github.com/getvaier/vaier/issues/325) ✅; see §6.9.) Notifies via `NotifyAdminsOfRemoteDiskPressureUseCase` on `NotificationService`,
    reusing admin-recipient resolution + SMTP gating; skips silently when SMTP is unconfigured. The
    watcher depends only on the new `RunRemoteCommandUseCase` (implemented on `TerminalService`, reusing
    `openTerminal`'s address + credential + host-key-pin assembly), never on the SSH ports directly. Error
    path is honest: a machine with no credential / SSH off is skipped, and an unreachable host or a `df`
    that times out, exits non-zero, or returns unparseable output is degraded (logged, tracker untouched),
    never treated as a full disk. TOFU gap closed: `CommandResult` gained a `hostKeyFingerprint` field so
    the exec path pins an unpinned host on first use exactly like the terminal. **Superseded the original
    local host watcher**: once `RemoteDiskWatcher` could reach the Vaier host itself over SSH-to-self, the
    old `DiskUsageWatcher` (and its `NotifyAdminsOfDiskPressureUseCase` port) were deleted outright to stop
    double-notifying admins for the same host — the Vaier host is now just another `Machine` in the fleet
    this watcher covers. With that watcher gone, the whole orphaned local host-disk-reading stack went too
    (`GetHostDiskUsageUseCase`, `HostMonitoringService`, the `ForReadingDiskUsage` port + `HostDiskUsageAdapter`,
    and the `domain.DiskUsage` value object) — nothing read the host disk directly anymore. `df` over SSH is
    now the entire disk story; see the disk-pressure-alerts entry above.
  - **#317 — Docker health over SSH + close the `apalveien5` 2375 hole.** 🔲

**Backlog:** SFTP *over the terminal session itself* remains out of scope — the terminal's V1 scope is the
interactive shell plus saved snippets. (Vaier does speak SFTP now, but as its own feature: see **6.20
Explorer**, which browses a machine's files over a separate SFTP connection sharing the terminal's
credential vault and host-key trust.) Further remote-telemetry watchers (reboot detection, systemd service
health, load/temperature) are backlog under #313.

---

### 6.19 Fleet Backup ✅ (Enterprise — admin UI shipped)

Back up the data on the machines in the fleet to a [borg](https://www.borgbackup.org/) repository on a **backup server** — bootstrapping that server from nothing when the fleet has no borg anywhere. Enterprise-gated: the whole REST surface is `@RequiresEnterprise` (Community instances get `402 Payment Required` and the **Backups** tab renders in a locked gate state); the UI reads `GET /license` to decide.

> **Not §6.7/#153.** This is **Fleet backup** — backing up the *fleet's machines* to a NAS. It is explicitly **not** the §6.7/#153 **Backup snapshot** feature, which is an export of *Vaier's own configuration*. The two are separate features with no shared vocabulary (see the distinct terms in `UBIQUITOUS_LANGUAGE.md` §15).

Domain: `BackupServer`, `BackupRepository`, `BackupJob`, `BackupRun` + `BackupRunStatus`, `Archive`, `BorgVersion`, `BorgServerImage`, `BorgServerSetupScript`, `BorgClientSetupScript`, `BackupFailureTracker`, `BackupServerHealthTracker`. Application: `BackupService` implements the use cases (adding `Get/Save/DeleteBackupServerUseCase`, `GenerateBackupServerSetupScriptUseCase`, `ProvisionBackupServerUseCase`, `AuthorizeBackupClientUseCase` to the existing `Get/Save/DeleteBackupRepositoryUseCase`, `Get/Save/DeleteBackupJobUseCase`, `RunBackupJobUseCase`, `GetBackupRunsUseCase`, `ListArchivesUseCase`, `CheckBackupPrerequisitesUseCase`, `InitBackupRepositoryUseCase`); `NotificationService` gains `NotifyAdminsOfBackupServerDownUseCase`. Driven ports `ForPersistingBackupServers` / `ForPersistingBackupRepositories` / `ForPersistingBackupJobs` / `ForRecordingBackupRuns` are file-backed (`Backup*FileAdapter`) — no database, consistent with the rest of Vaier; server reachability reuses `ForProbingTcp`. The rest layer's `BackupRunner` / `BackupProvisioner` drive borg over SSH (the latter also implementing `PrepareBackupClientUseCase` and, via twin `@Scheduled` sweeps sharing one private helper, pushing prepare-client **and** server-provision settle events on the `backups` SSE topic; `BackupRunner`'s poll sweep likewise publishes a `run-settled` event when an on-demand/nightly run settles — so the browser never polls for any of the three), `BackupServerWatcher` (`@Scheduled`) probes each backup server and alerts on transition, and `BackupRunner` also carries the nightly scheduler and a fail-fast borg-presence pre-flight. The admin web UI (`admin.html` → `backups.html`/`.js`/`.css`, reached at the `#backups` hash) is now the **guided-provisioning bridge** — setup-script download, provision, authorize-host, and per-job readiness checks — and renders the backup server, its repositories and its jobs **read-only**; their create/edit/delete (and a job's **Run now**, enable/disable, run-status display, and archive browsing) have moved into the Explorer's `backup` entry (§6.21).

**What's implemented (V1):**
- **Backup server ✅** — a machine running a borg server that holds repositories: `machineName` (the hosting `Machine`, often a LAN server such as NAS), `host`, `sshPort` (default `8022`), `borgUser` (default `borg`), `baseRepoPath` (default `home/borg/backups`, no leading slash), `serverDataPath` (the host path holding the borg volumes / `authorized_keys`), and a `managed` flag. `BackupServer.sshUrlPrefix()` / `authorizedKeysPath()` are domain renderings. CRUD via `GET/PUT/DELETE /backup-servers[/{name}]`. **Singleton invariant ✅ ([#323](https://github.com/getvaier/vaier/issues/323))** — the fleet has **at most one** backup server: `BackupService.saveBackupServer` refuses a save when a differently-named server already exists (`IllegalArgumentException` → `400`), so a second server can never be designated without removing the first. **Designated in the Explorer, not on the Backups page** — the create/edit/delete controls moved out of `backups.html` (the "+ New server" button, the server create/edit modal, and per-server Edit/Delete are gone); the Backups page now renders the single server **read-only** (its coordinates plus the operational actions — provision, authorize, setup-script download) with a link to the Explorer. Designation is a `backup` **entry** slice of the Explorer epic — see §6.21. **Provisioning ✅** — `GET /backup-servers/{name}/setup.sh` emits an idempotent bash script (`BorgServerSetupScript.generate`) that stands up a **pinned** borg-server container (`BorgServerImage.EXPECTED = horaceworblehat/borg-server:2.8.6`, no floating `:latest`); `POST /backup-servers/{name}/provision` runs it over SSH where docker-over-SSH works and **degrades gracefully** to `scriptOnly` (not a failure) where it doesn't (the Synology case): because `setup.sh` is Enterprise-gated behind admin auth (never anonymous, so it must not be curled onto a host), Vaier **stages** the script on the host over SSH (`BorgServerSetupScript.stageScript` base64-writes it to `<workDir>/<server>-borg-setup.sh` and echoes `STAGED <path>`) and returns the staged path plus the exact `sudo bash <path>` command in `stagedScriptPath`; if staging itself fails (no credential, SSH error) it degrades further to `scriptOnly` with a null path and tells the operator to download setup.sh from the UI. `GET …/provision/status` reports a detached run's `RUNNING`/`SUCCESS`/`FAILED` + log tail, but the **frontend never polls it**: a backend `@Scheduled` sweep (`BackupProvisioner.pollInFlightProvisions`, mirroring `pollInFlightPrepares` via a shared helper) settles the launched provision from its on-host `.rc` and publishes a `provision-settled` event (`{serverName,state}`) on the `backups` SSE topic, and the endpoint is kept only for API symmetry (the browser hits it once, on the pushed event, for the log tail). **Key trust ✅ (closes [#320](https://github.com/getvaier/vaier/issues/320))** — `POST /backup-servers/{name}/authorize/{machineName}` generates the client's ed25519 key if absent and upserts it into the server's `authorized_keys` exactly once (newline-safe), so borg — which runs on the client as the SSH user, not root — can authenticate. Reports `alreadyTrusted` distinctly from a fresh add. The entry Vaier writes is **restricted**, never a bare key (a bare key grants a full interactive shell as the borg user, so one compromised client could read and delete every other host's repositories — the weakness the live NAS had): `command="borg serve --restrict-to-path <abs> …",restrict <pubkey>`, with one `--restrict-to-path` per repository that machine backs up to on this server (`"/" + repoPathOn(server)`, sorted + deduped for a deterministic, idempotent line), plus `restrict` (no pty/forwarding/shell). Because the restriction is derived from the machine's jobs, **adding a repository for a machine requires re-authorizing that host** to widen its restriction; the entry is keyed by the public key's **key material** (the base64 blob, stable across options/comment) so a changed restriction set replaces the prior line rather than leaving two entries for the same key (`BorgCommand.keyMaterial` + a `grep -vF` upsert). If no repository targets the machine yet, the key is confined to the server's base repository path as a placeholder and the response says to re-authorize after creating a job — Vaier never writes an unrestricted key or a bare `--restrict-to-path`. Building the entry line and extracting the key material are domain rules on `BorgCommand`; the orchestrator only computes the path set from `GetBackupJobsUseCase` + `GetBackupRepositoriesUseCase`. **Host-key pinning ✅ (no trust-on-first-use)** — a freshly provisioned borg server generates brand-new SSH host keys, so a client with a stale `known_hosts` pin refuses to connect (`REMOTE HOST IDENTIFICATION HAS CHANGED`) and a brand-new client has no pin at all — and borg's non-interactive SSH (`StrictHostKeyChecking=ask`, no tty) fails rather than prompting; either way every backup fails with `borgAuthOk=false`. Vaier is the trusted broker (it reaches the server's machine with its own vault credential and pinned host key), so it obtains the server's host key **authoritatively** rather than via `ssh-keyscan` (absent on Synology) or `accept-new`: the **setup script** publishes the container's public host keys (`BorgServerSetupScript` waits bounded for `server_keys/ssh/ssh_host_ed25519_key.pub`, then concatenates the `*.pub` keys to `<serverDataPath>/ssh/host_keys.pub`, chowns to the SSH owner, `chmod 644` — the private `ssh_host_*_key` files are never touched); `authorizeClient` then reads them from the server's machine (`BorgCommand.readServerHostKeys` + `parseHostKeys`, which accepts only real `ssh-ed25519`/`ssh-rsa`/`ecdsa-sha2-*` lines and drops comments/MOTD/private-key noise) and pins them on the **client** before authorizing the client key (`BorgCommand.pinHostKeys` backs up `known_hosts`, removes only this `[host]:port` via `grep -vF`, appends one `[host]:port type key` line per key — the bare `host` form when `sshPort == 22` — locks the file to `0600`, and echoes `PINNED <n>`; idempotent). When the server has no published host-key file (an **adopted** server), pinning is skipped and the client key is **still** authorized, with `AuthorizeResult.hostKeyPinned=false` and a message telling the operator to re-run the setup script or pin manually — the pin never fails the authorize, and junk is never written into `known_hosts`. `BackupServer.hostKeysPath()` renders the published path; the parser, the pin command, and the `[host]:port`-vs-`host` decision are domain rules on `BorgCommand`.
- **Backup repository ✅** — one borg repository on a backup server: `name`, `serverName`, a **nullable** `repoPath` override (blank derives `base/<name>` via `BackupRepository.repoPathOn(server)`, so a new repo is added just by naming it; an explicit override still addresses an adopted, oddly-named repo), an encrypting **passphrase** (stored encrypted at rest via the same cipher as the credential vault / `awsSecret`, never returned to the browser; the UI auto-generates a strong, shell-safe one on create, shown once), and an **append-only** flag (documents the hardening choice; V1 ships a delete-capable key so nightly prune/compact work). `BackupRepository.borgRepoUrl(server)` renders the `ssh://user@host:port/path` URL from the server's coordinates. CRUD via `GET/PUT/DELETE /backup-repositories[/{name}]`; the response reports the **effective** (resolved) `repoPath` and only `hasPassphrase`. **Name/path hardening ✅** — a repository/server `name` is used verbatim as a shell/path token (the derived repo path, the pass-file name, each `--restrict-to-path`), so it is now validated at construction as a safe **identifier** (`[A-Za-z0-9_-]+`, mirroring `PeerId`) — a space or shell metacharacter (`a; rm -rf ~`) is rejected as `400`; `BackupRepository.sanitizedName` / `BackupServer.sanitizedName` slug operator input (spaces → `-`) and the UI live-slugs the name field so a bad name can't be submitted. Operator-settable **path** fields (`repoPath` override, `baseRepoPath`, `serverDataPath`) are validated to a safe-path charset (`[A-Za-z0-9._/-]+`), `host` to `[A-Za-z0-9.-]+`, `borgUser` to `[A-Za-z0-9._-]+`. As defense in depth `BorgCommand` **single-quotes every borg path** — the repo URL at all six sites (list/info/init/prune/compact/create, the create target rendered as adjacent `'URL'::'ARCHIVE'` tokens) and each `--restrict-to-path` — and the tolerant file adapters **skip** (don't crash on) a pre-fix entry whose name is now invalid.
- **Backup job ✅** — a per-machine spec: `machineName` (a VPN peer's canonical name), `repositoryName`, `sourcePaths` (≥1), `excludes`, retention (`keepDaily`/`keepWeekly`/`keepMonthly`, at least one > 0), `compression` (default `zstd,6`), `enabled`, and `backupAsRoot` (see below). The job owns the archive-naming convention (`archiveNameTemplate()` / `archiveGlob()`) so prune/list scope to just this job's archives in a fleet-shared repository. CRUD via `GET/PUT/DELETE /backup-jobs[/{name}]`.
- **On-demand run ✅** — `POST /backup-jobs/{name}/runs` starts a **backup run** now; `GET /backup-jobs/{name}/runs` lists a job's runs. A run has a `BackupRunStatus` (`RUNNING` → terminal `SUCCESS`/`WARNING`/`FAILED`, or `UNKNOWN` when a result can no longer be resolved). The exit-code→status rule follows borg's own contract and is a domain decision on `BackupRun.fromExitCode`/`completedFrom`: `0` = SUCCESS, `1` = WARNING (the archive was created but some files were skipped — e.g. unreadable by the non-root SSH user), `>= 2` = FAILED. WARNING is terminal but non-failing (`isFailure()` false), so it never pages admins and can itself all-clear a previously failing job. **The frontend never polls a run's outcome**: when `BackupRunner.pollRunningRuns` settles a run this tick it publishes a `run-settled` event (`{jobName,status}`) on the `backups` SSE topic (a publish failure is swallowed, never breaking the sweep), and the browser — guarding on the job it launched so an event for another job never refreshes the wrong card — re-fetches just that job's latest run once and renders it. The passphrase 0600 file and each run's `.rc`/`.log` state live in a per-host **work dir** — `~/.vaier-backup` on the target host (`rest.BackupWorkDirResolver` resolves the SSH user's `$HOME` over SSH and caches it), falling back to `/tmp/vaier-backup` when `$HOME` can't be resolved. It is the SSH user's own home, not a root-owned `/var/lib` path the non-root borg user could not create; the absolute path is resolved in the orchestration and passed into every `BorgCommand`, because borg runs `BORG_PASSCOMMAND` without a shell so an embedded `$HOME` would never expand.
- **Run diagnostics ✅** — a run captured borg's output in `summary` all along, but the UI only ever said *that* a run had warnings, never *what* they were: the detail was reachable only by reading `backup-runs.yml` on disk. Deciding which of a raw borg summary is fit for a human is a **domain decision** and lives on the entity as `BackupRun.diagnostics()`: borg pretty-prints its `--json --stats` object as a block whose braces sit alone at column 0, so the block is identified **structurally** (opens at the first line that is exactly `{`, closes at the first following line that is exactly `}`) and removed, keeping every line outside it — including borg prune's "Keeping archive (rule: …)" report, which follows the object on a clean run, so the JSON is *not* simply a trailing suffix. A summary with no such block (`sh: 1: borg: not found`) is diagnostics in its entirety; the method is total (never throws, never null, empty on a clean run) and, like `summary`, never carries the passphrase. `RunResponse` exposes it beside the raw `summary`, and on the Backups page the status badge of a **warnings**/**failed**/**unknown** run with diagnostics becomes a **disclosure** — reusing the page's existing `.bk-disclosure` caret control — that opens a monospace, horizontally scrollable panel of the lines. A clean run has no diagnostics and so gets **no affordance at all**, keeping the happy path quiet. The warning/failure toasts now point at that disclosure instead of dead-ending.
- **Back up as root ✅** — Vaier runs borg over SSH as the machine's credential user (e.g. `ubuntu`), never root, so **every file in a job's source paths that user cannot read is silently skipped**: borg exits 1, the run settles WARNING, and the archive has holes. The live fleet had exactly that — a mosquitto broker database owned `1883:1883` mode `0600` and a pihole file owned `root:root`, both missing from otherwise "successful" archives. Per-file `chmod` is whack-a-mole (every new container volume is a fresh silent hole), so `BackupJob` gains a `backupAsRoot` component, **default/opt-in false** — a job never escalates itself, and a job file written before the component existed deserialises to `false` (`BackupJobFileAdapter`), never to root. When it is on, **every** borg invocation in the run chain (info, init, create, prune, compact) is made through the one binary `BorgCommand.borgBinary` renders — `sudo -n HOME=… BORG_BASE_DIR=… BORG_RSH=… BORG_PASSCOMMAND=… borg` — which handles the four things sudo would otherwise break. **SSH cannot find the key or the host pin** — the borg client key and the pinned backup-server host key both live in the *SSH user's* home, and under sudo ssh runs as root, reading `/root/.ssh/`, where neither exists. **`HOME` does not fix this**, and the first cut of this feature wrongly assumed it did: **OpenSSH ignores `$HOME`** and resolves `~` from `getpwuid(getuid())`, i.e. the passwd entry of the UID it runs as. Since host-key verification precedes publickey auth, every as-root run died at the server with `Host key verification failed` (not the `Permission denied (publickey)` the code's comments predicted). The fix is **`BORG_RSH`** — `ssh -i <sshHome>/.ssh/id_ed25519 -o UserKnownHostsFile=<sshHome>/.ssh/known_hosts` — naming both files as absolute literals under the SSH user's home, so root's ssh is pointed straight at them; it carries neither `-p` nor the `user@host`, which borg appends itself from the repo URL. **HOME** is still set (an absolute literal, never a `$HOME` for the command to expand — sudo would reset it first) for the tools that *do* honour it, but it is no longer load-bearing for ssh. The **reset environment** (sudo discards the shell's exported `BORG_PASSCOMMAND`, so it is passed on the sudo line — exactly what the sudoers `SETENV:` tag permits, which equally covers `BORG_RSH`). And **root's borg cache** (`BORG_BASE_DIR` is isolated to a root-owned `<workDir>/root`, so root's cache cannot leave files that break a later non-root run of the same job; the first as-root run therefore rebuilds the chunk cache, which is slower but correct — dedup is content-addressed server-side, so nothing is stored twice). The SSH home is required either way, since it is what the `BORG_RSH` paths are built from: `BackupWorkDirResolver.homeFor` resolves and caches the SSH user's `$HOME` — the primitive the work dir already derived from — and, unlike `workDirFor`, has **no fallback**, because a missing home does not degrade an as-root run, it breaks it. `BackupRunner` therefore **refuses** an as-root run whose home can't be resolved (a recorded FAILED run with a clear message) rather than launching it to die at the backup server. A job with the toggle **off** renders a byte-for-byte unchanged command. **Security** — only the borg binary is ever sudoed: never `sudo env …`, never `sudo sh -c …`, never a wildcard, any of which is a trivial root shell for anyone who can run it and would turn a backup feature into a local privilege-escalation hole. **The grant ✅** — `BorgClientSetupScript` installs a sudoers drop-in at `/etc/sudoers.d/vaier-borg` giving the invoking SSH user (`$SUDO_USER`, `logname` as fallback) `NOPASSWD: SETENV:` for `/usr/bin/borg, /usr/local/bin/borg` and nothing else. It is written to a temp file, validated with `visudo -c`, and only then installed `0440 root:root` — never written straight into `/etc/sudoers.d`, where a malformed drop-in can lock the host out of sudo entirely; a host with no `visudo` is left alone with a loud warning rather than having an unvalidated file dropped on it. The script's old `exit 0` when borg was already present was **removed** (it would have meant the grant never landed on any host that already had borg — i.e. every host in the fleet): the install is now *skipped*, and the script continues to the grant. **Readiness ✅** — `CheckBackupPrerequisitesUseCase.checkRootBorg` / `BackupProvisioner` probe `sudo -n borg --version` (`BorgClientSetupScript.rootBorgProbe`/`parseRootBorg`), which passes only when the drop-in is in place *and* borg is on sudo's `secure_path`; a guarded-out host, timeout, or thrown SSH error reports a negative, never an optimistic yes. `GET /backup-jobs/{name}/provision/check` carries `backupAsRoot` + `rootBorgOk`, and the row is **only probed and shown for a job that opted in** — a job running as the SSH user is not "not ready" for lacking a grant it will never use, and there is no pointless SSH round trip. The job modal gains the **Back up as root** checkbox, and the readiness row offers **Prepare client** inline when the grant is missing.
- **Nightly schedule ✅** — Vaier runs every *enabled* job once a day at a configurable hour. The hour lives on the **ungated Settings** surface (not `@RequiresEnterprise`), like the disk-pressure threshold: `backupScheduleHour` in `vaier-config.yml` (`domain.VaierConfig`, default `2`, valid `0–23`), carried on `GET /settings/config`, updated via `PUT /settings/backup-schedule` (`UpdateBackupSettingsUseCase` on `SettingsService`). `BackupRunner` gates its scheduled sweep on the current hour matching `ConfigResolver.getBackupScheduleHour()`, read in the injected `Clock`'s zone. That clock is `Clock.systemDefaultZone()` (it was `systemUTC()`, which made the hour silently mean UTC — a schedule set for 02:00 fired at 04:00 in Europe/Oslo), so the container's `TZ` chooses the zone: `docker-compose.yml` sets `TZ: ${VAIER_TZ:-UTC}` on the `vaier` service. `GET /settings/config` also carries `backupScheduleZone`, taken from that same clock, so the UI names the zone rather than saying "server local time" and the label can never drift from the hour that actually runs.
- **Failure email alerts ✅** — a failed run emails every **admin**-role **access entry** via `NotifyAdminsOfBackupFailureUseCase` (on `BackupService`), reusing the same `sendToAdmins` SMTP path as the other alerts (silent when SMTP is unconfigured). `BackupFailureTracker` keeps it from re-paging on repeat failures of the same job.
- **Server-down alerts ✅** — `BackupServerWatcher` (`@Scheduled`, mirroring `RemoteDiskWatcher`) TCP-probes each backup server's borg port; `BackupServerHealthTracker` requires **two consecutive** failed probes before crossing to DOWN (a blip never pages) and a single success to recover. On a transition it emails admins once via `NotifyAdminsOfBackupServerDownUseCase` — `REFUSED` → "borg server is down on `<host>`", `UNREACHABLE` → "`<host>` is unreachable" — and once more on recovery; a Vaier restart never re-pages.
- **Guided provisioning ✅** — `GET /backup-jobs/{name}/provision/check` reports host readiness for a job's machine: whether borg is installed and its version (`borgInstalled` / `borgVersion`), whether that version is supported (`borgSupported`), whether the machine can reach the server (`nasReachable`), and — the checks that kill the **false all-green** — whether the client's key is actually **trusted on the server** (`borgAuthOk`), the server's borg version (`serverBorgVersion`), and whether client and server borg majors are **compatible** (`versionsCompatible`; borg 1.x/2.x repo formats are incompatible, so `BorgVersion.isCompatibleWith` requires matching majors) — plus, **only for a job that opted in to Back up as root**, whether borg can actually run as root on that machine (`backupAsRoot` / `rootBorgOk`; see that bullet). `borgAuthOk` is proved by running `borg info` on the repository's URL **from the client host** (via `BorgCommand.serverAuthProbe`, unlocked by the same `BORG_PASSCOMMAND` pass file as a run): reaching `borg serve` at all — even the "repository does not exist" of a not-yet-`init`-ed repo — means the key authenticated. This deliberately replaced a `borg --version`-over-SSH probe, which the **restricted, forced-command** client key silently broke (`command="borg serve …"` discards the requested command, so `borg --version` never runs). Because that same forced command makes the server's version unknowable over SSH, `serverBorgVersion` is **derived from the pinned image** (`BorgServerImage.borgVersion()` → borg 1.4.3) for a **Vaier-managed** server and left **unknown** for an **adopted** one; `versionsCompatible` fails closed (false) whenever either version is unknown. A green borg + reachable server no longer reads as ready on auth alone; the UI offers **Authorize host** inline when `borgAuthOk` is false. `POST /backup-repositories/{name}/provision/init` runs `borg init` from a machine that references the repository (`initialized` / `alreadyExisted`); it returns `409` when no job targets that repository, since it has no host of its own to init from. It is **not surfaced in the UI** — a **backup run** initialises its own repository when absent, and a button on a repository card would necessarily fail on a repository no job references yet.
- **Prepare client (install borg) ✅** — a job on a host with no borg client died with `exit 127` / `borg: not found` (the real NUC 02 incident); Vaier provisioned the *server* but never prepared the *client*. Two parts. **(1) Fail fast** — `BackupRunner.runJob` now probes `borg --version` on the job's machine (one pre-flight SSH round-trip, after the machine/SSH/credential/server guards and before the detached launch) and, when a clean non-timed-out probe exits non-zero or fails to parse (`BorgVersion.parse`), records a **FAILED** run with `"borg is not installed on <machine> — run Prepare client"` and never launches. A probe **exception** is treated as "couldn't verify" and the run **still launches** (a flaky probe must never block a working host — the run settles cleanly if borg really is missing); only a definite non-borg result blocks. **(2) Prepare client** — `POST /backup-jobs/{name}/prepare-client` (job-scoped; the readiness panel acts from the job, which knows its machine) resolves `job.machineName()` and runs `PrepareBackupClientUseCase` on `BackupProvisioner`, mirroring server provisioning. `BorgClientSetupScript.generate()` (pure domain, no args — it detects everything on the host) emits an **idempotent** bash install: root check, widened `PATH`, the right package per detected manager (`apt-get`→`borgbackup` after `apt-get update`, `dnf`/`yum`/`apk`/`zypper`→`borgbackup`, **`pacman`→`borg`** — Arch's package name differs), else `exit 5`; the install step is **skipped** (not an early `exit 0`) when borg is already present, so the script goes on to install the borg-as-root sudoers grant on an already-prepared host. The install needs root but Vaier SSHes non-root, so `prepareClient` probes **passwordless sudo** (`BorgClientSetupScript.passwordlessSudoProbe` → `sudo -n true`): with it, the script is launched **detached** under `sudo -n bash` (`BorgClientSetupScript.detachedLaunch`, base64 + `nohup`, rc/log files — it can outlast the 20 s exec cap); without it, Vaier **stages** the script (reusing `BorgServerSetupScript.stageScript`/`parseStagedPath`) and returns `scriptOnly` with the exact `sudo bash <path>` command (never a raw `curl | sudo bash`). `PrepareResult` reuses `ProvisionResult`'s shape/semantics (`prepared`/`scriptOnly`/`started`/`message`/`stagedScriptPath`). **The frontend never polls** the install: a backend `@Scheduled` sweep (`BackupProvisioner.pollInFlightPrepares`, every 3 s) reads the launched install's on-host `.rc` over SSH and, on settle, publishes a `prepare-client-settled` event (`{machineName,state}`) on the **`backups`** SSE topic; the browser opens `GET /backup-jobs/events` (`ForSubscribingToEvents.subscribe("backups")`, mirroring `VpnPeerRestController`'s `/events`) and re-checks readiness on the pushed event. A `GET /backup-jobs/{name}/prepare-client/status` one-shot (reusing `BorgCommand.pollStatus`/`parsePoll`/`fetchLog`) remains for API symmetry. All new routes are `@RequiresEnterprise` (402 on Community).
- **Archive browsing ✅** — `GET /backup-repositories/{name}/archives` lists the borg archives (point-in-time snapshots) in a repository, each a `domain.Archive` (name, id, time). `Archive.parseList` reads `borg list --json` in the domain and, like `RemoteDiskUsage.parse`, never throws — bad input yields an empty list.
- **Admin UI ✅** — the **Backups** tab (`backups.html`), reached at `#backups`, is where the fleet's one backup server is **provisioned and operated** (setup.sh download, provision, authorize-host) — the server is rendered **read-only** here, its create/edit/delete having moved into the Explorer's `backup` entry (§6.21) — **repositories are now rendered read-only too** (name, effective path, badges), their create/edit/delete and archive browsing having likewise moved into the Explorer (§6.21), with a "Manage repositories in the Explorer" link (the "+ New repository" button, the repository create/edit modal, and the archive-browser modal are gone from `backups.html`/`.js`); **jobs are now rendered read-only too** (name, machine, target repository, source-path count, retention, an enabled/disabled badge), their create/edit/delete, **Run now** and enable/disable toggle having likewise moved into the Explorer's `backup` entry (§6.21), with a "Manage backup jobs in the Explorer" link in both the empty state and the list. The one job control that stays is the per-job **readiness check** — the guided-provisioning bridge (with inline **Authorize host** when the key isn't trusted, and inline **Prepare client** when borg isn't installed or — on a **Back up as root** job — the borg-as-root grant is missing). The **run-status display is gone from this page** — the per-job last-run badge, the run-diagnostics disclosure, and the on-demand **Run now** button (the "+ New job" button, the job create/edit modal, and the generic delete-confirm modal are gone from `backups.html`/`.js` too) — and the page **no longer consumes the `run-settled` event** (the backend still publishes it for the Explorer). The prepare-client install and server provisioning run detached on the host and the browser learns each one finished over a **single `backups` SSE stream** (one `EventSource('/backup-jobs/events')` now handling only `prepare-client-settled` and `provision-settled`) — it never polls; the backend sweeps do all the host-side polling. There is no `setInterval` in `backups.js`. Machine pickers read `GET /machines` (peers **and** LAN servers such as NAS), not `/vpn/peers`. Locked gate state on Community.

**Backlog (deferred):**
- **The Vaier server as a backup client ✅** — the Vaier server is the one machine in the fleet that *is* the Docker host rather than something behind the tunnel, so it inherited no relay LAN routes: `traefik-lan-routes` and `LanRouteAdapter` write into container network namespaces, never the host's. A backup job for the Vaier server therefore failed every readiness check — `borg info` could not reach the backup server because the *host* had no route to the relay's LAN (`ip route get 192.168.3.3` left via the default gateway). The `host-lan-routes` service now mirrors `traefik-lan-routes` into the host's own netns, installing each relay peer's `lanCidr` via the wireguard container. Because the host cannot use Docker DNS, wireguard's bridge address is **pinned** (`x-wireguard-bridge-ip`, high in the subnet so Docker's sequential allocator can never collide with it) rather than mounting the Docker socket into a `NET_ADMIN` host-netns container. `wireguard-masquerade` already SNATs `172.20.0.0/16` out of `wg0`, and a host packet routed at the bridge is sourced from `172.20.0.1`, so return traffic needs no extra rule. borg itself still has to be installed on the host via **Prepare client**, and its key trusted via **Authorize host**, like any other client.
- **Restore from the UI — superseded by [#321](https://github.com/getvaier/vaier/issues/321) (Explorer).** [#319](https://github.com/getvaier/vaier/issues/319) proposed a restore modal hanging off the archive list; it is closed in favour of the Explorer, where restore is not a feature of its own but one destination of a general copy (see **6.20**). Until Explorer's Time slice lands, recovery is still via the borg CLI.
- **Browse an archive's files and download a selection 🔲 — folded into [#321](https://github.com/getvaier/vaier/issues/321).** Now Explorer slices 2 (download) and 3 (archive browsing via `borg mount`), rather than a separate archive-contents browser.
- **`borg check` weekly integrity verification** — a scheduled repository consistency check, separate from the nightly create/prune.
- **Per-archive size** via `borg info` — surface each archive's original/compressed/dedup size in the archive list.
- **True append-only hardening** — a separate management key so the client key can be genuinely append-only while prune/compact run under the management key (V1's `appendOnly` flag only documents the intent; the shipped key is delete-capable).

---

### 6.20 Explorer 🟡 (in progress — epic [#321](https://github.com/getvaier/vaier/issues/321))

One file browser across the fleet. A file has a coordinate — a **machine**, a **path**, and a point in
time — and Vaier is the only node with SSH to every machine, so it is the only place a fleet-wide file
tree can be assembled. **Replaces [#319](https://github.com/getvaier/vaier/issues/319)** (the stopgap
restore modal): restore is not a feature of its own, it is one destination of a general copy.

Browse, cross-machine copy and download are **Community**; the time rail, coverage and restore are
**Enterprise** (they are Fleet Backup features).

**Slices:**
- [x] **1 — Browse ✅.** `sshd-sftp` dependency, `ForBrowsingRemoteFiles` port + `MinaSftpAdapter`,
  `GET /machines/{name}/files?path=…` directory listings on any SSH-capable machine, and the read-only
  Explorer page over them. Community, and authenticated like every other machine endpoint.
- [ ] **2 — Move 🟡 (backend delivered).** Clipboard, cross-machine Transfer (streaming, tracked,
  SSE-settled), download to browser, size warning. Community. *(The backend landed — cross-machine
  **Transfer**, **download**, and transfer tracking with SSE; see **Delivered in slice 2** below. The
  **Clipboard** UI and the **size warning** are the frontend half, in progress. **Coverage** (slice 4) and
  delete/rename/new-folder (slice 5) remain.)*
- [ ] **3 — Time.** `borg mount` lifecycle, the time rail, archive overlay on the tree, restore as
  paste-from-the-past. Enterprise. *(The `borg mount` lifecycle backend and the **time-rail UI** both landed via
  §6.21 slice D — mount-on-demand, idempotent, idle-swept, with the rail scrubbing a machine's archives newest-first
  and the shell relighting into the past palette; restore-as-paste is what remains, and it rides on Clipboard/paste
  from slice 2.)*
- [ ] **4 — Coverage.** Coverage dots, draft-then-save to the backup job, "Uncovered only" filter,
  one-job-per-machine enforcement. Enterprise.
- [ ] **5 — Mutate 🟡 (delete backend delivered).** Delete, rename/move, new folder, behind a typed-confirmation
  gate. Community. *(The **delete** backend landed — recursive delete over SFTP, an SFTP-root guard, and
  `DELETE /machines/{name}/files`; see **Delivered in slice 5** below. **Rename/move** and **new folder** remain,
  and the typed-confirmation gate is the frontend half.)*

**Delivered in slice 1 (backend):**
- **A shared SSH target resolver.** Resolving a machine name to *where to connect, with which credential,
  against which pinned host key* was private to `TerminalService`. Explorer needs exactly the same thing,
  and a second copy of it would have been a second place trust-on-first-use is decided — a security
  hazard, not just duplication. It is now the driven port **`ForResolvingSshTargets`** with
  `MachineSshTargetAdapter` behind it, composing the machine registries, the credential vault and the
  host-key pin store. `TerminalService` depends on the port instead, with no behaviour change. The two
  *decisions* inside it went to the domain, where they belong: **`SshAddress`** (a peer answers at its
  tunnel IP, a LAN server at its `lanAddress`, the Vaier server host at its resolved host address —
  otherwise the machine does not exist) and **`SshTarget.needsPinning`** (pin only an unpinned host that
  actually presented a key; a mismatch is a refusal, never a silent re-pin). For the same reason the
  connect + authenticate + host-key-verify machinery itself was extracted from `MinaSshSessionAdapter`
  into one **`SshConnector`**, now shared by the terminal's PTY/exec channels and the Explorer's SFTP
  client: one copy of the code that decides a host key is trustworthy.
- **`domain.FileEntry`** — name, absolute path, directory-or-not, size, modified instant. It owns the
  decisions: what counts as a browsable **path** (`normalisePath` — absolute, `.`/`..` resolved, a climb
  above the root **refused rather than clamped**, NUL refused), how a child's path is built from its
  directory (so a remote answering `readdir` with a path-shaped name cannot fabricate an entry outside
  the directory listed), and what order a directory reads in (directories before files, then by name).
  Shell metacharacters are deliberately **preserved**: SFTP is a binary protocol with no command line to
  inject into, and `$(…)` is a legal Linux filename that must stay reachable.
- **`ForBrowsingRemoteFiles` + `MinaSftpAdapter`** — a listing owns its whole lifecycle (short-lived
  `SshClient`, session, SFTP channel, all closed again), so browsing cannot leak an SSH connection per
  directory clicked. Like a **remote command**, a listing reports the host key the machine presented, so
  a machine browsed before it ever had a terminal opened on it is still pinned on first use.
- **`ExplorerService`** (new domain, one service) implementing `BrowseFilesUseCase`, and
  `ExplorerRestController` (`GET /machines/{machine}/files?path=…`, DTOs as inner records). The path
  arrives from the browser and is normalised in the domain **before** any machine is resolved or any
  connection opened — a hostile path is a `400`, never a connection.

**Delivered after slice 1 — the SFTP root ✅ ([#326](https://github.com/getvaier/vaier/issues/326)):**

A file's coordinate is *(machine, path, point in time)*, and on the NAS that was quietly false. DSM chroots
its SFTP subsystem into `/volume1` but not its exec channel, so the Explorer called geir's home `/homes/geir`
while `df`, borg and the operator's own terminal all called it `/volume1/homes/geir` — one directory, two
coordinates. Everything downstream would have inherited the lie: slice 4's coverage compares a **backup
job**'s source paths against the tree and would have reported a backed-up directory as **uncovered**.

- **`domain.SftpRoot`** — the value object, and the decision. The jail is the *difference* between the two
  channels' names for the SSH user's home: when the SFTP name is a tail of the machine's own name, what is
  left in front of it is the jail. It maps both ways (`toJailPath` down into the jail for the SFTP call,
  `anchor` back out onto the machine's true paths), and **refuses to guess** — two homes that do not line up
  resolve to `NONE`, which changes nothing about a machine's paths. Not knowing is safe; a wrong prefix would
  silently corrupt every path on the machine, in both directions.
- **The NAS answers neither probe the way the issue predicted**, which is worth recording because it shaped
  the design:
  - `$HOME` over the exec channel is `/var/services/homes/geir` — a DSM **symlink** onto the physical
    `/volume1/homes/geir`. A chroot is a *physical* subtree, so an aliased home can never line up with one.
    Hence `SshHome.PHYSICAL_PROBE_COMMAND` (`cd "$HOME" && pwd -P`), used for root resolution only; the
    backup work dir keeps asking the plain way, since it only needs to *reach* the home.
  - SFTP's `realpath(".")` is **`/`** — the jail root itself, which says nothing about *where* that root is.
    So when the direct answer does not line up, the home is **located** instead: `SftpRoot.jailCandidates`
    names the paths a jail could know the home by (the home itself, then each shorter tail, never `/`), and
    the SFTP subsystem is asked which it can see. **The true home is always the first candidate**, so an
    unjailed machine matches immediately and can never be handed a jail it does not have — the property that
    makes the search safe to run fleet-wide.
- **`ForResolvingSftpRoots` + `CachingSftpRootAdapter`** — the exec home over the existing
  `ForRunningSshCommands` (no third way to reach a host) and the SFTP half over `ForBrowsingRemoteFiles`
  (`home`, plus `firstDirectory` for the search, which probes all candidates over **one** connection).
  Resolved once and cached: a root does not move, and without the cache every directory clicked would cost
  extra SSH connections to a machine behind a VPN. A machine that cannot be *reached* is not cached — a host
  that was merely asleep must not be branded rootless for the life of the process. **`domain.SshHome`** owns
  the `$HOME` probes, which `BackupWorkDirResolver` now shares rather than spelling out a second time.
- **The Explorer speaks the machine's coordinates.** `GET /machines/{machine}/files` now answers
  `{root, path, entries}` rather than a bare array — an array had nowhere to carry the root, and the browser
  cannot deduce it. Omitting `path` asks *where does this machine's tree begin?*, since a chrooted machine
  cannot be asked about `/` at all. A path above the root (`/volume2` on the NAS) is a `400` carrying its own
  sentence — **never an empty directory**, and never the jail's contents under another path's name.

**Notes / open risks:**
- **`borg mount` is verified ✅** — confirmed end-to-end on the Vaier server (mount + list + read over SFTP with
  Vaier's own credential). The catch the fleet actually hit: Debian's borg runs under a system `python3` shipping
  neither `pyfuse3` nor `llfuse`, so `borg mount` failed everywhere with "no FUSE support". `python3-pyfuse3` fixes
  it, and §6.21 slice D's `BorgClientSetupScript` now installs it (via **Prepare client**), non-fatally so a host
  that can't mount keeps backing up. Apalveien 5 and Colina 27 still need the binding installed.
- **Browsing is not confined to a subtree by Vaier** — but the *machine* may confine it. There is no chroot
  of Vaier's making: an operator can browse anywhere the machine's SSH user can read, by design (the fleet's
  Docker volumes live outside any one home directory), and the SSH user's own permissions are the boundary.
  Where an SSH daemon chroots its own SFTP subsystem, that jail is real and Vaier maps around it (the **SFTP
  root**, above); what lies above it cannot be browsed at all, however readable it is over SSH. Path
  validation still exists to reject *nonsense* paths, not to jail a legitimate one.
- **A symlink to a directory currently lists as a file** (the adapter reports what `readdir` says without
  a second `stat` round-trip), so it can't be entered. Worth revisiting when the UI lands.

**Delivered in slice 2 (backend — the Clipboard's engine):**
- **A file has a coordinate; the destination names the operation.** A different machine is a **copy**; the
  browser is a **download**. Vaier sits at the VPN hub and is the only node with SSH to every machine, so a
  cross-machine copy is an SFTP read from A relayed through Vaier's own JVM to an SFTP write on B — no
  host-to-host trust, no keys between peers, nothing new in the network model.
- **The relay runs in Vaier's JVM, not detached on a host.** Unlike `BackupRunner`'s host-detached
  `nohup`+`.rc` borg pattern (which can't apply to a cross-machine relay without host-to-host trust), a
  **Transfer** runs on a background `ExecutorService` and lives in an in-memory registry — an ephemeral live
  operation, not persisted history. It deliberately does **not** reuse the backup run store (which keys one
  latest run per job and a Transfer would clobber). Streaming uses a fixed buffer, so memory stays flat
  regardless of file size. A Vaier restart simply loses in-flight transfers (acceptable for V1).
- **`ForBrowsingRemoteFiles` gains the byte-moving primitives** (translation-only on `MinaSftpAdapter`, same
  error mapping as `list`): `stat`, `download` (stream a file to an `OutputStream`), `mkdirs` (idempotent),
  and `copyFile` (the flat-memory relay holding both SSH sessions, reporting cumulative bytes). A directory is
  walked with `list`; a file streams via `copyFile`. The item is copied **into** the destination directory
  keeping its basename, and `totalBytes` is measured by a pre-walk so progress has a denominator.
- **`Transfer` is a domain entity** owning the decisions: both paths absolute, a live-source copy onto its own
  file refused as a no-op (but a **past-source** one onto the same live path is a **restore**, and is
  allowed), and forward-only state (`RUNNING` → `DONE`/`FAILED`). **You can only paste into the present** — a
  transfer's destination never carries a time coordinate; its source may be a past archive.
- **The coordinate mapping is not re-implemented.** `ExplorerService` exposes `ResolveFileCoordinateUseCase`
  — the very mapping browsing uses (the SFTP jail in the present, the archive mount over the jail in the past)
  — and the `TransferRunner` (in `rest/`, beside `BackupRunner`) and the download path both resolve through
  it, so a second copy can never drift.
- **Endpoints.** `POST /transfers` (start; 400 on a non-absolute path or a no-op), `GET /transfers` (live +
  a capped settled tail, so a reconnecting browser can repaint), `GET /transfers/events` (the **`transfers`**
  SSE topic, publishing throttled `transfer-progress` and a final `transfer-settled`), and
  `GET /machines/{machine}/files/download` (streams the file as an `attachment`, `at` allowed since a download
  is a read). A directory downloads as a zip of its whole tree, built by walking it and streaming each file
  straight into the zip — flat memory, never buffered whole; entry names are relative to the directory
  (`sub/file.txt`), an empty subdirectory becomes a zip directory entry, and `Content-Length` is omitted (a
  zip's size isn't known ahead of time). **The whole walk runs over one SFTP connection** (`ForBrowsingRemoteFiles.walkTree`,
  see the zip-corruption fix below). A file that turns out unreadable mid-walk is **skipped** — its stream is
  opened before the visitor's zip entry, so a permission-denied or vanished file never even opens an entry and
  simply does not appear in the archive, rather than failing the whole download. All admin-authed, never
  anonymous. The **Clipboard** UI and the source-size **size warning** are the frontend half.
- **Zip-download corruption fixed (#321).** The zip walk used to call `download` — a fresh SSH connect +
  authenticate + teardown — **once per file**. Zipping a folder of dozens of files over the VPN became dozens
  of sequential connect cycles (~1s each), blew past Spring's default async-request timeout, and cut the
  response off mid-stream, so the browser received a truncated, corrupt zip (no end-of-central-directory), plus
  a secondary `HttpMessageNotWritableException` from the error handler trying to serialise an `ApiError` over
  the committed `application/zip` response. Three-part fix: (1) `ForBrowsingRemoteFiles.walkTree(target, rootPath,
  RemoteTreeVisitor)` — `MinaSftpAdapter` opens **one** connection and walks the whole tree over it (the same
  single-connection discipline `delete` already used), calling back `visitor.file(relativePath, stream)` /
  `visitor.directory(relativePath)`; `ExplorerService` implements the visitor by writing zip entries, so the
  adapter never learns what a zip is and connection ownership lives in the adapter; (2) `WebConfig.configureAsyncSupport`
  sets the default async timeout to `-1` (no timeout) so a legitimately long streaming download isn't aborted —
  SSE emitters set their own `Long.MAX_VALUE` timeout and are unaffected; (3) `GlobalExceptionHandler.handleUnexpected`
  returns `null` when the response is already committed, so a mid-stream failure ends the stream instead of
  throwing the secondary serialise error.

**Delivered in slice 5 (backend — delete):**
- **Delete is present-only and destructive.** A file or directory is removed from a machine's live
  filesystem; a directory is removed recursively (emptied, then removed). There is deliberately **no** `at`
  coordinate — you cannot delete the past, because a machine's past (an **archive**) is read-only by
  construction — so a delete only ever touches the present. The frontend gates it behind a typed
  machine-name confirmation; the backend's job is to delete safely and report clearly.
- **`ForBrowsingRemoteFiles` gains `delete`** (translation-only on `MinaSftpAdapter`, same error mapping as
  `list` — `NotFoundException` / `PermissionDeniedException` / `SshConnectException`). **The recursive walk
  lives in the adapter**, not the service: a directory is walked depth-first over a **single** open SFTP
  session (`readDir` → recurse into subdirectories and `remove` files, then `rmdir` each directory
  bottom-up, skipping `.`/`..`), because holding one connection for the whole tree is the whole point — a
  deep tree behind a VPN that reconnected per entry would be pathological. (Contrast the zip **download**
  walk, which lives in the service because it streams a read and tolerates a connection per directory; a
  delete does not.)
- **Two guards, both domain decisions.** The **SFTP root itself is never deletable** — deleting a machine's
  whole browsable tree is not a paste-shaped mistake to make easy — expressed as
  `SftpRoot.toDeletableJailPath`, which reuses the shared `toJailPath` down-mapping and refuses the one true
  path that maps onto the jail root `/` (`CannotDeleteSftpRootException`, a `400`). The path is normalised by
  the same `FileEntry.normalisePath` discipline browsing uses, so a non-absolute path or one climbing above
  the root is refused before any connection, and a path above the SFTP root stays a
  `PathOutsideSftpRootException`.
- **`DeleteFileUseCase` on `ExplorerService`** (a new narrow use case on the existing Explorer domain
  service — no new service), and **`DELETE /machines/{machine}/files?path=…`** on `ExplorerRestController`
  (no `at`). On success **`204 No Content`**; a missing path is the `404`, a permission-denied the `403`, the
  root guard a `400` carrying its sentence. Admin-authed like the rest of the Explorer. **Rename/move** and
  **new folder** remain.

---

### 6.21 Explorer becomes Vaier's only UI 🟡 (in progress — epic [#323](https://github.com/getvaier/vaier/issues/323))

The **Explorer** (§6.20) stops being *a page for files* and becomes **the tree that is Vaier's operator UI**.
Every page we have today collapses into a renderer for whatever is selected in it — one explorer, many
Inspectors. A file has a coordinate (machine, **path**, point in time) and so does a container, a published
service, an **archive**; Vaier is the only machine with SSH to every other, so it is the only place that
namespace can be assembled.

Three things this buys that a set of pages structurally cannot:

- **It kills the iframe.** `admin.html` renders its sections in `<iframe>`s for exactly one reason: to keep
  the **web terminal**'s live SSH sessions alive across tab switches. Once a `shell` is just an **entry** on
  a machine, the dock lives in the shell and the iframe has no job left — and with it go the trapped modals
  and the missing app-level toast layer.
- **Coverage becomes a property of a path.** A **backup job**'s source paths and a machine's file tree are the
  same namespace, so Vaier can flag an uncovered directory *while the operator is standing in it* — the
  production gap today (jobs cover `/home/geir` only; Home Assistant's database and every Docker volume are
  unprotected) is invisible to a separate Backups page.
- **One search.** One address space means one ⌘K over the whole fleet.

**Slices:**
- [x] **A — The shell ✅.** `explorer.html` is the tree shell: tree rail (fleet root → machines → `files`,
  `shell`), the **path** as the address bar, the **Inspector**, and ⌘K over every entry. The terminal dock
  moved in unchanged — the shell is the first Vaier page that is not a section inside an iframe. Machine
  liveness arrives on the existing `vpn-peers` SSE topic (the frontend never polls). The file browser shipped
  in §6.20 slice 1 keeps working under its own name (`explorer-files.html`) as the backup, and both read a
  directory through one shared `explorer-listing.js`. Sections not yet ported (Infrastructure, Backups, Users,
  Settings, Concepts) are **bridged**: they are entries in the tree whose Inspector is the existing page,
  framed whole — explicitly transitional scaffolding that each later slice deletes. Token additions: `--rail`
  (depth hairlines), `--radius-1` / `--radius-2` (there were seven raw radii and no token). Also fixes the
  latent height bug in `explorer.css` (it subtracted a topbar that is not in the document).
- [x] **B — Files in the tree ✅.** A directory is an **entry**. Expanding one reads it **lazily over SFTP**
  (`GET /machines/{machine}/files`, one directory per expand — never eagerly, never recursively: the fleet is
  on the far side of a VPN, and a tree that walks it eagerly is a tree that hangs). **Only directories become
  rows in the rail** — the rail carries structure, the Inspector lists the contents. Expanding also selects,
  so one SFTP round trip fills both; collapsing does not navigate away. A directory's children are **cached by
  machine and path**, so collapsing and re-expanding costs nothing, and a machine leaving the fleet takes its
  cached directories with it. Each directory owns its own `VaierListing` reader — and therefore its own
  monotonic ticket — so a re-read supersedes the read before it while several slow directories expanded at
  once all land (a single shared ticket would strand the earlier ones spinning forever). A directory that
  cannot be read **fails visibly and locally**: the row wears the failure and carries the server's own
  sentence ("Not allowed to read /root as geir."), and the Inspector shows it in full — it never pretends to
  be empty and never spins forever. ⌘K finds every directory the operator has already expanded, by walking the
  cache — it never crawls the fleet to build an index.
  **Liveness, corrected.** The rail's status dot knew only WireGuard **peers**, so every **LAN server** — the
  NAS, the NUCs, the Roon boxes, the machines the operator actually SSHes into — sat grey, and grey claimed
  "Vaier has no idea" when Vaier already knew. The dot now folds in `GET /lan-servers`, whose `status` is a
  **`MachineStatus`** the domain has already decided (`MachineStatus.forLanServer`), so the browser picks a
  colour and never recombines reachability with the Docker scrape: `OK` → up, `DEGRADED` → amber (on the
  network, Docker scrape failing), `DOWN` → down, `UNKNOWN` → **idle, never green** — no probe has run, and a
  green dot there would be a claim Vaier cannot make. The **Vaier server** is up because it is serving the
  page; it is never probed. Refreshes on the `lan-servers-updated` event **already published on the
  `vpn-peers` topic** the shell is subscribed to — no new endpoint, no new topic, no poll.
- [x] **C — Machines ✅.** A machine's **containers**, **published services** and **disk** are **entries**, so
  ⌘K finds them and the Inspector renders them. The machine grows them **conditionally and honestly** — it is
  not a uniform template: `files` + `shell` only with **SSH access**, `containers` only when it **runs Docker**,
  `services` only when something is actually published from it, `disk` only with SSH access. Both facts already
  travel on `GET /machines` (`sshAccess`, `runsDocker`), so the tree *asks* rather than assumes, and never
  opens an entry onto nothing.
  **Containers are read-only, deliberately.** `DockerServiceRestController` is `@GetMapping`s only: Vaier has
  no endpoint to start, stop or restart a container, and none to fetch logs. So **no container verb ships** —
  the Inspector shows image, version, state, ports, networks and container id, and offers nothing Vaier cannot
  honour. A control that looks like a verb and does nothing is a lie about what works; a test pins the absence
  (`noContainerVerbIsShipped_becauseNoEndpointBacksOne`). Container control is its own change with its own
  security thinking (see backlog).
  **A published service is one thing with three homes** — a container on a machine, a Traefik route, a DNS
  record — and the Inspector says so: DNS record + **DNS state**, route state, backend, **path prefix**, the
  backing container's image/version, **auth mode** and **allowed groups** (read from `/access/services`). Which
  machine a service is filed under is decided by the rule `vpn-peers.js` already uses (a **LAN service** by its
  LAN server, falling back to the relay peer; the hub's own routes on the **Vaier server**; everything else by
  host) — a second rule would file one service under two machines on two pages. **One verb ships**, because one
  is backed: **Unpublish** (`DELETE /published-services/{dnsName}`, path-prefix-aware), behind a confirmation,
  since it tears down a route and a DNS record. **Publishing is deliberately not in the tree** — it needs a
  subdomain / auth-mode / backend form and stays on Infrastructure rather than being half-built here.
  **Liveness, still never polled.** The shell now holds a **second** `EventSource`, on the existing
  `published-services` topic (`service-updated`, `publish-traefik-active`, `publish-rolled-back`) — a different
  topic on a different controller, and the shape `vpn-peers.js` already has. Two streams is the ceiling; a test
  pins the count. The containers scrape is fired unawaited at init (so ⌘K can find a container nobody went
  looking for) and re-read on that stream; services are awaited (the tree cannot be honest about a `services`
  entry before it knows there is one); a disk is read only when its entry is looked at — a fleet-wide `df` on
  page load would wake every sleeping machine to answer a question nobody asked.
  **Exactly one new endpoint: `GET /machines/{machine}/disk`.** `RemoteDiskWatcher` has taken this reading on a
  schedule since the disk alerts shipped and only ever *emailed* about it — the number Vaier already knew could
  not be looked at. `GetMachineDiskUsageUseCase` on `MachineService` runs `RemoteDiskUsage.DF_COMMAND` over the
  same `ForRunningSshCommands` exec port every other **remote command** uses (pinning an unpinned host on first
  use like every other SSH path), and returns **the domain's own verdict** — the same predicate the alert email
  is sent from — so the browser paints "under pressure" and never re-decides it. It is a sibling of
  `/machines/{machine}/files`: a non-whitelisted path under `/machines`, so it is behind the admin auth chain —
  reading a machine's disk is never anonymous. The `df` command string moved onto `RemoteDiskUsage.DF_COMMAND`,
  next to the parser that reads it, so the watcher and the on-demand read can never measure two different
  things. A disk that cannot be read throws the new `DiskUnreadableException` → **502** carrying its own
  sentence ("Vaier could not read the disk on X. The machine may be asleep…") rather than falling through to a
  generic 500 — and is **never rendered as 0%**: a disk Vaier failed to read is not a disk with room on it.
  *(Shipped scoped to the root filesystem — `df -P /`, one `usedPercent` judged by `RemoteDiskUsage.isAbove`.
  [#325](https://github.com/getvaier/vaier/issues/325) ✅ made it every filesystem: the endpoint now returns a
  **list**, each entry carrying its mount point, size, free space, effective threshold, `watched` and the
  `breaches` verdict, and a second endpoint — `PUT /machines/{machine}/disk/watch` — mutes a filesystem or
  gives it a threshold of its own. See §6.9.)* The **disk forecast / runway is deliberately not exposed** —
  `RemoteDiskForecastTracker` needs a *history* of samples and is private state inside the scheduled watcher;
  a single on-demand reading cannot produce a trend (see backlog).
  **The Infrastructure bridge stays.** The epic optimistically said this slice retires `vpn-peers.html`. It does
  not, and a test pins that too: that page still owns machine creation, the LAN scan, the world map, SSH
  credentials, setup scripts, allowed groups and discovered candidates. Regressing function to make the tree
  look finished would be the worst trade in the epic — the bridge goes when parity is real, not before.
  Refactor along the way: trust-on-first-use had been copied into `TerminalService` and `ExplorerService`; the
  third copy (the disk read) is what made it worth having exactly one, so the rule now lives on
  `SshTarget.pinOnFirstUse` and every SSH path (shell, exec, SFTP, disk) pins from it.
- [x] **D — Time ✅.** The archive rail (§6.20 slice 3). Scrubbing back re-lights the
  Inspector in the past's palette (`data-past`, defined in slice A and unused until now); the past has no liveness
  to report, so the status dots go out. Restore is not a feature — it is a paste into the present.
  **Delivered in slice D (frontend, `explorer-shell.{js,css}` + `explorer-listing.js`) — the past is seen, not told:**
  - **The time rail.** A machine's file view grows a horizontal **time rail** — a row of **stops**, one per borg
    **archive** of that machine, laid out newest-nearest-Now, fed by `GET /machines/{machine}/archives`. It appears
    only on a machine that has backups; a machine with no backup job grows no rail (an empty fragment appends
    nothing), so the file browser is untouched where there is no past to show. Archives are read **once** per machine
    when its files are first opened (never on a timer — consistent with the frontend-never-polls rule), and the
    relative "how long ago" is computed at paint, not on an interval.
  - **Scrubbing back mounts the past.** Clicking a stop browses the machine's files as they were in that archive via
    `?at=<archiveId>` on the existing files endpoint; the whole shell crossfades to the amber `data-past` palette,
    the liveness dots go dark, and a one-line reading states the archive's timestamp and how long ago it was.
    Clicking **Now** returns to the live filesystem and cools the shell back. The rail is the only surface that sets
    the past in motion — every stop routes through one path and Now through another, so the light and the reads move
    together and nowhere else.
  - **The invariant is now visible.** *The past is read-only* — you can only browse (and, once Clipboard/paste lands,
    paste into) the present. The backend enforces it at the kernel (the archive is a read-only FUSE mount); the
    frontend makes it *seen* rather than told, via the past palette and the one warm "Now" exit.
  **Delivered earlier in slice D (backend) — the past is a coordinate:**
  - **`borg mount` is the mechanism, and there is only one browse.** A file's third coordinate is *time*, and the
    trick is not to write a second directory lister for the past: mount a backup **archive** as a read-only FUSE
    filesystem on its own machine, and walk it with the exact same SFTP code that walks the live tree. borg strips
    the leading `/`, so a file at `/home/geir/x` in the archive is really at `<mountpoint>/home/geir/x` — a trivial
    prefix. The mount is `ro, user_id=1000` (`borgfs`), so **the kernel enforces "you can only paste into the
    present"** and Vaier never re-implements that invariant.
  - **`domain.MountedArchive`** — the decision, not the plumbing. The mountpoint is keyed by the archive **id**
    (opaque hex, filesystem-safe) because a borg archive *name* carries a `:` and cannot be a directory. The path
    mapping (`machinePath` down under the mount, `toArchivePath`/`anchor` back up) **reuses `FileEntry.normalisePath`**,
    so a path that climbs above the archive root is refused in the past *exactly* as it is in the present, and can
    never escape the mount onto the live filesystem. It is the inverse of **`SftpRoot`**: a jail hides a prefix the
    machine really has; a mount adds a prefix the archive's paths do not carry — and `ExplorerService` composes the
    two, so a jailed machine's past maps correctly too.
  - **`ForMountingArchives` + `BorgArchiveMountAdapter`** — the driven port that keeps `ExplorerService` free of
    borg: it asks for a mountpoint and never learns what borg is. The adapter resolves the machine's backup job →
    **repository** → **server**, provisions the pass file, reads the archive's *name* from the repository's
    `borg list` (the id keys the mountpoint; borg mounts by name), and mounts on demand — over the same
    `ForRunningSshCommands` exec path everything else uses. **Idempotent and lazy:** a cheap `mountpoint -q` probe
    short-circuits a warm re-browse to a single round trip, so the `borg list` + `borg mount` run only on a cold mount.
  - **`BorgCommand.mount`/`umount`/`isMounted`** — every mount command string lives in the one place that knows the
    borg command line, so no borg (or FUSE) flag leaks into a service or adapter. The passphrase reaches borg only
    through the `BORG_PASSCOMMAND` pass file, never argv or env, exactly like `create`/`list`. Unlike a `borg create`,
    `borg mount` daemonises and returns at once, so it needs none of the detached/nohup/poll machinery a run needs.
  - **The Explorer gains the time coordinate.** `GET /machines/{machine}/files?path=…&at=<archiveId>` — absent `at`
    is the present, unchanged (#326's "omitting `path` is a question, not a default" is **not** regressed); present
    `at` mounts the archive and lists the same directory inside it, and the response carries `at` so the browser knows
    it is looking at the past. `GET /machines/{machine}/archives` gives a machine's archives **newest first**
    (`Archive.newestFirst`, `ListMachineArchivesUseCase` on `BackupRunner` mapping machine → job → repository) — the
    data the time rail will scrub over.
  - **Idle mounts are swept off the fleet.** A mount holds a FUSE mount and a borg process on a machine, so
    `ArchiveMountWatcher` (`@Scheduled`, the fleet-backup watcher convention) periodically calls
    `ForMountingArchives.unmountIdle`, releasing every mount untouched beyond the idle window — a mount lives only as
    long as it is being browsed. *(In-memory registry: a Vaier restart orphans a live mount until the same archive is
    re-browsed, which re-adopts it; a future startup sweep could close that gap.)*
  - **`BorgClientSetupScript` installs the FUSE binding.** Debian's borg runs under a system `python3` that ships
    neither `pyfuse3` nor `llfuse`, so `borg mount` failed on **every** fleet host with "no FUSE support";
    `python3-pyfuse3` (`py3-` on Alpine, `python-` on Arch) fixes it. The install sits **outside** the
    borg-already-present branch — the same trap the sudoers grant hit, since every fleet host already has borg, so a
    nested install would never run on one real machine — and a missing binding is **reported, not fatal**: a host that
    cannot mount keeps backing up, only "browse the past" degrades there.
  - **Seam moved: `BackupWorkDirResolver` rest/ → application/.** It resolves the on-host work dir / SSH `$HOME` over
    SSH and is orchestration, not a controller — it sat in `rest/` only beside `BackupRunner`. The mount adapter needs
    it, and an adapter reaching into `rest/` is a layer inversion, so it moved to `application/` where it belongs. The
    resolved value it returns is a plain collaborator, not a `*UseCase` business call.
  - **FUSE verified end-to-end on the Vaier server** (`borg mount ssh://borg@nas::<archive>` then listing/reading over
    SFTP with Vaier's own credential). Apalveien 5 and Colina 27 still need `python3-pyfuse3` — now installed by
    **Prepare client**.
- [x] **The backup server, designated on a machine ✅.** The fleet's one **backup server** stops being a
  record you create on the Backups page and becomes an **entry** on the machine that plays the role — a `backup`
  child alongside `files` / `shell` / `disk` / `containers` / `services`. The tree loads it before first paint
  (`loadBackup` reads `GET /backup-servers` as its single head and `GET /backup-repositories`, never polled — a
  server is designated by an operator, not by a schedule), and grows the entry only on the machine whose name
  equals the server's `machineName`, since there is exactly one. Its Inspector shows the server's coordinates
  (name, reached-at `host:port`, borg user, base repo path, server data path, managed/adopted) and the
  **backup repositories** on it — each a navigable child **entry** (see the repository-management slice below,
  which superseded the original read-only listing) — with **Edit coordinates** and **Remove designation** actions
  and a link to the Backups page for the operations that poll for an outcome (provision, authorize, jobs) — those
  stay on the bridge because the shell never polls. **Any machine can become the backup server**, but only while
  none is yet: a machine's own Inspector offers **Make this the fleet's backup server** (form prefilled from the
  machine's address, name slugged the same way `BackupServer.sanitizedName` does server-side) and the offer
  disappears once a server exists — moving the role is a **Remove designation** (typed-name gate, since the fleet
  is left with nowhere to back up to) then a fresh designate, not an edit. The backend enforces the singleton:
  `BackupService.saveBackupServer` refuses a second, differently-named server. Part of epic
  [#323](https://github.com/getvaier/vaier/issues/323); the operational flow still lives on the Backups bridge,
  as the "Wizards" note below anticipated.
- [x] **Repository management in the tree ✅.** A **backup repository** stops being a card you manage on the
  Backups page and becomes an **entry** of its own — a child of the backup server's `backup` entry, at
  `fleet / <machine> / backup / <repoName>`. The tree therefore reads `machine ▸ backup (the server) ▸ each
  repository`; the repositories are filtered to the one designated server (a stale repo naming a departed server
  is kept out). The `backup` server entry's Inspector lists its repositories as navigable entries and gains a
  **New repository** action (name, an optional path override, an append-only toggle, and a strong auto-generated
  passphrase shown once and stored encrypted in the vault). A **repository entry**'s Inspector shows its path,
  its append-only setting, whether a passphrase is stored, and the **archives inside it** — read **on view**
  (`GET /backup-repositories/{name}/archives`, cached per repo, never polled); because `borg list` runs on a
  job's host, a repository no job targets shows an **empty** archive list rather than an error. It offers **Edit**
  and **Delete**: Delete **forgets the repository in Vaier** (`DELETE /backup-repositories/{name}`) — it does not
  erase the borg store or its archives, so it is a plain confirm, not a typed gate. Create and edit both reuse
  `PUT /backup-repositories/{name}`; no new endpoints — the whole of repository management now lives in single,
  un-polled calls in the shell. In lock-step, `backups.html`/`.js` dropped their **+ New repository** button, the
  repository create/edit modal and the archive-browser modal, and now render repositories **read-only** (name,
  path, badges) with a "Manage repositories in the Explorer" link and an Explorer-pointing empty state; the jobs
  section still references repositories by name (§6.19). Part of epic
  [#323](https://github.com/getvaier/vaier/issues/323).
- [x] **Job management in the tree ✅.** A **backup job** stops being a card you create on the Backups page and
  becomes something you manage on each machine's `backup` entry in the **Explorer** — create, edit, delete,
  **Run now**, and the enable/disable toggle all live there now. In lock-step, `backups.html`/`.js` dropped their
  **+ New job** button, the job create/edit modal and the generic delete-confirm modal, the per-job **Run now**
  button, the last-run status badge and its run-diagnostics disclosure, and stopped consuming the `run-settled`
  SSE event (the backend still publishes it, for the Explorer). The Backups page now renders jobs **read-only**
  (name, machine, target repository, source-path count, retention `keep Nd/Nw/Nm`, and an enabled/disabled badge)
  with a "Manage backup jobs in the Explorer" link in both the empty state and the list, and reworded the jobs
  stage to "you create a machine's backup jobs in the Explorer, on its backup entry; check readiness here so the
  nightly run can reach the repository." What **stays** on the Backups page is the **guided-provisioning wizard** —
  the per-job **Check readiness** button, **Prepare client**, the back-up-as-root fix and the `scriptOnly`
  fallback — a bridge exactly like the server's provisioning, still consuming `prepare-client-settled` and
  `provision-settled`. The nightly schedule and run semantics (warnings/failed/diagnostics, self-initialising
  repository, admin failure email, `~/.vaier-backup` working state) are unchanged (§6.19) — only *where* a run is
  triggered, and the run-status display, moved. Part of epic [#323](https://github.com/getvaier/vaier/issues/323).
- [ ] **E — The rest.** DNS, access, settings. `admin.html` and the iframes are deleted, and the bridge with
  them.

**Backlog (deliberately deferred out of slice C):**
- **Container control endpoints** — start / stop / restart a container, and read its logs. Vaier has none: the
  Docker adapters read, and the **docker socket proxy**'s allowlist is what stands between Vaier and a fleet-wide
  remote-exec surface. This is its own change with its own security thinking (who may restart what, whether it
  goes through the socket proxy or the machine's SSH, what an audit trail looks like) — not a button bolted onto
  the Inspector. Until it exists, the Inspector says plainly that the machine's **shell** is where that is done.
- **Publish from the tree.** Unpublish shipped because `DELETE /published-services/{dnsName}` exists; publishing
  needs a form (subdomain, **auth mode**, **path prefix**, backend, root redirect) and the **publishable
  service** candidate feed behind it. It stays on **Infrastructure** until the tree can host that form honestly.
- **The disk forecast in the Inspector.** `GET /machines/{machine}/disk` reports level, not trend: the
  **runway** and fill rate come from `RemoteDiskForecastTracker`, which needs a *history* of samples and is
  private state inside the scheduled `RemoteDiskWatcher`. Exposing **runway** means exposing that history (a
  read port onto the tracker, or persisting the samples) — a real design decision, not a field to add. Folds
  together with the host-monitoring backlog item in §6.9.
- **Retiring the Infrastructure bridge.** Only once the tree reaches parity: machine creation, the **LAN
  scanner**, the map, **host credentials**, backup setup scripts, **allowed groups**, and the discovered-candidate
  → publish flow all still live only on `vpn-peers.html`.

**Decided up front — where the tree does not fit:**
- **Wizards.** Fleet backup is a guided flow; a tree cannot teach. It stays a flow, rendered as the Inspector
  for a machine's backup entry.
- **The map.** Peer geography is not hierarchical: it becomes a view of the fleet root.
- **The launchpad stays a separate page** — different audience, unauthenticated, different job.
- **Creation.** "New machine" is not an entry until it exists; it is a persistent affordance at the end of the
  fleet.

---

## 7. End-to-End Workflows

### 7.1 New service on a peer (primary workflow)

1. Peer is already connected to VPN (created via Vaier)
2. Developer starts a Docker container on the peer
3. In Vaier → Services, the container appears in the **Discovered** list automatically
4. Developer clicks **+ Add**, enters a subdomain, toggles auth if needed, clicks **Add Service**
5. Modal closes immediately; service moves to the **Processing** list with live progress steps
6. Vaier creates: DNS CNAME → waits for propagation → Traefik route → (optional) social-login middleware chain
7. Processing card disappears; service appears in the **Active** list with live status
8. All updates arrive via SSE — no page reload or manual polling required

**Success:** zero manual DNS/Traefik/auth steps. The user always knows where their service is in the pipeline.

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

1. User creates `.env` with `VAIER_DOMAIN`, `ACME_EMAIL`, the Google OAuth credentials (`VAIER_OIDC_GOOGLE_CLIENT_ID` / `VAIER_OIDC_GOOGLE_CLIENT_SECRET`), `VAIER_ADMIN_EMAIL`, optionally `VAIER_AWS_KEY` / `VAIER_AWS_SECRET` (Route53 mode) and `VAIER_PUBLIC_HOST` / `VAIER_PUBLIC_IP` when not on EC2
2. `docker compose up -d` — oauth2-proxy(+init) start unconditionally as the auth gateway (no `social` profile)
3. Vaier auto-creates `vaier.<domain>` in Route53 and seeds the **configured administrator** (`VAIER_ADMIN_EMAIL`) as the first admin access entry
4. User opens `https://vaier.<domain>`, signs in with Google as that admin, and lands in the console

## 8. Technical Constraints

- **Stack is fixed:** WireGuard (linuxserver), Traefik, oauth2-proxy, AWS Route53
- **Sub-image versions are pinned** in `docker-compose.yml`; bumps are deliberate, tested, and released with a new Vaier version (no floating `:latest` tags for upstream images)
- **No database:** all state is file-based (WireGuard/Traefik configs, the access store) or cloud-based (Route53)
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
| OQ1 | Should the launchpad be unauthenticated or protected? | Launchpad is public; admin UI is protected by social login (Google via oauth2-proxy → Vaier `/authz/verify`). A dedicated `/launchpad/services` endpoint returns only DNS address and host address (no ports, auth state, or internal details). |
| OQ2 | Non-Docker Hub registries in v1? | No — Docker Hub only. GHCR / self-hosted are stretch goals for v2. |
| OQ3 | Pi-hole detection: automatic or env var? | N/A — Pi-hole removed from the project. |
| OQ4 | Update notifications: push or UI only? | UI only in v1. Webhook/email is a v2 consideration. |

---

## 12. Backlog

The backlog is tracked in [GitHub Issues](https://github.com/getvaier/vaier/issues). Feature specs for planned items are in the relevant section above (6.8–6.10). Bugs and smaller improvements are described directly in the issue.

"Server LAN CIDR" remaining follow-ups are closed by [#204](https://github.com/getvaier/vaier/issues/204) — `VAIER_SERVER_LAN_CIDR` passes through `docker-compose.yml`, and split-tunnel `UBUNTU_SERVER`/`WINDOWS_SERVER` peers' client-side `AllowedIPs` includes the resolved CIDR. The `wireguard-masquerade` sidecar's interface-name-agnostic `! -o wg0 -j MASQUERADE` rule covers the source-NAT step on the Vaier server (closes [#248](https://github.com/getvaier/vaier/issues/248) — the linuxserver `PostUp`'s `-o eth+` is a no-op on AWS's `ens5`).

**Infrastructure page merge ✅.** The Machines (`vpn-peers.html`) and Services (`published-services.html`) pages have been merged into one machine-centric **Infrastructure** page in shippable slices. **Slice 1 ✅:** the Machines page's diagram tab gained the published-service layer and was renamed Network → **Topology** (the join is a client-side helper, mirroring the existing peers+LAN-server join); the diagram itself was reworked from the hand-positioned SVG fan into an interactive Cytoscape + cola force-directed graph (drag/zoom/pan, self-spacing physics) after the fixed-radius layout crowded badly. *(Superseded: the force-directed graph was later reimagined as a per-service reachability map and then removed entirely, along with the Cytoscape/cola libraries — see §6.1.)* **Slice 2 ✅ (this change):** each machine card gained a unified **Services** section. It folds together what used to be two things — the read-only discovered-container list and the published-route view — into one list: published reverse-proxy routes appear first, each expandable into an inline editor (auth checkbox, display name, and an Advanced disclosure with redirect, version endpoint/property, direct-LAN-URL, launchpad visibility; text fields auto-save on blur, checkboxes apply immediately, a focus guard defers SSE/poll re-renders so an in-progress edit isn't wiped), with ✕ to delete and ↗ to open; below them, the host's discoverable-but-unpublished containers appear as muted **+ Publish** rows that open a container-mode publish modal (subdomain pre-filled from the suggestion, path prefix, auth, direct-LAN-URL, advanced redirect). Candidates are mapped to their machine card by **address** (`peer.tunnelIp` / `lanServer.lanAddress`, since the publishable feed carries the sanitized peer name, not the display name), and both ignored containers and ones already published on that exact port are filtered out. Publishing is async — a toast reports progress and the published-services SSE (`publish-traefik-active` / `-rolled-back` / `-dns-timeout`) reconciles the result. Frontend only (`vpn-peers.html` + `vpn-peers.js` + `vpn-peers-helpers.js` + `vpn-peers.css`); reuses the existing `/published-services` discover/publishable/publish/PATCH/DELETE endpoints — no backend change. Known minor regression: the old per-host "not connected / unreachable / none discovered" diagnostic line under Services is gone (an unreachable host now shows an empty Services section; its state still reads from the card icon + last-seen). **Slice 3 ✅ (this change):** the two top-nav entries (across `admin.html`, `launchpad.html`, `mypage.html`) collapsed into a single **Infrastructure** entry (`#infrastructure`), the page was retitled Machines → **Infrastructure**, and the standalone `published-services.html` (+ `.js`/`.css`) was deleted — the old `#services` and `#vpn` hashes now alias to `#infrastructure`. The inner **List · Map** tabs are unchanged; published services are managed inside each machine card's Services section. To make sure nothing was lost when the Services page retired, four capabilities were ported onto the Infrastructure page: (1) **ignore / unignore** discovered candidates — every "+ Publish" row has an Ignore button, and a machine with ignored candidates shows a collapsible "N hidden" line to reveal/Unignore them; (2) **manual LAN-host publish** — relay-anchored LAN-server cards gained a "+ Publish LAN port" button opening the publish modal in LAN mode (port + protocol + subdomain), POSTing to `/published-services/lan`; (3) **publish progress UI** — a floating, non-blocking progress card per in-flight publish that advances DNS-created → DNS-propagation → reverse-proxy-route via the `published-services` SSE events, going green on success / red on rollback or DNS timeout, and rebuilt from `/published-services/pending` on reload; (4) **delete confirmation** is now an in-app modal (not the browser `confirm()`) that shows the busy overlay during Traefik/DNS teardown. Frontend only — all `/published-services/*` REST endpoints and the `published-services` SSE topic are unchanged.

