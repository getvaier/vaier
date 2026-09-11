# Product Requirements Document — Vaier

**Last updated:** 2026-07-29
**Status:** Living document
**Issues:** https://github.com/getvaier/vaier/issues — GitHub issues are part of the spec and represent confirmed requirements and bugs.

---

## 1. Overview

Vaier is a self-hosted infrastructure management tool for developers running a homelab. It eliminates the manual work of maintaining a WireGuard VPN server and a reverse proxy by providing a single interface that wires everything together automatically. DNS is one **wildcard DNS** record the operator creates once, before first boot (§6.4).

The core value proposition: add a new Docker service anywhere on your VPN, select a subdomain, and Vaier handles the reverse proxy and HTTPS — end to end.

Vaier is a personal tool that will be open-sourced. It is not intended to compete with general-purpose infrastructure platforms (Portainer, Coolify, Rancher, etc.). It is opinionated about its stack: WireGuard + Traefik + Google sign-in (via oauth2-proxy). It is deliberately **not** opinionated about DNS — any host that can serve one wildcard `A` record qualifies.

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
2. Add a DNS record at your DNS host and wait for it to propagate
3. Write a Traefik dynamic config file with the right router, service, and middleware
4. Optionally wire in forward-auth (Google sign-in via oauth2-proxy)
5. Verify everything landed correctly

Each step is done in a different tool, with no feedback loop. Mistakes are silent (wrong IP, missing middleware, typo in DNS name). Vaier removes all of this — and step 2 stops being per-service work entirely, because one **wildcard DNS** record made at install answers for every name Vaier will ever publish (§6.4).

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
- Not a DNS manager at all — Vaier reads DNS, never writes it. The operator owns one wildcard record; see §6.4
- Not a monitoring platform
- Not a general layer-4 proxy — a non-HTTP port is published as a **stream**, and a stream is exactly one shape: a Traefik TCP router on the `websecure` (443) entrypoint that already exists, matched by `HostSNI`, TLS terminated by Traefik with a per-hostname Let's Encrypt certificate, bytes forwarded to the backend port unchanged. Never a new entrypoint, never a new host port, never a firewall rule — publishing a stream stays a hot file write like every other publish. And never auth-gated: nothing inside a TCP stream is an HTTP request, so oauth2-proxy and the CrowdSec bouncer cannot see it, and the domain refuses social login on a stream rather than implying a gate that isn't there. The service's own credentials are its only gate; see `docs/NETWORKING.md`
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
  - **Mobile/client dual marker** ✅ — `MOBILE_CLIENT` and `WINDOWS_CLIENT` peers plot twice: a marker at the device's **placement** plus a firm marker stacked at the Vaier server's location ("internet via Vaier"), given `AllowedIPs = 0.0.0.0/0` full-tunnel routing. *Superseded 2026-08-11 (§6.45): the first marker used to be an always-drawn dotted "connecting from" carrier-IP guess; it is now a soft uncertainty circle for an ISP estimate, a firm pin for a reported position, and absent entirely with no placement — see §6.45.* Server-type peers keep a single marker at their own endpoint.
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
- Ubuntu/Windows server peers can additionally specify a **LAN address** (e.g. `192.168.1.50`) — the server's reachable host/IP on that LAN. Used by the launchpad to return direct, proxy-bypassing URLs when the caller is on the same LAN. The same caller-on-host-LAN fact also puts that machine's section at the top of the launchpad, whether or not a direct URL is handed out ✅ (2026-09-07). Editable inline on the expanded server card via `PATCH /vpn/peers/{id}/lan-address`, so existing peers can be annotated without recreation.
- Any machine — VPN peer or LAN server — can carry an optional **description** ✅ ([#54](https://github.com/getvaier/vaier/issues/54)) — operator-supplied free text (e.g. "Home media server (NUC, Ubuntu 22.04)") to record what it is for. Set on the Add Machine form, shown as a muted subtitle under the machine name, and editable inline on the expanded card. For VPN peers it is stored in the `# VAIER:` metadata comment (JSON-escaped so the single-line comment stays valid) and updated via `PATCH /vpn/peers/{id}/description`; for LAN servers it is a field in `lan-servers.yml`, updated via `PATCH /lan-servers/{name}/description`.
- A peer has a stable **id** and a separate, editable display **name** ✅ ([#209](https://github.com/getvaier/vaier/issues/209)). The id is the slug Vaier derives from the operator-typed name at creation (`domain.PeerId` — non-slug characters folded to `-`, runs collapsed; deduplicated with a numeric suffix `-2`, `-3`, … when it would collide). The id is the peer's `peers/<id>/` config directory and the segment in every `/vpn/peers/{id}` REST path, and is frozen for the life of the peer. The name is free text the operator edits at will, stored in the `# VAIER:` metadata comment; a peer with no stored name falls back to its id rendered with dashes as spaces (so an auto-slugged `media-server` reads as `media server` in the UI and on the launchpad). Existing peers need no migration — their config directory name becomes the id verbatim.
- VPN peers and LAN servers can be **renamed** in place ✅ (peer half of [#55](https://github.com/getvaier/vaier/issues/55)) — edit the **Name** field on the expanded card. A peer rename (`PATCH /vpn/peers/{id}`) only rewrites the `name` in the `# VAIER:` metadata comment — the peer's id, config directory, and published services never move (the running tunnel is keyed by public key server-side, and routes/launchpad resolve from IP at runtime). Display names must be unique across every machine (see #284 below). A LAN server rename (`PATCH /lan-servers/{name}`) rewrites the `lan-servers.yml` entry; published LAN routes keep working (keyed by address). **Still open in #55:** renaming a published *service* (its DNS name) — tracked separately. It got materially cheaper with #331 (§6.4): with no per-service DNS record to rewrite, a service rename is a Traefik-route rewrite and nothing else.
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
  - The discovered-host DTO from the scan endpoint (`GET /lan-scan`) gains `deviceCategory` (string, derived read-only via `DeviceCategory.detect(hostname, null, guessedRole)` — never persisted).
- **UI** — the per-category icon (`deviceCategoryIconKind` in `vpn-peers.html`) drives the glyph for every machine on the List and Map tabs, replacing the old machine-type icon. The expanded machine card carries a **Device type** selector (`deviceCategorySelectHtml`): an "Auto-detect" option plus the twelve categories, with an "Auto-detected: …" caption when no override is set; choosing a category `PATCH`es the override, choosing "Auto-detect" clears it. The LAN-scan results picker shows each discovered host's device icon, and picking a host carries its derived category into the new LAN server via the `POST /lan-servers` `deviceCategory` field.

#### Show-once peer config ✅ (closes [#202](https://github.com/getvaier/vaier/issues/202))

The WireGuard config artefacts (`.conf`, QR PNG, docker-compose, setup-script — anything embedding the peer's private key) are delivered **exactly once**. The threat model: WireGuard has no session concept, no server-side revocation, and the same config works on any number of devices, so a screenshot of the QR or a copied `.conf` would otherwise be a permanent backdoor.

- A filesystem marker (`<wireguardConfigPath>/<peerName>/<peerName>.conf.viewed`) lives next to the peer's `.conf` and is created atomically on the first GET to any of the five secret-bearing endpoints (`/config`, `/config-file`, `/qr-code`, `/docker-compose`, `/setup-script`). Subsequent GETs on any of those five return `410 Gone` with `{"reason":"already-viewed","action":"delete-and-recreate"}`. Driven by the `ForTrackingPeerConfigRetrieval` port (`FilePeerConfigRetrievalTracker` adapter); the existing peer-delete flow removes the whole peer directory, which also clears the marker for free.
- The create response (`POST /vpn/peers`) inlines every artefact — config text, base64 QR PNG, docker-compose, setup-script (when applicable) — so the UI's create-success modal renders all of them without a follow-up GET. The marker is **not** set on create, so a one-shot raw curl GET after create still works for tooling that prefers to fetch the artefact out-of-band.
- The create-success modal opens with a **Getting Started** panel (closes [#51](https://github.com/getvaier/vaier/issues/51)) — one sentence of per-peer-type guidance pointing at the 80/20 next step: mobile = "scan the QR code with the WireGuard app", `UBUNTU_SERVER` = "copy `setup-<name>.sh` to the host and run `bash setup-<name>.sh`", Windows = "import `<name>.conf` into the WireGuard Windows client". The matching download button gets primary styling; alternatives stay secondary so the eye lands on the recommended action without the others being hidden. Recommendation is decided by `recommendedArtifactFor(peerType)` in the browser.
- Pre-existing peers (created before this change) have no marker file and are therefore treated as **not yet viewed**: the first GET after update is allowed, then the peer is locked.
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
- Publish a service: writes a Traefik route + optional social-login middleware chain. No DNS step — the name already resolves under the operator's **wildcard DNS** record (§6.4)
- Publish a non-HTTP port as a **stream** ✅ — a Traefik TCP router on the same 443 entrypoint, matched by `HostSNI`, TLS terminated by Let's Encrypt, bytes forwarded unchanged (§6.52)
- Toggle authentication on/off per service
- Check publish status (Traefik active)
- Delete published service (removes the Traefik route; DNS is untouched)
- Edit root path redirect on published services
- Auto-delete published services when a VPN peer or LAN server is deleted

**Publish flow (confirmed UX):**

1. User sees two lists on the published services page:
   - **Discovered** — containers with exposed TCP ports not yet published, found on the Vaier server and reachable VPN peers
   - **Active** — published services with their DNS/reachability state
2. Clicking **+ Add** on a discovered service opens a modal: subdomain input + auth toggle
3. On submit, the modal closes immediately and the service moves into a **Processing** list that sits between the discovered and active lists
4. The processing card shows the one live progress step there is: Traefik route active
5. When the Traefik route is confirmed active, the processing card disappears and the service appears in the active list
6. The discovered list hides the service as soon as it enters processing (server-side, not client-side)
7. Both active and processing lists are driven by SSE — no polling from the browser
8. Processing state survives page refresh (backed by in-memory server state, not persisted to disk)
9. Duplicate submissions are rejected: attempting to add a service already in active or processing shows an error

**Also implemented:**
- **Root redirect path UI** — collapsible "Advanced" section in the publish modal with an optional root path redirect input, wired to the `rootRedirectPath` API field. Redirect path is also editable on published services via a modal.
- **Service cleanup on peer deletion** — when a VPN peer is deleted, all published services routing to that peer's IP are automatically removed (Traefik routes only; no DNS is involved since #331)
- **Service cleanup on LAN-server deletion** — when a LAN server is deleted, all published services whose backend address equals that LAN server's `lanAddress` are automatically removed (Traefik routes). Mirrors the peer cascade: `LanServerService.delete` finds the matching reverse-proxy routes and removes each via `DeletePublishedServiceUseCase` before deleting the LAN-server record
- **Published services page cleanup** — consolidated host/status rows, hide discovered section when empty, replaced fragile optimistic auth toggle with server-side refresh
- **Publish rollback on failure** — if Traefik route creation throws, or Traefik never picks up the new route, Vaier removes the route so no orphan route remains. Emits `publish-rolled-back` on the `published-services` SSE topic. Since #331 **Traefik is the only thing that can trigger a rollback** — the DNS-timeout branch and the `publish-dns-timeout` event are gone.
- **Contextual help + error explanations in the publish flow** ✅ (closes [#56](https://github.com/getvaier/vaier/issues/56)) — UX/observability pass over the existing publish flow; no new endpoints or concepts.
  - **Progress-step tooltips** — each Processing-card step carries a native hover tooltip explaining what is happening and why. *(The two DNS steps this originally covered — "DNS record created" and "Waiting for DNS propagation…" — no longer exist; since #331 the only step is "Activating reverse proxy route…".)*
  - **Rejection reason surfaced on 400** — `POST /published-services/publish` and `POST /published-services/lan` return the rejection reason in the response body (the `400` body was previously empty). Since [#275](https://github.com/getvaier/vaier/issues/275) this is the shared `ApiError` envelope (the validation exception propagates to `GlobalExceptionHandler`); originally it was a bespoke `PublishError(String message)` record. The browser's `explainPublishError()` helper reads `.message` and shows that human message plus a suggested next step instead of a raw `HTTP <code>`, with status-keyed fallbacks for 409, 5xx, and network errors.
  - **Rollback surfaced to the operator** — the browser now handles the `publish-rolled-back` SSE event (which the backend already emitted but the UI ignored), telling the operator the Traefik route was removed so they can safely retry.
- **LAN service publishing** ✅ (closes [#175](https://github.com/getvaier/vaier/issues/175)) — expose a LAN host (NAS, IPMI, printer, IoT) reachable through a relay peer's `lanCidr` *or in the Vaier server's own subnet* (see "server LAN CIDR" below), no Docker container required. The publish flow validates that the target IP falls inside some relay peer's `lanCidr` or the server LAN CIDR (`LanAnchor`) and writes a Traefik route whose backend is `http(s)://<lan-ip>:<port>` (it wrote a DNS CNAME too until #331). For a relay-anchored target, cryptokey routing on `wg0` plus the relay's #170 forwarding deliver packets; for a server-anchored target, the Traefik container reaches it directly out the host's LAN/VPC NIC. Surfaces with a small "LAN" badge in the published-services list; relay-anchored routes use the target host as the launchpad direct-URL shortcut for on-LAN callers and a server-anchored route's host state is always OK.
- **LAN server registration (Docker optional)** ✅ (closes [#177](https://github.com/getvaier/vaier/issues/177), [#184](https://github.com/getvaier/vaier/issues/184), [#181](https://github.com/getvaier/vaier/issues/181)) — register any machine on a relay peer's LAN *or in the Vaier server's own subnet* (see "server LAN CIDR" below) as a `LAN_SERVER` machine, with optional Docker. With Docker on, Vaier scrapes its remote Docker socket through the relay (same `tcp://<host>:<port>` pattern as VPN peers) — or, for a server-anchored LAN server, directly from the Vaier container. With Docker off, the LAN server still appears on the Machines page and is publishable through the manual LAN-service flow. Registration validates that `lanAddress` falls inside some relay peer's `lanCidr` or the server LAN CIDR; the Add Machine modal asks only for the address. Persisted as YAML at `${VAIER_CONFIG_PATH}/lan-servers.yml` (legacy `lan-docker-hosts.yml` is auto-migrated on startup). V1 scope: insecure tcp 2375 only; no TLS/SSH yet. Backed by the unified `MachineType` taxonomy: `MOBILE_CLIENT`, `WINDOWS_CLIENT`, `UBUNTU_SERVER`, `WINDOWS_SERVER`, `LAN_SERVER`. A unified `GET /machines` endpoint returns all five in one list. Registration validates the address only (Docker need not be reachable yet); the operator then runs the host's **unified per-host setup script** from its card — see [Per-host LAN setup script](#per-host-lan-setup-script--closes-249) below.
- **Per-host LAN setup script** ✅ (closes [#249](https://github.com/getvaier/vaier/issues/249)) — one script the operator runs on a registered LAN server, `GET /lan-servers/{name}/setup.sh`, that **adapts to that host**: it opens the Docker engine API (native + snap, the same daemon.json/systemd-drop-in logic as before) when the host runs Docker, **and** installs static routes via its relay peer when it is relay-anchored. Routes cover the server LAN CIDR, the VPN subnet, and every *other* relay peer's `lanCidr` (so a host behind one relay can reach the Vaier VPC and — once #250 lands — other sites' LANs), persisted across reboots by a `vaier-lan-routes.service` systemd oneshot (`ip route replace … via <relay lanAddress>`, distro-agnostic, idempotent — mirrors the relay's `vaier-wg-relay-iptables.service`). Strict hex: the decision of what the script must do (effective Docker port, relay-anchor → gateway, routed-CIDR set, "relay has no LAN address" → 409, "nothing to do" → 404) lives in the pure domain `LanServerSetupScript.forHost(...)` / `routedDestinations(...)`; `LanServerService` only reads the `LanServer`, peer configs, server LAN CIDR and VPN subnet from driven ports and hands them over. This **replaces** the old generic `docker-setup.sh` (static classpath script + `--port`) — register-first, since registration never needed Docker reachable; the old endpoint and `scripts/lan-docker-setup.sh` are retired. Sibling-site LAN routes are installed but only carry traffic once the site-to-site mesh follow-up ([#250](https://github.com/getvaier/vaier/issues/250) — sibling-relay LANs in peer `AllowedIPs` + broadened relay forwarding) ships; the script header says so. The endpoint is on oauth2-proxy's **public** path (skip-auth) so `curl … | sudo bash` works on a fresh LAN host with no Vaier session — the script carries no secrets.
- **Presence for personal devices** ✅ (2026-09-07) — a phone or PC painted nothing whether it was on the VPN or off, because "a sleeping phone is not news" had been read as "say nothing"; the operator could not tell on from off at all. A non-server peer now says it with its own name and icon — normal while present, dimmed while away — in the text greys and never red or amber, and never a dot (the first cut drew one, and the operator asked for the font or icon instead). The same move then took the down and degraded states off the tree's dot and onto the name and icon too — red and amber — since the card had already gone that way and the tree was drawing one answer two ways; the liveness dot is now only the anchor the stream repaints from. Servers keep the rule that fine paints nothing and down paints red. The fleet's "N online" count keeps counting a present device.
- **Setup command only where it can run** ✅ (2026-09-07) — the Explorer offered "Setup command" to every LAN server and left a 204 to say "nothing to set up" after the click; for an appliance behind a relay peer not even that, since being relay-anchored made the routes rule hand out a `curl … | sudo bash` for a sprinkler controller. The domain now answers up front (`Machine.acceptsSetupScript`: a LAN server that runs Docker, or that Vaier may SSH into, or whose effective device category is not an appliance — a Roon server filed under MEDIA keeps its script, a sprinkler controller does not), the answer rides on `GET /machines`, the verb is not rendered otherwise, and the setup-token route says nothing to set up for the same machines.
- **Setup-script guard** ✅ — every generated setup script (peer and LAN server) opens with a guard block, emitted by the pure-domain `SetupScriptGuard` and placed ahead of the script's first mutating line, that refuses to run on a machine the script was not generated for. Written after a peer setup script was pasted into the wrong terminal (2026-07-23) and reconfigured the Vaier **staging** server: it ran `docker compose down` on the Vaier stack, deleted the server's `wg0`, rewrote `/etc/docker/daemon.json` to expose the engine API, and routed the host's own `/20` into a tunnel that could never come up — severing the box from its default gateway. Five refusals: (1) the host is a Vaier server (a running `getvaier/vaier` container, or an install at `$HOME/vaier`, which still catches a server whose stack is down); (2) the host is stamped as a different machine — each completed setup writes its machine name to `/etc/vaier/machine`, best-effort so a host that won't take the stamp still sets up; (3) a CIDR the script would tunnel contains the address on the host's default-route interface — for a peer these are read off the client-side `AllowedIPs` the script installs, *after* its split-tunnel rewrite, so the check sees what will really be routed; (4) the host lacks the address Vaier recorded for the machine (LAN servers only — a peer has no address until the script builds its tunnel); (5) ✅ 2026-09-11, [#355](https://github.com/getvaier/vaier/issues/355): a LAN server's relay routes are never installed beside a WireGuard client — the route to the VPN subnet is exactly the one `wg-quick` then fails to add ("File exists"), and it deletes the interface on the way out, on every boot, while the container stays "running"; the refusal looks for a live WireGuard interface, a native `/etc/wireguard/*.conf`, or a WireGuard container running or not. A machine is a peer or a LAN server, never both; a Docker-only LAN server with no routes is left alone. Checks are non-interactive by necessity (the script arrives on stdin through a pipe); `VAIER_FORCE=1` overrides; a refusal exits 3. Machine names are rendered as single-quoted shell literals, so operator-typed text can never become shell. Check 3 is a genuine bug fix beyond the misfire: a peer created for any machine sharing the Vaier server's subnet inherits the **server LAN CIDR** in its client-side `AllowedIPs` and would blackhole its own uplink on first connect.
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
- **Path-based routing — multiple services per subdomain** ✅ — every published service gains a nullable, normalised `pathPrefix` (e.g. `/auth`). When set, the Traefik rule becomes `Host(\`fqdn\`) && PathPrefix(\`path\`)`, the router/service/redirect-middleware names get a path-derived slug so siblings on one FQDN don't collide, and the read-back parser extracts the prefix back onto `ReverseProxyRoute`. Route uniqueness moves from FQDN-only to `(fqdn, pathPrefix)`. *(The publish flow was sibling-aware for DNS — first publish on a host created the CNAME, later siblings skipped it, and the CNAME went with the last sibling. #331 removed all of it along with `ReverseProxyRoute.hasSiblingOnHost`: siblings now share only the name they answer on, which the wildcard record covers regardless.)* The publish modal exposes Path prefix next to the Subdomain input on both the peer and LAN flows; the launchpad emits one tile per path-based service whose landing URL is decided by `ReverseProxyRoute.landingPath()`: when a `rootRedirectPath` is set it wins over the path prefix; otherwise the path prefix is used verbatim, including any trailing slash the operator typed (so an SPA at `bmp/builder/ui/` can be expressed either by typing the slash on the prefix or by registering a redirect to `/builder/ui/`). Per-row toggles (auth, redirect, Disable direct LAN URL) target the specific `(fqdn, pathPrefix)` route — flipping auth on `bmp/auth` no longer affects `bmp/CorpoWebserver`. The Direct-URL-disabled set on disk migrates transparently: legacy bare-FQDN entries keep working for host-only routes; new writes use the unique router name. Decisions like duplicate detection and sibling lookup live on `ReverseProxyRoute` (static helpers); services orchestrate, the domain decides.

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
- When the caller's public IP matches a VPN peer's WireGuard endpoint IP (i.e. they share a NAT gateway with that peer), and the service is hosted on that peer, the tile links to `http://lanAddress:port` directly — bypassing Traefik and its auth. Falls back to the public HTTPS URL otherwise. The caller IP is taken from `X-Forwarded-For` only when the direct peer (`RemoteAddr`) is inside the **trusted proxy CIDR** (`vaier.trusted-proxy-cidr`, falling back to the older `launchpad.trusted-proxy-cidr`, default `172.20.0.0/16`).
- **Auth-mediated tile URL** — when an auth-protected service has no direct-LAN bypass, the tile routes the browser through the auth gateway first (rather than the service URL itself), defeating PWA service workers (e.g. openHAB) that would otherwise serve a cached SPA from their own origin and trap the user in the app's own login screen because XHRs to `/rest/*` get answered with `401` rather than a cross-origin `302` redirect to sign-in. _(Originally an Authelia `https://login.<domain>/?rd=…` redirect; under social login the domain-wide oauth2-proxy SSO cookie carries the signed-in session across origins.)_
- **Per-service direct LAN URL opt-out** — the reverse-proxy route carries a `directUrlDisabled` flag (persisted in the Traefik YAML as `x-vaier-direct-url-disabled`). When set, the launchpad always serves the public HTTPS hostname for that service, skipping the direct LAN URL shortcut. This is required for services whose public origin differs from `http://lan:port` — Vaultwarden is the canonical case: its `DOMAIN` env is `https://vaultwarden.<domain>`, so opening the LAN URL yields a near-blank page because the Vue app won't initialise against a mismatched origin. Available as a checkbox both in the Publish service modal (so it can be set on creation) and on the expanded service details row. Also togglable via the unified `PATCH /published-services/{dnsName}` partial-update endpoint (`{"directUrlDisabled": ...}`); accepted on `POST /published-services/publish` as a `directUrlDisabled` body field.
- **Per-service hide-from-launchpad toggle** ✅ — the reverse-proxy route carries a `hiddenFromLaunchpad` flag (persisted in the Traefik YAML as `x-vaier-hidden-from-launchpad`). When on, the route stays reachable but the launchpad never renders a tile for it. Use case: internal APIs that back another service and don't need an operator-clickable tile. Togglable from the expanded service details row in the admin published-services page, or via the unified `PATCH /published-services/{dnsName}` partial-update endpoint (`{"hiddenFromLaunchpad": ...}`).
- **Domain-owned tri-state launchpad visibility** ✅ — `ReverseProxyRoute.launchpadVisibility(hostState, AccessEntry viewer, ForResolvingServiceGroup serviceGroups)` returns `LaunchpadVisibility.{NOT_VISIBLE, VISIBLE_INACTIVE, VISIBLE_ACTIVE}`, consolidating every reason a route might be hidden, dimmed, or active — including per-viewer reachability via `isVisibleToLaunchpadViewer(viewer, serviceGroups)` (a NONE route is always shown; a SOCIAL route only when the viewer may reach it). The launchpad use case is a thin pass-through that filters `NOT_VISIBLE` and forwards the rest with `visibility` on `LaunchpadServiceUco`; the launchpad client only renders the value and never has to understand individual reasons. New visibility rules accrete inside the domain method, not in the application layer. Since #331 DNS state is no longer one of the inputs — a wildcard-covered name always resolves, so DNS can no longer hide a tile — and the `dnsState` parameter is gone from both overloads.
- **Domain-owned launchpad tile liveness dot** ✅ (issue [#208](https://github.com/getvaier/vaier/issues/208) follow-up) — the launchpad tile's status dot is driven by a **separate** presentation tri-state, `ReverseProxyRoute.launchpadLiveness(hostState)` → `LaunchpadLiveness.{LIVE, PENDING, OFFLINE}`, so it can read honestly during startup. `LaunchpadVisibility` still maps an `UNKNOWN` host to `VISIBLE_ACTIVE` (the tile stays clickable), but the dot no longer flashes green before reachability is confirmed: `OK` → LIVE (green), `UNKNOWN` → PENDING (grey — no probe yet), `UNREACHABLE` → OFFLINE (red). `PublishingService` populates `liveness` on `LaunchpadServiceUco` (serialized straight to JSON), and the launchpad client keys the dot class off `liveness` while keeping the link/dim logic keyed off `visibility`; the per-section and hero "online" counts now mean *confirmed reachable* (`liveness === 'LIVE'`).
- **Branded offline page for unreachable backends** ✅ — when a published service's backend is down, Traefik no longer serves its bare black "Bad Gateway" page; visitors get Vaier's branded **offline page**. A shared Traefik `errors` middleware (`vaier-errors`) catches 502/503/504 and forwards to an http service (`vaier-error-pages`) pointing at the Vaier container, which serves `GET /error-pages/{status}` — a self-contained HTML page (inline CSS, no cross-origin asset links) that names the unavailable service from `X-Forwarded-Host`, shows a friendly message, and offers Retry and Back-to-launchpad links. The status→title/message mapping is the domain `GatewayError` record. The middleware is attached to every router `addReverseProxyRoute`/`addLanReverseProxyRoute` creates, and an idempotent startup backfill (`TraefikReverseProxyAdapter.backfillErrorPages`, on `ApplicationReadyEvent`) appends `vaier-errors` to every pre-existing router and ensures the shared infra exists — without touching existing auth/redirect middlewares, load-balancer servers, or `x-vaier-*` metadata. Web layer mirrors the icon shape: `GetOfflinePageUseCase` → `OfflinePageService` → `OfflinePageController`.
- **Branded offline page when Vaier itself is down** ✅ — the offline page above is served *by Vaier*, so it can't help when the Vaier container itself is down (Traefik then falls back to its bare "Bad gateway", and Vaier's own routers — defined on its container's labels — vanish while the self-published file-provider router still points at the dead backend). A separate, always-up `vaier-offline` service (tiny pinned nginx, `offline/html` + `offline/default.conf` bind-mounted) stands in: it serves one self-contained branded page (matching the offline-page styling, HTTP 503, 15s auto-refresh) for any path. A **low-priority Traefik fallback router** (`priority=50`) for the Vaier host sits above the lingering file router (30) but below Vaier's real docker routers (100/200), so while Vaier is up its own routers win and when the container stops this becomes the top match. A `vaier-down` `errors` middleware on the same container also covers the transient "container up but returning 5xx mid-restart" window. Infra-only (docker-compose + static assets); no Java change. A `.gitignore` exception keeps `offline/default.conf` tracked despite the blanket `*.conf` ignore.
- **Version visible under Settings → About** ✅ — the running Vaier build version is surfaced so the operator always knows which build is deployed. The Maven `build-info` goal bakes `project.version` into the jar as Spring `BuildProperties`; `ForReadingAppVersion` / `BuildPropertiesVersionAdapter` reads it (falling back to `dev` when absent), exposed via `GetAppVersionUseCase` on `SettingsService` and `GET /settings/version`. The Settings page renders an **About** card showing `v<version>`. Replaced an earlier always-on corner badge on the machines page that was too intrusive. (The card originally also showed the running **edition** — see §6.14, retired.)
- **Host-down indicator on the launchpad** ✅ (closes [#208](https://github.com/getvaier/vaier/issues/208)) — `Server.State` is now tri-state: `OK`, `UNREACHABLE`, `UNKNOWN`. A `VISIBLE_INACTIVE` launchpad tile (host confirmed unreachable) shows an 11px red status dot in its top-right corner with a "Host offline" tooltip *and* drops its `href` so a click can't follow the dead link. An `UNKNOWN` host stays `VISIBLE_ACTIVE` (we don't know it's down) — no dot, normal link — but the services card icon goes grey (`icon-unknown`) instead of misleadingly green. `ReverseProxyRoute.hostState(...)` consumes the LAN-reachability snapshot (`Map<String, Reachability>`): `DOWN` → `UNREACHABLE` (regardless of relay), `UNKNOWN` → `UNKNOWN` when the route is otherwise routable, `OK` → existing relay / server-LAN-CIDR check. A dead relay tunnel still beats `UNKNOWN`. The snapshot comes from a new `ForCheckingLanReachability` driven port, implemented by `InMemoryLanReachabilityCache` (`adapter/driven/`) — LAN-reachability state was moved out of `LanServerReachabilityService` into that cache adapter so the cross-service read goes through a domain port rather than a use-case back-channel; the orchestrator service writes through a sibling `ForRecordingLanReachability` port. A confirmed UP↔DOWN transition invalidates the published-services cache and publishes `service-updated` on the `published-services` SSE topic, so the launchpad and services pages re-fetch immediately. The `Reachability` enum lives in the domain.
- **Public, viewer-adaptive launchpad** ✅ (closes [#207](https://github.com/getvaier/vaier/issues/207)) — the launchpad is public but adapts its tile set to the **viewer**, replacing the earlier binary "authenticated?" flag with the actual resolved identity. It consumes two sibling endpoints. `GET /launchpad/services` stays anonymously reachable and returns the public-only subset (auth mode `NONE`). `GET /launchpad/services-authenticated` sits on the **identity-optional router** (oauth2-authn only — no forced sign-in, no admin gate): a valid session gets its `X-Auth-Request-*` headers injected and the endpoint resolves the caller's **access entry** and returns the public tiles *plus* every social service that identity may reach; an anonymous request gets `401` and the page falls back to the public feed. Visibility is decided per-viewer by `ReverseProxyRoute.isVisibleToLaunchpadViewer(viewer, serviceGroups)` — admins see all social services, a user sees a social service iff its **access rule**'s allowed groups intersect the user's **access groups**, and anonymous/pending/unknown identities get public only. The launchpad path is a pure read: it resolves the viewer via `ResolveViewerUseCase` (read-only; unknown/blank → empty) and never creates a pending entry (only `/authz/verify` does). Under social login, auth on a service means "internal — sign in first"; a social service's tile now surfaces to exactly the users allowed to reach it. Live updates come from a third, public endpoint: `GET /launchpad/events` (also anonymously reachable) is a **signal-only** SSE stream — it fires the same event names as the admin's `/published-services/events` but with an empty payload, so a logged-out visitor's tiles refresh on change without the private service **subdomain** the full stream carries in its data ever leaking. `SseEventPublisher` fans each publish out to full subscribers (with data) and signal-only subscribers (data stripped); the detailed `/published-services/events` stays on the identity router for the admin publish-progress toasts that need the subdomain.
- **`GET /users/me` for the viewer-adaptive topbar** ✅ — `AuthRestController` serves `/users/me` behind the identity-optional router (oauth2-authn only), reads the caller identity from oauth2-proxy's `X-Auth-Request-User`/`-Name`/`-Email`/`-Connector`/`-Connector-Uid` headers, and returns `MeResponse(username, displayname, email, isAdmin, logoutUrl, loginUrl, provider, providerUserId)`. The launchpad renders its topbar from this — greeting the signed-in identity by photo (else name) and showing the admin nav only when `isAdmin`. An anonymous caller is stopped at the router with `401`, which the page treats as logged-out. Because the launchpad is a viewer's main authenticated touch-point and never crosses `/authz/verify`, `/users/me` also **captures** the presented display name and last-used provider onto the caller's existing access entry (`CaptureViewerIdentityUseCase`) — so a user who only ever uses the launchpad still has a named, provider-badged **Users** card. Capture is a no-op write when nothing changed, never wipes a stored value on a blank/absent header, and (unlike `/authz/verify`) never creates a pending entry for an unknown email — first-sighting stays on the forward-auth path.
- **Domain-owned launchpad tile name + alias** ✅ — `ReverseProxyRoute.launchpadDisplayName(baseDomain)` decides the tile label: operator-supplied `launchpadAlias` wins (persisted as `x-vaier-launchpad-alias`), otherwise the final segment of `pathPrefix` for path-based routes (so `services.example.com/grafana` displays as `grafana`, not `services/grafana`), otherwise the first DNS label. The subdomain moves into the tile's sub-line beside the peer when it differs from the display name. Editable via the Display name input in the admin published-services details panel and the unified `PATCH /published-services/{dnsName}` partial-update endpoint (`{"launchpadAlias": ...}`).
- **Domain-owned icon lookup identity** ✅ — `ReverseProxyRoute.launchpadIconQuery()` decides what the launchpad sends to `/icon`: host-only routes resolve a single icon per FQDN; path-based routes carry both host and `pathPrefix` so siblings on one subdomain (e.g. `services.example.com/grafana` and `…/jenkins`) cache separately. The icon fetcher accepts a `pathPrefix` query param and probes `https://host{pathPrefix}/` first for HTML/`favicon.ico` discovery before falling back to the FQDN root; the CDN-by-name fallback uses the final path segment when present. Closes the regression where every path-based sibling shared a single cache key and fell back to the letter avatar.
- **Filesystem-backed icon cache** ✅ — a resolved service icon is now fetched online at most once, then served from disk across restarts. `IconService` checks the in-memory map, then a new `ForStoringIcons` driven port (filesystem store at `icon.cache.path`, default `/icons`, mounted read-write in `docker-compose.yml`), then resolves online and persists the positive result to disk (negatives are remembered only in memory so a once-dead host can recover). The shared `IconResolution.cacheKey(host, pathPrefix)` addresses both tiers so memory and disk never drift. `FilesystemIconStoreAdapter` stores each icon as two files named by the SHA-256 of the key (`<hash>` bytes + `<hash>.ct` content-type), written via temp-file-then-atomic-move, and degrades gracefully (disables itself, never throws) when the directory can't be created/written — mirroring the geoip DB. The `Icon` value object moved to the domain (`net.vaier.domain.Icon`).
- **Backing container image on the launchpad tile** ✅ (closes [#210](https://github.com/getvaier/vaier/issues/210)) — hovering a tile reveals the Docker image and version of the container behind the service, so an operator can see what's running without inspecting the host by hand. `ReverseProxyRoute.backingContainer(...)` resolves a route to its container among the discovered peer / Vaier-server / LAN-server containers: a peer route matches by VPN IP, a Vaier-server route by container name (or port), a LAN-service route by LAN address. The image/version ride along on `LaunchpadServiceUco`; the launchpad renders them in a small styled tooltip. `PublishingService` caches the discovered-container snapshot for 60s (`containerImageSnapshotTtlMillis`) so the launchpad's aggressive reloads (tab focus, SSE) don't re-query every Docker daemon over the VPN. A service published as a bare LAN host:port has no container — its tile simply omits the version.
- **LAN-native service version** ✅ (the [#210](https://github.com/getvaier/vaier/issues/210) follow-up) — a published service can carry an operator-configured **version endpoint**: a URL (a path on the service, or an absolute URL) plus a property name. The version is read via the `ForProbingServiceVersion` driven port — invoked from `ReverseProxyRoute.probeVersion(...)` so the domain owns the port call — by GETting the endpoint and extracting a `property="value"` label from the response (Prometheus text-exposition style). The probed version takes precedence over a backing container's image tag and rides along on `LaunchpadServiceUco`, so a service running natively on a LAN machine (no discoverable container) still reports its version in the tile tooltip. Probes run concurrently and `PublishingService` caches them for 60s (`versionProbeSnapshotTtlMillis`). Configured via the Version endpoint inputs on the published-services details panel and the unified `PATCH /published-services/{dnsName}` partial-update endpoint (`{"versionEndpoint": "...", "versionProperty": "..."}`); persisted in the Traefik YAML as `x-vaier-version-endpoint`.
- **Backing image + version on the Services-page card** ✅ (closes [#245](https://github.com/getvaier/vaier/issues/245)) — the same image/version that the launchpad tile shows on hover is also surfaced inline on the Services-page service card as a `Version` detail row (image as a monospace code chip, dim `version <x>` beside it). Same resolution as the launchpad: `ReverseProxyRoute.backingContainer(...)` against the discovered container snapshot, with a configured `versionEndpoint`'s probed value taking precedence. Threaded through `PublishedServiceUco.image` / `version` so the browser doesn't re-derive either. The row is omitted when neither value is set (e.g. bare LAN host:port).
- **A peer's tunnel coming up now invalidates the cache, and a peer's address is judged by its own tunnel alone** ✅ (fixed 2026-09-08) — every Colina service showed `OFFLINE` on the launchpad for up to 40 minutes on a perfectly healthy tunnel. Cause: the published-services cache was rebuilt on route edits, Vaier-server container events and LAN-server reachability changes, but never when a *peer's* tunnel came up — and `wireguard` starts roughly three minutes after `vaier` in the compose dependency order, so the first read after boot froze every peer-hosted service `UNREACHABLE` before the tunnel had a chance to be up. Fix: `PeerStatsScheduler`'s existing 10s tick now compares a new domain value, `PeerLiveness` (the set of currently-connected peers' public keys), against the previous tick; any flip invalidates the published-services cache and publishes `service-updated` on `published-services`, exactly as the LAN UP↔DOWN transition above already does. A second, related bug rode the same code path: `ReverseProxyRoute.hostState` let a Vaier-server container listening on the same port vouch for a route whose address actually belonged to a peer — openHAB on Colina's `:8080` read `OK` only because the Vaier container itself happens to listen on `8080`, not because Colina was reachable. An address inside a peer's `AllowedIPs` is now judged by that peer's own tunnel state alone, checked before the local-container match rather than after it.

**Launchpad backlog:**
- **Live updates for the public launchpad** — the launchpad's SSE stream (`/published-services/events`) is not on the public or identity-optional router, so it falls through to the admin catch-all. An anonymous (or non-admin) launchpad therefore renders once and won't receive push updates when a service goes up/down or is (un)published; it only refreshes on reload / tab focus. Exposing a viewer-scoped SSE feed on the identity-optional (or public) tier would let anonymous and non-admin launchpads live-update without leaking admin-only detail.

---

### 6.4 Wildcard DNS ✅ (implemented 2026-07-29, closes [#331](https://github.com/getvaier/vaier/issues/331) — part of the [#330](https://github.com/getvaier/vaier/issues/330) newcomer-onboarding epic)

**DNS is one record the operator makes once, and Vaier never writes DNS again.** Before first boot:

| Record | Type | Value |
|--------|------|-------|
| `*.<domain>` | A | the Vaier server's public IP |

That record answers for the three infrastructure hosts (`vaier.<domain>`, `oauth2.<domain>`, `dex.<domain>`) and for every service Vaier will ever publish. Publishing a service is now **one step**: write the Traefik route. Let's Encrypt is untouched — HTTP-01 still issues a certificate per hostname; the wildcard only makes the name *resolve*, and there is no wildcard certificate anywhere in the stack.

**Stronger than the issue asked for: Route53 was removed, not demoted.** #331's acceptance criteria kept a Route53 mode alongside the wildcard. The decision taken instead was to delete AWS DNS outright, so there is exactly one DNS story and no mode to pick. This makes Vaier **provider-agnostic**: any DNS host that can serve one wildcard `A` record qualifies, which is the point — the newcomer's DNS is wherever they bought the domain, and asking them for an IAM key was the single largest onboarding cliff. Deleted with it: `Route53DnsAdapter`, the AWS SDK route53 dependency, `VAIER_AWS_KEY` / `VAIER_AWS_SECRET` (env *and* `vaier-config.yml`), `PUT /settings/aws` and the AWS credentials UI, `ManualDnsAdapter` and `DnsAdapterConfig`, the entire DNS domain (`DnsProvider`, `DnsZone`, `DnsRecord`, `DnsState`, `ForPersistingDnsRecords`, `ForValidatingAwsCredentials`), `DnsService`, `DnsRestController` and its `/dns/*` endpoints, and the Add/Delete DnsRecord/DnsZone + GetDnsInfo use cases. `ForResolvingDns` survives — Vaier still *reads* DNS, it just never writes it.

**What went out of the publish flow.** The per-publish CNAME create, the DNS propagation wait (`waitForDnsThenActivate` / `waitForLanDnsThenActivate`, the 2-minute timeout and 3s retry), the DNS-timeout rollback branch, and the `publish-dns-created` / `publish-dns-propagated` / `publish-dns-timeout` SSE events. A **publish rollback now only ever fires because Traefik failed**. Unpublish no longer deletes a record; machine deletion no longer cleans DNS up; `ReverseProxyRoute.hasSiblingOnHost` and `dnsState(...)` are gone, and `launchpadVisibility` lost its `dnsState` parameter — a wildcard-covered name always resolves, so **DNS can no longer hide a launchpad tile**.

**Boot-time verification (`domain.WildcardDns`).** Since Vaier no longer creates the record, it checks it. At boot `Lifecycle` resolves a random probe name under the domain against a public resolver (`1.1.1.1` / `8.8.8.8` — what Let's Encrypt queries, not the container's split-horizon view) and compares the answer with this server's own public IP (`ForResolvingPublicHost`). The verdict is a `WildcardDnsReport` carrying a `WildcardDnsStatus` — `COVERED`, `NOT_RESOLVING`, `RESOLVES_ELSEWHERE`, `UNCONFIRMED` — and a plain-language sentence naming the one action to take. It is stated in the boot log *and* held in `WildcardDnsStatusHolder` so the Settings pane can state it too (a log line scrolls away; the question "did I set DNS up right?" should be answerable without one). The random labels are minted in `StartupLifecycleRunner`, not in the domain, so the check stays deterministic under test.

**The probe is deliberately two labels deep — do not "simplify" it to one.** Vaier publishes machine-qualified hostnames two labels deep, e.g. `openhab.colina27.eilertsen.family`. A DNS wildcard matches by **closest encloser** (RFC 4592): `*.example.com` stops covering `anything.colina27.example.com` the moment the zone gains *any* real record under `colina27`. A single-label probe (`<random>.example.com`) is answered by the wildcard regardless, so it would report `COVERED` while every machine-qualified service was dead — precisely the failure the check exists to catch. Probing `<random>.<random>.<domain>` exercises the depth Vaier actually publishes at. The two random slices are independent, so neither label can collide with a name the zone really has.

**Operator-facing consequence of closest encloser.** The zone must not carry a real record under a machine label. `install.sh`'s `.env` template and the README both say so; a `*.<machine>.<domain>` wildcard alongside it is the escape hatch when an operator genuinely needs a record there.

**V2** — Cloudflare as a first-class *automated* provider was tracked in [#154](https://github.com/getvaier/vaier/issues/154). With DNS out of the product entirely, the case for any provider integration is now much weaker: it would re-introduce the credential-per-provider onboarding cost that #331 removed. Keep #154 open only for a concrete need the wildcard cannot serve.

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

### 6.8 Container Update Notifications 🟡 (detection, rollup email, the Explorer mark and the on-demand check implemented — [#57](https://github.com/getvaier/vaier/issues/57))

Keep the operator aware when a container's image has an **update available**. Reopened after a stale `vaultwarden` image on a server peer silently broke Bitwarden mobile sync: pulling it fixed it in seconds, but nothing in Vaier knew, so nothing could tell the operator. This is that signal.

**What's implemented:**
- **Detection is digest drift on the same tag.** The **local image digest** (read from Docker's `RepoDigests` — *not* `ImageId`, which is the config sha and never matches a registry) is compared against the **registry digest** that registry serves for the very same tag today. Different digest ⇒ **update available**. `UpdateAvailability.compare` owns the verdict so the sweep, the email and the REST payload can never disagree.
- **Registry-generic, not Docker Hub-specific.** `RegistryV2ImageAdapter` (`ForResolvingRegistryDigest`) speaks the plain Registry v2 HEAD-manifest + anonymous bearer-token flow: HEAD → 401 challenge → pull-scoped token from the realm the challenge names → HEAD with the bearer. Docker Hub, `ghcr.io` and `lscr.io` differ only in host and realm, so all three work with no credentials configured. `ImageReference` parses Docker's implicit naming grammar (`redis` → `registry-1.docker.io/library/redis:latest`; `lscr.io/…` → that host) — the first segment is a registry host only if it has a dot or colon, or is `localhost`.
- **Three verdicts, and UNKNOWN is the point.** `UPDATE_AVAILABLE`, `UP_TO_DATE`, `UNKNOWN`. An unreachable/rate-limited registry, a locally-built image with no registry digest, and a digest-pinned (`@sha256:…`) image all render UNKNOWN — never outdated, never up to date. Collapsing "cannot tell" into either answer makes the monitor lie (into up-to-date it hides the vaultwarden case; into update-available it cries wolf until admins filter the mail).
- **Daily sweep, 24 h TTL cache.** `ImageUpdateWatcher` runs `@Scheduled(fixedDelay = 24 h, initialDelay = 2 min)`; `ImageUpdateSweep` asks **once per distinct image**, not once per container, because rate limits count requests (anonymous Docker Hub ≈ 100 manifest requests / 6 h). Successful answers are cached 24 h keyed by canonical image reference; **failures are deliberately not cached** — one blip must not blind the next sweep. The 30s container scrape never touches a registry. The sweep is total: a throwing/timing-out registry degrades that one image to UNKNOWN and the sweep carries on.
- **Edge-transition rollup email.** `ImageUpdateTracker` reports only images that have *just* become out of date; `ImageUpdateRollup` renders one mail listing them (three stale images in one sweep = one email; nothing changed = no email) to every **admin**-role **access entry**, via the shared SMTP notifier. Two rules differ from `PeerConnectivityTracker`, both deliberately: it is **not baseline-quiet** (an image already stale at the first sweep *is* reported — that is the incident this feature exists for; `RemoteDiskPressureTracker` has since made the same call, for the same reason), and **UNKNOWN is not a change** (it leaves the last known verdict standing, so a rate-limited sweep can't re-mail about an image already reported).
- ✅ **The latch survives a restart.** `ImageUpdateTracker` reaches its state through a driven port (`ForPersistingImageUpdateState`, file-backed by `ImageUpdateStateFileAdapter` in `update-available.yml`) instead of an in-memory map nothing persisted. It was the map that made the operator receive the same two or three images over and over: every restart it came back empty, so the next sweep read images that were genuinely still out of date as *newly* out of date — and this project rebuilds and redeploys several times a day. **The inverse of the disk-alert fix, deliberately.** That one persisted its latch *and* made the first observation after a restart speak, because a disk already full at startup deserves to be heard. An image already known stale does not: the operator has been told, nothing changed while Vaier was down, and repeating it is exactly the noise being removed. Same mechanism, opposite conclusion — which is why the tracker says so in a comment. UNKNOWN still leaves what was known standing, a confirmed pull still clears an image so a genuine regression re-announces, and an image that leaves the sweep is still forgotten. A missing or corrupt state file degrades to knowing nothing — one duplicate mail, never a dead sweep.
- ✅ **Machine-aware tracking (#57 refinement).** The tracked unit is a **scoped image** — `domain.ScopedImage(machine, image)`, rendered `vaultwarden/server:latest on Apalveien 5` — not a bare image string, because an operator told only the image can't tell which host to SSH into. The sweep is fed containers grouped by machine (`ImageUpdateSweep.MachineContainers`; the Vaier server's own under `LanAnchor.VAIER_SERVER_NAME`, each peer's under `peer.peerName()`) and returns `Map<ScopedImage, UpdateAvailability>`; the tracker, rollup and `UpdateCheckOutcome` are all keyed by it, and the alert names the machine in subject and body. **The registry is still asked once per distinct image string, never once per (machine, image)** — the same tag resolves to the same digest everywhere, so the per-machine granularity comes only from comparing each container's *own* local digest against that one shared registry answer. This also fixed two latent bugs the image-only keying hid: the same tag on two machines with different local digests used to collapse to one last-wins verdict, and a tag going stale on a second machine after the first was already reported used to raise no new alert.
- **No auto-update, ever.** Read-only detection: Vaier never pulls and never restarts. The mail says so explicitly ("Vaier does not pull or restart anything — updating is your call").
- **Coverage:** the Vaier server's own containers and VPN **server peer** containers (`ContainerService.sweepImageUpdates`, over the existing snapshots).
- **The verdict ships on the existing `/docker-services/*` payloads.** `DockerService` gained `updateAvailable` (defaulting to `UNKNOWN`, since a host scrape can't know it) and `imageDigest`. Detection opened no endpoint; only the on-demand check below did.
- ✅ **The Explorer mark (UI slice).** A container with an **update available** wears a small yellow mark in the Explorer: in the tree beside its name (container rows are first-class entries, so it is visible while scanning the fleet) and in the machine's container list. One helper, `updateMark()`, decides only *whether there is something worth drawing* — it reads the `updateAvailable` enum and nothing else. **The browser never sees a digest**: `imageDigest` is not read in JS at all, so `UpdateAvailability.compare` in the domain stays the only place the two digests are ever weighed, and the rollup email and the mark cannot disagree. Advisory yellow (the degraded dot's colour), never red — nothing is broken when an update exists, and red must keep meaning down. **UNKNOWN renders nothing**, deliberately: it is the resting state (unreachable registry, no sweep yet), and a mark on every row for it would train the operator to ignore the column. Because absence of a mark is therefore not a promise, the single container's Inspector carries an honest `Update` row naming the verdict in words — "Update available" / "Up to date" / "Vaier cannot tell". At the time this shipped no verb went with it: Vaier had no endpoint to pull or restart, so the tooltip named the *operator's* action ("pull this image on the machine yourself"). **Superseded by §6.37 ([#352](https://github.com/getvaier/vaier/issues/352)):** a container that is both update-available and **updatable** now offers an Update action on its own page; the tooltip on the mark itself still names the operator's action for a container Vaier will not recreate, since that is the only one there is.

- ✅ **The on-demand update check (slice 3).** The operator reads the rollup mail, SSHs in, `docker compose pull && up -d`, and wants Vaier to agree *now* — not in up to 24 h. A stale mark on an image they already fixed is corrosive: a mark you know is wrong is a mark you learn to ignore. **Check the registries now**, on a machine's container list in the Explorer, is that control. `POST /docker-services/image-updates/check` → `CheckForImageUpdatesUseCase` (on `ContainerService`) → `UpdateCheckOutcome`. Authenticated like every other admin endpoint. **Two staleness traps, on opposite sides of the comparison, and both had to be closed or the button would confirm the very mark it was pressed to clear:** (1) the *registry* digest is cached 24 h, and what changes when the operator pulls is the *local* digest — so re-sweeping from cache could compare their new local digest against a pre-pull registry answer and report **update available** on the image they just updated. `ForResolvingRegistryDigest.resolveDigestNow` / `ImageUpdateSweep.sweepFresh` refuse every remembered answer and *replace* the cached one, so the next daily sweep agrees too. (2) the *local* digest comes from the 30 s container scrape, and they click seconds after pulling — so the check calls `refresh()` **before** sweeping. **Rate-limit floor:** a forced sweep bypasses every cache, so a click-spammed button is a direct route to a 429 (which would degrade every image to UNKNOWN and blind the fleet at the worst possible moment). `UpdateCheckFloor` coalesces any check within 60 s of the last *admitted* one — measured from the last admitted check, not the last attempt, or an impatient operator would push the floor ahead of itself forever. A coalesced check asks nothing, scrapes nothing, and **says so**: `UpdateCheckOutcome.checked` is false and the UI reports when Vaier last really looked rather than painting a tick over a check that never ran. **The check mails its own news.** It folds its verdicts into `ImageUpdateTracker.update` exactly as the daily sweep does, and `UpdateCheckOutcome.newlyOutOfDate` carries the images that just went stale to the driving edge, where the shared `ImageUpdateAlerter` (the watcher's mailer too) sends one rollup — so the mail leaves with the yellow mark rather than with the next sweep, up to 24 h later. Because the check latches, the sweep that follows finds nothing new and stays quiet: one verdict, one mail, whichever path learned it. (The earlier rule — a check may *clear* an image's alert state but never *consume* one, leaving bad news for the daily mailer to break — was replaced 2026-09-03 after the operator watched a container go yellow on a check and got no mail: Vaier knew and sat on it for eight hours.) A check that finds an image up to date still forgets it, so a confirmed pull can never disarm a future alert. The tracker is a `@Bean` (`ImageUpdateConfig`) shared by the watcher and the service, since two instances would each hold half the truth. **Still read-only:** it checks, it never pulls or restarts — labelled *Check the registries now* rather than *Check for updates* (the phrasing every OS updater uses immediately before installing something) to keep that distinction, even now that the Explorer offers one container verb elsewhere (**Update**, §6.37, [#352](https://github.com/getvaier/vaier/issues/352)): checking and updating stay two different buttons doing two different things.
- ✅ **Live push (for the check).** A settled check publishes `service-updated` on the existing **`published-services`** topic — the one the container payloads already ride and `watchServices()` already re-reads containers on — so every open Explorer repaints without a poll. Published **only when a verdict actually moved**; the browser that clicked learns "nothing new" from its own response and needs no event.
- ✅ **A moving tag keeps the mark and stops the mail (2026-09-04).** `netdata/netdata:latest` on Colina 27 was mailed to every admin *every morning*, and the verdict was right every time: on Docker Hub `netdata:latest` **is** `:edge`, a nightly rebuilt around 01:37 UTC, so every daily **update sweep** truthfully found an **update available**. The operator updates, the next nightly lands, the latch releases, and the mail comes again tomorrow. A tag that moves on its own is a *channel*, not trouble, and Vaier notifies only on trouble. So a **moving tag** — an image tag Vaier has seen move to a new **registry digest** twice running, each change within about a day and a half of the next and the last of them that recently — still earns the **update available** mark in the Explorer and never raises an **update-available alert**. **Two changes, not one:** one change is exactly what a settled tag looks like when it cuts a release, which is the news [#57](https://github.com/getvaier/vaier/issues/57) exists to carry.
  - **The rule is about *when* the digest changed, not how many sweeps ran** — and the first attempt, which counted sweeps, was defeated by this project's own habits before it ever reached the fleet. `ImageUpdateWatcher` is `@Scheduled(fixedDelay = 1 day, initialDelay = 2 min)`, so **every boot sweeps two minutes in**, and `RegistryV2ImageAdapter`'s 24-hour cache is an in-memory map that dies with the process — so an afternoon redeploy re-asked the registry, got the morning's digest back, and recording that as an answer read as the tag *settling*. On a box that redeploys several times a day the suppression would have engaged approximately never. `RegistryDigestHistory` now appends **only a digest that differs from the last one recorded**, each entry carrying the `Instant` it was **first seen**; `isMoving(image, now)` wants three entries with both changes, and the newest, inside a 36-hour window. An unchanged answer is therefore inert wherever it comes from, which also let the earlier "an update check does not vote" exclusion be **deleted** — a check can answer honestly now, and a genuinely new digest the operator finds first is recorded rather than thrown away. It also means a channel that goes quiet stops being moving **on the clock alone**, with no sweep needing to say so, and that an ordinary release cadence measured in weeks can never qualify however many times it changes.
  - **Persistence.** The answers live beside the latch in a second `registryDigests:` section of `update-available.yml` — digest plus `firstSeen` per entry, at most three per image — on disk because a history that died with the process would never see two changes. Each section is written **without disturbing the other**, since a save that dumped only its own key would drop the other's: for the latch that is a fleet-wide re-announcement, for the history a suppression that never engages. A file carrying only `updateAvailable:` still loads, and an entry whose instant will not parse is **skipped rather than dated to now** — "now" would invent a change that just happened, which is exactly what makes a tag read as moving. **An image missing from one sweep keeps its history**: an unreachable peer reports an *empty* container list, so a single absence must not cost days of learning; only a week with nothing running it drops an image.
  - **A moving tag is still latched.** Without recording the change, the first sweep after the tag settled would mail a change from days ago — the silence would end in a backlog rather than with the next real news.
  - **And the silence is visible.** The tracker publishes the moving tags through `ForStoringContainerSnapshots.storeMovingTags`, so `/docker-services/*` containers carry a `movingTag` boolean and the Explorer draws one short muted word — `moving`, the glossary's own term rather than a cadence like *nightly* that would promise a rhythm the rule does not measure — beside the yellow mark, dim and unshaped (colour and shape stay reserved for trouble), with the container's Inspector saying "Update available — a moving tag, so Vaier marks it and never mails about it". A suppressed alert nobody can see the reason for reads as Vaier having missed it.

**Backlog (not built):**
- 🔲 **LAN-server container coverage.** LAN-server containers are not swept and therefore render UNKNOWN. Their scrape goes through a relay peer, so the sweep needs those snapshots folded in.
- 🔲 **Newer-tag detection for pinned tags.** Digest drift cannot see a *newer tag*: `traefik:v3.2.1` never drifts, so a released `v3.3.0` is invisible and the image reads UP_TO_DATE — correctly, for the question asked. Detecting "a newer tag exists" is tag-list + version-ordering work (and per-registry), explicitly a later slice.
- ✅ **Historical dangling images were cleared by hand (2026-09-04).** An update now removes the one image it replaced (§6.37), but Vaier never goes looking for what earlier updates left behind. The backlog this feature exposed — **11.4 GiB on Colina 27 and 6.3 GiB on Apalveien 5** — was **pruned by the operator by hand today**, so nothing is outstanding. It stays out of scope by design: removing the single image you just replaced and sweeping a host for everything unused are different acts with different blast radii, and only the first is Vaier's to take.
- 🔲 **The update-available latch is still dropped when a peer is unreachable.** The digest history now survives a single absence (an unreachable peer reports an empty container list, so every image on it vanishes from the sweep), but the latch in `updateAvailable:` does not: it is a bare set of **scoped images** with no timestamps, so "absent" and "gone" are indistinguishable and an image comes back reading as newly out of date. The cost is one duplicate mail per peer outage. Fixing it means timestamping the latch too, which is a file-format change of its own — deliberately not smuggled in with this one.
- 🔲 **Live push for the *daily* sweep.** The on-demand check pushes (above), but `sweepImageUpdates()` still does not — so a daily sweep landing while the Explorer is open does not repaint until the containers are re-read. The plumbing now exists and it is a small step: publish on the same topic when the sweep's verdicts change.
- 🔲 **Vaier's own update must carry the stack, not just the image** ([#343](https://github.com/getvaier/vaier/issues/343)). Distinct from the detection above — this is the **self-update** control that updates Vaier itself. `SelfUpdateScript.generate` pins the running digest, then pulls and force-recreates **only the `vaier` service**; it never refreshes `docker-compose.yml` or the committed asset trees the stack bind-mounts, and never recreates another service. So a fix living outside the app image — an init container, a Traefik middleware, a security header, the oauth2-proxy sign-in template — is invisible to Update, and the operator has to re-run `install.sh` in the install dir by hand. Surfaced when the #332 follow-up (a GitHub button on an install with no GitHub connector) turned out to be un-shippable through Update. The sync should reuse `install.sh`'s `RUNTIME_PATHS` (guarded by `InstallScriptCoverageTest`) at the ref matching the image being updated to, then `docker compose up -d` the whole project. **Rollback grows with it:** today it restores one pinned digest; once the compose file can change, the previous compose file and assets have to be restored on the same failure path. Operator state (`.env`, every runtime dir) stays out of scope, exactly as in `install.sh`.

**Out of scope:** automatic updates (never — see above), self-hosted/authenticated private registries, webhook/push notifications.

---

### 6.9 Email Notifications ✅ (implemented)

Vaier ships an SMTP notifier that carries Vaier's admin alert emails (machine up/down, disk pressure, new access requests). Settings and the password are stored in `vaier-config.yml` (the password owner-only; read back via `ForReadingStoredSmtpPassword`, implemented by `VaierConfigFileAdapter`). (It previously also powered Authelia's password-reset mail; Authelia has been removed entirely.)

**What's implemented:**
- Settings → *Email notifications* form with host, port, username, password, sender, and a "Send test email to …" recipient field.
- **Send test email** button does a full AUTH + roundtrip send via Jakarta Mail so misconfigurations surface without touching the auth layer.
- **Save** verifies credentials against the SMTP server *before* storing them. On failure the REST endpoint returns HTTP 400 with the upstream SMTP error. The password is persisted to `vaier-config.yml` (owner-only); saving no longer writes any Authelia notifier block or restarts a container — the Authelia integration is gone entirely.
- Password field can be left blank on save/test to reuse the stored value, so host/sender/etc. can be edited without retyping the secret.
- **Server machine up/down alerts** ([#173](https://github.com/getvaier/vaier/issues/173)): two 30s schedulers — one watching WireGuard handshake age for `UBUNTU_SERVER`/`WINDOWS_SERVER` peers, one watching the LAN reachability TCP probe for `LAN_SERVER` machines. Mobile/Windows clients are excluded — their disconnects are routine user behaviour. On a state change either watcher emails every **admin**-role **access entry** with subject `[Vaier] <name> is now <connected|disconnected>` and a body containing the machine's name, type, last handshake (or last-seen timestamp for LAN servers), LAN address, and a link back to `vaier.<domain>/explorer.html` (was `/vpn-peers.html` before the Infrastructure page was deleted). Per-machine state is in-memory; the first observation after Vaier startup is treated as a baseline so a restart never produces a notification storm. No quiet-hours setting — alerts fire 24/7.
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
  - **Trackers are keyed on machine AND mount point.** `RemoteDiskPressureTracker` and `RemoteDiskForecastTracker` were machine-keyed, which was defensible only while a machine had one disk. It is not now: the NAS's `/` sits permanently above the threshold, so a machine-keyed tracker would already read "in pressure" and `/volume1` crossing would be swallowed as "no change" — the exact silence this issue is about. Likewise a machine-keyed **disk-fill trend** would fit its least-squares slope through a sawtooth of unrelated disks. `DiskFillForecast` and `DiskFillForecastCleared` gained a `mountPoint`, and the forecast hands off to the pressure alert at *this filesystem's* resolved threshold. *(The machine half of that key was the machine **name** until the disk-pressure fix below re-keyed both trackers on **machine id** — two machines in this fleet are both called "Printer".)*
  - **Application + REST** — `GetMachineDiskUsageUseCase.getDiskUsage` returns `List<MachineFilesystemUco>` (device, mount point, raw `*Kb` block counts, human `size`/`available`, `usedPercent`, the **effective** `thresholdPercent`, `watched`, `aboveThreshold`), so `GET /machines/{machine}/disk` returns a **list**. New `SetDiskWatchUseCase` + `GetDiskWatchesUseCase`, both on the existing `MachineService` (one service per domain concept), behind `PUT /machines/{machine}/disk/watch` with `{mountPoint, watched, thresholdPercent}` — the **mount point travels in the body**, because a mount point is full of slashes and a path variable carrying them is a routing/encoding bug waiting to happen. Both endpoints are non-whitelisted paths under `/machines`, so they sit behind the admin auth chain; nothing anonymous. A `df` that yields no real filesystem is still a `DiskUnreadableException` → **502**, never an empty list ("this machine has nothing to watch" would be the same silence again).
  - **Explorer** — the `disk` Inspector lists every filesystem: mount point + device (mono, they're coordinates), size and free space, a usage meter with a **threshold tick**, and a watch control (mute switch + threshold input). A **muted filesystem** keeps its meter but loses its tick — nothing is being judged. Saving a watch re-reads the machine's disks rather than recomputing locally, because the verdict is the server's.
- **Disk-fill forecast (early-warning alert)** ✅ — a second, forward-looking consumer of the same `df -P` readings the disk-pressure watcher already takes (no extra SSH round-trip). Per **filesystem** (per machine until #325), `RemoteDiskWatcher` feeds each reading and `clock.instant()` into a `RemoteDiskForecastTracker` (sibling of `RemoteDiskPressureTracker`), which reaches its own state through the driven port `ForPersistingDiskFillTrends` and then decides only *whom to tell*. **Rebuilt** ✅ — as first shipped it was structurally incapable of a correct early warning and sent exactly zero emails, for three compounding reasons (an integer-percent fit, a one-hour window, and in-memory state), and a fourth found during the rebuild: a **runway measured to 100% that the hand-off gate made unreachable**. What it is now:
  - **The line is fitted through free space in bytes, not `df`'s `Use%` column.** The old fit ran over integer percent in a one-hour window (K=12 samples at the watcher's five-minute cadence). This host climbed 70% → 81% over twelve days — about 0.9%/day — so every sample in any such window read the *same integer*, the least-squares slope was exactly zero, and no forecast could ever be produced. In the rare window straddling an integer tick it read ~1.09%/h instead: a 19-hour runway for a disk with 23 days left. Blind almost always, wildly wrong otherwise. A `DiskFillSample` now carries `availableKb` (1024-blocks), which has no such floor.
  - **The samples and the already-warned latch live on disk.** They were fields on the scheduled `RemoteDiskWatcher`, wiped by every redeploy — several a day here — which is the same latch bug fixed for the *level* path in `26196ef`, one line further down. A projection needs days of baseline, and nothing that lives only as long as the process can accumulate days of anything. New driven port `ForPersistingDiskFillTrends` (`find` / `save` / `retainOnly`) with `DiskFillTrendFileAdapter` writing `${VAIER_CONFIG_PATH}/disk-fill-trend.yml` under a root `trends:` key — no secrets, so a plain tolerant SnakeYAML round-trip like `DiskWatchFileAdapter` and `DiskPressureStateFileAdapter`. Keyed on **machine id** and mount point together, never a machine name. Tolerant on load: an entry or a sample that will not parse is dropped and logged rather than being fatal, and an absent file is the healthy first-boot state — losing a trend costs a week of rebuilt baseline, never a warning that was owed.
  - **New domain types, two deletions.** `DiskFillTrend` is the **disk-fill trend**: one filesystem's retained samples plus its warned latch, owning every decision the forecast rests on — what is retained and for how long, how much baseline a projection needs, the fit itself, and the gates. `DiskFillSample` is one `(at, availableKb)` reading. `DiskFillHistory` (the K=12 ring buffer) and `DiskPressureTracker` (the boolean-latch class) are **deleted**; the latter had no consumer left once the forecast's latch moved onto the trend.
  - **Retention: about 7 days, at most one sample an hour** (≈168 per filesystem). `DiskFillTrend.observing` returns *itself* when the newest sample is younger than the hour, so the five-minute sweep writes the store roughly hourly instead of twelve times an hour, and `observe` saves only when the trend actually moved. `checkRemoteDiskUsage` now also calls `diskFillTrends.retainOnly(fleet)` beside the existing `retainOnly`s, so a machine deleted while Vaier was running does not keep a week of samples forever.
  - **The signal-quality gates are measured in days.** At least **12 retained samples** *and* at least **3 days** of span (was 3 samples and 15 minutes), and the fit window is additionally cut to a **whole number of days**. This host's disk sawtooths — a Docker build takes ~1.2 GiB and the nightly `docker-builder-prune.timer` gives it back — and that daily cycle only cancels out of a least-squares slope across complete days: over a single day it reads as ~1.9 GiB/day of fill on a disk going nowhere. A flat or draining filesystem still projects nothing; it has no finite runway.
  - **The runway counts down to the filesystem's own threshold, not to 100%** — and without this the feature is unreachable arithmetic, a second flavour of the "can never be true" bug the rebuild was about. At 0.9%/day, 80% → 100% is ~22 days, so a runway under the seven-day horizon needed the disk at ≥93.7% — where `currentPercent <= levelThreshold` is already false and the hand-off has given it to the level alert. **For the exact fill rate this was commissioned to catch, no early warning could ever be sent.** `DiskFillTrend.forecast` now takes the resolved `levelThreshold` and counts down to `RemoteDiskUsage.availableKbAt(threshold)` — measured against `used + available`, the same base `df` computes `Use%` from, so the forecast's floor and the level alert's trigger are the same point on the disk rather than two points a reserved-blocks apart. A filesystem whose *fitted* free space is already at or past that floor returns **no forecast at all**: there is no runway left to report, the level alert owns it, and reporting a zero runway instead would re-warn every time the nightly prune dipped it back under the line for a few hours. It is also the more honest quantity — the operator wants warning before the disk becomes a problem, not before it becomes a catastrophe — and it makes the hand-off exact: the forecast warns *while below* the threshold, *about crossing* it, and the level alert takes over at the moment the forecast predicted. On the real numbers this turns "never" into a mail while the disk is still in its low seventies, about a week before it crosses 80%.
  - **The runway divides the *fitted* free space** — the regression's value at the newest sample — not the live reading, so a build spike that drops free space 1.2 GiB for an afternoon cannot halve the runway, nor flip the gate on each morning and off each evening.
  - **The horizon is seven days.** `DiskFillForecast.FORECAST_HORIZON = Duration.ofDays(7)` replaces the fixed 24h: the trend is measured over a week now, and a projection that can only speak a day ahead is not an early warning of anything. Its companion `EARLY_WARNING_CLEAR_HORIZON = Duration.ofDays(10)` is **hysteresis** — a runway grazing back over the line (6.96 days → 7.00 → 7.04) would otherwise yield an all-clear followed by a fresh warning twice a week about a disk whose situation never changed, which is the same reasoning as the pressure **bands**: slipping back is not a recovery. The gate is now `warrantsEarlyWarning(int levelThreshold, boolean alreadyWarned)`, taking the wider horizon once warned. **The hand-off clause is unchanged, and deliberately has no hysteresis**: `currentPercent <= levelThreshold`, so the moment a disk crosses its level threshold the disk-pressure alert owns it and the forecast falls silent. `levelThreshold` is still the threshold *this filesystem* is judged against (its **disk watch**'s own, or the global one), already resolved by the caller, so the forecast hands off at exactly the level the pressure alert fires at — it warns once while below, then the level alert takes over, never both at once.
  - **The rate reads per day.** `fillRatePercentPerHour` became `fillRateKbPerHour` (1024-blocks per hour), and `fillRateHuman()` renders it as `1.2 GiB/day` by reusing `RemoteDiskUsage.humanBlocks` (now public), because a day is the unit an operator can picture. `runwayHuman()` says `30h` under two days and `4.5 days` beyond. **The mail says what it now means** — subject *"projected to reach its 80% threshold in ~4.5 days"*, never *"projected full"*, which on a slowly filling disk would be weeks wrong; the body names the threshold alongside `currentPercent` and the rate, and `DiskFillForecastCleared` says "no longer trending toward its alert threshold". `DiskFillForecast` gained a `thresholdPercent` component and, with two same-typed `int`s now adjacent in a six-component record, a Lombok `@Builder` — the house pattern for exactly this swap risk.
  - **Unchanged around the rebuild** — the tracker reports `CROSSED_ABOVE`/`CROSSED_BELOW` on the gate boolean, and — keeping the decision in the domain — splits a `CROSSED_BELOW` by *why* it flipped: still `<= levelThreshold` (drained, or fill slowed so the runway rose clear of the wider all-clear horizon) is a **genuine recovery** and yields a runway-free `DiskFillForecastCleared` (machine + mount point + current percent, "no longer trending toward its alert threshold, now at N%"); `> levelThreshold` is a **hand-off** to the disk-pressure alert and yields nothing, so admins never get a contradictory "cleared" and "is 86% full" at the same poll. The `Observation` carries `earlyWarning` and `cleared` payloads; the watcher just sends whichever is present. Notifies via a new `NotifyAdminsOfDiskFillForecastUseCase` (`notifyAdminsOfDiskFillForecast(DiskFillForecast)` / `notifyAdminsOfDiskFillForecastCleared(DiskFillForecastCleared)`) implemented on the existing `NotificationService`, reusing the `sendToAdmins` SMTP path. `Clock` is injected (bean `ClockConfig.systemClock()`, `Clock.systemUTC()`) so tests feed a stepped clock a rising series deterministically. Quiet on a filesystem's first observations by construction rather than by a baseline rule — the gates cannot be met until days of samples exist — and the failed-/unparseable-`df` paths `return` before the forecast feed, so a transient failure records no sample. Distinct from **remote disk pressure**: **runway**/trend ("will cross its threshold") vs level ("has crossed it"). Follows the strict-TDD, domain-owns-the-decisions discipline: slope, runway and the gate all live in the domain; the watcher only orchestrates.
- **Disk pressure alerts on the first sighting, and escalate by band** ✅ (implemented 2026-07-31) — the Vaier server's own root filesystem reached **89% against an 80% threshold and never sent one email**. Two faults compounded, and neither fix works alone:
  - `RemoteDiskPressureTracker` was a boolean latch whose *first* observation was always a silent baseline (borrowed from the machine up/down alerts, where a restart storm is the risk). A filesystem that was **already** above its threshold could therefore never produce a crossing.
  - That latch was an in-memory field on the scheduled `RemoteDiskWatcher`, wiped by every redeploy — several a day here — so every sweep was that silent first observation, forever. Persisting the latch alone would have converted "never alerts" into "never alerts, permanently"; speaking on the first observation alone would re-mail on every deploy.
  - **The first observation now speaks**, which is only safe because **the state is persisted**: new driven port `ForPersistingDiskPressureState` (`find` / `save` / `clear`) with `DiskPressureStateFileAdapter` writing `${VAIER_CONFIG_PATH}/disk-pressure.yml` under a root `pressure:` key. No secrets, so a plain tolerant SnakeYAML round-trip like `DiskWatchFileAdapter`. **Only filesystems currently in pressure have an entry**; an absent file is the healthy state. The tolerance errs the right way for a fix about silence: an unparseable entry is dropped, and a dropped entry means Vaier re-alerts about that filesystem rather than going quiet about it.
  - **Escalation is by band, never by timer** — new `domain.DiskPressureBand` (five-point steps: 80/85/90/95/100, `of(usedPercent)`, `isHigherThan`). A filesystem in pressure is alerted about once per band and again only on climbing into a higher one: 86 → 89 is silence, 89 → 91 is an email. Re-sending every N hours was rejected outright — it pages on the passage of time, which is exactly the heartbeat noise this project refuses to produce. Five points is deliberately coarse: finer and a disk wobbling either side of an edge pages on every wobble. **Slipping back down a band is not a reset** (91 → 89 → 91 never left pressure); only a genuine recovery below the threshold clears the state, so a disk that recovers and re-fills climbs the ladder from the bottom again. The band's constructor rejects a non-multiple-of-5 floor, so a hand-edited `disk-pressure.yml` can't shift the ladder.
  - **`domain.DiskPressureState`** — `(machineId, mountPoint, notifiedBand)`, the **notified band** for one filesystem, with the identity (`isFor`) and escalation (`isEscalatedBy`) predicates on the entity. The tracker reaches its state through the port (domain owns the port call); the watcher hands the port in and then only decides *whom to tell*. `update(...)` returning a crossing enum became `observe(machineId, filesystem, verdict)` returning a `Verdict(outcome, band, notifiedBand)` over `QUIET` / `ALERT` / `SUPPRESSED` / `EASING` / `RECOVERED`.
  - **Suppression is logged at INFO** — `… is at 89% — above its 85% threshold, already notified at band 85%; staying quiet`. The old no-op branch logged nothing, so a disk sitting at 89% in total silence looked exactly like a disk nobody was watching; an operator must be able to tell the two apart from the log alone.
  - **Recovery needs a margin, not a crossing** ✅ *(follow-up fix)* — the same "slipping back is not a recovery" reasoning had never been applied to the recovery edge, and *any* reading at or below the threshold cleared the state and mailed a `RECOVERED`. The next reading over the line was then a first-ever observation again, so it mailed an `ALERT`. On this host that is **an alert-and-recovery pair every single day**: `/` sits against its 80% threshold, a Docker build adds ~1.2 GiB and pushes it over, the nightly `docker-builder-prune.timer` pulls it back. Found while writing the forecast's sawtooth test, where a `verify(notifier)` fired seven times over a week where once was right. **Recovery is now `RemoteDiskUsage.isClearOf(threshold)`**: used percent at or below `threshold − RECOVERY_MARGIN_PERCENT`, floored at empty so a filesystem watched at 3% can still recover. The margin is `DiskPressureBand.STEP` — five points, one whole band — so the hysteresis on the way down is the same size as the step Vaier escalates in, rather than a number of its own. In between, a new `Outcome.EASING`: still in pressure, no mail, and **logged at INFO like `SUPPRESSED`** (`… is at 82% — under its 85% threshold but not by the 5-point recovery margin, still in pressure at band 85%; staying quiet`), so "waiting to clear" is as visible as "already notified". `observe` now takes the whole `RemoteDiskUsage.DiskVerdict` instead of its `breaching` flag — the reading and the threshold are both needed to decide it, and the call site had them already. **Nothing was added to `DiskPressureState` or `disk-pressure.yml`**: the gap is derived from the reading and the threshold, so the file live on the host loads unchanged (covered by a test). Escalation by band and the forecast hand-off are untouched.
  - **Both trackers are now keyed on `MachineId`, not the machine's name.** `lan-servers.yml` really does hold two machines both called "Printer": name-keyed, they shared one tracker slot and swallowed each other's transitions, and `RemoteDiskForecastTracker` fitted their samples through each other's — while a **rename** silently reset a disk's escalation state and discarded its whole trend. `RemoteDiskForecastTracker.observe` now takes `(MachineId, RemoteDiskUsage, Instant, int)`, keying on the machine id while still *naming* the machine from the reading. *(Both trackers are port-backed now — the forecast's key travels into `disk-fill-trend.yml` via `ForPersistingDiskFillTrends`, and which stored **disk-fill trend** belongs to which filesystem is `DiskFillTrend.isFor`, a domain rule, not a map inside the tracker. See the forecast rebuild above.)* (Consistent with §6.22: a name is a label, only the id is identity.)
- **Disk pressure on the fleet's machine cards** ✅ — disk pressure was a thing Vaier knew and only ever *emailed* about: `RemoteDiskWatcher` swept every SSH-accessible machine with `df -P` every five minutes, judged every filesystem, and threw the readings away. The one number an operator most wants without opening anything was therefore absent from the fleet listing, and could not be added without a fleet-wide `df` at paint time — N SSH round trips waking every sleeping machine to answer a question nobody asked. **The fix costs no new SSH and no new poll: the sweep now keeps what it already read.**
  - **`domain.MachineDiskStanding`** — the **machine disk standing**: the worst thing true about one machine's watched filesystems right now `(machineId, worstMountPoint, worstUsedPercent, worstThresholdPercent, breachingFilesystems, watchedFilesystems)`, with `level()` over `domain.DiskStandingLevel` — `CLEAR`, `CLOSING` (within one `DiskPressureBand.STEP` of the threshold) or `BREACHING`. **Which filesystem is "the worst" is a domain decision** and lives on the factory: it is the one with the least headroom *against the threshold it is judged by*, never the fullest — a `/boot` at 92% under a 99% threshold of its own is fine while a `/volume1` at 86% under 85% is not. Every verdict comes from the existing `RemoteDiskUsage.judge`, the same single method the alert email asks, so a card and a mail can never disagree about a disk. Ties break on used percent and then, deterministically, on mount point: a tie that flipped from sweep to sweep would push an SSE event and repaint the fleet for nothing.
  - **Absence is not health.** `MachineDiskStanding.of` returns an `Optional`, empty when nothing was read or every filesystem is **muted**, and `DiskStandingLevel` deliberately has **no `UNKNOWN`** — the failure this fleet has already been bitten by is missing state reading as fine (§6.9's 89% silence). A machine the sweep has not reached is simply absent from the read model, and the client's contract is to draw nothing for it.
  - **Retained, not persisted** — new driven port `ForHoldingMachineDiskStandings` (`record` / `forget` / `getAll` / `retainOnly`) with `InMemoryMachineDiskStandingCache`, the sibling of `InMemorySshServerPresenceCache` and keyed on `MachineId`, never a name. A standing is what Vaier *last saw*, not a record of anything, so a restart means nothing is known until the next sweep — which is exactly what a card must then draw. `RemoteDiskWatcher` records after its existing per-filesystem alert loop, `forget`s a machine whose filesystems are now all muted (a card must stop showing a verdict nobody is making), and `retainOnly`s the fleet on each sweep so a deleted machine leaves no reading behind.
  - **Published only on a change**, as `disk-standing-changed` on the **existing** `vpn-peers` SSE stream the fleet page already holds open — no second connection, no timer, and no five-minute drumbeat for disks that are sitting still. `MachineDiskStanding.differsFrom` is a whole-value comparison on purpose: everything a standing carries is something a card draws or says on hover, so anything that moved is something an open Explorer is currently getting wrong. A machine never read before always speaks.
  - **Every reading refreshes the standing, not only the sweep's** ✅ — the mute an operator set on the NAS's `/` left the card naming `/` as that machine's worst filesystem until the next five-minute sweep happened to run: up to five minutes of a card asserting a verdict about a disk nobody was judging any more, and the same for un-muting one or moving its threshold. The mute logic was never wrong; the *fresh* reading was being thrown away. `GET /machines/{machine}/disk` already runs a live `df` and already judges every filesystem through the same `RemoteDiskUsage.judge` — and the disk pane re-reads it the instant a watch is written — so the fix retains that reading too. `MachineDiskStanding.retain(...)` is a domain method receiving the `ForHoldingMachineDiskStandings` and `ForPublishingEvents` ports (the domain owns the port call; a side-effect is not a use case), and both the sweep and the live read hand their reading to it, so "commit it, forget a machine with nothing left to judge, wake the fleet only if it moved" is decided in exactly one place. **No new connection and no fleet sweep** — drawing the fleet still wakes no machine — and the mark now self-heals the moment anyone looks at a machine. A `df` that **failed** never reaches `retain` (both callers bail first): an unreachable machine tells you nothing new about its disks, so the last known standing stands rather than being erased into a green or absent one, which would be §6.9's absence-read-as-health all over again.
  - **Application + REST** — `GetMachineDiskStandingsUseCase` on the existing `MachineService` (one service per domain concept) behind **`GET /machines/disk-standings`**: the whole fleet in **one** memory-backed request, deliberately not N per-machine ones for a single row of glyphs, and deliberately not a fleet-wide `df`. A literal segment under `/machines`, so it sits behind the same admin auth chain as the rest; nothing anonymous. `DiskStandingResponse` carries the domain's `level` as a **name** rather than letting the browser recompute it from the percentage and the threshold — the same reason `aboveThreshold` travels on `FilesystemResponse`.
  - **Explorer** — the **machine marks** gain a **disk mark**: the existing disk glyph tinted green/amber/red by the server's level, the percentage shown **only when it is not `CLEAR`** (a number on every card is a row of digits nobody reads; on the two that are filling it is the thing you were looking for), and a title naming the worst mount point, its percentage and the threshold in words ("well under" / "closing on" / "over"), plus the breaching count when more than one filesystem is over. Read once on shell load (`render()` never fetches) and re-read on the SSE event. Like the update mark it **stands down in the past**: how full a disk is is a fact about now.
- **New pending access-request alert** ✅ — when `UserService.verify()` sees a Google identity for the first time, it auto-creates a `PENDING` `AccessEntry` and, only in that new-pending branch, notifies admins via the `ForNotifyingAdmins` driven port (`notifyNewPendingIdentity(email)`), implemented by `NotificationService`. The email content is rendered by the `domain.PendingIdentity` value object (subject `[Vaier] New access request awaiting approval`; body names the email and links to `vaier.<domain>/admin.html#users`), mirroring `PeerSnapshot`. Recipients are the **admin**-role **access entries** (`accessStore.getEntries()` filtered by `AccessEntry::isAdmin`), reusing the same SMTP path as the other alerts, so it stays silent when SMTP is unconfigured or there are no admins. Because `verify()` runs on the Traefik forward-auth hot path for every request to a social-gated service, the send is non-blocking and exception-safe: the notifier method is `@Async` (`@EnableAsync` on `VaierApplication`) and the call site in `UserService` swallows/logs any failure, so a misbehaving notifier can never add latency to or throw into the access decision. It does not fire for existing entries, repeat sign-ins by the same pending user, or allowed decisions. The cross-service cycle (NotificationService reads admins via the access store, UserService notifies via `ForNotifyingAdmins`) is broken with `@Lazy` on the UserService dependency.

**Known gotcha:** Gmail requires an **App Password** (not the account password) when 2FA is on. The pre-save verification catches this cleanly — save is rejected with the Gmail `534 5.7.9 Application-specific password required` message.

**Addendum 2026-09-11 — a machine with no default route reads as fully healthy** ✅ (closes [#357](https://github.com/getvaier/vaier/issues/357)). A LAN server lost its `default via` route and every probe Vaier makes still passed: it reaches a LAN server *through that server's relay peer* (same-subnet traffic, no default route needed), the disk read came back over that same path, and the backup server is on the same LAN so backups kept succeeding. The machine could not pull an image or reach anything on the internet. Egress was a genuine blind spot rather than an oversight — **everything else Vaier asks a machine is answerable from inside its own LAN** — and it is the blind spot most likely to bite, because a machine with no egress keeps serving local traffic and looks entirely normal. It also breaks Vaier's own **update available** flow, which pulls an image.

- **The fact was already on the wire.** `MachineNetworks.IP_COMMAND` (`ip -o -4 addr show; ip -o -4 route show default`) has always carried the routing table's `default` line, read on the five-minute rounds for the **route LAN** nudge — and `defaultRouteInterface` was already null for a machine with no default route. The distinction was parsed and thrown away. No new SSH command, port, cache or sweep: the whole change reads a field that was already there.
- **Domain** — `MachineNetworks.defaultRoute()` returns the three-valued **default-route standing** (`domain.DefaultRouteStanding`: `UNKNOWN` / `PRESENT` / `ABSENT`), mirroring `SshServerPresence`. `UNKNOWN` when the reading `isUnknown()`, `ABSENT` when the machine answered and named no route, `PRESENT` otherwise — *any* interface, so a full-tunnel peer routing by `wg0` is `PRESENT` even though `lanCandidate()` says nothing about it. **Unknown is not "no"**, exactly as §6.9's disk work learned.
- **Nudge** — `MachineNudge.Kind.NO_DEFAULT_ROUTE`, composed **first** by `MachineNudges.forMachine` off the networks already in `MachineSignals`, so the nudges endpoint gathers nothing new. It is the only kind that is trouble rather than an invitation, and the only one with **no button**: Vaier can see this and cannot fix it, so the card ends in a sentence instead of an action (`nudgeCard` draws no button for a nudge whose `NUDGE_ACTION` entry carries no label). Evidence is the interfaces and addresses Vaier read; `UNKNOWN` draws nothing at all.
- **Alert** — `NotifyAdminsOfMissingDefaultRouteUseCase` on the existing `NotificationService`, words from `MachineNetworks.missingDefaultRouteSubject/defaultRouteRestoredSubject/defaultRouteBody` (the `RemoteDiskUsage.pressureSubject()` precedent), sent through `ForSendingAdminNotification.sendToAdmins`. One body serves both mails and follows the reading it is given, so the all-clear never repeats the warning it cancels. `domain.MissingDefaultRouteTracker` owns the rule — **the first observed `ABSENT` speaks** (a machine that lost its route before Vaier started is the ordinary case, not the exception), then silence while it stays broken, one `RECOVERED` on the way back, and `UNKNOWN` moves the latch in neither direction so a failed sweep can never send a false all-clear. The latch is persisted in `vaier/config/missing-default-routes.yml` behind `ForPersistingMissingDefaultRoutes` (`MissingDefaultRouteFileAdapter`) for §6.9's reason: several deploys a day would otherwise re-send the same mail on each one. Called from `RemoteDiskWatcher.detectNetworks` in its own try/catch, so a mail that will not go out can never take the sweep down with it.
- **Deliberately not built** — no active egress probe (a `curl` out from the machine), no attempt to repair the route. In the observed case a DHCP lease *did* supply a router and `systemd-networkd` installed host routes via that gateway while never installing the default route itself, so the configuration was not wrong and a config audit would not have caught it. Reading the live routing table is what finds this class of fault, it costs no traffic, and it asks nothing of a sleeping machine.

**Addendum 2026-09-11 — a machine reboots, comes back without its containers, and reads as fully healthy** ✅ (closes [#356](https://github.com/getvaier/vaier/issues/356)). A machine rebooted; the containers with a restart policy came back, one with `restart: no` did not, and Vaier called the machine green on every axis it watched — reachable, disk fine, backups green — because all of that was true. The service was gone and a human found it. Vaier already had the evidence: the 30-second container scrape had watched that container run and could see it stopped. Nothing acted on the difference.

- **The honest trigger is "was up, now isn't."** Not "a container is stopped" — a great many are, on purpose and forever. Vaier remembers only containers it has actually seen running, so the rest raise nothing, ever. **Exit code is not a filter either**: the container in question exited `255` with `OOMKilled: false` and an empty `Error` after a clean shutdown, so a rule keyed on non-zero exit would have called that a crash and been wrong.
- **Domain** — `domain.ContainerStanding` (`RUNNING` / `GONE`) is the **container standing**, per `(MachineId, container name)` in `domain.MachineContainerStanding`, which also owns the mail's and the card's words. `domain.ContainerObservation` carries the one distinction the scrape already knew and used to throw away: whether the machine *answered*. `domain.ContainerStandingTracker` owns every rule — a machine that did not answer moves nothing; a container never seen running is never news; **two** consecutive misses, not one, because one is a Vaier-driven **Update** or a restart in progress; a `GONE` container still present keeps its standing; a `GONE` container that has *vanished* from the scrape is forgotten after its one alert, so a deliberate teardown costs one mail and leaves no lingering card.
- **Persistence** — `ForPersistingContainerStandings` / `ContainerStandingFileAdapter`, `vaier/config/container-standings.yml`, tolerant on load. On disk for §6.9's reason, sharper here: the scrape lives in memory, and after a deploy a container that stopped during it would look like one Vaier had never seen run — the one case this is deliberately silent about.
- **Driving** — `JudgeContainerStandingsUseCase` on `ContainerService` turns the three caches the scheduler has just refreshed (Vaier's own stack, every server peer, every LAN server) into observations and hands them to the tracker; `StateRefreshScheduler` switches on the verdicts and calls `NotifyAdminsOfContainerGoneUseCase` on the existing `NotificationService`, each mail in its own try/catch. Vaier's own host is covered too — the masquerade sidecar dying after a deploy is this exact bug on the Vaier server — and an *empty* local reading counts as no answer, since Vaier itself runs in a container there.
- **Reboot context** — `domain.MachineBoot` reads `/proc/uptime` **in front of** the sweep's own `df`, exactly as the Docker probe does (`MachineBoot.readAheadOf(DockerCommandAccess.probeAheadOf(RemoteDiskUsage.DF_COMMAND))`), so the fleet still costs one SSH sign-in per machine per sweep: three questions, one connection. When the machine booted after a container was last seen running, the mail and the card say so; when no boot instant has been read, the sentence is simply absent.
- **Surface** — `MachineNudge.Kind.CONTAINER_GONE`, one card per gone container, composed by `MachineNudges.forMachine` beside `NO_DEFAULT_ROUTE` (trouble first) off `GetContainerStandingsUseCase`. No button: Vaier reads containers over the Docker API and has no endpoint that starts one. In the Explorer a gone container reads **gone** rather than `exited`, fed by one fleet-wide `GET /machines/container-standings` and re-read on a `container-standing-changed` push on the `vpn-peers` stream the page already holds open — no poll, and nothing at all when nothing moved.
- **The scrape had to see stopped containers at all.** `DockerServerAdapter.getServicesWithExposedPorts` listed with `withShowAll(true)` and then kept only containers whose `Container.getPorts()` was non-empty — and Docker reports **no** port mappings for a container that is not running, so every exited container dropped out of every machine's scrape. Found on the deployed build: a probe went `GONE` correctly, then vanished, was read as *removed*, forgotten, and `docker start` produced no all-clear. The adapter now reads `HostConfig.PortBindings` for non-running containers (what they were actually published on, which survives a stop — deliberately **not** `Config.ExposedPorts`, the image's own `EXPOSE` list, which would invent ports nobody published). Running containers are untouched: their mappings already come down with the listing, and a fleet-wide scrape must not grow one inspect per running container. All three scrape sources share this method, so peers, LAN servers and the Vaier host behave alike. Consumers are guarded by the domain's own `DockerService.isRunning()`: a stopped container is not a **+ Publish** candidate (`PublishingService`, `InMemoryContainerSnapshotStore`) and was already excluded from the image-update sweep and from a route's OK state. The visible effect beyond #356 is that a stopped container with published ports is now listed at all, reading **DOWN**.
- **It delivers the alert half of [#317](https://github.com/getvaier/vaier/issues/317)** ("Docker health over SSH") without an SSH channel or a new port on any machine: the Docker-API scrape Vaier already makes over the tunnel is enough to say that a container which was running has stopped. What #317 still holds is closing the open `2375`.

**Host-monitoring follow-ups (backlog):**
- Monitor host CPU and memory pressure alongside disk, with their own thresholds and alerts.
- ~~Per-mount monitoring (not just the host root) — e.g. a separate data volume — each with its own threshold.~~ **Shipped** in [#325](https://github.com/getvaier/vaier/issues/325): every real **filesystem** is read, watched and alerted on, each with its own **disk watch** (muted or watched, at its own threshold or the fleet-wide fallback). See the entry above.
- ~~An in-UI disk widget so the operator sees current disk usage without waiting for an alert email~~ — the **level** half shipped in [#323](https://github.com/getvaier/vaier/issues/323) slice C and grew into the full picture in [#325](https://github.com/getvaier/vaier/issues/325): a machine's **disk** entry in the **Explorer** reads `df` on demand (`GET /machines/{machine}/disk`) and lists every filesystem with its size, free space and usage against its resolved threshold, with the domain's own `breaches` verdict — and is where a filesystem is muted or given its own threshold (`PUT /machines/{machine}/disk/watch`). The level half now also rides on the fleet listing without anyone opening a machine at all: every machine card carries its **machine disk standing** (`GET /machines/disk-standings`, read out of the sweep's retained readings — see the entry above). Still open: surfacing the **disk-fill forecast** (**runway** + fill rate) beside it. **This is now unblocked and simply was not built** — the objection used to be that the forecast needs a *history* of samples and that history was private state inside the scheduled `RemoteDiskWatcher`, so a single on-demand reading could not produce it. The history is persisted now (`disk-fill-trend.yml` behind `ForPersistingDiskFillTrends`, ~168 samples per filesystem), so a read of it is a read port and an endpoint away. The forecast rebuild deliberately stopped at the alert path and added no UI.
- Make the **forecast horizon** configurable (it is a fixed 7-day constant today, with a 10-day all-clear margin), and/or draw the retained **disk-fill trend** as a sparkline — the samples it would need already exist on disk.

---

### 6.10 First-Run Setup Wizard — removed ✅ (closes [#48](https://github.com/getvaier/vaier/issues/48), [#145](https://github.com/getvaier/vaier/issues/145), [#161](https://github.com/getvaier/vaier/issues/161))

The in-app wizard at `/setup.html` was deprecated on 2026-04-23 (a tester walking through the README found it unreachable when the four required env vars were populated, and not documented when they weren't). It was deleted on 2026-05-04 along with `SetupRedirectFilter`, `SetupRestController`, the three setup use case interfaces, and `SetupService`. Removal also retires the unauthenticated `/api/setup/*` surface that #145 flagged as a race-condition admin-claim window. First-run is now exclusively the env-var path documented in `README.md`.

### 6.11 Zero-touch first-run DNS boot ✅ (implemented 2026-04-23, closes [#163](https://github.com/getvaier/vaier/issues/163), [#164](https://github.com/getvaier/vaier/issues/164))

- **Boot verifies the wildcard record instead of creating anything** (#331, §6.4). Vaier still resolves the server's own public address in order — `VAIER_PUBLIC_HOST` → `VAIER_PUBLIC_IP` → EC2 IMDSv2 `public-hostname` → DNS lookup of `vaier.<domain>` — but now uses it to *judge* the operator's `*.<domain>` record rather than to write records. An unresolvable public address is not fatal: the report settles `UNCONFIRMED` and the stack stays up. *(Superseded: this step used to auto-create `vaier.<domain>` and the two auth-stack CNAMEs.)*
- **First-boot auth is now zero-touch via oauth2-proxy** — `oauth2-proxy-init` renders oauth2-proxy's config into `./oauth2/config` before oauth2-proxy starts, and the access store seeds the **configured administrator** (`VAIER_ADMIN_EMAIL`), so the operator signs in with Google on first boot. _(Superseded: the former `authelia-init`/`redis-init` one-shots that seeded a placeholder Authelia config were removed when Authelia and Redis left the stack.)_
- **Fresh-install ACME race guard.** Originally: Vaier created the `vaier`/`oauth2`/`dex` records at boot while Traefik, started by the same `docker compose up`, raced ahead to Let's Encrypt — LE's validator got NXDOMAIN, Traefik's retry burst tripped LE's "5 failed authorizations per hostname per hour" limit, and the site sat an hour on Traefik's self-signed default cert (`ERR_CERT_AUTHORITY_INVALID`). The fix lives in **Traefik's own entrypoint**: before it `exec`s traefik — and thus before any ACME — it holds until all three infra hostnames resolve on a **public** resolver (`1.1.1.1`/`8.8.8.8`, what LE queries, not the container's split-horizon/VPC view). **Under #331 (§6.4) the race is gone at the source**: the operator's `*.<domain>` record exists before first boot, so all three names resolve on the first attempt and the wait returns immediately. The wait **stays in `docker-compose.yml` unchanged** and is now a safety net for the install that forgot the record: it **fails open** after ~5 min so a missing wildcard never leaves the box permanently proxy-less, and Traefik's own ACME retry then takes over. It deliberately is **not** a separate gate service Traefik `depends_on` (`vaier` `depends_on` `traefik: service_started`, so a completion-gated sidecar would deadlock). Needs `VAIER_DOMAIN` in Traefik's environment to build the hostnames it waits on. A `DockerComposeStructureTest` case pins the wait (all three hostnames, a public resolver, ordered before `exec traefik`, fail-open on timeout).
- **Internal-resolution complement (Traefik network aliases).** The public-DNS wait fixes Traefik's own ACME, but service-to-service calls have the sibling problem: oauth2-proxy (and Vaier) run OIDC discovery against `https://dex.<domain>` by its *public* name (the issuer must match, so an internal `http://dex:5556` shortcut is not an option), resolving through the **container** resolver. On a fresh install a too-early lookup gets NXDOMAIN and poisons that resolver's **negative** cache (an SOA negative TTL is commonly ~15 min), crash-looping oauth2-proxy on `no such host` long after the record exists. (#331 makes the too-early lookup much less likely — the wildcard record predates first boot — but the alias is kept: it costs nothing and still covers an install whose record is wrong or late.) Fix: alias `vaier`/`oauth2`/`dex.<domain>` onto the **Traefik** container on `vaier-network`, so Docker's embedded DNS answers them from its own registry (straight to Traefik over the bridge) before ever forwarding externally — no public DNS, no negative cache. Traefik terminates TLS with the real cert and routes on to the backend. Pinned by a `DockerComposeStructureTest` case.

### 6.12 Docker socket hardening ✅ (closes [#147](https://github.com/getvaier/vaier/issues/147))

The Docker socket is no longer bind-mounted into Vaier or Traefik. A pinned `tecnativa/docker-socket-proxy:v0.4.2` sidecar holds the real socket and exposes a restricted HTTP API on `tcp://docker-proxy:2375` over `vaier-network`. Tecnativa's stock allowlist (`CONTAINERS`, `EVENTS`, `EXEC`, `IMAGES`, `PING`, `POST`, `ALLOW_RESTARTS`) covers GET access cleanly, but `CONTAINERS=1 + POST=1` would also permit `/containers/create` and `/containers/{id}/start` — leaving the privesc chain open. To close it, the `haproxy_template` Compose `configs:` entry overrides the upstream haproxy template with explicit `http-request deny` rules for `/containers/create`, `/containers/{id}/start`, `/images/create`, `/images/load`, and `/images/*/push` *before* the broad `CONTAINERS` allow. The template is embedded inline in `docker-compose.yml` so the stack ships as a single file — no separate config download. A smoke test confirms each denied path returns `HTTP/1.0 403` while `/containers/json`, `/_ping`, `/events`, `/images/{id}/json`, and `/containers/{id}/restart` still return 200/204. Net result: an attacker with RCE in Vaier cannot launch a `--privileged` container, pull a fresh malicious image, or alter swarm/network/volume state.

The Vaier container's PID 1 (the Java process) runs as UID 1000. The `Dockerfile` ENTRYPOINT is `setpriv --reuid=1000 --regid=1000 --init-groups --inh-caps=+net_admin --ambient-caps=+net_admin -- java …` — `setpriv` (from `util-linux`) starts as root so it can manage capabilities, raises `CAP_NET_ADMIN` to ambient (so it transfers to `ip` invoked by `ProcessBuilder`), then drops to UID 1000 before exec'ing Java. A one-shot `vaier-init` container (busybox) `chown`s the bind-mounted config dirs (`vaier`, `traefik`, `wireguard`, `icons`) to `1000:1000` on every start so the non-root process can read and write its own state. (The `authelia`/`redis` services and their PUID/PGID re-root workaround are gone with Authelia's removal from the stack.) `cap_add: NET_ADMIN` is retained at the container level so `VpnNetworkSetupAdapter` and `LanRouteAdapter` can install routes inside the Vaier container — file caps alone don't transfer reliably under Docker overlayfs, hence the ambient-cap path. (#151's keep-as-hedge rationale therefore stands.)

### 6.13 Argv-style sinks for user-supplied lanCidr ✅ (closes [#195](https://github.com/getvaier/vaier/issues/195))

The three live, authenticated `wg`/`ip` sinks that consumed user-supplied `lanCidr` (`WireGuardVpnAdapter.setPeerAllowedIps`, `WireGuardVpnAdapter.reconcileKernelRoutes`'s `ip route del`, `VpnService.addPeerToServer`) no longer use `sh -c` + `String.format`. They invoke the underlying binaries directly via argv, so shell metacharacters in the input cannot escape `allowed-ips` or `dev` arguments. The `2>/dev/null || true` shell idiom on `ip route del` is replaced by relying on `executeInContainer`'s existing exit-code-discarding behaviour.

A new strict validator `domain.Cidr.validateLanCidr(String)` is applied at the boundary in `VpnService.updateLanCidr` and `VpnService.createPeer` before any state change. It accepts only `A.B.C.D/N` with octet 0-255 and prefix 0-32, rejecting hostnames, IPv6, leading zeros, and any input containing whitespace, `;`, `|`, backticks, `$()`, `&`, quotes or newlines. This is intentionally stricter than `Cidr.parse()`, which uses `InetAddress.getByName()` and silently accepts hostnames — that method stays for trusted internal CIDR strings.

One residual `sh -c "echo '$psk' > $pskFile"` remains in `VpnService.addPeerToServer`. The PSK is generated by `wg genpsk` (base64, no shell metacharacters); the file path is Java-controlled. User-supplied `lanCidr` does not flow through it. Documented in the source comment as a known sh-c invocation that's not user-input-reachable.

### 6.14 Editions and Enterprise licensing — removed ✅

Vaier briefly shipped an open-core **Community/Enterprise** split (the split introduced by [#338](https://github.com/getvaier/vaier/issues/338)): an offline, Ed25519-signed **licence token** (`domain.License`, minted by `LicenseMintingTool`, installed via `VAIER_LICENSE`, verified by `Ed25519LicenseVerifierAdapter`) resolved the running **edition** at runtime, and an `@RequiresEnterprise`/`EnterpriseLicenseInterceptor` gate (`402 Payment Required`) restricted **Fleet backup** (§6.19, including the **survival kit**) to a paid licence.

**Removed entirely on 2026-07-29** — "Remove the enterprise gating, from now on everything is community." There is now one edition and no licence concept: `Edition`, `License`, `LicenseService`, `GetEditionUseCase`, `GetLicenseStatusUseCase`, `RequiresEnterprise`, `EnterpriseLicenseInterceptor`, `LicenseRestController` (so `GET /license` no longer exists), `ForVerifyingLicense`, `ForReadingLicenseToken`, `Ed25519LicenseVerifierAdapter`, `EnvLicenseTokenAdapter` and `LicenseMintingTool` are all deleted, and `VAIER_LICENSE` is gone from `docker-compose.yml`. Every feature described in this document — including Fleet backup and the survival kit — is unconditionally available. Any other mention of `@RequiresEnterprise`, "Enterprise-gated", "Community", or `GET /license` elsewhere in this document is historical and no longer reflects the code.

### 6.15 LAN scanner 🟡 (first slice [#246](https://github.com/getvaier/vaier/issues/246))

Originally the first Enterprise-only feature under the since-removed Community/Enterprise split (§6.14); moved out of the gate by [#338](https://github.com/getvaier/vaier/issues/338) — the feature most likely to convert a newcomer should not be the one they can't have, and nobody pays to discover three machines. The split itself was later removed entirely (§6.14), so the scanner, like everything else, is simply available. An on-demand **LAN scanner** sweeps every **relay peer's** `lanCidr` and the **server LAN CIDR**, surfacing responsive hosts not yet registered ("Discovered LAN machines"). The probe is a narrow TCP-connect sweep over common service ports (each connect bounded by `timeout 1`), run from the Vaier WireGuard container (which already routes to every relay LAN over the tunnel) via `ForScanningLan` / `LanScanAdapter`; `LanScannerService` orchestrates the relay/server CIDRs **concurrently**, maps each hit to a `domain.DiscoveredLanMachine` with a **guessed role** (Docker host / web UI / SSH / printer / unknown), and drops any host whose address is **already claimed by a registered machine** — both LAN servers *and* VPN peers (relays/Ubuntu servers carry a LAN address). The "already registered" check is a domain decision on `DiscoveredLanMachine`, fed the union of claimed addresses by the service.

Because a sweep is slow (~20s per /24), it runs **on demand and asynchronously**: `POST /lan-scan` (`ScanLanUseCase`) kicks off a background scan and returns `202 Accepted`; `GET /lan-scan` (`GetDiscoveredLanMachinesUseCase`) returns the latest snapshot — `status` (`IDLE`/`SCANNING`), the discovered machines, and `lastScanCompleted`. On completion the service publishes a `lan-scan-updated` SSE event on the `vpn-peers` topic. Neither endpoint is gated.

The scanner is surfaced through the Explorer's **Add-a-machine** flow (see **§6.15.1**). The operator picks a machine kind, chooses **A LAN server**, then **picks which LAN to scan** and adopts a discovered host. The flow is **cached-first and SSE-driven, never polling** ([[feedback_frontend_never_polls]]), and each sweep is **targeted to the picked LAN**. This replaced an earlier standalone "Discovered LAN machines" page section and, superseding an interim in-modal "Scan LAN for machines" picker that merely filled the LAN-address field, now registers a discovered host in one **adopt** action rather than pre-filling a form.

Delivered in this slice: domain + role-guessing, scanning port + relay-exec adapter (with a unit-tested output parser), async stateful orchestration service with already-registered filtering and SSE completion event, async POST-trigger / GET-snapshot REST endpoints, and the discovery UI (the original in-modal pick-to-fill, since superseded by the Explorer **adopt** flow in §6.15.1). **Backlog for #246:** per-host ignore list (the discovered-services ignore pattern), scheduled background scans on a slow cadence, MAC-vendor / mDNS hostname enrichment, per-relay enable/disable, and CIDRs larger than `/24` (the current sweep covers the `.1–.254` range of the network's first three octets).

#### 6.15.1 The "Add a machine" use case ✅ (complete — slices 1–7)

The consolidated **"Add a machine"** initiative turns a **Discovered LAN machine** into a registered **LAN server** in one call, so the operator no longer re-types fields the scan already knows. **Slice 1 (backend) ✅ in this change:** every registerable field is derived in the **domain** on `DiscoveredLanMachine.adoptionProfile()` — suggested name (the resolved hostname, else the IP), LAN address (the host's IP), `runsDocker` + `dockerPort` (the open `2375`/`2376`, via `LanMachineRole.dockerPort`), and device category (`DeviceCategory.detect(hostname, null, guessedRole)`, pinned as the override on adoption) — with the override-vs-suggested name choice a domain rule (`AdoptionProfile.chosenName`). `AdoptDiscoveredMachineUseCase.adopt(ipAddress, nameOverride)` is implemented on the existing `LanServerService`: it reads the candidate through the driven port `ForGettingDiscoveredLanMachines`, registers it via the shared registration path, then drops it from the scan snapshot through the driven port `ForForgettingDiscoveredLanMachines` (both implemented by `LanScannerService`, keeping the cross-domain read/side-effect off any `*UseCase`). Exposed as `POST /lan-scan/{id}/adopt` (`{id}` = the discovered host's LAN IP; body `{nameOverride}`), returning the created LAN server.

**Slice 2 (backend) ✅ in this change — attach an SSH credential during adoption, proven to work before it is stored:** because the operator tests the credential while the machine is still only a candidate, the verify primitive is host-address-based (not machine-name-based) and persists nothing. A driven port `ForVerifyingSshCredentials.probe(SshTarget)` opens one throwaway connection through the single shared `SshConnector` (adapter `MinaSshCredentialVerifier`) and returns the presented fingerprint, throwing the distinct domain SSH exceptions on failure; the domain value object `SshCredentialVerification.probe(target, port)` maps that outcome — success → reachable+authenticated, `SshAuthException` → reachable-but-not-authenticated, `SshConnectException` → not reachable — so the "did it authenticate?" decision lives in the domain and nothing is pinned. `VerifySshCredentialUseCase.verify(address, port, SshCredentialDraft)` (on `TerminalService`, the credential-vault domain) is exposed as `POST /lan-scan/{id}/ssh-credential/test` (`{id}` = the candidate's LAN IP; body `{username, authMethod, secret, passphrase}`) returning the **redacted** `{reachable, authenticated, fingerprint}` — never the secret. Adoption gains an overload `adopt(ipAddress, nameOverride, SshCredentialDraft)` (also on `LanServerService`) that keeps registration and the credential separable: the machine is always registered and the candidate forgotten, then the credential is re-verified server-side against the machine's LAN address and stored via the credential vault **only when it authenticates** (keyed to the new machine's name, unmanaged) — a rejected credential never rolls back the registration. The `POST /lan-scan/{id}/adopt` body gains an optional `credential` block and the response reports `{credentialProvided, credentialVerified, credentialStored, hostKeyFingerprint}`. New domain type `SshCredentialDraft` (the pre-registration credential shape without a machine name) owns both derivations: the no-pin test target and the vault credential keyed to the machine.

**Slice 3 (frontend) ✅ in this change — the fork-first, pick-a-LAN-then-scan, one-decision Add-a-machine flow in the Explorer:** the Explorer's `addMachine()` now opens **`addMachineFork`**, one modal with internal screens (`explorer-shell.js`). It opens on the one fork Vaier can't infer — **A peer** (routes to the existing peer-create form unchanged; its own intent reframe is a later slice) or **A LAN server**. The LAN-server branch is now **pick-a-LAN-then-scan** and stays **cached-first and push-driven, never polling** ([[feedback_frontend_never_polls]]): the operator **always picks which LAN to scan first**, from the list of **scannable LANs** — each relay peer that routes a LAN, plus the Vaier-server LAN — read from `GET /lan-scan/lans` (`ListScannableLansUseCase`, each a `{anchor, name, cidr}` said to the operator as **"via <name>"** with its CIDR). Picking one runs a **targeted** scan of just that CIDR via `POST /lan-scan?anchor=<key>` (`ScanLanAnchorUseCase`; an unknown anchor → `404 NotFoundException`), so the page stays small and the sweep is fast; the still-existing anchor-less `POST /lan-scan` remains the fleet-wide sweep. Candidates render instantly from the last `GET /lan-scan` snapshot in `S.lanScan` (readable scoped to one anchor), and a finished scan still repaints the list over the `lan-scan-updated` SSE topic the shell already holds — `loadLanScan()` calls a `_lanScanModalRefresh` hook the discover screen arms only while it is on screen. The scannable-LAN enumeration and the anchor→CIDR resolution are **domain decisions** shared by scanner and picker: `LanAnchor.scannable(...)`, `LanAnchor.byKey(...)`, and `LanAnchor.anchorKey()` (the stable routing key = the relay peer id, or `"Vaier server"`). The discover list is **scoped to the one picked LAN** (the old cross-LAN "All"/site-chip view is gone), with both the picked LAN and each candidate's site shown as **"via <name>"** (the resolved anchor's display name; "anchor"/"relay" stay code words, never operator-facing). Picking a candidate opens the **adopt sheet**: a single editable **Name** over a read-only *Detected by Vaier* readout (kind, LAN address, reached via, any open Docker port, and the cross-site route resolved from the anchor machine's `lanCidr`), plus — **only when the discovered host actually listens on port 22** (the domain predicate `DiscoveredLanMachine.sshAvailable()`, surfaced as `sshAvailable` on the discovered-machine snapshot DTO) — an optional **Add SSH access** disclosure with a live **Test** button (`POST /lan-scan/{id}/ssh-credential/test`, green check on `authenticated:true`); a host that doesn't speak SSH is adopted without a credential. **Add machine** calls `POST /lan-scan/{id}/adopt` with `{nameOverride, credential?}`, then refreshes the fleet and navigates to the new machine. The old manual LAN-server fields in the add form are **retired** in favour of adopt; the form is now peer-only (`machineForm` → `peerForm`). A de-emphasized **Add by address instead** fallback (wired to `POST /lan-servers`) survives for empty/failed scans. The fleet's *Discovered on the LAN* section's per-host action now opens the same adopt sheet (`addMachineFork('adopt', d)`) rather than the old direct-register. Verified manually in the browser (no JS test harness in this repo).

**Slice 4 (frontend + web) ✅ in this change — the peer branch's intent-first reframe:** the fork's **A peer** choice no longer opens a `MachineType` dropdown. The legacy standalone `peerForm` dialog and the `createPeer` JS helper are gone, folded into the same `addMachineFork` modal and its Back-stack as four in-modal screens (`explorer-shell.js`): (1) **What is this?** — *A server* vs *A personal device*; (2) an explicit, always-shown OS second step — a server asks *Which OS?* [Ubuntu][Windows], a personal device asks *Which device?* [Phone / Mac / Linux][Windows PC]; (3) **Name** (the one field Vaier can't generate, over a read-only "Vaier will generate" tunnel-IP/keys readout); (4) **Handoff**. The operator states intent + whether it runs Windows, never a raw routing type. The intent → `MachineType` decision is a **domain** decision on the new enum `net.vaier.domain.MachineIntent` (`SERVER`/`PERSONAL_DEVICE`, `toMachineType(boolean windows)`: SERVER+Windows→`WINDOWS_SERVER`, SERVER→`UBUNTU_SERVER`, PERSONAL_DEVICE+Windows→`WINDOWS_CLIENT`, PERSONAL_DEVICE→`MOBILE_CLIENT`; Windows is the only platform detail that changes the type within an intent). `POST /vpn/peers` (`CreatePeerRequest`) now accepts `intent` + `windows` **instead of** a raw `peerType`; the controller resolves the type via `MachineIntent.toMachineType(...)` and delegates to the unchanged `CreatePeerUseCase` — peer creation, keys, and `AllowedIPs` defaults are untouched. The legacy `peerType` field is still honoured when no `intent` is given (backward-compatible; `intent` wins when both are present). The **handoff** reuses the existing one-shot **peer config retrieval** (#202) — the inline create response's config/QR/docker-compose/setup-script artefacts, no new endpoint and nothing anonymous or token-gated — in four in-modal variants: **Ubuntu server** → a **no-sudo recipe** (log in as yourself, save the shown `vaier-up.sh` onto the box, `sh vaier-up.sh` writes the config and starts WireGuard in a container — Docker only, no root; the setup-script body is shown once in the authenticated console to transfer by hand, deliberately *not* a curl-with-token line), plus its docker-compose and setup-script downloads; **Windows server** → config file + docker-compose + brief WireGuard-for-Windows import steps; **Phone / Mac / Linux** (`MOBILE_CLIENT`) → QR + config; **Windows PC** (`WINDOWS_CLIENT`) → config file + WireGuard-app import steps. Each handoff shows a live "waiting for first handshake — turns green on its own" dot. **Edge:** the peer flow no longer collects a server's routed LAN (`lanCidr`) up front — `createPeer` is called with an empty `lanCidr`, and a server's LAN is set later from its machine page (existing `PATCH /vpn/peers/{id}/lan-cidr`). Domain-tested (`MachineIntentTest`) and controller-tested (`VpnPeerRestControllerTest`); the UI verified manually in the browser.

**Slice 4b (domain + web + frontend) ✅ in this change — the tokenized `login → curl → run` setup download:** the Ubuntu-server handoff's **primary** recipe is now a copy-and-paste **setup link** — `curl -fsSL 'https://<host>/vpn/peers/<id>/setup?t=<token>' | bash` — so the operator logs into a bare box as themselves (no sudo), pastes one line, and it turns green on its own; the box pulls its own config over HTTPS and starts WireGuard in a container. This supersedes Slice 4's save-the-file-by-hand stance for Ubuntu servers (the manual script download stays as a fallback disclosure). **Security posture (the reason this needs one anonymous route):** a bare box has no oauth2 session, so a single-use **setup token** is the sole authorization, validated **in Vaier** — per-peer, ~15-minute TTL, single-use, and served **HTTPS-only**. Redeeming it also **burns the shared one-shot config-retrieval budget** (#202), so an intercepted-and-spent link leaves the box unable to come up and the operator regenerates — interception is detectable rather than silent. New domain value object `net.vaier.domain.SetupToken` owns the authorization decision (`authorizes(peerId, now)`); the driven port `ForVendingSetupTokens` (`issue`/`consume`, single-use `remove`-then-decide) is implemented by the in-memory store adapter `InMemorySetupTokenStore` (32-byte `SecureRandom` value, base64url). The token is minted alongside the setup script in the create/reissue response (`CreatePeerResponse.setupToken`). A **single, surgical Traefik forward-auth exemption** exposes exactly one path — the `vaier-public` router's rule gains one `PathRegexp(^/vpn/peers/[^/]+/setup$)` alternative (anchored, single id segment, `/setup` only); nothing else is opened, and the authed `vaier`/`vaier-identity` routers are untouched. The endpoint `GET /vpn/peers/{peerId}/setup?t=<token>` consumes the token **first** (a used link is spent even if the config budget then 410s), then applies the one-shot gate, then serves the script as `text/plain` so `curl … | bash` works; a missing/invalid/spent token → `401` and nothing is generated. The token is never logged. Domain-tested (`SetupTokenTest`), adapter-tested (`InMemorySetupTokenStoreTest`), and controller-tested (`VpnPeerRestControllerTest`); the UI verified manually in the browser.

**Slice 5 (backend) ✅ in this change — progressive-adoption nudges, composed at the driving edge:** once a machine is registered, Vaier suggests the next capability to adopt as an evidence-backed, single yes/no **nudge**. Three kinds, each a **domain** decision on a static factory that returns `Optional.empty()` when it doesn't apply: **PUBLISH** (`MachineNudge.publish` — the machine exposes services not yet routed through Vaier), **BACK_UP** (`MachineNudge.backUp` — reachable, Vaier holds an SSH credential, nothing backed up yet), and **DESIGNATE_BACKUP_SERVER** (`MachineNudge.designateBackupServer` — the fleet has no backup server yet *and* the machine is storage-class; the "no server yet" gate is `BackupFleet.needsBackupServer()`, so it stops firing once one exists). The pure-domain `MachineNudges.forMachine(...)` assembler composes whichever apply; the supporting predicates `Machine.isReachable(...)`, `PublishableService.ownerMachineName(...)`, and `DeviceCategory.isStorageClass()` are domain too. Exposed as `GET /machines/{machine}/nudges` (admin-gated, non-whitelisted). **Deliberately edge-composed, not a service:** the controller (a driving adapter) gathers each signal from an existing `*UseCase` — machine, publishable services, stored-credential presence, backup jobs, backup servers, reachability — and hands them to the assembler. No application service reaches across domains to collect nudges, and none implements a driven port to expose them (the pattern the [hex cross-domain-read rule](CLAUDE.md) now forbids). Domain-tested (`MachineNudgeTest`, `MachineNudgesTest`, `BackupFleetTest`) and controller-tested (`MachineRestControllerTest`). **A fourth kind — `BACK_UP_AS_ROOT` — arrived later with [#334](https://github.com/getvaier/vaier/issues/334)** (see §6.19), which also changed the assembler's shape: it now takes the machine's `Optional<BackupJob>` and `Optional<BackupRun>` instead of a pre-computed `alreadyProtected` boolean, because *whether a machine is protected* is one reading of a job and *should it back up as root* is another — the driving edge fetches the job once and judges neither.

**Slice 6 (frontend) ✅ in this change — rendering the nudges on the machine pane:** the Explorer's machine pane (`renderMachine`, `explorer-shell.js`) now reads `GET /machines/{name}/nudges` once per machine (`loadNudges`, cached in `S.nudges`, repainted, **never polled** — [[feedback_frontend_never_polls]]) and renders whatever the domain returns under a **Suggested next steps** section: each nudge a quiet, evidence-backed card (accent left-edge + glyph — an invitation, not the warn treatment) showing the domain's title and evidence with one outline action. This **replaces the hand-rolled `if (!S.backupServer)` designate-backup-server offer** the pane used to render — a business decision that lived in JS — so the frontend no longer decides *whether* a nudge fires; it only routes the *action* (a UI concern): PUBLISH → the machine's `services` entry, BACK_UP → its file browser (tick-and-protect), DESIGNATE_BACKUP_SERVER → the make-the-backup-server form (`NUDGE_ACTION`). One behaviour change falls out: the designate offer is now storage-class-gated (the domain rule) rather than shown on every machine. New CSS component `.ex-nudge` on the existing Explorer tokens. Verified manually in the browser (no JS test harness); confirmed live — NAS shows Publish + Back up, and the designate nudge fires nowhere because the NAS already holds the role.

**LAN-server setup parity ✅ in this change — the same copyable `curl … \| sudo bash` for LAN hosts:** a LAN host being onboarded has no oauth2 session either, so its setup script gets the same treatment a server peer's did, reusing the Slice 4b `SetupToken` machinery (the token id is just the LAN server name). Two endpoints on `LanServerRestController`: a mint `POST /lan-servers/{name}/setup-token` (admin-gated, off the whitelist) returning `{token, expiresInSeconds}` — or **204** when the host has nothing to install (runs no Docker, anchors no LAN), so the UI shows "nothing to install" rather than a link that 404s — and an anonymous, token-gated `GET /lan-servers/{name}/setup?t=<token>` that consumes the single-use token first, then serves the script as `text/plain` (`sudo` needed — it installs Docker). The LAN script bears no WireGuard secret but does reveal LAN topology, so it is **never served plainly anonymous**; the single-use token is the sole gate (there is no #202 budget for LAN servers). A **second surgical Traefik exemption** — one `PathRegexp(^/lan-servers/[^/]+/setup$)` alternative on the `vaier-public` router (note `/setup`, not the still-admin-gated `/setup.sh`). The copyable command is shown in the Explorer's **adopt** handoff (`paintLanHandoff` — the adopt flow no longer navigates away silently) and on the machine-pane Setup-script control (`lanSetupScript`, which previously showed a one-liner that 401'd on a bare host — now fixed). Controller-tested (`LanServerRestControllerTest`); verified live end-to-end through the public edge.

**LAN setup script now firewalls the Docker API ✅ in this change:** the Docker engine API the script exposes is unauthenticated (root-equivalent), so the script no longer merely *prints* "restrict this port yourself" — it locks it. `LanServerSetupScript.firewallBlock` emits, for a relay-anchored Docker host, a **reconciling** rule set — a dedicated `VAIER-DOCKER-API` chain flushed and repopulated each run, so re-running after the host **moves to a different relay/network** replaces the rule cleanly with no stale gateway left behind (Vaier can't auto-detect a physical move, so a moved host needs its LAN address updated and the Setup command re-run) — that ACCEPTs tcp/`dockerPort` only from the **relay gateway** — the LAN IP the host actually sees Vaier's scrape arrive from, because the relay masquerades VPN traffic to its own LAN address (diagnosed the hard way: a host with a `-s 10.13.13.0/24` rule still dropped Vaier, since the source is re-stamped to the gateway) — and DROPs it from everyone else, installed as a `vaier-docker-firewall.service` systemd oneshot so it re-applies on boot. A no-op when iptables is absent, and skipped for a Docker host with no relay (on the Vaier server's own LAN), which keeps the by-hand advice. So a relay-anchored Docker host now comes up **green and locked to the fleet gateway** with no manual iptables. Domain-tested (`LanServerSetupScriptTest`), and the whole generated script (nested heredocs and all) verified to parse under `bash -n`. **Backlog:** the real hardening is an authenticated `docker-socket-proxy` per LAN host (as the Vaier server itself uses) instead of raw 2375; and the same firewall lock for a Docker host anchored to the Vaier server's own LAN (a different, non-gateway source).

**Manual add-by-address parity ✅ in this change — the "Add by address" fallback now probes and attaches credentials, exactly like adopting a scanned host:** the de-emphasized **Add by address instead** path (Slice 3, the fallback that survives empty/failed scans) is no longer a bare form. A new endpoint `POST /lan-servers/probe` (`{address}`) runs a targeted, non-intrusive single-host probe of the one address the operator typed — it inspects only the host they already named, not the discovery sweep across a CIDR — and returns `{reachable, openPorts, sshAvailable, runsDocker, dockerPort, guessedCategory, routedVia}`, the same detected read-offs adopt shows (`LanMachineRole.dockerPort`, `DiscoveredLanMachine.sshAvailable`, `DeviceCategory.detect`, and the resolved **LAN anchor** as `routedVia`). An unroutable or silent address comes back `reachable:false` with empty fields (never a 500), so the UI falls back to the manual fields. `ProbeLanHostUseCase.probeHost` (on `LanScannerService`, which already owns the scan domain) resolves what routes to the address in the domain (`LanAnchor.resolve`), probes that one host through the driven port `ForScanningLan.scanHost`, and the domain value object `LanHostProbe` (`reached` / `notReachable`) carries the outcome — an empty address, no covering anchor, or a no-answer all map to `notReachable`, never an error. Registration gains the adopt treatment too: `POST /lan-servers` now accepts an optional SSH `credential` block (`{username, authMethod, secret, passphrase}`); the overload `RegisterLanServerUseCase.register(..., SshCredentialDraft)` (on `LanServerService`) registers first and independently, then re-verifies the credential server-side against the machine's LAN address through the **shared** `verifyAndStoreCredential` path now factored out of adopt — stored **only when it authenticates**, a rejected or unreachable one never rolling back the registration — and the `RegisterResponse` reports `{credentialProvided, credentialVerified, credentialStored, hostKeyFingerprint}`, never the secret. The credential-free `POST /lan-servers` shape is unchanged (empty `200`), so every existing caller and the plain manual add are untouched. **Frontend (`explorer-shell.js`):** the Add-by-address form now **detects on blur** — Docker port and **device category** prefilled from the probe, plus an optional **Add SSH access** disclosure with a **Test connection** button revealed only when the host answers on port 22 — and **detection never blocks Add**: a host that doesn't answer, or a probe that fails, still adds by hand. So manual add-by-address and adopting a scanned host are now the same experience; the only difference is whether Vaier discovered the host or the operator typed its address. Controller-tested (`LanServerRestControllerTest`), service-tested (`LanServerServiceTest`, `LanScannerServiceTest`), adapter-tested (`LanScanAdapterTest`); verified manually in the browser.

**This closes the "Add a machine" initiative.** What began as ~19 separate onboarding operations across six controllers is now one intent-first flow: the operator forks peer-vs-LAN-server, states intent (never a routing type), gives at most a name, and Vaier discovers/derives the rest, verifies any SSH credential before storing it, hands over a copyable one-liner to bring the box online, and then surfaces evidence-backed nudges for the next capability to adopt.

**Backlog (later, related):** adopting non-LAN-server discoveries; bulk adoption; the same tokenized `curl … \| sudo bash` setup-link parity for the **backup server** setup script (today it stays an admin-gated download run with `sudo bash <path>` — it has no session-less onboarding need yet, but the ergonomics would match); and a null-safety guard in `TraefikReverseProxyAdapter` (it builds a config path from `System.getenv("TRAEFIK_CONFIG_PATH")` without guarding null, so a local run with the env unset writes to a stray `null/remote-apps.yml` — harmless in Docker, but it should refuse rather than concatenate `"null"`).

### 6.16 Uniform API error envelope ✅ (closes [#268](https://github.com/getvaier/vaier/issues/268))

A step toward operator-friendly error feedback under the V2 usability theme: every uncaught exception from any controller is now translated into one consistent JSON shape so the web UI can always show the operator *what went wrong* instead of a bare status code or a leaked stack trace.

- A `@RestControllerAdvice` (`net.vaier.rest.GlobalExceptionHandler`) maps uncaught exceptions to the **API error envelope** — `net.vaier.rest.ApiError(code, message, detail)`, where `code` is a stable machine-readable token, `message` is an operator-safe human-readable explanation, and `detail` is optional/nullable.
- `IllegalArgumentException` — the convention domain validation throughout Vaier already uses to signal bad input — maps to `400` with `code=BAD_REQUEST` and the exception's message surfaced **verbatim**, so operator-readable validation messages reach the UI.
- Any other exception maps to `500` with `code=INTERNAL_ERROR` and a safe generic message; the real exception is logged in full server-side and its details (which may include hostnames, IPs, or credentials) are **not** leaked to the client.
- **Migrated the two remaining bespoke error flows onto the shared envelope ✅ (closes [#275](https://github.com/getvaier/vaier/issues/275)).** `PublishedServiceRestController` no longer catches `IllegalArgumentException` to return a `PublishError{message}` for publish / LAN-publish / delete — those validation failures now propagate to the `GlobalExceptionHandler` and render as the uniform `400` `ApiError`; the `PublishError` record is deleted. `SettingsRestController` keeps its deliberate `Exception`→`400` mapping (an SMTP auth failure is a client error that should be `400`, not the generic `500` a raw provider exception would otherwise yield — the AWS-credential flow it also covered is gone with §6.4) but now emits the shared `ApiError` instead of its own `ErrorResponse{error}`, which is deleted. The frontend is standardised on the envelope's `.message` field (`settings.html` now reads `err.message`; the publish flow already did).
- **Extended the envelope to not-found and conflict across the rest layer ✅ (closes [#282](https://github.com/getvaier/vaier/issues/282)).** Two domain exceptions — `net.vaier.domain.NotFoundException` and `net.vaier.domain.ConflictException` — give the handler typed signals for the two missing categories (`PeerNotFoundException` now extends `NotFoundException`). `GlobalExceptionHandler` maps `NotFoundException` → `404` `ApiError(code=NOT_FOUND)` and `ConflictException` → `409` `ApiError(code=CONFLICT)`, alongside the existing `IllegalArgumentException`→`400`/`BAD_REQUEST` and catch-all→`500`/`INTERNAL_ERROR`. The conflict/not-found throw sites were retyped from raw `IllegalStateException` / `NoSuchElementException` / `RuntimeException` to the new typed exceptions: `VpnService` (LAN-CIDR already owned → `ConflictException`), `LanServerService` (not-found → `NotFoundException`, name-taken → `ConflictException`), and `LanServerSetupScript` (relay without a LAN address → `ConflictException`). (`AutheliaUserAdapter` was similarly retyped at the time but has since been deleted with the rest of the Authelia code.) Genuine server faults (SMTP provider unavailable, file-write failures) deliberately stay `IllegalStateException`/`RuntimeException` → `500`. Four controllers were migrated to let these exceptions propagate to the handler instead of hand-rolling responses, removing: `VpnPeerRestController`'s body-less `4xx`/`5xx` on rename / delete / lan-address / lan-cidr / description / reissue; `LanServerRestController`'s body-less `4xx` and its `Map.of("error", …)` `409`; `AuthRestController`'s bare-string error bodies (7 handlers); and `DockerServiceRestController`'s body-less `500`s on discovery. Net result: validation, not-found, conflict, and `500` all render as `ApiError`.
- **Caveat:** `ApiError` is now the shape for every error response *except* one intentional case: the deliberately body-less `404`s for a missing optional GET artifact (the icon, and an already-retrieved one-shot peer config). (A former second exception, the enterprise-gate `402`, no longer exists — see §6.14.)

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
`autheliaLogoutUrl()` / `logoutUrl(AuthMode, …)`, and `mandatoryDnsRecords()` returned only the `vaier` CNAME. *(That method is itself gone now — #331 deleted every record Vaier used to create; see §6.4.)*
The SMTP password moved off the legacy `authelia/config/secrets.properties` store into `vaier-config.yml`
(owner-only), and `docker-compose.yml` dropped the `./authelia/config` mounts, `AUTHELIA_CONFIG_PATH`, and
`VAIER_CONSOLE_AUTH_MODE`. **Deliberately retained:** `TraefikReverseProxyAdapter` keeps an idempotent startup
sweep that removes any leftover Authelia Traefik objects (login router / authelia service / auth-middleware)
from previously-deployed stacks, and the `ServiceNames.AUTHELIA`/`REDIS`/`AUTH`/`AUTH_MIDDLEWARE` constants
survive to feed that cleanup and the defensive `VaierServerCatalogue` infra-exclusion. No Authelia runtime, no
Authelia config written, and no dead Authelia Java classes remain.

**Delivered (fresh-install console sign-in fix, TDD-first):** ✅ — the Vaier console's own compose-label
routers (`vaier`, `vaier-identity`) reference the social auth middlewares (`oauth2-signin`, `oauth2-authn`,
`vaier-authz`) via `@file` unconditionally, but on a fresh install with no published service those middlewares
were never written to the generated Traefik config, so Traefik disabled the console routers and the operator
could not sign in (Google login completed, then every authenticated route 503'd to the offline page). The
startup hook `TraefikReverseProxyAdapter.backfillSocialMiddlewaresOnStartup` — which early-returned when
`oauth2-authn` was absent — was renamed to `ensureConsoleAuthMiddlewaresOnStartup` and now ensures the three
middlewares and the `oauth2-proxy-svc` service exist on every startup (creating the `http` section if absent),
regardless of whether any social service is published. Idempotent (it rewrites the same deterministic
definitions, so it still self-heals older configs onto newly-forwarded headers) and tightly scoped — it never
touches published-service routers/services or other config.

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
- **Each provider is independently optional** ✅ ([#332](https://github.com/getvaier/vaier/issues/332)) —
  `dex-init` renders a provider's connector only when both its client id and its client secret are
  non-empty, so an operator who only wants Google sign-in is never forced to also register a GitHub OAuth
  App. If zero providers end up configured, or the oauth2-proxy↔Dex shared secret is blank, `dex-init`
  fails fast (naming exactly which variables are missing, non-zero exit) instead of writing a config with
  an empty connector, which would otherwise crash-loop Dex behind the only way into the UI. With a single
  connector configured, Dex's own connector-selection screen is skipped and sign-in goes straight to that
  provider.
  The **sign-in page follows the same four variables** — `oauth2-proxy-init` renders `sign_in.html` (and
  the `connector_id` allow-list) into the runtime templates dir, keeping only the buttons whose provider
  is configured, and promoting GitHub from secondary to primary styling when it is the only one left. The
  first cut of #332 trimmed the connectors but left both buttons hard-coded, so a Google-only install
  still offered **Continue with GitHub** and Dex answered the click with `Bad Request: Connector ID does
  not match a valid Connector`.
- oauth2-proxy's `alpha.yaml` render is repointed from `provider: google` to a generic `provider: oidc`
  brokered by Dex (`issuerURL: https://dex.<domain>`, `connector_id` login param), with the oauth2-proxy↔Dex
  shared secret in the mode-0600 `client-secret` file.
- Secrets: `VAIER_DEX_CLIENT_SECRET` (oauth2-proxy↔Dex) is auto-generated into `.env` like
  `VAIER_OAUTH2_COOKIE_SECRET`; `VAIER_OIDC_GITHUB_CLIENT_ID` / `VAIER_OIDC_GITHUB_CLIENT_SECRET` are
  operator-provided alongside the Google pair.
- UI: the branded oauth2-proxy sign-in page offers a button per **configured** provider — **Continue with
  Google** (primary) and **Continue with GitHub** (quieter outlined secondary) — each submitting its
  `connector_id`.

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
  (`HostKeyMismatchException`); `DELETE /machines/{name}/host-key` clears a pin (now `{machineId}` — §6.22;
  it had no caller in the UI until §6.31). The WebSocket relays
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
- **#309 — Managed ed25519 keypair generation ✅** (the `managed` flag, now read as well as written) — see §6.34.
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
    per-filesystem `RemoteDiskPressureTracker` so it never alerts per poll. (It shipped scoped to `df -P /` —
    the root filesystem and only the root filesystem — and reading *every* filesystem, each with its own
    **disk watch** and its size, is [#325](https://github.com/getvaier/vaier/issues/325) ✅; see §6.9. It also
    shipped alerting only on a *boundary crossing*, with a silent baseline first observation held in memory —
    the combination that let the Vaier host's own root filesystem reach 89% in silence. It now alerts on the
    first observation in pressure and escalates by **pressure band** off persisted state; see §6.9.) Notifies via `NotifyAdminsOfRemoteDiskPressureUseCase` on `NotificationService`,
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
  - **#317 — Docker health over SSH + close the `apalveien5` 2375 hole.** 🔲 The *health* half is delivered by the container-standing work of [#356](https://github.com/getvaier/vaier/issues/356) (see §6.9's 2026-09-11 addendum) — over the existing Docker-API scrape, with no SSH channel and no new port. What remains here is closing the open `2375`.
- **Paste button in the terminal window's top bar ✅.** `Ctrl/Cmd+V` already reached the shell (527244c),
  but a phone's soft keyboard has no Ctrl at all, so there was no way to paste into a shell from a phone.
  `terminal-window.js` gains a **Paste** action beside Duplicate / Send password / Exit shell: on click it
  reads `navigator.clipboard.readText()` — only callable from a user gesture, which the click itself is —
  and hands the text to `term.paste()` so a multi-line paste still arrives **bracketed**, i.e. the remote
  receives it as pasted text rather than a queue of lines it starts executing as they arrive. Disabled
  until the shell's WebSocket is connected (tooltip explains why); a refused clipboard read reports itself
  and points at `Ctrl+V` / `⌘V` as the fallback. Gives desktop a discoverable alternative to the keyboard
  shortcut too.

**Backlog:** SFTP *over the terminal session itself* remains out of scope — the terminal's V1 scope is the
interactive shell plus saved snippets. (Vaier does speak SFTP now, but as its own feature: see **6.20
Explorer**, which browses a machine's files over a separate SFTP connection sharing the terminal's
credential vault and host-key trust.) Further remote-telemetry watchers (reboot detection, systemd service
health, load/temperature) are backlog under #313.

**Fleet credential ✅ (shipped; issue TBD).** #307 gave Vaier one **host credential** per machine: a
secret *Vaier* uses to reach a machine. The mirror image of that — a secret the *operator* needs to exist
identically **on** every machine — now ships as the **fleet credential**: one named secret, sealed in the
vault Vaier already has, delivered to one path on every machine that runs a shell Vaier can reach, and kept
there. The motivating case was the Claude Code OAuth credential (`~/.claude/.credentials.json`), which
otherwise has to be established by logging in separately on each host, in a terminal where copy/paste may
not even work — but **nothing in the code knows that**, and that case has since left this feature
entirely: **Claude sign-in** (§6.51) signs each machine's own CLI in rather than copying a credential
Vaier is not allowed to hold. The generic mechanism is untouched and still right for every other secret. Vaier distributes bytes to a path and verifies what
arrived; it never reads or interprets the content. That opacity is the feature, not a gap in it: the moment
Vaier learned what one credential *meant*, this would stop being one feature and start being one per secret.
It is deliberately **not** a second vault — it reuses `SecretCipher`, the same sealing `host-credentials.yml`
gets, and the same SSH road the five-minute disk sweep already drives down. `UBIQUITOUS_LANGUAGE.md` §13
carries **fleet credential** (explicitly contrasted with **host credential**), **distribute**, **withdraw**
and **fleet-credential standing**; §15 carries the **Credentials view** and its **coverage strip**.

- **Domain ✅** — `FleetCredential` (name, `targetPath`, `mode`, content, and whether it has ever been
  distributed) validates its own name (`[A-Za-z0-9_-]+`, as `PeerId` and `BackupRepository` do, and
  deliberately not shared with either), its path (absolute or `~/`-relative, safe charset, no `..`) and its
  mode, and **renders its own shell** for write / verify / remove. Three rules live there rather than in an
  adapter: every path is single-quoted (with a leading `~` expanded as a double-quoted `"$HOME"`, since a
  single-quoted tilde is a literal directory called `~`); the content travels **base64-encoded**, so
  arbitrary bytes survive the shell and the secret never appears in a command line, a shell history or a
  process list; and every command ends by reporting the file's actual owner, mode and SHA-256 back on a
  `VAIER-FLEET-CREDENTIAL` marker line. `FleetCredentialState` (`CURRENT`/`STALE`/`MISSING`/`SKIPPED`/
  `WITHDRAWN`/`FAILED`/`UNREACHABLE`) owns `needsHealing()`; `FleetCredentialTarget` owns *who may receive
  one* (`sshAccess` **and** a host credential); `FleetCredentialStanding` is one machine's state plus
  `anyLanded()`, the line a push must cross before the reconcile is licensed; `FleetCredentialView` is the
  only shape allowed out of the process, carrying neither the content **nor its digest** (a digest of a short
  secret is a secret). `FleetCredential.toString()` is overridden for the same reason a record's generated
  one is dangerous here.
- **Storage ✅** — `ForPersistingFleetCredentials` / `FleetCredentialFileAdapter` over
  `fleet-credentials.yml`, keyed by the credential's own name (a fleet credential *is* its name; renaming
  one is creating a different one). Content sealed with the existing `SecretCipher` (`enc:v1:`); name, path,
  mode and the `distributed` flag in the clear so the file stays legible during a recovery; file locked to
  `0600` via `SecureFilePermissions`. The `distributed` flag is **on disk on purpose** — it is what licenses
  the background reconcile, and a latch living in a field is wiped by every redeploy, which is exactly the
  bug that silenced the disk alerts for months (§6.9).
- **Use cases and service ✅** — six narrow use cases (`Save`/`Get`/`Delete`/`Distribute`/`Withdraw`/
  `GetFleetCredentialStandings`). CRUD lands on the existing `TerminalService` — same vault, same cipher,
  same redaction discipline, so no new `*Service`. Distribution does **not**: it needs the machine list that
  domain does not own.
- **Distribution ✅** — `rest.FleetCredentialDistributor`, a driving adapter (driven by an HTTP request and
  by a clock, as `RemoteDiskWatcher` is), composing `GetMachinesUseCase` + `GetHostCredentialUseCase` +
  `RunRemoteCommandUseCase` at the driving edge rather than teaching one service about another's data. It
  decides nothing: eligibility is `FleetCredentialTarget`, the command is the entity's, the reading of the
  report is the entity's, and the licence to reconcile is `FleetCredentialStanding.anyLanded`.
- **Reconcile ✅, and deliberately timid** — a `@Scheduled` sweep on the disk sweep's five-minute cadence,
  offset from it so the two do not knock on every door at the same instant. It **only heals**: it verifies
  first and writes only where the credential is `MISSING` or `STALE`, and only for a credential the operator
  has already distributed by hand at least once. A credential never pushed is never pushed by a timer; a
  withdrawn one is never healed back; an unreachable machine is skipped in silence (machines sleep, and a
  fleet-wide secret sweep is the last thing that should be emailing anybody). Verification compares
  **digests**, so a check costs no secret bytes on the wire. Standings are an in-memory observation — nothing
  is suppressed on their strength and no alert hangs off them, which is why they are *not* on disk.
- **REST ✅** — `GET /fleet-credentials` (each with its per-machine standings), `PUT|DELETE
  /fleet-credentials/{name}`, `POST …/{name}/distribute`, `POST …/{name}/withdraw`. Non-whitelisted, so
  Tier-3 admin auth applies automatically. The name comes from the path, never the body. The secret is
  **write-only over HTTP**: it arrives and is never returned, not even as a digest.
- **Credentials view ✅** — a native top-level Explorer entry (`credentials`, key icon) in the **Vaier
  menu**, beside Security. Each credential is a card with a **coverage strip** — one cell per machine that
  *could* hold it, coloured by standing — a tally, the machines it is **not** in place on listed worst-first,
  and Distribute / Withdraw / Replace secret / Delete. `SKIPPED` machines are excluded from the strip by
  design and named quietly underneath: a phone can never hold the file, and an indicator that can never come
  clean is one an operator learns to ignore. An empty `machines` list reads "Not checked yet", never "0 of
  0". "Replace secret", never "Edit" — Vaier will not show what it holds, so a save replaces the secret
  whole. Withdraw and Delete confirm (Delete by typed name) and say plainly which of the two reaches the
  fleet; Distribute asks nothing, because that is what the entry is for.

The three questions that gated the design, and where they landed:

1. **Does the credential survive being shared at all?** Still the operator's call, and Vaier stays opaque
   precisely so it remains theirs. If an OAuth provider's refresh tokens *rotate* (single-use, reissued on
   each refresh), then N hosts holding one copy is not a fleet but a race: the first host to refresh
   invalidates every other copy, and the rest fail later, silently, far from the cause — a failure mode worse
   than the manual logins it replaces. The answer is never to sync the file harder; it is to distribute a
   different artefact. **Claude credentials are the exception, and they are now out of this feature
   altogether:** Anthropic's terms forbid a third party collecting, storing or intermediating them, so
   Vaier never holds one — §6.51 signs each machine's own CLI in instead, and the `claude setup-token`
   fan-out this paragraph once recommended is rejected there on exactly those grounds. For every other
   secret the choice stays the operator's, because Vaier cannot tell one from another.
2. **Ownership and mode ✅** — the trap Vaier has already been burned by: the JVM runs as uid 1000, and a
   root-owned `0600` file is *silently unreadable* rather than loudly broken, so a push that lands as the
   wrong user produces a credential that exists, looks right, and does not work. The write runs under
   `umask 077` (never briefly world-readable, even before the `chmod`) as the target SSH user, and is then
   **verified rather than trusted**: owner, mode and digest are read back off the machine, and a mismatch is
   `FAILED`, never a quiet success. `readWriteOutcome` has no middle ground — a write is the one moment Vaier
   knows exactly what should be there. `stat`/`sha256sum` carry a BSD/Synology fallback so a NAS answers the
   same question a Linux server does.
3. **Blast radius ✅** — one credential on N hosts means any single host compromise is an account compromise:
   a considered trade on a trusted fleet, not a free one. So **withdraw shipped alongside distribute** —
   distribution without one-place revocation would not have been finished work. Withdraw removes the file
   from every machine Vaier can reach *and* stands the credential down, so the healer stops even where a
   sleeping machine still holds a copy; the standings say which those are. Delete is the separate, narrower
   act of making Vaier forget its own copy, and reaches no machine at all.

Prerequisite still outstanding: nuc02's key hygiene is unresolved, and pushing a shared secret to a host with
a world-readable key would hand it to anyone with a login there. Until that cleanup lands, that host should
not be a target for a fleet credential.

---

### 6.19 Fleet Backup ✅ (admin UI shipped)

Back up the data on the machines in the fleet to a [borg](https://www.borgbackup.org/) repository on a **backup server** — bootstrapping that server from nothing when the fleet has no borg anywhere. Freely available to every instance — the Community/Enterprise gating that once restricted this REST surface (`@RequiresEnterprise`, `402 Payment Required`, a locked-gate Backups tab) was removed along with the rest of the licensing subsystem (§6.14).

> **Not §6.7/#153.** This is **Fleet backup** — backing up the *fleet's machines* to a NAS. It is explicitly **not** the §6.7/#153 **Backup snapshot** feature, which is an export of *Vaier's own configuration*. The two are separate features with no shared vocabulary (see the distinct terms in `UBIQUITOUS_LANGUAGE.md` §14).

Domain: `BackupServer`, `BackupRepository`, `BackupJob`, `BackupRun` + `BackupRunStatus`, `Archive`, `BorgVersion`, `BorgServerImage`, `BorgServerSetupScript`, `BorgClientSetupScript`, `BackupFailureTracker`, `BackupServerHealthTracker`. Application: `BackupService` implements the use cases (adding `Get/Save/DeleteBackupServerUseCase`, `GenerateBackupServerSetupScriptUseCase`, `ProvisionBackupServerUseCase`, `AuthorizeBackupClientUseCase` to the existing `Get/Save/DeleteBackupRepositoryUseCase`, `Get/Save/DeleteBackupJobUseCase`, `RunBackupJobUseCase`, `GetBackupRunsUseCase`, `ListArchivesUseCase`, `CheckBackupPrerequisitesUseCase`, `InitBackupRepositoryUseCase`); `NotificationService` gains `NotifyAdminsOfBackupServerDownUseCase`. Driven ports `ForPersistingBackupServers` / `ForPersistingBackupRepositories` / `ForPersistingBackupJobs` / `ForRecordingBackupRuns` are file-backed (`Backup*FileAdapter`) — no database, consistent with the rest of Vaier; server reachability reuses `ForProbingTcp`. The rest layer's `BackupRunner` / `BackupProvisioner` drive borg over SSH (the latter also implementing `PrepareBackupClientUseCase` and, via twin `@Scheduled` sweeps sharing one private helper, pushing prepare-client **and** server-provision settle events on the `backups` SSE topic; `BackupRunner`'s poll sweep likewise publishes a `run-settled` event when an on-demand/nightly run settles — so the browser never polls for any of the three), `BackupServerWatcher` (`@Scheduled`) probes each backup server and alerts on transition, and `BackupRunner` also carries the nightly scheduler and a fail-fast borg-presence pre-flight. The web UI is the Explorer's `backup` **entries** — the server, its repositories and each machine's job, with create/edit/delete, **Run now**, enable/disable, run status, archive browsing and the guided-provisioning actions (setup-script download, provision, authorize-host, per-job readiness) all native there. `backups.html`/`.js`/`.css` are deleted (§6.21).

**What's implemented (V1):**
- **Backup server ✅** — a machine running a borg server that holds repositories: `machineName` (the hosting `Machine`, often a LAN server such as NAS), `host`, `sshPort` (default `8022`), `borgUser` (default `borg`), `baseRepoPath` (default `home/borg/backups`, no leading slash), `serverDataPath` (the host path holding the borg volumes / `authorized_keys`), and a `managed` flag. `BackupServer.sshUrlPrefix()` / `authorizedKeysPath()` are domain renderings. CRUD via `GET/PUT/DELETE /backup-servers[/{name}]`. **Singleton invariant ✅ ([#323](https://github.com/getvaier/vaier/issues/323))** — the fleet has **at most one** backup server: `BackupService.saveBackupServer` refuses a save when a differently-named server already exists (`IllegalArgumentException` → `400`), so a second server can never be designated without removing the first. **Designated in the Explorer ✅** — the server is created, edited and deleted on the `backup` **entry** of the machine that plays the role, and its operational actions (Provision, Authorize a host, Download setup script) are native there too. There is no separate Backups page — `backups.html` was deleted when the Explorer absorbed it (§6.21). **Provisioning ✅** — `GET /backup-servers/{name}/setup.sh` emits an idempotent bash script (`BorgServerSetupScript.generate`) that stands up a **pinned** borg-server container (`BorgServerImage.EXPECTED = horaceworblehat/borg-server:2.8.6`, no floating `:latest`); `POST /backup-servers/{name}/provision` runs it over SSH where docker-over-SSH works and **degrades gracefully** to `scriptOnly` (not a failure) where it doesn't (the Synology case): because `setup.sh` is gated behind admin auth (never anonymous, so it must not be curled onto a host), Vaier **stages** the script on the host over SSH (`BorgServerSetupScript.stageScript` base64-writes it to `<workDir>/<server>-borg-setup.sh` and echoes `STAGED <path>`) and returns the staged path plus the exact `sudo bash <path>` command in `stagedScriptPath`; if staging itself fails (no credential, SSH error) it degrades further to `scriptOnly` with a null path and tells the operator to download setup.sh from the UI. `GET …/provision/status` reports a detached run's `RUNNING`/`SUCCESS`/`FAILED` + log tail, but the **frontend never polls it**: a backend `@Scheduled` sweep (`BackupProvisioner.pollInFlightProvisions`, mirroring `pollInFlightPrepares` via a shared helper) settles the launched provision from its on-host `.rc` and publishes a `provision-settled` event (`{serverName,state}`) on the `backups` SSE topic, and the endpoint is kept only for API symmetry (the browser hits it once, on the pushed event, for the log tail). **Key trust ✅ (closes [#320](https://github.com/getvaier/vaier/issues/320))** — `POST /backup-servers/{name}/authorize/{machineName}` generates the client's ed25519 key if absent and upserts it into the server's `authorized_keys` exactly once (newline-safe), so borg — which runs on the client as the SSH user, not root — can authenticate. Reports `alreadyTrusted` distinctly from a fresh add. The entry Vaier writes is **restricted**, never a bare key (a bare key grants a full interactive shell as the borg user, so one compromised client could read and delete every other host's repositories — the weakness the live NAS had): `command="borg serve --restrict-to-path <abs> …",restrict <pubkey>`, with one `--restrict-to-path` per repository that machine backs up to on this server (`"/" + repoPathOn(server)`, sorted + deduped for a deterministic, idempotent line), plus `restrict` (no pty/forwarding/shell). Because the restriction is derived from the machine's jobs, **adding a repository for a machine requires re-authorizing that host** to widen its restriction; the entry is keyed by the public key's **key material** (the base64 blob, stable across options/comment) so a changed restriction set replaces the prior line rather than leaving two entries for the same key (`BorgCommand.keyMaterial` + a `grep -vF` upsert). If no repository targets the machine yet, the key is confined to the server's base repository path as a placeholder and the response says to re-authorize after creating a job — Vaier never writes an unrestricted key or a bare `--restrict-to-path`. Building the entry line and extracting the key material are domain rules on `BorgCommand`; the orchestrator only computes the path set from `GetBackupJobsUseCase` + `GetBackupRepositoriesUseCase`. **Host-key pinning ✅ (no trust-on-first-use)** — a freshly provisioned borg server generates brand-new SSH host keys, so a client with a stale `known_hosts` pin refuses to connect (`REMOTE HOST IDENTIFICATION HAS CHANGED`) and a brand-new client has no pin at all — and borg's non-interactive SSH (`StrictHostKeyChecking=ask`, no tty) fails rather than prompting; either way every backup fails with `borgAuthOk=false`. Vaier is the trusted broker (it reaches the server's machine with its own vault credential and pinned host key), so it obtains the server's host key **authoritatively** rather than via `ssh-keyscan` (absent on Synology) or `accept-new`: the **setup script** publishes the container's public host keys (`BorgServerSetupScript` waits bounded for `server_keys/ssh/ssh_host_ed25519_key.pub`, then concatenates the `*.pub` keys to `<serverDataPath>/ssh/host_keys.pub`, chowns to the SSH owner, `chmod 644` — the private `ssh_host_*_key` files are never touched); `authorizeClient` then reads them from the server's machine (`BorgCommand.readServerHostKeys` + `parseHostKeys`, which accepts only real `ssh-ed25519`/`ssh-rsa`/`ecdsa-sha2-*` lines and drops comments/MOTD/private-key noise) and pins them on the **client** before authorizing the client key (`BorgCommand.pinHostKeys` backs up `known_hosts`, removes only this `[host]:port` via `grep -vF`, appends one `[host]:port type key` line per key — the bare `host` form when `sshPort == 22` — locks the file to `0600`, and echoes `PINNED <n>`; idempotent). When the server has no published host-key file (an **adopted** server), pinning is skipped and the client key is **still** authorized, with `AuthorizeResult.hostKeyPinned=false` and a message telling the operator to re-run the setup script or pin manually — the pin never fails the authorize, and junk is never written into `known_hosts`. `BackupServer.hostKeysPath()` renders the published path; the parser, the pin command, and the `[host]:port`-vs-`host` decision are domain rules on `BorgCommand`.
- **Backup repository ✅** — one borg repository on a backup server: `name`, `serverName`, a **nullable** `repoPath` override (blank derives `base/<name>` via `BackupRepository.repoPathOn(server)`, so a new repo is added just by naming it; an explicit override still addresses an adopted, oddly-named repo), an encrypting **passphrase** (stored encrypted at rest via the same cipher as the credential vault, never returned to the browser; the UI auto-generates a strong, shell-safe one on create, shown once), and an **append-only** flag (documents the hardening choice; V1 ships a delete-capable key so nightly prune/compact work). `BackupRepository.borgRepoUrl(server)` renders the `ssh://user@host:port/path` URL from the server's coordinates. CRUD via `GET/PUT/DELETE /backup-repositories[/{name}]`; the response reports the **effective** (resolved) `repoPath` and only `hasPassphrase`. **Name/path hardening ✅** — a repository/server `name` is used verbatim as a shell/path token (the derived repo path, the pass-file name, each `--restrict-to-path`), so it is now validated at construction as a safe **identifier** (`[A-Za-z0-9_-]+`, mirroring `PeerId`) — a space or shell metacharacter (`a; rm -rf ~`) is rejected as `400`; `BackupRepository.sanitizedName` / `BackupServer.sanitizedName` slug operator input (spaces → `-`) and the UI live-slugs the name field so a bad name can't be submitted. Operator-settable **path** fields (`repoPath` override, `baseRepoPath`, `serverDataPath`) are validated to a safe-path charset (`[A-Za-z0-9._/-]+`), `host` to `[A-Za-z0-9.-]+`, `borgUser` to `[A-Za-z0-9._-]+`. As defense in depth `BorgCommand` **single-quotes every borg path** — the repo URL at all six sites (list/info/init/prune/compact/create, the create target rendered as adjacent `'URL'::'ARCHIVE'` tokens) and each `--restrict-to-path` — and the tolerant file adapters never let one bad entry abort the whole load. **Legacy-name repair ✅** — a pre-fix repository entry whose stored `name` is now unsafe (a space, e.g. `"NUC 02"` created before names were slugged) is **repaired to its safe slug** on read (`"NUC-02"`, via the case-preserving `BackupRepository.sanitizedName`) rather than silently dropped; only a name that slugs to nothing at all is skipped, with a warning. Dropping it was itself the data-loss bug: an invisible repository is a lookup miss for the get-or-create in the protect flow (§6.21), which then mints a **duplicate** repository with a fresh passphrase over the live borg repo and orphans it (borg can no longer decrypt it). `BackupJobFileAdapter`/`BackupServerFileAdapter` still skip an unrepairable entry with a warning.
- **Backup job ✅** — a per-machine spec: `machineName` (a VPN peer's canonical name), `repositoryName`, `sourcePaths` (≥1), `excludes`, retention (`keepDaily`/`keepWeekly`/`keepMonthly`, at least one > 0), `compression` (default `zstd,6`), `enabled`, and `backupAsRoot` (see below). The job owns the archive-naming convention (`archiveNameTemplate()` / `archiveGlob()`) so prune/list scope to just this job's archives in a fleet-shared repository. CRUD via `GET/PUT/DELETE /backup-jobs[/{name}]`.
- **On-demand run ✅** — `POST /backup-jobs/{name}/runs` starts a **backup run** now; `GET /backup-jobs/{name}/runs` lists a job's runs. A run has a `BackupRunStatus` (`RUNNING` → terminal `SUCCESS`/`WARNING`/`INCOMPLETE`/`FAILED`, or `UNKNOWN` when a result can no longer be resolved). The rule is a domain decision on `BackupRun.statusFor`, and it reads the run's **output** as well as its exit code, because borg's exit `1` conflates two unrelated facts: `0` = SUCCESS, `1` = **INCOMPLETE** when the output names files borg could not read (a failure — see the *incomplete archive* bullet) else **WARNING**, `>= 2` = FAILED. WARNING is terminal but non-failing (`isFailure()` false), so it never pages admins and can itself all-clear a previously failing job; INCOMPLETE **is** failing and pages like one. **The frontend never polls a run's outcome**: when `BackupRunner.pollRunningRuns` settles a run this tick it publishes a `run-settled` event (`{jobName,status}`) on the `backups` SSE topic (a publish failure is swallowed, never breaking the sweep), and the browser — guarding on the job it launched so an event for another job never refreshes the wrong card — re-fetches just that job's latest run once and renders it. The passphrase 0600 file and each run's `.rc`/`.log` state live in a per-host **work dir** — `~/.vaier-backup` on the target host (`rest.BackupWorkDirResolver` resolves the SSH user's `$HOME` over SSH and caches it), falling back to `/tmp/vaier-backup` when `$HOME` can't be resolved. It is the SSH user's own home, not a root-owned `/var/lib` path the non-root borg user could not create; the absolute path is resolved in the orchestration and passed into every `BorgCommand`, because borg runs `BORG_PASSCOMMAND` without a shell so an embedded `$HOME` would never expand. **Clean-run exclusions ✅** — every `borg create` now excludes two paths so a run whose source paths include a user home comes back a clean **SUCCESS** rather than a spurious **WARNING**, and so the passphrase is never archived: the **work dir** itself (it holds the `0600` pass file — the repo passphrase must never land in an archive — and, for an as-root run, borg's `BORG_BASE_DIR` cache/security dir under `<workDir>/root`), and `*/.config/borg` (a non-root run's borg security dir, whose per-repo `nonce` file changes mid-run — borg would otherwise report "file changed while we backed it up" and exit `1`/WARNING). Both patterns are single-quoted so the shell never globs them; borg applies them as `--exclude` fnmatch patterns ahead of the job's own excludes.
- **Run diagnostics ✅** — a run captured borg's output in `summary` all along, but the UI only ever said *that* a run had warnings, never *what* they were: the detail was reachable only by reading `backup-runs.yml` on disk. Deciding which of a raw borg summary is fit for a human is a **domain decision** and lives on the entity as `BackupRun.diagnostics()`: borg pretty-prints its `--json --stats` object as a block whose braces sit alone at column 0, so the block is identified **structurally** (opens at the first line that is exactly `{`, closes at the first following line that is exactly `}`) and removed, keeping every line outside it — including borg prune's "Keeping archive (rule: …)" report, which follows the object on a clean run, so the JSON is *not* simply a trailing suffix. A summary with no such block (`sh: 1: borg: not found`) is diagnostics in its entirety; the method is total (never throws, never null, empty on a clean run) and, like `summary`, never carries the passphrase. `RunResponse` exposes it beside the raw `summary`, and the status badge of a **warnings**/**failed**/**unknown** run with diagnostics becomes a **disclosure** — a caret control that opens a monospace, horizontally scrollable panel of the lines (originally on the now-deleted Backups page, native on the machine's `backup` entry in the Explorer now — see §6.21). A clean run has no diagnostics and so gets **no affordance at all**, keeping the happy path quiet. The warning/failure toasts now point at that disclosure instead of dead-ending.
- **An incomplete archive is a failure, not a decoration ✅** — the live report that forced this: job *Colina 27* (`backupAsRoot=false`, sources `["/home"]`) emitted hundreds of `/home/nut-http/logs/…: open: [Errno 13] Permission denied: '…'` lines, borg skipped every one of those files, exited `1`, and Vaier recorded the run **WARNING** — terminal, non-failing, silent. The operator found out months later by reading raw borg output. borg's exit `1` conflates two unrelated facts, so the exit code alone can never decide the outcome: the run's **own output** must be read. New domain value object **`domain.UnreadableFiles`** does that — one regex over borg's denial lines (`^(.+?): (?:[A-Za-z_]+: )?\[Errno 13\]`, so `open:`/`scandir:`/`opendir:`/no-syscall shapes all read alike; the lazy path group keeps a path containing `": "` intact), keyed on **`[Errno 13]`** rather than the words *permission denied* so a human sentence ("2 files skipped (permission denied)") can never be mistaken for a lost file. Paths are **deduped** (borg can hit one file on two passes; an operator counting lost *files* must not be told lines) and the retained **sample is capped at 10** while the **count stays exact** — the value reaches a UI pane and an inbox, so an unbounded path list belongs in neither, but "how much did I lose" is the number that decides whether anyone acts. `BackupRunStatus` gains **`INCOMPLETE`**: terminal, and `isFailure()` **true**. `BackupRun.statusFor(exitCode, summary)` is the refined domain rule — `0` → SUCCESS (output is *not* consulted: borg cannot exit clean having skipped a file, and a stray tail line must never demote a clean run), `1` → **INCOMPLETE** when the output names unreadable files else **WARNING** (a benign "file changed while we backed it up" still captured everything), `>= 2` → **FAILED**, untouched — a run that wrote no usable archive is a different problem with its own outcome and its own wording. `BackupRun.unreadableFiles()` is **derived from `summary`**, not stored: `summary` *is* the captured borg output and is already bounded at capture (`tail -c 4096`), so parsing on demand keeps the unbounded path list out of the run store entirely and survives a restart for free. Because `INCOMPLETE` is a failure, it travels the **existing** admin-notification road with no new wiring — `BackupRunner.alertOnTransition` → `NotifyAdminsOfBackupFailureUseCase` → `NotificationService` → the SMTP admin notifier — and the entity words it: **`failureSubject()`** says *"[Vaier] Backup incomplete: …"* rather than *failed* (in an inbox "failed" reads as "did not run" and gets dismissed as noise on a job that visibly runs nightly), and `failureBody()` puts `UnreadableFiles.report()` — the count, the capped sample, and the named fix — **above** the raw borg tail, because an operator must not have to find the denial lines themselves. The Explorer paints `INCOMPLETE` **red** (not amber like WARNING), opens the run diagnostics for it, and leads with the plain sentence *"Some files were not backed up… the backup ran, but the data is not all there."*
- **Back up as root is a control, not an API field ✅** — `backupAsRoot` was on the job and reachable over `PUT /backup-jobs/{name}`, but after the job modal was retired into the Explorer (§6.21) **no control for it shipped anywhere in `explorer-shell.js`**. That is the whole reason Colina 27 ran non-root over `/home` for months: nothing on screen said which way it was set, and nothing let an operator change it. The machine's `backup` entry now carries a **Reading the files** section with the shell's existing `checkRow(…)` checkbox — labelled in the consequence, not the mechanism: **"Back up files owned by other users"**, with a note that says what *off* costs ("any file here that belongs to someone else is skipped, and the archive is missing it"). **No endpoint was opened**: the flag lives on the job spec, so `toggleBackupAsRoot` re-`PUT`s the whole job with every other field carried through (drop one and the job silently loses its protected paths), optimistic like the SSH-access toggle and reverting on a failed save. When the last run was `INCOMPLETE`, the run pane points straight at this setting by name.
- **A holed folder no longer wears a full shield ✅** — reported on Colina 27 and Apalveien 5: the openhab logs folder is an **excluded path**, yet `/home` above it kept its **full** shield. `backedUp` was `ProtectedPaths.covers(path)`, which asks only "is this path part of the backup" — quite true of `/home` — while a full shield is read as *everything under here is in the archive*. With a hole inside it that is a claim about data borg walks straight past: the same class of lie as a run reporting success while skipping files. The badge is now its own domain question, beside the coverage one: `Excludes.anyStrictlyInside(path)` (an exclusion *deeper* than the path; an exclusion **at** it means the folder is out, not partial, and a **glob** never counts — Vaier cannot tell what `*.tmp` bites into, so inventing a verdict from a pattern would be a guess dressed as fact), then **`ProtectedPaths.isBackedUp`** = covered **and** unholed, and **`ProtectedPaths.containsBackedUp`** = holds backed-up content without being whole (protected content strictly inside it, **or** covered-but-holed) — mutually exclusive by construction, so the controller restates nothing. A **file** has nothing inside it, so its verdict is unchanged. In the shell, both shields now travel with the selection and one predicate `anyBackedUp(s)` gates **Stop backing up** — gating it on the full shield alone would have quietly removed the verb from `/home` the moment one exclude appeared inside it — and the half shield's tooltip is worded true of both ways to earn it: *"Partly backed up — not everything inside is in the archive."*
- **Back up as root ✅** — Vaier runs borg over SSH as the machine's credential user (e.g. `ubuntu`), never root, so **every file in a job's source paths that user cannot read is silently skipped**: borg exits 1, the run settles WARNING, and the archive has holes. The live fleet had exactly that — a mosquitto broker database owned `1883:1883` mode `0600` and a pihole file owned `root:root`, both missing from otherwise "successful" archives. Per-file `chmod` is whack-a-mole (every new container volume is a fresh silent hole), so `BackupJob` gains a `backupAsRoot` component, **default/opt-in false** — a job never escalates itself, and a job file written before the component existed deserialises to `false` (`BackupJobFileAdapter`), never to root. When it is on, **every** borg invocation in the run chain (info, init, create, prune, compact) is made through the one binary `BorgCommand.borgBinary` renders — `sudo -n HOME=… BORG_BASE_DIR=… BORG_RSH=… BORG_PASSCOMMAND=… borg` — which handles the four things sudo would otherwise break. **SSH cannot find the key or the host pin** — the borg client key and the pinned backup-server host key both live in the *SSH user's* home, and under sudo ssh runs as root, reading `/root/.ssh/`, where neither exists. **`HOME` does not fix this**, and the first cut of this feature wrongly assumed it did: **OpenSSH ignores `$HOME`** and resolves `~` from `getpwuid(getuid())`, i.e. the passwd entry of the UID it runs as. Since host-key verification precedes publickey auth, every as-root run died at the server with `Host key verification failed` (not the `Permission denied (publickey)` the code's comments predicted). The fix is **`BORG_RSH`** — `ssh -i <sshHome>/.ssh/id_ed25519 -o UserKnownHostsFile=<sshHome>/.ssh/known_hosts` — naming both files as absolute literals under the SSH user's home, so root's ssh is pointed straight at them; it carries neither `-p` nor the `user@host`, which borg appends itself from the repo URL. **HOME** is still set (an absolute literal, never a `$HOME` for the command to expand — sudo would reset it first) for the tools that *do* honour it, but it is no longer load-bearing for ssh. The **reset environment** (sudo discards the shell's exported `BORG_PASSCOMMAND`, so it is passed on the sudo line — exactly what the sudoers `SETENV:` tag permits, which equally covers `BORG_RSH`). And **root's borg cache** (`BORG_BASE_DIR` is isolated to a root-owned `<workDir>/root`, so root's cache cannot leave files that break a later non-root run of the same job; the first as-root run therefore rebuilds the chunk cache, which is slower but correct — dedup is content-addressed server-side, so nothing is stored twice). The SSH home is required either way, since it is what the `BORG_RSH` paths are built from: `BackupWorkDirResolver.homeFor` resolves and caches the SSH user's `$HOME` — the primitive the work dir already derived from — and, unlike `workDirFor`, has **no fallback**, because a missing home does not degrade an as-root run, it breaks it. `BackupRunner` therefore **refuses** an as-root run whose home can't be resolved (a recorded FAILED run with a clear message) rather than launching it to die at the backup server. A job with the toggle **off** renders a byte-for-byte unchanged command. **Security** — only the borg binary is ever sudoed: never `sudo env …`, never `sudo sh -c …`, never a wildcard, any of which is a trivial root shell for anyone who can run it and would turn a backup feature into a local privilege-escalation hole. **The grant ✅** — `BorgClientSetupScript` installs a sudoers drop-in at `/etc/sudoers.d/vaier-borg` giving the invoking SSH user (`$SUDO_USER`, `logname` as fallback) `NOPASSWD: SETENV:` for `/usr/bin/borg, /usr/local/bin/borg` and nothing else. It is written to a temp file, validated with `visudo -c`, and only then installed `0440 root:root` — never written straight into `/etc/sudoers.d`, where a malformed drop-in can lock the host out of sudo entirely; a host with no `visudo` is left alone with a loud warning rather than having an unvalidated file dropped on it. The script's old `exit 0` when borg was already present was **removed** (it would have meant the grant never landed on any host that already had borg — i.e. every host in the fleet): the install is now *skipped*, and the script continues to the grant. **Readiness ✅** — `CheckBackupPrerequisitesUseCase.checkRootBorg` / `BackupProvisioner` probe `sudo -n borg --version` (`BorgClientSetupScript.rootBorgProbe`/`parseRootBorg`), which passes only when the drop-in is in place *and* borg is on sudo's `secure_path`; a guarded-out host, timeout, or thrown SSH error reports a negative, never an optimistic yes. `GET /backup-jobs/{name}/provision/check` carries `backupAsRoot` + `rootBorgOk`, and the row is **only probed and shown for a job that opted in** — a job running as the SSH user is not "not ready" for lacking a grant it will never use, and there is no pointless SSH round trip. The job modal gains the **Back up as root** checkbox, and the readiness row offers **Prepare client** inline when the grant is missing.
- **Back up as root becomes an evidence-backed question, not a checkbox ✅ (closes [#334](https://github.com/getvaier/vaier/issues/334))** — the checkbox was the wrong shape for the decision: asked up front it is really two questions nobody can answer on the spot (*who owns the files inside my container volumes?* and *what does a passwordless `sudo borg` grant?*), put to an operator with no evidence either way — which is why Colina 27 ran without it over `/home` for months. So the question is now **asked only when Vaier can prove it costs data**, and answered in **one** action. **The nudge** — a fourth `MachineNudge.Kind`, `BACK_UP_AS_ROOT` (`MachineNudge.backUpAsRoot(machineName, latestRun, job)`, §6.15 Slice 5), fires on exactly three conditions: there is a **backup job** (the nudge asks a job to change), the job is **not** already backing up as root (root already reads everything, so whatever it missed this cannot fix), and the machine's **latest run** settled `INCOMPLETE` **with `UnreadableFiles.any()`** — keyed on the denial evidence rather than the status word, so an incomplete run that lost data some other way raises nothing. It titles *"This backup is missing N files"* and its evidence is the new `UnreadableFiles.inOneLine()`: up to `INLINE_LIMIT = 3` named paths, "and N more", then what their absence costs — a sibling of `report()` on the same value object, so a nudge card and the failure email can never word one loss two ways. No "Vaier is already root here" guard is needed: a root login is never denied, so it produces no denial line, so `any()` is false and the nudge cannot fire there. **One action** — `POST /backup-jobs/{machineId}/back-up-as-root` (`EnableBackupAsRootUseCase` on `BackupService`) installs the sudoers grant *and* turns the flag on, replacing a two-step dance nobody could discover (`PUT /backup-jobs/{machineId}` flipped the flag, `POST …/prepare-client` installed the grant, and doing only the first produced a job whose every run died on `sudo -n` before borg started). The decision is the entity's: `BackupJob.enablingBackupAsRoot(ForReadyingBackupClients)` asks the port whether the machine grants root borg — a new `ForReadyingBackupClients.canBackUpAsRoot(machineId)`, implemented by `BackupProvisioner` by **delegating to the existing `checkRootBorg`** so the wizard's view and the domain's can never disagree, and never optimistic (unreachable host, timeout or thrown SSH error all report `false`) — and returns `BackupAsRootOutcome(job, granted, changed, readying)`: grant present → the flag goes on; grant absent → it is **requested** through the same port (`readyForBackup`) and **the flag stays exactly where it was**. That asymmetry is the point: the grant install is detached, so its success is not yet knowable, and flipping the flag on that hope would trade an archive with holes for no archive at all. The service persists only when `changed`, and a machine with no job is a **404** rather than a job invented on the spot — opting into root reads is not a way to start backing a machine up. The response is deliberately compound (`{granted, job, provisioning}`): "the call succeeded" must never be read as "root reads are on tonight". **Frontend (`explorer-shell.js`)** — the nudge card renders the accept as its one action and carries a quiet *"What that means ›"* link to `concepts.html#back-up-as-root` (a new `learn` slug on `NUDGE_ACTION`, on the only nudge whose answer changes what Vaier may do on a machine). When the grant had to be installed, the machine is remembered in `S.rootAfterGrant` and the **existing** `prepare-client-settled` push finishes the action exactly once (a `retrying` flag, so a host that will never grant it gets one more try and then stops) — the operator is never asked to come back and say yes again. Turning it **off** is still a plain job save (`stopBackingUpAsRoot`, re-`PUT`ting the whole spec, optimistic and reverting on failure): the two directions are not mirror images, so they are not one call. The **checkbox itself moved under an `Advanced` disclosure** on the machine's backup pane — still reachable, its current state still visible, but no longer the way the question is asked. **No new polling** ([[feedback_frontend_never_polls]]): the `run-settled` SSE event now also drops that machine's cached nudges, so the card appears the moment the evidence lands instead of on the operator's next visit to the pane. **Concepts** — `OperatorGlossary` gains a **Backups** group (Backup job, Backup run, Incomplete backup, Back up as root, Archive); the operator-facing explanation of back-up-as-root — including its honest price, that the machine must let Vaier's login run the backup program as root without a password, which makes that login as powerful as root there — now lives there, and `docs/BACKUP.md` points at it rather than carrying a second copy that would drift. Domain-tested (`MachineNudgeTest`, `MachineNudgesTest`, `BackupJobTest`, `UnreadableFilesTest`, `OperatorGlossaryTest`), service-tested (`BackupServiceTest`), adapter-tested (`BackupProvisionerTest`) and controller-tested (`BackupRestControllerTest`, `MachineRestControllerTest`, `ExplorerShellTest`).
- **Nightly schedule ✅** — Vaier runs every *enabled* job once a day at a configurable hour. The hour lives on the **Settings** surface, like the disk-pressure threshold: `backupScheduleHour` in `vaier-config.yml` (`domain.VaierConfig`, default `2`, valid `0–23`), carried on `GET /settings/config`, updated via `PUT /settings/backup-schedule` (`UpdateBackupSettingsUseCase` on `SettingsService`). `BackupRunner` gates its scheduled sweep on the current hour matching `ConfigResolver.getBackupScheduleHour()`, read in the injected `Clock`'s zone. That clock is `Clock.systemDefaultZone()` (it was `systemUTC()`, which made the hour silently mean UTC — a schedule set for 02:00 fired at 04:00 in Europe/Oslo), so the container's `TZ` chooses the zone: `docker-compose.yml` sets `TZ: ${VAIER_TZ:-UTC}` on the `vaier` service. `GET /settings/config` also carries `backupScheduleZone`, taken from that same clock, so the UI names the zone rather than saying "server local time" and the label can never drift from the hour that actually runs.
- **Failure email alerts ✅** — a failed run emails every **admin**-role **access entry** via `NotifyAdminsOfBackupFailureUseCase` (on `BackupService`), reusing the same `sendToAdmins` SMTP path as the other alerts (silent when SMTP is unconfigured). `BackupFailureTracker` keeps it from re-paging on repeat failures of the same job.
- **Server-down alerts ✅** — `BackupServerWatcher` (`@Scheduled`, mirroring `RemoteDiskWatcher`) TCP-probes each backup server's borg port; `BackupServerHealthTracker` requires **two consecutive** failed probes before crossing to DOWN (a blip never pages) and a single success to recover. On a transition it emails admins once via `NotifyAdminsOfBackupServerDownUseCase` — `REFUSED` → "borg server is down on `<host>`", `UNREACHABLE` → "`<host>` is unreachable" — and once more on recovery; a Vaier restart never re-pages.
- **Guided provisioning ✅** — `GET /backup-jobs/{name}/provision/check` reports host readiness for a job's machine: whether borg is installed and its version (`borgInstalled` / `borgVersion`), whether that version is supported (`borgSupported`), whether the machine can reach the server (`nasReachable`), and — the checks that kill the **false all-green** — whether the client's key is actually **trusted on the server** (`borgAuthOk`), the server's borg version (`serverBorgVersion`), and whether client and server borg majors are **compatible** (`versionsCompatible`; borg 1.x/2.x repo formats are incompatible, so `BorgVersion.isCompatibleWith` requires matching majors) — plus, **only for a job that opted in to Back up as root**, whether borg can actually run as root on that machine (`backupAsRoot` / `rootBorgOk`; see that bullet). `borgAuthOk` is proved by running `borg info` on the repository's URL **from the client host** (via `BorgCommand.serverAuthProbe`, unlocked by the same `BORG_PASSCOMMAND` pass file as a run): reaching `borg serve` at all — even the "repository does not exist" of a not-yet-`init`-ed repo — means the key authenticated. This deliberately replaced a `borg --version`-over-SSH probe, which the **restricted, forced-command** client key silently broke (`command="borg serve …"` discards the requested command, so `borg --version` never runs). Because that same forced command makes the server's version unknowable over SSH, `serverBorgVersion` is **derived from the pinned image** (`BorgServerImage.borgVersion()` → borg 1.4.3) for a **Vaier-managed** server and left **unknown** for an **adopted** one; `versionsCompatible` fails closed (false) whenever either version is unknown. A green borg + reachable server no longer reads as ready on auth alone; the UI offers **Authorize host** inline when `borgAuthOk` is false. `POST /backup-repositories/{name}/provision/init` runs `borg init` from a machine that references the repository (`initialized` / `alreadyExisted`); it returns `409` when no job targets that repository, since it has no host of its own to init from. It is **not surfaced in the UI** — a **backup run** initialises its own repository when absent, and a button on a repository card would necessarily fail on a repository no job references yet.
- **Prepare client (install borg) ✅** — a job on a host with no borg client died with `exit 127` / `borg: not found` (the real NUC 02 incident); Vaier provisioned the *server* but never prepared the *client*. Two parts. **(1) Fail fast** — `BackupRunner.runJob` now probes `borg --version` on the job's machine (one pre-flight SSH round-trip, after the machine/SSH/credential/server guards and before the detached launch) and, when a clean non-timed-out probe exits non-zero or fails to parse (`BorgVersion.parse`), records a **FAILED** run with `"borg is not installed on <machine> — run Prepare client"` and never launches. A probe **exception** is treated as "couldn't verify" and the run **still launches** (a flaky probe must never block a working host — the run settles cleanly if borg really is missing); only a definite non-borg result blocks. **(2) Prepare client** — `POST /backup-jobs/{name}/prepare-client` (job-scoped; the readiness panel acts from the job, which knows its machine) resolves `job.machineName()` and runs `PrepareBackupClientUseCase` on `BackupProvisioner`, mirroring server provisioning. `BorgClientSetupScript.generate()` (pure domain, no args — it detects everything on the host) emits an **idempotent** bash install: root check, widened `PATH`, the right package per detected manager (`apt-get`→`borgbackup` after `apt-get update`, `dnf`/`yum`/`apk`/`zypper`→`borgbackup`, **`pacman`→`borg`** — Arch's package name differs), else `exit 5`; the install step is **skipped** (not an early `exit 0`) when borg is already present, so the script goes on to install the borg-as-root sudoers grant on an already-prepared host. The install needs root but Vaier SSHes non-root, so `prepareClient` probes **passwordless sudo** (`BorgClientSetupScript.passwordlessSudoProbe` → `sudo -n true`): with it, the script is launched **detached** under `sudo -n bash` (`BorgClientSetupScript.detachedLaunch`, base64 + `nohup`, rc/log files — it can outlast the 20 s exec cap); without it, Vaier **stages** the script (reusing `BorgServerSetupScript.stageScript`/`parseStagedPath`) and returns `scriptOnly` with the exact `sudo bash <path>` command (never a raw `curl | sudo bash`). `PrepareResult` reuses `ProvisionResult`'s shape/semantics (`prepared`/`scriptOnly`/`started`/`message`/`stagedScriptPath`). **The frontend never polls** the install: a backend `@Scheduled` sweep (`BackupProvisioner.pollInFlightPrepares`, every 3 s) reads the launched install's on-host `.rc` over SSH and, on settle, publishes a `prepare-client-settled` event (`{machineName,state}`) on the **`backups`** SSE topic; the browser opens `GET /backup-jobs/events` (`ForSubscribingToEvents.subscribe("backups")`, mirroring `VpnPeerRestController`'s `/events`) and re-checks readiness on the pushed event. A `GET /backup-jobs/{name}/prepare-client/status` one-shot (reusing `BorgCommand.pollStatus`/`parsePoll`/`fetchLog`) remains for API symmetry.
- **Archive browsing ✅** — `GET /backup-repositories/{name}/archives` lists the borg archives (point-in-time snapshots) in a repository, each a `domain.Archive` (name, id, time). `Archive.parseList` reads `borg list --json` in the domain and, like `RemoteDiskUsage.parse`, never throws — bad input yields an empty list.
- **UI ✅** — backups have no page of their own. Everything lives on the Explorer's `backup` **entries**: the fleet's one server, its repositories and each machine's job, together with **Run now**, the enable/disable toggle, the per-job readiness check (with inline **Authorize host** and **Prepare client**), the last-run badge and the run-diagnostics disclosure. `backups.html`/`.js`/`.css` are deleted (§6.21). The **frontend never polls**: the prepare-client install and server provisioning run detached on the host, backend sweeps do the host-side polling, and the browser learns each one finished over the `backups` SSE stream (`prepare-client-settled`, `provision-settled`, `run-settled`). Machine pickers read `GET /machines` (peers **and** LAN servers such as NAS), never `/vpn/peers`.

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

Browse, cross-machine copy and download, plus the time rail, coverage and restore, are all freely
available (the time rail, coverage and restore are Fleet Backup features — see §6.14 for the
now-removed Community/Enterprise split that once separated them).

**Slices:**
- [x] **1 — Browse ✅.** `sshd-sftp` dependency, `ForBrowsingRemoteFiles` port + `MinaSftpAdapter`,
  `GET /machines/{name}/files?path=…` directory listings on any SSH-capable machine, and the read-only
  Explorer page over them. Community, and authenticated like every other machine endpoint.
- [ ] **2 — Move 🟡 (backend delivered).** Clipboard, cross-machine Transfer (streaming, tracked,
  SSE-settled), download to browser, size warning, **download a whole Selection as one zip**. Community.
  *(The backend landed — cross-machine **Transfer**, **download**, transfer tracking with SSE, and the
  **Selection**-zip download (`POST /machines/files/download-zip` streams one zip from a fleet-wide list of
  coordinates; the `domain.Selection` owns the filename and per-coordinate zip-entry naming — machine-prefixed
  across machines, basename-deduped within a namespace); see **Delivered in slice 2** below. The **Clipboard**
  and selection-bar UI and the **size warning** are the frontend half, in progress. **Coverage** (slice 4) and
  delete/rename/new-folder (slice 5) remain.)*
- [ ] **3 — Time.** `borg mount` lifecycle, the time rail, archive overlay on the tree, restore as
  paste-from-the-past. *(The `borg mount` lifecycle backend and the **time-rail UI** both landed via
  §6.21 slice D — mount-on-demand, idempotent, idle-swept, with the rail scrubbing a machine's archives newest-first
  and the shell relighting into the past palette; restore-as-paste is what remains, and it rides on Clipboard/paste
  from slice 2.)*
- [ ] **4 — Coverage 🟡 (backend delivered).** Coverage dots, draft-then-save to the backup job, "Uncovered only" filter,
  one-job-per-machine enforcement. *(The **select-and-back-up** backend landed — per-entry
  **backed-up** / **contains-backed-up** flags on the file listing, and one endpoint per direction that
  get-or-creates the machine's repository and job and folds a path selection into the job's **protected
  paths**; see **Delivered in slice 4** below.)*
- [ ] **5 — Mutate 🟡 (delete and upload delivered).** Delete, upload, rename/move, new folder, behind a
  typed-confirmation gate. Community. *(The **delete** backend landed — recursive delete over SFTP, an
  SFTP-root guard, and `DELETE /machines/{name}/files` — and the typed-confirmation gate is its frontend half.
  **Upload** landed whole, backend and frontend: files from the browser into a machine's directory, streamed,
  present-only, with a name already taken refused as a **conflict** and replaced only on the operator's yes.
  See **Delivered in slice 5** below. **Rename/move** and **new folder** remain, as does **folder upload**,
  deliberately deferred.)*

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
- **Two SSH-side browse failures now map to actionable responses instead of a generic `500`** — same "never
  answer 'I can't reach that' with 'nothing there'" principle as the SFTP-root work. `GlobalExceptionHandler`
  gains two typed handlers: a machine with **no stored SSH credential** (`domain.NoHostCredentialException`,
  which now also carries the machine name) → **`424 Failed Dependency`** `ApiError(code=NO_CREDENTIAL)`, the
  machine name in `detail` so the browser can offer the fix for that exact machine and the message says what to
  do ("No SSH credential is stored for … — add one to browse its files"); a stored credential the **host
  rejects** (`domain.SshAuthException`) → **`502 Bad Gateway`** `ApiError(code=SSH_AUTH_FAILED)`, like an
  unreadable disk (§6.20, `DiskUnreadableException` → 502) and never a generic `500`. The raw auth message can
  carry the SSH `user@host`, so it is logged server-side but **never returned** — the operator is told to check
  the credential Vaier holds instead. `ApiError` gains a three-arg `of(code, message, detail)` factory to carry
  the machine name.
- **A machine whose SSH server has no SFTP subsystem now says so ✅ (2026-07-29, closes
  [#344](https://github.com/getvaier/vaier/issues/344)).** The third and last browse failure that dead-ended on
  the generic `500`, and the one that hid best: SSH works completely — shell, `df`, the Docker scrape — and only
  the SFTP channel dies during subsystem init, so the machine looks healthy from everywhere except its files.
  **A fleet fact, not one machine:** both DietPi **Roon** endpoints (`192.168.3.104` Roon kjøkken,
  `192.168.3.106` Roon loftstue) run Dropbear, which serves SFTP only when an external `sftp-server` binary is
  present and a minimal install has none — so both were silently unbrowsable, kjøkken since the day it was added.
  New domain type `NoSftpSubsystemException`, shaped like `NoHostCredentialException` (it carries the machine so
  a handler can name it) and owning the **decision**: `isSubsystemRefusal(rootMessage)` is a static domain
  predicate, deliberately narrow — it recognises MINA's `Closing while await init message`, the shape a channel
  closed during subsystem init takes, and nothing that could equally be a refused connection, a timeout or a
  rejected credential, because sending an operator to install a package on a machine that is merely asleep is a
  worse answer than an imprecise one. `MinaSftpAdapter.translate` **asks** the predicate rather than
  string-matching (its `SSH_FX_NO_SUCH_FILE` → `NotFoundException` and `SSH_FX_PERMISSION_DENIED` →
  `PermissionDeniedException` branches are unchanged); it names the machine by its SSH host, which is all an
  `SshTarget` carries. → **`502 Bad Gateway`** `ApiError(code=NO_SFTP_SUBSYSTEM)`, machine in `detail`, message
  naming the machine *and the action*: install `openssh-sftp-server`, or switch it to OpenSSH.
- **`SshConnectException` and `HostKeyMismatchException` are mapped too ✅ (2026-07-29, #344).** The actual
  reported defect: `SshConnectException` was never registered, so **every** SSH transport failure — refused,
  unreachable, timed out — reached the browser as "an unexpected error occurred". → **`502`**
  `ApiError(code=SSH_UNREACHABLE)` returning `e.getMessage()`, like `DiskUnreadableException`'s handler: the
  message names a host and a path the operator is already looking at, so there is nothing in it they do not
  know (deliberately unlike `SshAuthException`, whose raw text can carry the SSH user and is withheld). A
  sibling audit of every `net.vaier.domain` exception against the registered handlers found one more of the
  same defect: `HostKeyMismatchException`, handled on the terminal's own socket but falling to the catch-all on
  every REST path that reaches a machine — hiding a rebuilt host or a man in the middle behind a generic `500`.
  → **`502`** `ApiError(code=HOST_KEY_MISMATCH)` carrying the domain's own message, which names the machine,
  both fingerprints and the way out. No frontend change was needed: `explorer-listing.js` already passes the
  envelope's `message` through verbatim, so the new sentences reach the rail's hover title and the Inspector on
  their own. *(Extended by §6.31: the handler now also fills `detail` with the two fingerprints as data, and
  the Explorer grew the action the message had only been naming.)*
- **A refused SSH connect now says exactly that ✅ (2026-07-29, #344).** Uninstalling a machine's SSH server
  entirely reads as a plain `SshConnectException` ("Connection refused") unless the domain is asked — the
  same shape as a network fault, which sends the operator hunting for one that is not there. Found live on
  **Roon kjøkken** (`192.168.3.104`), whose SSH server had been deliberately removed. New domain type
  `NoSshServerException`, shaped like `NoSftpSubsystemException`: a static predicate,
  `isNoServerListening(rootMessage)`, recognises "Connection refused" specifically — narrow and
  high-confidence, never a timeout or "no route to host" (either of which could just mean the machine is
  asleep) — thrown from `SshConnector.establish`, the one shared connect path both the Explorer's SFTP client
  and the web terminal go through. → **`502`** `ApiError(code=NO_SSH_SERVER)`, machine in `detail`, message
  naming the machine and the remedy: install and start Dropbear or OpenSSH. §6.25 builds on this predicate to
  stop the Explorer from offering SSH-dependent controls on a machine already known to have none, rather than
  only failing reactively after the click.

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
- **Open a viewable file in the browser ✅.** `GET /machines/{machine}/files/view` (`path`, optional `at`) —
  a **second** endpoint, never a mode of `/files/download`, which still always answers `attachment` +
  `application/octet-stream` for everything. Whether a file is **viewable**, the media type it is served as,
  and the `Content-Security-Policy` it is served under are all `domain.ViewableFile`'s decision: a strict,
  case-insensitive extension allowlist (images, PDF, common audio/video, and text-ish files all collapsed to
  `text/plain`), with markup a browser can run script from — `html`, `htm`, `xhtml`, `mhtml`, `svg` and
  relatives — refused outright, because an inline response is served on Vaier's own origin against the
  operator's signed-in session. A directory is never viewable. Asking to view anything else is a `400`
  carrying the domain's sentence, never a silent fallback to serving it inline. The response is
  `Content-Disposition: inline`, `X-Content-Type-Options: nosniff` and the domain's CSP — sandboxed for
  everything, except a PDF, where the browsers' own viewers cannot load under `sandbox`'s opaque origin, so it
  keeps the tightest policy that still renders (`default-src 'none'; object-src 'self'; frame-src 'self'`).
  `at` is allowed: a view is a read. The listing carries a per-entry `viewable` flag so the Explorer renders a
  viewable file's name as a link (`target="_blank"`, `rel="noopener noreferrer"`) without holding a second
  copy of the allowlist — a security boundary duplicated in the browser is a boundary that drifts. The
  Download button stays on every row.
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

**Delivered in slice 5 (backend + frontend — upload):**
- **Upload is a download run backwards, and its own thin path.** One file from the operator's browser into a
  directory on a machine — the only Explorer operation whose bytes begin outside the fleet. It deliberately
  does **not** reuse the **Transfer** machinery: a Transfer carries a coordinate between two machines and
  therefore has a source machine, a point in time, and a no-op-onto-its-own-coordinate rule, all invariants it
  documents carefully; an upload has none of them, and forcing it in would mean nulling out half the record and
  weakening what the other half promises. Present-only, like every write — a machine's past is a read-only
  **mounted archive**, so there is no `at` and nowhere in it to put a file. **Files only**: uploading a folder
  means recreating a tree on the far side, a different operation with its own failure modes, deliberately
  deferred.
- **A name already taken is a conflict, never a silent replacement.** `Upload` asks the machine what is at the
  destination before a byte is sent. Nothing there → write. A file there → `ConflictException` (a `409`
  carrying the domain's own sentence), unless the upload was made with `overwrite`, which is the operator
  having been asked and having said yes. A **folder** there → a conflict no overwrite settles, because
  replacing a directory with a file would mean deleting it and everything inside.
- **`Upload` is a domain value object** owning every decision: the destination is a `FileEntry.childPath` of
  the normalised directory (so a multipart filename that is not a single path segment cannot escape the folder,
  however the part was written by hand), the existence check and its verdict, and the jail down-mapping. It is
  a domain method that **receives the driven port and calls it** (`writeTo(target, root, files, content)`); the
  service only resolves the machine and its `SftpRoot` and passes them in. `FileEntry.childPath` /
  `requireValidName` are now the single shared copy of the "a child's path is built this way" rule, which
  `FileEntry.in` also uses.
- **`ForBrowsingRemoteFiles` gains `upload`** (translation-only on `MinaSftpAdapter`, same error mapping as
  `list`): the mirror of `download`, piping the caller's `InputStream` into an SFTP write stream with the same
  fixed 64 KiB buffer the relay uses, so memory stays flat however large the file is. The write creates or
  truncates, so a shorter file over a longer one leaves no tail behind. The adapter asks nothing about whether
  replacing was allowed — that decision is already taken.
- **`UploadFileUseCase` on `ExplorerService`** (a new narrow use case on the existing Explorer domain service —
  no new service), and **`POST /machines/{machine}/files/upload?path=…&overwrite=…`** on
  `ExplorerRestController`: multipart, **one file per request** so each gets its own progress and its own
  answer, which is what makes a per-file "Replace it?" possible at all. `MultipartFile.getInputStream()` goes
  straight to the use case — the bytes are never read whole into Vaier's heap. Admin-authed like the rest of
  the Explorer; no whitelist or auth chain was touched. Spring's multipart limits are raised in
  `application.yml` (2 GB per file and per request, `resolve-lazily: true`) — the defaults are 1 MB/10 MB and
  would refuse almost anything worth putting on a fleet machine.
- **One bar above a listing, not three (the pane-head merge).** The pane head, the selection toolbar
  (`.ex-selbar`) and the paste bar (`.ex-pastebar`) were **the same row shape doing the same job three times**
  — label on the left, actions on the right — and stacked they buried the listing under a pile of bars, with
  Refresh holding a whole row open by itself most of the time. They are now **one** row: the directory pane's
  head carries every verb that applies right now, in one `.ex-pane-actions` group. Both body bars are deleted
  outright, DOM and stylesheet alike. *(Operator's words: "Can we manage with only ONE topbar" and "the
  refresh button is a bit lonely there occupying a bar".)*
  - **Left — the subtitle carries state, the title never moves.** `directorySubtitle` answers "how much is
    here" (`24 items`) and, while anything is ticked, the more urgent version of the same fact
    (`2 selected · 3 machines`). The **title stays the machine name** throughout: it is the pane's identity and
    where the operator is standing, and swapping it for a count would move the one label that must not move.
  - **Right — a stable order.** `actions.append(selectionVerbs(), pasteVerb(…), folderVerbs(…))`. The group
    hugs the right edge, so the contextual verbs grow it *leftward* and **Refresh and Upload stay anchored** —
    the two controls reached for idly never shift under the pointer as a selection or a Clipboard comes and
    goes. One hairline (`.ex-selverbs`'s own right border, so no JS restates the condition) splits what you
    have *picked* from what acts on *this folder*.
  - **Paste says what it does.** The old bar's "Clipboard: 3 items" label plus "Paste here" button becomes one
    `Paste 3 items` button — in a shared row the label has no room and "here" has no referent. What is *on*
    the Clipboard is still listed in the tray, which is where it belongs.
  - **The time rail keeps its own row**, deliberately: it is an axis, not a verb — it is scrubbed, so it needs
    the width — and it exists only on a machine that has archives.
  - **On a phone, the merged group floats.** It has strictly more to fit than the old three bars did, so the
    head and the group both `flex-wrap` and the verbs take the roomier ~44px touch padding. While something is
    ticked the group leaves the scrolling flow for a fixed bar at the foot of the screen — the same element in
    the same order, only repositioned — preserving the thumb-reachable selection bar the phone layout already
    had, since the head can be scrolled away and the rows gave their own verbs up to it.
- **Frontend (`explorer-shell.{js,css}`).** Two affordances, split by whether they need to be permanent. The
  always-available one is an **Upload icon in the directory pane's head, beside Refresh** (`renderUploadAction`,
  the same `ex-iconbtn` shape), which costs the listing no vertical room. The drop affordance is a **dropzone
  overlaying the whole pane body** (`renderDropzone`), drawn hidden and lit only while a drag *carrying files*
  is actually overhead. *(It first shipped as a permanent dashed bar above the listing and the operator
  rejected it on sight — "there are now 3 bars at the top". An
  overlay rather than a tray that slides in, because the pane body **is** the drop target so lighting exactly
  it is the honest statement, and because a tray would push the listing down at the instant a file is held over
  the rows being aimed at.)* The **whole body** takes the drop — a file let go over the listing must upload,
  not make the browser open it and navigate the tab off the Explorer — with the overlay `pointer-events: none`
  so it never intercepts the drop it advertises. Files are sent one at a time and
  each appears in the **tray** in a Transfer row's own clothes, with a determinate progress bar. Progress comes
  from the browser's own send events (XHR `upload.onprogress`) — the one operation where the browser is the
  thing doing the work, so its progress is its own to report; **nothing polls**, and there is no endpoint that
  could be asked. A `409` opens the Replace question carrying the server's sentence, and only a yes re-sends
  with `overwrite` — once, so a folder in the way is reported rather than asked about twice. A landed upload
  re-reads the directory. Guarded by asset-invariant tests in `ExplorerShellTest`, as the shell's other
  invariants are.

**Delivered in slice 4 (backend — select and back up):**
- **The operator makes the decision; the backend absorbs the machinery.** The whole flow an operator sees is
  *select files, click Back up*. Everything under it — the machine's **backup repository**, its **backup job**,
  the encrypting passphrase, path normalization, coverage — is the backend's problem, hidden behind one call
  per direction. This is the design direction for Vaier generally: shrink what the operator has to hold in
  their head, not the code.
- **`domain.SourcePaths`** — a new value object: the normalized **protected paths** of a job, and the home of
  the containment decisions. Normalization is a *minimal cover* — no path is a descendant of another (an
  ancestor covers its children), exact duplicates collapse, paths are trimmed, blanks ignored, a non-absolute
  path refused (a source path goes verbatim into borg's `create`), a trailing slash stripped. `protecting(paths)`
  / `without(paths)` return new normalized sets (removing a path also drops any descendant of it), and
  `protectsWithin(path)` answers "would removing this drop anything?". `covers(path)`
  is true when the path equals or sits under a source path ("this path is **backed up**") — the same containment
  the minimal cover uses to drop redundant descendants; `enclosesUnder(path)` is true when a source path is a
  *strict* descendant of the given path ("this folder is not itself backed up but **contains backed up**"). Both
  are the domain's, unit-tested, and computed once server-side — never re-implemented in the browser. The
  coverage verdict an entry is marked with is now **`domain.ProtectedPaths`** — source paths *minus*
  **excluded paths** (see the *stop backing up really stops it* fix below).
- **The file listing gained two booleans.** `GET /machines/{machine}/files` entries now carry `backedUp`
  (`protectedPaths.isBackedUp(entry.path)`) and `containsBackedUp` (`protectedPaths.containsBackedUp(entry.path)`,
  mutually exclusive by construction in the domain — full shield vs half shield). They are **present-only**: a past (archived)
  listing marks nothing, because an old archive's shape is not today's protection. The read side gets the verdict
  through a new driven port **`ForReadingProtectedPaths`** (`protectedPathsFor(machineName)` → `ProtectedPaths`,
  implemented by `BackupJobProtectedPathsAdapter` over the backup-job store — reading each job's source paths
  **and** its excludes), so `ExplorerService` depends on it and **not** on
  any backup use case — the hex boundary holds, and a machine with no job simply protects the empty set.
- **`ProtectMachinePathsUseCase` on `BackupService`** (a new narrow use case on the existing backup domain
  service — no new service). `protect(machine, paths)` get-or-creates the machine's repository (name =
  `BackupRepository.sanitizedName(machine)`, on the fleet's single **backup server**, `appendOnly` false,
  passphrase minted by the new `Passphrases.strong()` — 32 alphanumeric chars from `SecureRandom`, **never taken
  from the client**) and its job (retention 7/4/6, `zstd,6`, enabled), then folds the posted paths into the job's
  protected set. **Reuse must win over regenerate ✅** — `createRepository` is now name-keyed idempotent
  (`repositories.getByName(name).orElseGet(...)`): only a truly-new name gets a fresh passphrase, and an existing
  repository is **reused as-is**. Minting a fresh passphrase over a live repository orphans it — the passphrase
  seals the borg repo on the NAS and borg cannot adopt a new one, so every backup then fails to authenticate — and
  a name/slug lookup miss (the legacy-name drop this fix pairs with, §6.19) was exactly the path that clobbered a
  live secret. `unprotect(machine, paths)` stops backing them up — see the fix below for how — deleting the job (repository
  kept) when its last path goes. `BackupJob.withSourcePaths` carries every other field through unchanged.
- **Endpoints.** `POST /machines/{machine}/backup/paths` `{"paths":[…]}` → `200` with the updated job (and, on a
  machine's **first** back-up only, a nullable `provisioning` object — see below); `404`
  when the machine is unknown; `409` (`ConflictException` → `ApiError`, *"Designate a backup server before backing
  up machines."*) when no backup server is designated yet, since a repository has nowhere to live without one.
  `DELETE /machines/{machine}/backup/paths` `{"paths":[…]}` → `200` with
  `{changed, stopped[], job}`, `204` when that removal emptied the job (deleted); `404` when the machine is
  unknown.
- **First back-up readies the host ✅** — a machine's **first** back-up now provisions its **backup client**
  automatically, so the operator never runs the guided provisioning wizard by hand. When
  `POST /machines/{machine}/backup/paths` creates a **new** job for a machine that had none, Vaier (1) trusts the
  machine's SSH key on the **backup server** (the same idempotent **Authorize host** work) and (2) launches the
  idempotent **Prepare client** borg-client install **detached**, its progress riding the existing
  `prepare-client-settled` SSE event on the `backups` topic. It runs **only** on first back-up — adding paths to a
  machine that already has a job never re-readies an already-provisioned host. A readying failure **never fails the
  back-up**: the paths are still saved and the reason is carried back to the caller. The decision is a **domain**
  rule (`BackupJob.readyClientHostForFirstBackup`), reached through a new driven port **`ForReadyingBackupClients`**
  (implemented by the rest layer's `BackupProvisioner`), so `BackupService` stays clear of the provisioning
  machinery and the hex boundary holds. The POST response gains a **nullable `provisioning`** object
  (`{started, scriptOnly, stagedScriptPath, message}`), populated only on that first back-up.
- **"Stop backing up" really stops it — and says so honestly ✅** — selecting a folder *inside* a protected
  path (e.g. `/home/openhab/userdata/logs` under a job protecting `/home`) and clicking **Stop backing up** used
  to do **nothing at all** while reporting *"Stopped backing up 1 item."*: removal only matched a stored source
  path or a descendant of one, so a path covered by an ancestor that stays protected matched nothing, the job was
  saved unchanged, and the `200` read as success. Two decisions, both now in the **domain**:
  - **`BackupJob.unprotecting(paths)`** answers each path on its own terms — a protected path (and everything
    under it) leaves the set; a path a *remaining* protected path still covers becomes an **excluded path** on the
    job (the `excludes` field borg already honours); a path that is neither changes nothing. It returns
    **`domain.Unprotection`** (`job`, `stopped[]`, `changed()`, `jobDeleted()`) — the honest account of what
    happened, so a no-op can never be reported as a removal. `BackupService.unprotect` only orchestrates: load,
    ask the job, then delete / save / touch nothing.
  - **`BackupJob.protecting(paths)`** clears every exclusion that conflicts with a freshly protected path (one it
    covers, *or* one that covers it), so *stop backing up X* → *back up X* leaves X genuinely protected instead of
    shielded on screen and silently skipped by every run.
  - **`domain.Excludes`** is the new value object for the holes: a *minimal cover* like `SourcePaths` (an
    exclusion already covered by another collapses, duplicates never accumulate), with `excluding` / `clearedFor`
    / `prunedTo` / `excludes(path)`. borg fnmatch patterns (`*.tmp`) set in the job editor are carried verbatim
    and never collapsed or pruned — a pattern is not a path. `domain.PathCoverage` holds the one containment rule
    `SourcePaths`, `Excludes` and `ProtectedPaths` all share.
  - **The Explorer's shield was the second half of the bug**: `backedUp` was computed from source paths alone, so
    an excluded folder would have kept a full shield and the fix would have looked broken. `ForReadingProtectedPaths`
    now returns **`ProtectedPaths`** (sources minus excludes) and the excluded folder — and everything under it —
    is marked not backed up.
  - **Honest end to end**: `DELETE /machines/{machine}/backup/paths` answers `{changed, stopped[], job}` (still
    `204` when the job itself went), and the Explorer's toast counts what the backend says **stopped**, saying
    *"Nothing changed — Vaier was not backing that up."* when nothing did. A job's Inspector also lists its
    excluded paths under **Not backed up**, beside what it protects.

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
  unprotected) is invisible to a separate Backups page. *(The backend for this landed in §6.20 slice 4:
  each browsed entry now carries a **backed up** / **contains backed up** flag, and a select-and-back-up
  call get-or-creates the machine's repository and job from a path selection.)*
- **One search.** One address space means one ⌘K over the whole fleet.

**Slices:**
- [x] **A — The shell ✅.** `explorer.html` is the tree shell: tree rail (fleet root → machines → `files`,
  `shell`), the **path** as the address bar, the **Inspector**, and ⌘K over every entry. The terminal dock
  moved in unchanged — the shell is the first Vaier page that is not a section inside an iframe.
  *(Superseded — the single-shell model: the Explorer no longer carries a `shell` tree entry and no longer
  embeds the terminal dock. A machine's shell opens from its **SSH access** section via an **Open shell**
  button into its own pop-out window (`terminal-window.js`), one window per machine reattaching to one
  persistent tmux session. `terminal-dock.js` survives only for `admin.html`. See "One shell model in the
  Explorer ✅" below.)* Machine
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
  **Containers were read-only, deliberately.** At the time of this slice, `DockerServiceRestController` was
  `@GetMapping`s only: Vaier had no endpoint to start, stop or restart a container, and none to fetch logs. So
  no container verb shipped — the Inspector showed image, version, state, ports, networks and container id,
  and offered nothing Vaier could not honour. A control that looks like a verb and does nothing is a lie about
  what works; a test pinned the absence (`noContainerVerbIsShipped_becauseNoEndpointBacksOne`, which still
  holds — start, stop, restart and logs remain unshipped). **One verb has since shipped: Update ([#352](https://github.com/getvaier/vaier/issues/352), §6.37).** It is `POST`, not `GET`, and it is the one exception to "containers are read-only" — everything else this paragraph says still stands.
  **A published service is one thing with three homes** — a container on a machine, a Traefik route, a DNS
  record — and the Inspector says so: DNS record + **DNS state**, route state, backend, **path prefix**, the
  backing container's image/version, **auth mode** and **allowed groups** (read from `/access/services`). Which
  machine a service is filed under is decided by the rule `vpn-peers.js` already uses (a **LAN service** by its
  LAN server, falling back to the relay peer; the hub's own routes on the **Vaier server**; everything else by
  host) — a second rule would file one service under two machines on two pages. **One verb ships**, because one
  is backed: **Unpublish** (`DELETE /published-services/{dnsName}`, path-prefix-aware), behind a confirmation,
  since it tears down a route and a DNS record. **Publishing was deliberately not in the tree** in slice C — it
  needed a subdomain / auth-mode / backend form and stayed on Infrastructure rather than being half-built here.
  *(Superseded: publishing is native in the tree now — see the "Infrastructure ported, and the page deleted ✅"
  slice.)*
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
  gives it a threshold of its own. See §6.9.)* The **disk forecast / runway is still not exposed** —
  `RemoteDiskForecastTracker` needs a *history* of samples and a single on-demand reading cannot produce a
  trend. That history was private state inside the scheduled watcher when this shipped; it is a persisted
  **disk-fill trend** now, so the endpoint is unblocked and merely unbuilt (see backlog).
  **The Infrastructure bridge stays — *for now*.** The epic optimistically said this slice retires
  `vpn-peers.html`. In slice C it did not, and a test pinned that: that page still owned machine creation, the
  LAN scan, the world map, SSH credentials, setup scripts, allowed groups and discovered candidates. Regressing
  function to make the tree look finished would have been the worst trade in the epic — the bridge goes when
  parity is real, not before. *(Superseded: parity was later reached and the bridge removed — `vpn-peers.html`
  is deleted and its function is native in the tree. See the "Infrastructure ported, and the page deleted ✅"
  slice below.)*
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
    relative "how long ago" is computed at paint, not on an interval. **The rail holds its own room ✅** — the
    archive list lands *after* the listing is already on screen, so a rail that materialised on arrival dropped a
    bar into the page and shoved the rows down mid-read. Whether a rail is coming is now settled at first paint by
    a cheaper question Vaier has already answered (`jobsOn(machine)` — the job list loads at boot): a backed-up
    machine draws the rail immediately in an `is-waiting` state (track and reading dimmed, no stops), and the stops
    fill into a track that is already there. A machine with no job still grows nothing.
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
  - **Idle mounts are swept off the fleet, and a leaked mount never blocks backups ✅.** A mount holds a FUSE mount
    and a borg process on a machine — and, critically, the **repository's read-lock**, so a mount left live blocks
    that machine's scheduled backups (borg's "Failed to create/acquire the lock … timeout"). `ArchiveMountWatcher`
    (`@Scheduled`, the fleet-backup watcher convention) periodically calls `ForMountingArchives.unmountIdle`, releasing
    every mount untouched beyond the idle window — a mount lives only as long as it is being browsed. Three fixes make
    that self-healing rather than best-effort. **(1) A failed unmount is retried, not forgotten.** `BorgCommand.umount`
    now re-probes with `mountpoint -q` and reports `UNMOUNTED` vs `STILL_MOUNTED` (a FUSE unmount routinely fails
    "Device or resource busy" while a handle is open, and `borg umount`'s `2>/dev/null` hid that); `parseUnmounted`
    reads the truth, and the adapter drops a mount from tracking **only when the release actually took** — a failed one
    stays tracked so the next sweep retries, where before it was forgotten regardless (exactly how a `borg mount`
    orphans and holds the lock forever). **(2) Graceful shutdown releases everything.** A `@PreDestroy releaseAll()`
    unmounts every tracked mount on a clean redeploy, so a restart does not strand a live mount. **(3) Orphans a hard
    kill left are adopted.** The in-memory registry does not survive a restart, so a mount live across one would never
    be swept; a slower second `@Scheduled` pass, `ForMountingArchives.reconcileMounts` (`BorgCommand.listArchiveMounts`
    /`parseArchiveMounts` asks each backed-up machine what is really mounted under its work dir), **adopts** any orphan
    the registry has forgotten — last-accessed now, so it gets a full idle window before the sweep releases it —
    re-entering it into the idle lifecycle. `fixedDelay` fires the first reconcile shortly after startup, so a
    restart's leftovers are caught promptly. Closes the "a future startup sweep could close that gap" TODO.
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
  as the "Wizards" note below anticipated. *(Superseded: the Backups page is deleted and the server operations
  — Provision, Authorize a host, Download setup script — are native on the `backup` entry now. See "The Backups
  page is gone — backups are fully native ✅" below.)*
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
  [#323](https://github.com/getvaier/vaier/issues/323). *(Superseded: `backups.html` is deleted — repositories
  live only in the tree now. See "The Backups page is gone" below.)*
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
  *(Superseded: `backups.html` is deleted; the guided-provisioning wizard's operations — Provision and Authorize
  a host — are native on the backup server's `backup` entry now. See "The Backups page is gone" below.)*
- [x] **Infrastructure ported, and the page deleted ✅.** The Infrastructure page's whole function moved into the
  tree as native entries/panes, and `vpn-peers.html` (+ `vpn-peers.js`, `vpn-peers-map.js`,
  `vpn-peers-helpers.js`, `vpn-peers.css`) was **deleted**. Ported: **machine editing** (an **Edit details**
  dialog on a machine's pane — rename, description, LAN address/CIDR for server peers, device category); the
  **SSH access** toggle (whether Vaier may open an SSH session, distinct from *storing* the credential — turning
  it off hides that machine's `files`/`shell`/`disk`); **setup scripts** (the show-once peer setup-script
  download in the new-config dialog, and a re-viewable LAN-host `setup.sh` dialog — curl one-liner + download);
  **Regenerate config** (#202 — rotate a peer's keypair by delete+recreate) and **Reissue** (same keypair), both
  under an **Advanced** fold on the machine pane; the **published-service editor** (auth mode public/social,
  allowed-group chips, launchpad display name + visibility, and an **Advanced** fold for root redirect, version
  endpoint/property, direct-LAN-URL); the **publish forms** for a discovered container and a by-hand LAN
  host:port (each gaining an **Advanced** fold — path prefix, root redirect, direct-URL); **Add Machine**
  including the **LAN server** type (LAN address, runs-Docker + port, device category); and the world **Map**
  (now a fleet-root entry). New UI pattern: a quiet **Advanced** progressive-disclosure fold (native
  `<details>`) that keeps rare/advanced controls out of sight. The `BRIDGES` array drops its Infrastructure
  entry — only **Backups** is left in it; **Users** and **Concepts** are the remaining bridged globals, and
  **Settings** is native. *(Backups later left the bridge too, when its page was deleted — see "The Backups page
  is gone" below; only **Users** and **Concepts** remain bridged.)* `admin.html`'s Infrastructure tab is removed; a stale `/admin.html#infrastructure`
  (and the `#services` / `#vpn` aliases) now redirects to `/explorer.html`; the launchpad's **Infrastructure**
  link points to `/explorer.html`; and `PeerSnapshot`'s peer-notification "Vaier UI" link points to
  `/explorer.html` instead of `/vpn-peers.html`. (Note: `vpn-peers` remains an SSE **topic** name on the event
  bus — unrelated to the deleted page — and is unchanged.) This closes the slice-C "the Infrastructure bridge
  stays" caveat and the "Publish from the tree" / "Retiring the Infrastructure bridge" backlog items below. Part
  of epic [#323](https://github.com/getvaier/vaier/issues/323).
- [x] **Capability glyphs, the Vaier server's containers, and a fleet-level Add machine ✅.** Four follow-ons
  after the Infrastructure port:
  - The **Vaier server** machine now reports **`runsDocker`** (`Machine.vaierServer`) — the box is itself the
    Docker engine hosting the whole compose stack — so the Explorer grows a **`containers`** entry for it and
    lists its own containers (the tree previously showed none, even though the update-available sweep and
    `DiscoverVaierServerContainersUseCase` already covered them, which was a quiet gap between the README claim
    and the tree). Its port stays null: Vaier reaches that engine over the local socket, not a TCP port.
  - Each machine row draws small **capability glyphs** just before its **status dot** — a relay glyph when it is
    a **relay peer**, a Docker glyph when it runs Docker, and a **safe** glyph when it is the fleet's **backup
    server** — ported from the retired Infrastructure page's machine cards, so the **capability strip** is back,
    now per-row in the tree rather than a card header. They run in the order reached → runs → keeps. A machine
    with none gets an empty strip so names still line up; the glyphs read as dim metadata that lift with the row
    on hover/selection. The backup-server glyph is deliberately neither the machine's **device shape** (the NAS
    wears `nas` because it *is* a NAS, and any machine can be designated) nor the **shield** (which says a thing
    is stored, the opposite of storing).
  - **"Repository" leaves the UI ✅.** It is a borg noun the operator never chose: Vaier creates exactly one
    per machine behind **Back up**, names it after the machine and generates its passphrase. So the Explorer
    shows a repository as *the machine whose backups are in it* (resolved through the job that targets it —
    `repoLabel`), the server's entry reads "Backups kept here", and path/append-only/passphrase fold under
    "Storage details". The **+ New repository** control is gone entirely (`newRepository` deleted): creating
    one by hand is exactly what once minted a second repository with a fresh passphrase over a live borg
    store and orphaned it, and there is now no normal path that needs it. Adopting a store Vaier did not
    create remains possible over the API. A repository no job targets keeps its own name and says nothing
    backs up to it any more — an adopted store, or a renamed machine's leftover (the fleet has one: the
    orphaned `NUC-02` job). *Open: designating a backup server still does not provision it — the operator
    must find and press Provision afterwards, which is the same shape of gap this entry closes.*
  - **A failed run names a fix that exists ✅.** `BackupRunner`'s borg pre-flight refused a run with "borg is
    not installed on X — run Prepare client", naming a button on `backups.html` — a page deleted when the
    Explorer absorbed it. The one failure an operator can fix was therefore reported by pointing at a control
    that existed nowhere, and the automatic path could not help either (preparation runs only on a machine's
    **first back-up**, so a machine with an existing job was stranded). Now `BackupRun.borgMissing` owns the
    wording and `BackupRun.needsClientReadying()` owns the verdict (a domain rule — the shell never
    pattern-matches an error string; `RunResponse` carries the flag). The machine's `backup` entry offers
    **Get this machine ready**, which POSTs the existing `…/prepare-client` route; where Vaier cannot gain
    root it keeps the staged `sudo bash …` line on the entry instead of flashing it in a toast, since it has
    to be retyped on another machine. No endpoint was opened — the route outlived the page that called it.
  - **Trouble is visible from the tree ✅.** A machine's `backup` entry carries a **status dot** coloured by that
    machine's job's last run — red for `FAILED` *and* `INCOMPLETE`, amber for `WARNING`, green for `SUCCESS`,
    grey for `RUNNING`/`UNKNOWN`/never-run — from the same `RUN_DOT` map the job pane uses, so the tree and the
    pane cannot disagree. `GET /backup-jobs` now carries `lastRunStatus` per job (composed at the driving edge
    from `GetBackupRunsUseCase.latestForJob`, one cheap lookup per job), so the dot is read off state the shell
    already loads at boot — painting the tree fires no request per row. Anything that learns a newer outcome
    (`loadJobRun`, an on-demand run, the `run-settled` push) writes it back to the job list, so the dot never
    shows last night's result all day. The backup server's own `backup` entry has no job behind it and grows no
    dot. *Still open: the same treatment for a machine's `disk` entry — it needs a fleet-wide read model, since
    `RemoteDiskWatcher` currently discards its readings and a per-machine `df` at paint time would be N SSH
    round trips.*
  - **Add machine** moved from the Explorer topbar to the **fleet root's** Inspector (the fleet page), since
    adding a machine is a fleet-level act rather than something floating over whatever path you happen to be
    standing on — matching the "creation is a persistent affordance … of the fleet" decision below.
  - Tree labels are now **one proportional font**: identifiers no longer render monospace, so a machine's
    siblings (`files`, `shell`, `containers`, `disk`) read as one list instead of two typefaces. Monospace
    stays in the Inspector title, where a path is the content.
  Part of epic [#323](https://github.com/getvaier/vaier/issues/323).
- [x] **The Backups page is gone — backups are fully native ✅.** The last backup bridge was retired:
  `backups.html`/`.js`/`.css` are **deleted**, and with them the final fleet-level iframe. Everything the page
  still held moved onto the backup server's `backup` **entry** in the Explorer, in a **Server operations**
  section — **Provision**, **Authorize a host**, and **Download setup script** — so the whole backup chain
  (server ▸ repository ▸ job, plus its operations) now lives in the tree. Provision is the one operation that
  awaits an outcome, and it still **never polls**: its dialog subscribes to the `backups` SSE topic and settles
  itself off the `provision-settled` event (`BackupProvisioner.pollInFlightProvisions`), reading the log tail
  once on the push. `BRIDGES` now holds only **Users** and **Concepts**; the launchpad's and `admin.html`'s
  **Backups** links are removed, and `/admin.html#backups` no longer resolves to a page. This **supersedes** the
  "the operational flow still lives on the Backups bridge" / "renders read-only" caveats on the backup-server,
  repository and job slices above. Part of epic [#323](https://github.com/getvaier/vaier/issues/323).
- [x] **Deterministic shell reattach ✅.** Opening a machine's shell no longer **scavenges** a random orphaned
  tmux session — the source of the "sometimes a fresh shell, sometimes an old one" surprise, where whether you
  landed in a new shell or an old one depended only on whether an orphan happened to be lying around. Each
  machine has one **stable primary** pane id (`VaierPanes.primary`, remembered per browser across reloads and
  redeploys), so **Open shell** (labelled *Open shell window* when this shipped; now in a machine's
  **SSH access** section) always returns to the very same session; **Duplicate** mints a *fresh*
  session in its own window when the operator deliberately wants a second shell on a machine. Ending the primary
  session forgets its id, so the next open mints a fresh primary rather than reattaching to a session the
  operator closed. Reattaching now happens solely through an explicit id — the primary, or a pane id carried in
  a window's own URL across a reload/pop-out — never by chance.
- [x] **Fleet-wide persistent selection, and download-as-one-zip ✅.** Ticking files in the Explorer now builds
  a **Selection** that survives every navigation — across folders and across machines — where it used to be
  per-directory and cleared on each move. The selection is fleet-wide (`N selected · M machines`, said by the
  directory pane's own subtitle since §6.20's one-bar merge) and its
  verbs (Copy, Download, Back up, Stop backing up, Delete) **fan out per machine** over the live items.
  **Download** hands the browser one file as itself and one folder as its own zip, but **two or more** items
  come down as a single `application/zip` streamed from `POST /machines/files/download-zip` (a hidden-form
  submit, so the browser streams straight to disk with nothing buffered in the tab); the backend and the
  `domain.Selection` that owns the zip-entry naming landed in §6.20 slice 2. Part of epic
  [#323](https://github.com/getvaier/vaier/issues/323).
- [x] **One shell model in the Explorer ✅.** The Explorer no longer embeds the bottom terminal dock: its
  markup, the `watchDock` wiring, and `terminal-dock.js` were removed from `explorer.html`/`explorer-shell.js`
  (`terminal-dock.js` stays in the tree only because `admin.html` still uses it). A machine's shell is no
  longer a `shell` tree entry either — the machine's Inspector grows an **SSH access** section with an **Open
  shell** button beside its SSH credential, and clicking it opens the shell in its **own pop-out window**
  (`terminal-window.js`), one window per machine reattaching to that machine's one **primary shell**
  (`VaierPanes.primary`). Closing the window keeps the tmux session alive — it even survives a Vaier restart,
  and reopening **reattaches** — so only the window's **Exit shell** button (renamed from *End shell*; still the
  `end-shell` control frame → `EndTerminalSessionUseCase`) stops it. Part of epic
  [#323](https://github.com/getvaier/vaier/issues/323).
- [x] **Launchpad console nav is just Explorer ✅.** The launchpad admin nav (`launchpad.html`, rendered from
  `/users/me`) dropped its Infrastructure, Users, and Settings links — the whole console is the Explorer now,
  with Users and Settings as entries inside it — so an admin viewer gets a single **Explorer** link. `admin.html`
  also dropped its **Backups** top-nav button. Part of epic [#323](https://github.com/getvaier/vaier/issues/323).
- [x] **LAN servers vs WireGuard peers on the machine Inspector ✅.** A **LAN server** is not on the WireGuard
  mesh (it sits on a relay's LAN, reached through the relay), so its Inspector shows LAN address + **Last seen**
  + Docker + device category, and *not* the mesh-only facts (tunnel address, endpoint, latest handshake,
  transfer) — showing those as blanks would falsely imply a tunnel that is merely down. A WireGuard peer still
  shows the mesh facts. `Last seen` (LAN server) and `Latest handshake` (peer) are both raw Unix epoch seconds
  off the wire and are now rendered as a human "… ago" (`agoFromEpochSeconds`). Part of epic
  [#323](https://github.com/getvaier/vaier/issues/323).
- [x] **The Explorer has an address, and the tree stops being the way around ✅.** Five changes that only make
  sense together, all frontend (`explorer.html`, `explorer-shell.js`, `explorer-shell.css`) — no endpoint, no
  port, no Java.
  - **Where you are is the URL.** The location lived in a JS variable, so a reload dumped the operator back at
    the fleet root and no folder, machine or archive could be linked to at all — in a tool whose whole premise
    is that everything in the fleet has one coordinate. The **Explorer address** is now the only place the
    location lives: `#/fleet/<machineId>/files/home/ubuntu`, with the **stop** being read carried as
    `?at=<archiveId>`, and `S.path`/`S.at` are read back out of it rather than set behind its back. Reload,
    Back, Forward, bookmark and open-in-new-tab all work. Arriving deep — a pasted link, a reload three folders
    down — opens the branches above you and reads the path chain before the first paint, so the link paints
    where it says instead of landing on the fleet and jumping. A **hash**, deliberately, and not the History
    API: the shell is a static file, so `/explorer/fleet/<id>/files/home` would 404 unless Vaier grew a
    catch-all forward — new backend surface inside the forward-auth chain, bought for nothing the hash does not
    already give. Segments are percent-encoded, so a file called `report Q1?.pdf` survives the round trip.
    Navigating to the address you are already on repaints and leaves history alone (a Back that walks through
    the same folder five times is a Back that is broken), and a visitor who arrives with no address at all has
    a normalised one written into the bar on arrival, so the first Back is not a step out of the app.
  - **The rail is optional on a desktop and gone on a phone.** The tree was a slide-over **drawer** behind a
    hamburger on narrow screens; the drawer, its scrim and the hamburger are **deleted**. Every row in it named
    something the pane behind it already listed, with less room for a thumb. On a wide screen the **rail** now
    folds away from a topbar toggle and stays folded — a private preference in `localStorage`
    (`vaier.explorer.tree`), shown by default, and deliberately **not** in the address: a link says where to
    stand, not how someone likes their own window arranged. Navigation is now the pane's cards and listings to
    drill down, the **address bar**'s crumbs to go back up, and ⌘K to go sideways.
  - **The tree's ambience was rehomed first — that is what made folding it away cost nothing.** What the rail
    alone carried was the state of machines nobody is looking at, so the fleet listing's machine cards grew
    **machine marks**: the **capability strip**, the last **backup run**'s outcome, and a count of that
    machine's containers that are **update available**. The backup outcome is a **tinted archive glyph, not a
    second dot** — the rail could hang a bare coloured dot off a `backup` child row because the row said
    "backup" beside it, but on a card that same dot would sit next to the liveness dot with nothing to tell
    them apart, and a green dot that might mean either is worse than none. The marks stand down in the past,
    like the liveness dots, since a registry verdict and a tunnel are about now. The **Map** also moved into
    the fleet's own listing, in a section of its own (it is a different way of looking at the same machines,
    not another machine); it had been reachable only from the rail.
  - **Vaier's own entries left the tree for a topbar Vaier menu.** **Settings**, **Users**, **Security** and
    **Concepts** were a second root in the rail, which made it a forest and made its "Fleet" label a half-truth.
    They are now the **Vaier menu** in the chrome — the one surface that survives the rail being folded away or
    absent — alongside a **Fleet** entry back to the fleet root, which the menu has to carry: a global's crumb
    bar is one segment long ("Settings" is not inside anything), so without it, standing on Settings with no
    rail left no way back to the fleet at all. The rail is now exactly what it is labelled.
  - **⌘K matched identities, not names ✅ (a live defect).** The palette's index kept only the address path, so
    it matched and displayed `/fleet/7a6d0e35-…/files` and typing a machine's **name** found *nothing* — the
    §6.22 machine-id refactor's shadow, invisible while the rail was there to carry you. The index now holds
    both the addressed path and the **human-readable** one (they differ at exactly one segment: a machine is
    addressed by identity and read by name) and matches and displays the readable one. It also indexes the
    **Vaier menu**'s entries, which matters more now they are behind a menu, and reads each row's icon off the
    last path segment rather than `path[1]`, which had given every Vaier entry the fallback file glyph.
  Part of epic [#323](https://github.com/getvaier/vaier/issues/323).
- [ ] **E — The rest.** DNS, access (Users), Concepts. `admin.html` and the last iframes are deleted, and the
  bridge with them.

**Backlog (deliberately deferred out of slice C):**
- **Container control endpoints** — start / stop / restart a container, and read its logs. Vaier still has
  none of these: the Docker adapters read, and the **docker socket proxy**'s allowlist is what stands between
  Vaier and a fleet-wide remote-exec surface. This is its own change with its own security thinking (who may
  restart what, whether it goes through the socket proxy or the machine's SSH, what an audit trail looks like)
  — not a button bolted onto the Inspector. Until it exists, the Inspector says plainly that the machine's
  **shell** is where that is done. (**Update**, [#352](https://github.com/getvaier/vaier/issues/352), §6.37,
  is a different verb that has since shipped — it recreates a container via `compose` over SSH, never through
  the socket proxy, and is not a precedent for start/stop/restart/logs.)
- ~~**Publish from the tree.**~~ ✅ **Done** — publishing is native in the tree now: a machine's
  discovered-but-unpublished containers appear as **+ Publish** rows and a relay-anchored LAN server offers a
  by-hand **Publish LAN port** form, each with a subdomain / **auth mode** / **path prefix** / backend / root
  redirect (behind an **Advanced** fold). Shipped in the "Infrastructure ported, and the page deleted ✅" slice.
- **The disk forecast in the Inspector.** `GET /machines/{machine}/disk` reports level, not trend: the
  **runway** and fill rate come from `RemoteDiskForecastTracker`, which needs a *history* of samples that a
  single on-demand `df` cannot produce. The design decision this item was waiting on — where that history
  lives — **has since been taken**: the forecast rebuild persists it as a **disk-fill trend** in
  `disk-fill-trend.yml` behind `ForPersistingDiskFillTrends`. So this is now a read model and an endpoint,
  not an open question; it was out of scope of that rebuild and remains unbuilt. Folds together with the
  host-monitoring backlog item in §6.9.
- ~~**Retiring the Infrastructure bridge.**~~ ✅ **Done** — parity was reached and the bridge removed: machine
  creation, the **LAN scanner**, the map, **host credentials**, setup scripts, **allowed groups**, and the
  discovered-candidate → publish flow are all native in the tree, and `vpn-peers.html` is deleted. Shipped in
  the "Infrastructure ported, and the page deleted ✅" slice. (The remaining bridges are **Backups**, **Users**
  and **Concepts** — slice E.)
- ~~**Retiring the desktop rail as well**~~ ✅ **Done (2026-09-07).** The rail is deleted outright — markup,
  script and stylesheet. The argument that took it off the phone, followed all the way: it drew a **second
  view of what the Inspector already lists**, and everything it alone carried had already moved onto the
  fleet's **machine cards** as **machine marks**, so removing it deletes a surface, not information. The
  comparing-two-machines case it was kept for turned out not to be missed. What went with it: the topbar fold
  toggle and its remembered `vaier.explorer.tree` preference, the expanded-set the fold needed (opening a
  **directory** was always driven by *selection*, never by expanding, so lazy SFTP reads are untouched), the
  per-row capability glyphs and the **backup entry dot** — both already said on the card. The **Inspector**
  now takes the whole width at every width, which is what the phone already had, so there is one layout
  instead of a wide-screen one with a phone exception. `.ex-main` is a single column; the `--rail` colour
  token stays, spent on the **time rail**'s gradient. Trouble is now worn by the machine's own **name** on
  its card and in its **pane title** — the two surfaces that survive — rather than by a red dot in a row.
  Navigation is the Inspector's cards and listings to drill in, the **address bar**'s crumbs to come back up,
  and ⌘K to go sideways.
- ~~**The rest of the tree's ambience on a card.**~~ ✅ **Done** — the gap was a machine's **disk**: **remote
  disk pressure** is the thing an operator most wants to see without opening anything, and it was absent from
  both the rail and the card because `RemoteDiskWatcher` discarded its readings and a per-machine `df` at paint
  time would be N SSH round trips. The watcher now **retains** what it already read as a **machine disk
  standing**, the fleet reads all of them in one memory-backed request, and the **machine marks** carry a
  tinted disk mark — with no mark at all for a machine the sweep has not reached. See "Disk pressure on the
  fleet's machine cards ✅" in §6.9. (The **disk-fill forecast** — **runway** and fill rate — is still not on a
  card or in the Inspector, but no longer for want of the sample *history*: that is persisted as a
  **disk-fill trend** now. It is simply unbuilt — the item above.)

**Decided up front — where the tree does not fit:**
- **Wizards.** Fleet backup is a guided flow; a tree cannot teach. It stays a flow, rendered as the Inspector
  for a machine's backup entry.
- **The map.** Peer geography is not hierarchical: it becomes a view of the fleet root.
- **The launchpad stays a separate page** — different audience, unauthenticated, different job.
- **Creation.** "New machine" is not an entry until it exists; it is a persistent affordance at the end of the
  fleet.

---

### 6.22 Machine identity ✅

**Why.** Vaier had no machine registry. It had three — WireGuard config directories, `lan-servers.yml`,
and a flag in the Vaier config — unified only by a read projection that identified machines by their
**name**. A name is a label an operator edits, so editing one orphaned everything keyed to it. The live
fleet already showed the damage: `Colina-27` had no stored name at all, so `"Colina 27"` was re-derived at
read time from a directory name, and three config files used that derived string as their primary key.

**The fix.** Every machine carries a `MachineId` — an opaque generated UUID, deliberately not derived from
the name, the address, or the machine kind. Identity is *read, never minted*: a machine whose stored id is
missing or malformed does not load, rather than coming back as a stranger to its own records.

#### Done ✅

- `domain.MachineId` — UUID value type. Validation is by pattern, **not** `UUID.fromString`, which accepts
  `"1-1-1-1-1"` and silently expands it into a different valid-looking UUID.
- Identity on all three machine kinds: peer `# VAIER:` metadata, `lan-servers.yml`, `vaier-config.yml`
  (`vaierServerMachineId`, assigned on first use). `Machine.id` is non-null.
- Credential vault + host-key store keyed by `MachineId`. **Both copies of `migrateSshState` deleted**,
  along with `HostCredential.reKeyedTo` — the code that existed only to compensate for name-keying.
- `SshTarget` carries its own `machineId`; `pinOnFirstUse` no longer takes a name that could disagree with it.
- Disk watches keyed by `MachineId`, with the file adapter refusing an unreadable id **loudly** (its
  fallback is watched-at-the-global-threshold, so a silent miss reverts a disk to a threshold nobody chose).
- `GET /machines` and `/machines/vaier-server` expose `id`.
- `ForResolvingMachineIds` + `MachineIdRegistryAdapter` — the single name↔id seam, in both directions
  (`idForName` for callers holding what an operator typed, `nameForId` for callers holding an identity and
  needing something to show a person). **Scaffolding**; see below.
- **Backup group.** `BackupJob`/`BackupRun`/`BackupServer` hold a `MachineId`; the three file adapters store
  `machineId:` and skip an unreadable one **loudly**, because a job that quietly fails to load is a machine
  that silently stops being backed up. `BackupWorkDirResolver`'s `$HOME` cache is keyed by identity, and
  `BackupJobProtectedPathsAdapter` crosses name→id through the one seam. The provisioning use cases
  (`checkBorg`, `checkNas`, `checkServerAuth`, `checkRootBorg`, `initRepo`, `prepareClient`,
  `authorizeClient`, `readyForBackup`) all take a `MachineId`, and `prepareRunId` derives the on-host
  `.rc`/`.log` filenames from it, so a rename mid-install cannot strand a run nobody can find again.
  - **A machine's name is presentation, so it is passed in, never held.** `BackupRun.failureSubject/Body`,
    `BackupServer.downBody/recoveryBody`, `BackupRun.borgMissing` and `RecoverySheet.render` take a display
    label from the caller that has already resolved the machine. A UUID in an inbox — or on the survival kit
    an operator reads after losing their fleet — would be the one thing that made it useless.
  - **SFTP root cache.** `CachingSftpRootAdapter` remembers a machine's jail under its `MachineId`, taken
    from the `SshTarget` that reached it. Name-keying here was the dangerous kind: a name is reusable, so one
    machine's jail could be served to another that later took its name — silently rewriting every path Vaier
    shows for it, in both directions. A target with no identity (a pre-registration credential test against a
    bare address) is probed each time rather than cached against a null. `ForResolvingSftpRoots`' javadoc had
    said outright that "Vaier's canonical identity for a machine is its name"; it no longer does.
  - **REST DTOs carry both.** Job/run/server responses expose `machineId` *and* a resolved `machineName`
    (null once the machine has left the fleet), and requests still accept a `machineName`, resolved at the
    controller. That keeps this slice backend-only — the browser is entirely name-keyed and flips in the
    driving-edge slice, for which the `machineId` field is already in place. An unknown machine name on a
    write is now a `404`, which is the door the two dead `NUC 02`/`NUC02` jobs walked through.

- **The SSH path, and then everything else.** `ForResolvingSshTargets.resolve` and
  `RunRemoteCommandUseCase.run` take a `MachineId`; `SshAddress.of` matches stored peers and LAN servers
  on their id, and the Vaier server recognises **itself** by the id in its own config rather than by
  comparing against `LanAnchor.VAIER_SERVER_NAME`. `BackupWorkDirResolver`'s `machineName` parameter died
  as planned. Then credentials, the web terminal, disk usage and watches, host-key clearing and the
  SSH-access toggle followed, along with `/machines/{machineId}/...` and the terminal WebSocket — both
  parse the segment through `MachineId.of`, so a name there closes as unknown rather than being looked up.
  `setMachineSshAccess` searched three stores by name and identified the Vaier server by string
  comparison; it matches on identity in all three now.
- **`ForResolvingVaierServerIdentity`.** The Vaier server's id was read in one layer and minted in
  another, so whether Vaier could reach *itself* over SSH depended on whether something had already loaded
  the Machines page. One port owns read-and-assign-once now, and assigning is confined to it.
- **Three defects found by doing this**, each a lookup that changed meaning when it stopped being by name:
  `reconcileMounts` skipped any machine whose name would not resolve, stranding a `borg mount` on the
  repository lock so the next backup failed with a lock timeout; the nudges endpoint took an id but still
  matched with `hasSameName`, so it would have 404'd on every machine; and `ownerUserFor` began probing
  the vault for a backup server whose machine had left the fleet, where the name lookup used to
  short-circuit — an orphaned credential must not name the owner of a machine that is gone.

- **A fourth defect, found by using it (fixed 2026-07-27).** `126038b` moved the browser to identities and
  updated the tree, the listing reader and the terminal **dock** — but not `terminal-window.js`, the
  pop-out shell window, which is where *every* "Open shell" goes since shells moved out of the dock
  (§6.21). It put the `?machine=` name straight into the socket path, and the handler — correctly —
  refuses to look a name up there, so every pop-out shell closed `4404` and the window rendered its
  literal "Machine not found." The id now travels in the URL from each of the three openers (the
  Explorer's Open shell, the dock's pop-out, the window's own Duplicate), and the name stays for what a
  name is for: the title, the header and the tooltips. A window opened without an id (a bookmark from
  before) resolves it from `/machines` once, writes it into its own URL, and says plainly when the name is
  no longer in the fleet. **Pane ids were still keyed by machine name** at this point — deliberately, since
  re-keying them naively re-mints every primary and strands the tmux sessions running; they moved to
  identity, with a migration, in the final slice below.

#### Done — the refactor is complete (2026-07-28)

Every machine record, every REST path, every read feed and the whole browser address a machine by its
`MachineId`. **Machine names need not be unique**, which was the point.

**Shell sessions travel by identity too ✅ (2026-07-28) — the last name-keyed thing in Vaier.** A shell is
a tmux session that deliberately outlives its WebSocket, and the browser is the only thing that can ever
end one, so `terminal-panes.js` owning those ids under a machine's *name* meant a rename lost every shell
open on that machine: fresh ids minted under the new name, and the old sessions left running on the host
with nothing to reach or kill them by. Two machines sharing a name would likewise have handed one
machine's session to the other. `claim`/`primary`/`adopt`/`release` key on the `MachineId` now, and the
pop-out window's OS-level window name does too — named by the display name, a rename opened a *second*
window onto a machine whose first window was holding the live session.

**Re-keying alone would have been the bug, not the fix**, which is why this waited for its own slice:
every session running at deploy time is filed under a name, so the first lookup by identity would find
nothing, mint a fresh id, and abandon a live session. `migrateLegacyName` moves a machine's entries across
the first time it is asked about — once, and never over what the identity already holds, because that is
the newer truth. The name is passed in for that and nothing else. A window opened from a pre-identity
bookmark claims its pane only *after* resolving its identity: claiming at parse time would file the
session under `null`, where every such window shares one bucket.

**One thing stays name-shaped, and it is not machinery:** the pre-§6.22 **shell bookmark** fallback
resolves a name when given no id, and **refuses to guess** between two matches rather than open a shell on
the wrong host.

   2i. **The audit, and the regression it caught ✅ (2026-07-28).** Sweeping for anything that still
   identifies a machine by its name turned up one **real regression that dropping the guard had created**:
   a machine's backup **job and repository are both named after it**, so two machines called "NAS" computed
   the same slug — and neither failure would have been visible. The second machine would have backed up
   into the *first* one's borg repository, mixing two machines' archives, and its job would have overwritten
   the first's in a store that upserts by job name, so **the first machine would silently stop being backed
   up**. `BackupRepository.freeName` now hands a new machine the first slug nobody has taken (`NAS`,
   `NAS-2`, …), consulted only when a slug is first minted — a machine that already has a job keeps it,
   because its job is found by identity, so no established repository is renamed. Also fixed: the map placed
   a LAN server on the Vaier server's dot by comparing its relay's *name* to `"Vaier server"`, which a peer
   an operator had since called that would have satisfied; it tests for the absence of a relay identity now.

   The sweep also closed the last naming lies, which is the class of bug that caused every defect in this
   refactor: `ForResolvingPeerNames.resolvePeerNameByIp` returned a peer **id**, and about twenty
   signatures, two DTO fields and a REST path variable called that id a "peer name". They say `peerId` now.
   The audit script lives in the session scratchpad; it strips comments so it asks about code, not prose.

   2j. **Backups are keyed by identity end to end ✅ (2026-07-28).** The `freeName` step-aside above was a
   patch over the real problem, which was that a **job** was keyed by its name and a **run** by its job's
   name. Both are labels. `ForPersistingBackupJobs` upserts and deletes by `MachineId`, `ForRecordingBackupRuns`
   records and reads by it, the `run-settled` SSE event carries it, and every `/backup-jobs/{…}` endpoint
   addresses the machine rather than the job — so the browser's run cache is keyed by the same thing the
   tree stands on. `JobRequest` lost its `machineId`: the path already says which machine, and two places to
   say it is two places to disagree.

   **Repositories are named by identity too**, which is a decision with a real cost and was taken
   deliberately. A repository is a *directory on the backup server*, and its name was the one identifier in
   Vaier meant to be read by a human with no Vaier — SSH into the NAS and `Apalveien-5` tells you what you
   are looking at where a UUID does not. The operator's call was to name them by identity and let the
   **survival kit** carry the mapping, which it does: the sheet prints the machine's name beside the
   repository's full `ssh://` URL, and now names a *departed* machine from the repository's own name rather
   than writing it off as "no machine" — an orphan directory is a UUID, so "no machine" would leave nothing
   to attach it to. `RecoverySheet` also stopped calling `Map.get(null)` on that path, which throws on an
   immutable map: an NPE while rendering the one page that exists for when everything else has failed.

   What a store is *called* is now a separate question from what it is *named*, answered by
   `BackupStoreLabel` in the domain: the machine's name, plus where the machine is when another machine
   shares that name. The browser reads that label off the feed and never re-derives it — two surfaces
   working out which store is which is how they come to disagree, and being wrong means restoring the wrong
   machine's data.

   **Migration (done on the live instance, by hand).** Three of the four repository directories were moved
   on the NAS to their machines' identities and verified by listing their archives back through borg
   (Apalveien 5: 10, Vaier server: 10, Colina 27: 7). Moving one turns out to be **three** steps, not one:

   1. `mv` the directory. borg then refuses the next *non-interactive* access to it — it records where a
      repository was last seen and asks before touching it somewhere new, and with no terminal it aborts.
      Verified against the real binary. Every borg command Vaier issues now exports
      `BORG_RELOCATED_REPO_ACCESS_IS_OK=yes`: Vaier is the thing that moves repositories, so the prompt is
      asking the wrong party. It is needed only on the first access after a move, but it is set always,
      because the alternative is a backup that fails on a night nobody is watching.
   2. Point Vaier at it (drop the `repoPath` pin, so the path derives from the identity-name again).
   3. **Re-authorise the client on the backup server.** Its `authorized_keys` entry confines the key with
      `--restrict-to-path` per repository, so a moved repository is `Repository path not allowed` until the
      client is re-authorised — after the config change, since the allowed paths are derived from it.

   **Roon server's repository nearly cost a night's backup, and the near-miss was mine.** It failed to move
   with `passphrase … is incorrect`, and the reason was two layers deep. Vaier's *stored* passphrase for it
   was wrong — an old orphaning, and the reason its archives had always read as empty. Its nightly runs
   worked anyway, because the *client* holds its own pass file (`~/.vaier-backup/<repo>.pass`) written when
   the repository was provisioned, and that one was right. Renaming the repository then made
   `ensurePassFile` write a **new** file, named for the new repository, from Vaier's wrong stored
   passphrase — and `ensurePassFile` is create-if-absent, so it would never have corrected itself. Tonight's
   run would have failed where last night's succeeded. Repaired by taking the working passphrase off the
   host into Vaier's store and removing the stale file; the repository holds **6 archives**, so
   `Roon server has never backed up` was never true — it only looked that way because a failed read
   answered `200 []`.

   **A repository's passphrase lives in two places and they can drift.** That is the standing hazard this
   exposed: Vaier's store, and a create-if-absent file on each client. Renaming a repository or rotating a
   passphrase updates one and not the other, and nothing says so until a run fails.

   **The three orphan directories were deleted** (`NUC-02` 118 MB, `NUC02` 88 KB, `colina27` 45 MB) —
   confirmed as churn from testing the identity work, not backups of anything. 163 MB reclaimed; the four
   live repositories were listed back afterwards to prove they were untouched.

   2l. **An unreadable repository says so ✅ (2026-07-28).** `listArchives` returned an empty list when borg
   failed, and logged the reason at `debug`. "This repository holds no archives" and "I could not read this
   repository" are different facts, and the second is the only sign an operator gets that a machine's
   backups have become unreachable. It throws `ArchivesUnreadableException` now — `502`, because the
   failure is upstream and Vaier is relaying borg's own words ("Repository path not allowed",
   "passphrase … is incorrect"), which are worth more than any status Vaier could invent. Found by being
   misled by it: the first migration attempt reported success against a repository borg had refused.

   **With one exception, found by an operator testing it.** A repository that does not exist *yet* is the
   ordinary state of a machine just ticked for backup — borg creates it on the first run — so that reads as
   empty, not as a fault. Reported as a failure, every machine looks broken between being selected and its
   first nightly run. Matched on borg's own wording, since no exit code distinguishes it.

   2k. **The terminal dock is deleted ✅ (2026-07-28).** `terminal-dock.js` (1,334 lines), admin.html's
   terminal panel and tab, and ~360 lines of its CSS are gone. It tiled shells inside admin.html and was
   reached from a Terminal button on the Infrastructure page — a page the Explorer replaced (#323) — so
   nothing had called it since; its own empty state still told operators to open a shell from a button that
   no longer existed. Two shell systems is two places for session ownership to drift, which is the bug
   `terminal-panes.js` exists to prevent. Its lifetime guards moved to `terminal-window.js`, the surface
   that is actually live.

**No config migration is outstanding.** The stores' on-disk shapes did not change in this pass — peers and
LAN servers already carried `id:` from the earlier slices, and `ignoreKey` was deliberately left alone so
the ignored-services file does not orphan. The one visible break is that setup links minted before this
deploy carry a name where the route now expects an identity; they are single-use and expire in ~15 minutes,
so the answer is to mint a new one.

#### How it got here

The work that got this far is **committed and pushed** on `main`: the SSH path went id-native in
`7969746`, the Explorer's coordinates in `5c843a5`, the rest of the machine-keyed backend in `7bf0d6f`,
and the browser in `126038b`. `5581ca1` fixes two findings the hex checker raised against the first two,
and `4cdf3c8` fixes the pop-out shell window that `126038b` missed (the fourth defect above).
What is left is one step — the payoff step:

1. ~~**`/vpn/peers` must expose `machineId`.**~~ **✅ done 2026-07-27.** `machineId` travels
   `PeerConfiguration` -> `VpnPeerView` -> `VpnPeerResponse`, **read from the stored config and never
   minted**: a live WireGuard peer with no config on disk is in no machine registry, so its `machineId` is
   null rather than a fabricated value that would join to nothing while looking like it could. The browser's
   `S.peers` is keyed by identity now, which closes the live bug — when `/machines` and `/vpn/peers`
   disagreed about a name by one character, `isPeer` went false and a delete was routed to
   `/lan-servers/<name>`, where it 404'd with **no backend log line and no UI error**, the confirm dialog
   simply closing while the peer stayed. (The live fleet showed exactly that shape: peer id `Colina-27`
   against display name `Colina 27`.) Three lookups genuinely start from a name and now resolve through the
   machine registry rather than matching peer names against each other — the tree's liveness dot, the
   capability strip, and a LAN server's stored `relayPeerName` (`peerNamed`). What arrives keyed by
   WireGuard peer id — the liveness stream and the container scrape — goes through a second index
   (`S.peersById`) and one named crossing, `peerDisplayName`. Adding the field mid-record changed both
   arities, so the compiler enumerated every construction site instead of letting a misplaced argument
   compile.

2. ~~**Delete the scaffolding**, in one commit with the uniqueness rule.~~ **Wrong shape, found by doing
   it (2026-07-27).** The registry and the uniqueness guard cannot go together: `Machine.nameIsTaken` is
   what makes the *remaining* name→machine lookups unambiguous, so deleting it while any of them stands
   trades a rule that annoys the operator for silent mis-routing — this refactor's own bug class,
   reintroduced a layer down. It is really four steps, and the payoff is the last of them, not the first.

   2a. **The registry is gone ✅ (2026-07-27).** `ForResolvingMachineIds` and `MachineIdRegistryAdapter`
   deleted with their test, and all three crossings the plan listed went with them.
   `MachineService.labelFor` asks its own fleet — naming a machine was never a cross-domain question, and
   the port existed only because callers held a name where they should have held an identity.
   `TerminalService.labelFor` is gone outright: it fed log lines that already carry the host address,
   which identifies a machine better than a name does in a log, and the service has no fleet of its own
   (nor may it ask a use case), so resolving a name there was the last thing keeping the port alive.
   `BorgArchiveMountAdapter` resolves nothing now — two of its messages named the machine, but they
   surface on that machine's own pane in the Explorer, where the operator can already see which machine
   they asked about, and needing a name was the whole reason an adapter that reaches machines by identity
   had to look one up.

   2b. **The backup API takes identities ✅ (2026-07-27) — and this one was a live bug, not a tidy-up.**
   `126038b` moved the browser onto identities including `/machines/{machine}/backup/paths`, while this
   controller went on resolving that segment as a **name** — so every protect and unprotect had been
   answering **404 since that commit**, and ticking a folder to back it up silently did nothing. Because
   the protect flow is also what readies a host (installs borg, trusts its key on the backup server), a
   machine's *first* back-up could not complete either, which is why the manual **Authorize a host** button
   was the only way through. Confirmed against the running instance before the fix (id → 404, name → 200)
   and after (id → 200, name → 404). `findMachineNamed`/`machineNamed` are replaced by a lookup on
   `MachineId`; a segment that does not parse as one is simply not a machine, never a name to try instead.
   `/backup-servers/{name}/authorize/{machineId}`, `ServerRequest.machineId` and `JobRequest.machineId`
   moved with it, along with the browser call sites — the authorize picker's option values are identities
   now and its text is still the name. Responses keep `machineName` beside `machineId`, because the tree
   still renders by name until 2c. One test lost its subject: the lookup used to need `Machine.hasSameName`'s
   leniency so a name rejected at creation for colliding could still be found — an identity has no case or
   whitespace to be lenient about.

   2c. **The tree carries identities ✅ (2026-07-28).** A tree entry's path segment is the machine's
   `MachineId` and its row label is the name, so every coordinate in the shell — the URL hash, the
   directory cache, the Clipboard, the fleet-wide selection — is an identity, and `midOf(name)` is gone
   with `window.vaierMachineIdOf`. One name→id lookup survives, named for the moment it serves:
   `justCreated(name)`, because a create response says what it made but not which machine it became. It
   goes when the create endpoints answer with the id.

   **Three feeds still publish only names**, so the shell crosses id→name at exactly three helpers
   (`servicesOn`, `containersOn`, `candidatesOn`) rather than at every call site: `/lan-servers`,
   `/docker-services/*` and `/published-services/discover`. `/backup-jobs` already carried `machineId` and
   `jobsOn` now matches on it. Still here from the original entry: `MachineRestController`'s
   publishable-service owner count, the last `Machine.hasSameName` consumer, which needs the publishing
   feed to carry identities first.

   **A partial conversion is worse than none, and this slice proved it twice.** Renaming function bodies to
   `machineId` while their parameters were still `machine` left nine `ReferenceError`s that aborted a
   render silently — the file pane painted nothing while the tree, reading the same cache, was fine. Worse,
   the file pane kept a local called `machine` holding a *name* beside `machineId`, and keyed the selection
   by it: every bulk verb then addressed `/machines/<display name>`, which the controller cannot parse as
   an identity, so it 404'd **before its own log line** — no request logged, no error shown, "Stop backing
   up" simply doing nothing. Three more references resolved to `window.name`: a downed server peer looked
   like a sleeping laptop, every LAN server's dot was grey, and the backup-server glyph had never appeared.
   Eyeballing found none of these. What found them was **parsing the file**: a scope analyser reporting
   every identifier that resolves to no binding, and a checker reporting every call that hands a name to a
   parameter called `machineId`. Both now run clean, and no variable in the shell called `machine` holds a
   name — that ambiguity was the whole bug, so the convention is the guard.

   2d. **The LAN-server store is keyed by identity ✅ (2026-07-28) — and 2d turned out to be a
   prerequisite nobody had written down.** Deleting the uniqueness guard would have been unsafe while
   `lan-servers.yml` was keyed by name: `save()` upserted with `removeIf(name equals)`, `deleteByName()`
   removed by name and `LanServer.findByName` was the lookup, so two machines called "NAS" would have
   overwritten each other in the store and a delete would have removed whichever `getAll()` returned
   first — silent mis-routing, one layer below the one the guard was protecting. The port is
   `save`/`deleteById`, the domain lookup is `findById`, and `/lan-servers/{machineId}` addresses every
   write, the setup-script download and the tokenized setup route. `publishLanService` takes a
   `MachineId` too: publishing writes a DNS record and a route at whatever address the lookup returns, so
   a name matching the wrong machine would put a service on the internet in front of a host nobody chose.
   A rename now *replaces* its own entry instead of writing-then-deleting, which is the whole difference
   an identity-keyed store makes. Deleting an unknown machine is a `404` rather than a silent success.
   VPN peers needed none of this: their `{peerName}` path segment was always the immutable WireGuard
   config id, whatever it was called.

   2e. **Creating a peer wrote no identity at all — a live defect found by doing 2d.** `vaierJson` never
   emitted an `id`, and `WireguardConfigFileAdapter` refuses (rightly) to load a peer whose metadata has
   none rather than inventing one. So **every peer created since the identity slice landed joined the
   WireGuard server and was then invisible to Vaier**: no machine, no credential, no backup, and no error
   anywhere — `wg show` would list it while `/machines` did not. `WireGuardPeerConfig.generate` now stamps
   the `MachineId` that `VpnService.createPeer` mints, which is the one moment in Vaier an identity is
   created rather than read. **`reissue` had the same hole from the other side**: it re-renders the whole
   config, so it was erasing the id of any peer it touched. It carries the existing one through now —
   read off the config being reissued, and left absent when there is none, so reissuing a never-migrated
   config does not quietly mint an identity and orphan the records keyed to the old one. The live fleet's
   four peers were checked and all carry ids; the exposure was to peers created after the refactor.

   2f. **The create endpoints answer with the identity ✅ (2026-07-28).** `CreatePeerResponse`,
   `RegisterResponse` and `AdoptResponse` carry `machineId`, so `justCreated(name)` — the shell's last
   name→identity lookup, which re-read the fleet and matched on the name the operator had just typed — is
   **deleted**. Nothing in the browser resolves a machine by name any more.

   2g. **The remaining feeds carry identities ✅ (2026-07-28).** `/docker-services/peers` and
   `/docker-services/lan-servers` name their machine by `machineId`, so the Explorer's container cache is
   keyed by the same thing the tree stands on and `peerDisplayName` — the crossing from a WireGuard peer id
   to a display name — is **deleted**. `/published-services/discover` carries the identity of the machine a
   route's backend runs on, decided in the domain (`ReverseProxyRoute.hostMachineId`) rather than
   re-derived in JS from display names, which is how one service could appear under two different machines
   on two different pages. The publishable feed carries it too, so `PublishableService.ownerMachineName`
   (plus the Vaier-server-name and address→name maps that existed only to feed it) becomes
   `belongsTo(MachineId)` — **the last `Machine.hasSameName` consumer**. `/machines` marks the Vaier
   server (`vaierServer: true`), retiring six comparisons against the literal string `"Vaier server"`: a
   name doing an identity's job, which stopped recognising the machine the moment someone renamed it. That
   lookup is **guarded** — it reads config and shells into the WireGuard container, and a failure to label
   one machine must not blank the whole fleet list. `LanServerView` carries `relayMachineId` beside
   `relayPeerName`, so the map joins a LAN server to its relay by identity, and the scan's candidate rows
   resolve their relay through the peer id instead of fuzzy-matching names.

   **Image-update verdicts were name-scoped, and that was two bugs waiting.** `ScopedImage` keyed
   "this image on this machine" by machine **name**, so two machines sharing one would have collapsed into
   a single verdict — one of them silently stops being watched — and, because consecutive sweeps are diffed
   to find what has *newly* gone stale, a rename would have read as every image on that machine going stale
   at once and mailed the operator about it. The key is a `MachineId` now and the display name is supplied
   at the moment the alert is written (`ScopedImage.label(name)`, `ImageUpdateRollup(images, machineNames)`)
   — the same "a name is presentation, passed in, never held" rule the survival kit and the backup mails
   follow.

   2h. **The guard is gone, and machine names need not be unique ✅ (2026-07-28) — the payoff.**
   `Machine.nameIsTaken`, `Machine.hasSameName`, the four uniqueness checks in
   `VpnService`/`LanServerService` and both `otherMachineNames` helpers are **deleted**. Two machines may
   now be called "NAS", and the reserved `"Vaier server"` name is reserved no longer. Nothing is keyed to a
   label: identity is a `MachineId`, the peer id (a config directory, so genuinely unique) is still
   deduplicated by `PeerId.generate`, and a duplicate name buys a machine nothing but the name.

   **One thing is deliberately left name-shaped.** `terminal-window.js` still resolves a machine by name
   when a pre-§6.22 bookmark supplies no `id` — but it now **refuses to guess**: more than one match and it
   says so rather than opening a shell, because a shell on the wrong host is the one outcome worse than no
   shell. Terminal *pane* ids also stay name-keyed (see the fourth defect above), so a rename still orphans
   a live shell; re-keying them re-mints every primary and strands the tmux sessions running now.

Then: run the hex checker over the whole range, sync `README.md` and `UBIQUITOUS_LANGUAGE.md`, and
migrate. **The migration is by hand, no migration code** — back up `vaier/config/` first, and migrate the
YAML and deploy in the same step, because the running container and the config must agree on the key.

**A hazard this refactor creates, found twice in production.** Identity-keying turns field accesses into
*lookups*, and a lookup can fail — the machine registry reads WireGuard by shelling into a container that
restarts. Two scheduled sweeps were taken down by it: `BackupServerWatcher`, and `BackupProvisioner`'s settle
sweep, which loses its event permanently because it clears the in-flight entry before publishing. Anything on
a scheduled path that now looks a name up where it used to read a field needs a guard, and where the answer
only decorates a message it must degrade rather than throw.

#### Working method (proven across the slices above)

Change the record, let the compiler enumerate every call site, bulk-convert the mechanical ones with a
script, then hand-fix assertions that compared whole records (they now differ by id). `TestMachineIds.of(name)`
gives fixtures a stable name-derived id — production never derives an id from anything. Watch for fixtures
where a `Machine` mints a random id while its related record uses a name-derived one; that mismatch is
invisible except as a test that fails for the right reason.

**Migration is by hand** — one site, no migration code. Resolve names to ids by reading the three stores,
back up `vaier/config/` first, migrate, then build and deploy in the same step: the running container and the
config files must agree about which key they use.

### 6.23 Edge hardening ✅ (headers + TLS options half of [#258](https://github.com/getvaier/vaier/issues/258))

**Why.** Traefik emitted no security headers at all and set no `tls.options`, so every response — Vaier's
own console and every published service — went out with whatever the backend happened to send, and the
handshake floor was Go's default rather than a deliberate choice. The redirect half of #258 was already
done (`--entrypoints.web.http.redirections.entrypoint.to=websecure`); the issue's claim otherwise was stale.

**Where it lives.** `traefik/` is gitignored in its entirety, so the **edge security policy** cannot be a
committed file — Traefik's own entrypoint renders `security.yml` into its watched dynamic-config directory
before it starts, on every boot. That is a correctness requirement, not a convenience: the middlewares are
referenced from Traefik's *static* config and from Vaier's compose labels, and a reference Traefik cannot
resolve **disables** the router carrying it. Rendering from Vaier — which boots after Traefik — would leave
that window open. It is a second file in the directory; the one Vaier generates is never written to here.

**What it sets, and what it deliberately does not.**

- **Security headers** (`X-Content-Type-Options: nosniff`, `Referrer-Policy: strict-origin-when-cross-origin`)
  are bound to the `websecure` **entry point**, not to individual routers. That is what makes "every router"
  true by construction — compose-label routers, every route `TraefikReverseProxyAdapter` generates, and
  anything added by hand later — with **no backfill** and without touching `remote-apps.yml`, so the
  adapter's middleware readers (`extractAuthInfo`, `extractRootRedirectPath`) cannot regress. Both headers
  are safe to impose on an application Vaier did not write.
- **Frame guard** (`X-Frame-Options: SAMEORIGIN`) is per-router, on Vaier's own surfaces only — `vaier`,
  `vaier-public`, `vaier-identity`, `vaier-oauth2`, `oauth2-proxy`, `dex`, `vaier-offline`. Fleet-wide frame
  protection would break a published app that legitimately embeds or is embedded, silently and at scale.
  `SAMEORIGIN` rather than `DENY` because the Explorer frames its own pages (the Users/Concepts bridge).
  It is **appended** to each chain, never prepended — at the time it was written the adapter reported a
  router's auth from the first auth-*looking* middleware on the list, so position mattered. Since §6.24 it
  no longer does: the guard is simply not one of the **auth middlewares**, wherever it sits.
- **No `Content-Security-Policy` at the edge, on purpose.** `GET /machines/{id}/files/view` already serves
  every previewed file under its own tight per-media-type CSP (`ViewableFile.SANDBOXED_POLICY` / `PDF_POLICY`).
  An edge CSP would either overwrite that — silently weakening a real boundary — or stack with it, and a
  browser enforces the **intersection** of every CSP header, breaking file viewing outright. The rendered
  file carries a comment saying so, since its absence is otherwise the thing a reader would ask about first.
- **No HSTS.** Deferred deliberately — it cannot be taken back once a browser has seen it, so it is a
  decision of its own, tracked as [#342](https://github.com/getvaier/vaier/issues/342). Not present in any
  form, not even commented out.
- **Edge TLS policy** — `tls.options.default`: `minVersion: VersionTLS12` and ECDHE+AEAD cipher suites only
  (GCM / ChaCha20-Poly1305; no CBC, RC4, 3DES or static-RSA key exchange). Being named `default` it applies
  to every router that does not name its own, so it needs no route-definition changes. Certificate issuance
  is untouched: the ACME HTTP-01 challenge is served over the plain `web` entry point, which terminates no
  TLS, and no TLS-ALPN challenge is configured.

**Testing.** `DockerComposeStructureTest` executes the *real* traefik entrypoint under `sh` (stubbing only
`getent`/`ip`/`nslookup`/`traefik`, the same PATH-shim trick the `dex-init` cases use) and asserts on the
file it actually writes, rather than regexing YAML. The policy was additionally booted against the pinned
`traefik:v3.6.14` image: both middlewares load `enabled`, the entrypoint middleware attaches to a router
that declares none, a published-service-shaped router answers with the two safe headers and **no**
`X-Frame-Options`, a Vaier-shaped router answers with all three, TLS 1.1 is refused while 1.2 and 1.3
negotiate — and a deliberately corrupted cipher name is rejected by Traefik, which proves the accepted list
is really being validated.

**No Java changed.** There is no per-route decision to encode: the middleware set is identical for every
router, and Traefik's static config enforces it. This is the opposite case to `AuthMode.authMiddlewareNames()`,
which lives in the domain precisely because the auth chain *does* vary per route.

---

### 6.24 A forward-auth middleware is not proof of authentication ✅ (implemented 2026-07-29, closes [#341](https://github.com/getvaier/vaier/issues/341))

**Why.** Reading a route back out of Traefik, Vaier decided "this service is authenticated" from the mere
presence of a `forwardAuth` block on one of its middlewares. That held only because oauth2-proxy was the
single `forwardAuth` in the stack — an accident of there being exactly one, not a property of `forwardAuth`,
which is a general transport. Chain any second one **in front of** oauth2-proxy (where you would put a
bouncer, since you want it to reject traffic before the auth hop) and two things broke without erroring: a
**public** published service reported as **authenticated**, and a gated one named the new middleware as its
auth provider. That value is what the Explorer shows as a service's **auth mode** and what the launchpad's
viewer-adaptive logic keys on, so a service genuinely open to the internet was displayed as protected.
Prerequisite for **#329 slice 1** (CrowdSec chains its bouncer exactly this way), but not a CrowdSec bug —
any second `forwardAuth` does it, and a future rate limiter or maintenance gate would have found it instead.

**The fix — positive identification, in the domain.** `AuthMode.isAuthMiddlewareName` answers the question
once: exact membership of `AuthMode.allAuthMiddlewareNames()` (`oauth2-signin`, `oauth2-authn`,
`vaier-authz`), tolerating the `@provider` suffix Traefik's API appends. It sits beside the list it tests
membership of, so the two cannot drift. Identification is positive rather than a blocklist on purpose: a
blocklist means every future non-auth `forwardAuth` reintroduces the bug by default, which is exactly how
this one arrived. `TraefikReverseProxyAdapter.extractAuthInfo` now gates its `forwardAuth` branch on that
predicate and **keeps walking** the chain instead of returning on the first hit — so a bouncer is stepped
over and the provider label comes from the middleware that actually authenticates, in any order. `basicAuth`
and `digestAuth` are untouched: those are authentication **by type**, which is precisely what `forwardAuth`
never was, and that asymmetry is the lesson of the bug.

**A second site, not in the issue.** The same defect lived one layer up as
`ReverseProxyRoute.AuthInfo.isAuthMiddlewareName` — a substring heuristic (`contains("auth") ||
contains("oauth") || contains("sso")`) used on the Traefik-API read path, where only middleware *names* are
exposed. It failed in both directions: a CrowdSec bouncer named `crowdsec-forwardauth` matched and
authenticates nobody; so would `authenticated-rate-limit`. That method is **deleted**, not repaired —
keeping it as a delegating forwarder would have left two names for one rule and somewhere for a second rule
to regrow. Every caller now asks `AuthMode`.

**Testing.** `AuthModeTest` pins the predicate itself, including the names the old heuristic wrongly matched.
`TraefikReverseProxyAdapterTest` covers **both** read paths end to end — the config-file path via a seeded
`remote-apps.yml`, and the API path via a stub Traefik API serving `@file`-qualified names — for: a bouncer
alone reporting **public**; a bouncer before *and* after the auth chain both naming oauth2-proxy; the
CrowdSec-shaped regression (`crowdsec-bouncer@file` and the nastier `crowdsec-forwardauth@file`); `basicAuth`
still detected whatever its middleware is called; and today's stack shape reported byte-identically to before.

---

### 6.25 Proactive SSH-server-presence gating ✅ (implemented 2026-07-30)

**Why.** §6.20/§6.24's `NoSshServerException` fix made a refused SSH connect surface as a precise, actionable
error — but only reactively, after the operator clicked. Uninstall a machine's SSH server (as happened on
**Roon kjøkken**, `192.168.3.104`) and the Explorer still offered the SSH-access checkbox, **Open shell**, and
the Files/Disk tree entries exactly as if the server were there, each one a dead end that took a click to
discover.

**The fix — piggyback on the existing sweep, never a second SSH round-trip.** `RemoteDiskWatcher` already
SSHes into every SSH-accessible, credentialed machine every 5 minutes to read disk usage. Its `checkMachine`
now recognises `NoSshServerException` specifically (via the existing `isNoServerListening` predicate) and
records the machine **ABSENT**; any successful command run (regardless of the command's own exit status —
reaching a result at all proves the session authenticated) records it **PRESENT**, self-healing the gate the
next time a sweep reaches the machine. Every other failure (a timeout, a rejected credential, a host-key
mismatch) is ambiguous — it could just as easily mean the machine is asleep — and leaves the tracker
untouched. New domain enum `SshServerPresence` (`UNKNOWN` / `PRESENT` / `ABSENT`) — Vaier's *last-known
belief*, distinct from **SSH access** (the operator's intent) and from **machine status**/reachability
(general network liveness, not SSH-specific). Tracked in `InMemorySshServerPresenceCache`
(`adapter/driven/`, implementing the new `ForCheckingSshServerPresence` / `ForRecordingSshServerPresence`
ports, keyed by `MachineId`), the fleet-wide sibling of the existing LAN-reachability cache. `TerminalService`
— the domain service that already owns the SSH/credential/terminal concept — gains one narrow
`GetSshServerPresenceUseCase` reading the cache.

**Wiring.** `GET /machines` composes `sshServerPresence` into `MachineResponse` at the driving edge (same
pattern as the existing `hasCredential`), so the Explorer has the current state on page load. On a boundary
crossing, `RemoteDiskWatcher` publishes `ssh-server-presence-changed` on the `vpn-peers` SSE stream the
Explorer already holds open for fleet liveness — no new connection, no timer, and no noise on every sweep
(only on the crossing).

**Four UI gates, greyed rather than removed** (`explorer-shell.js`): the state is transient and
self-healing, so nothing disappears — it becomes inert with a tooltip until a later sweep lifts it.
1. The **SSH access** checkbox disables (with "No SSH server detected on last check") only while it is
   currently *off* — an operator who already turned access on can always turn it back off, see the stored
   credential, or retry; the gate never stops turning access **off**, only turning it **on** for a machine
   already known to have nothing listening.
2. **Open shell** disables for either of two independent reasons, each with its own words: no stored
   credential ("Give this machine an SSH credential first" — a separate, static condition, already carried
   by `hasCredential`), or no known SSH server.
3. The Files and Disk entries — both in the machine pane's "Inside this machine" grid and the fleet tree
   rail — grey out the same way, closing the gap where a `sshAccess=true` machine with a dead SSH server
   still showed clickable entries that only relocated the dead end one click deeper.

**Testing.** `InMemorySshServerPresenceCacheTest` covers the adapter in isolation. `TerminalServiceTest`
covers the use case delegation. `RemoteDiskWatcherTest` covers: recording ABSENT on `NoSshServerException`
and PRESENT on any successful run (whatever `df`'s own exit status), publishing only on a boundary crossing
(never on a repeat), never touching the tracker on an ambiguous failure or a skipped machine (no access, no
credential), and evicting a deleted machine's stale state each sweep (`retainOnly`). `MachineRestControllerTest`
covers the driving-edge composition into `GET /machines`.

### 6.26 Fleet threat detection — Slice 1 ✅ (implemented 2026-07-30, closes Slice 1 of [#329](https://github.com/getvaier/vaier/issues/329))

**What.** A real CrowdSec Security Engine + a standalone Traefik bouncer, blocking malicious HTTP
traffic at the edge — with the operator's own networks provably un-bannable and a documented
recovery path from a false-positive lockout. No UI, no notifications (Slice 2/3). The earlier
"agent on every host" slice (Slice 4) was dropped before this work started: peers have no public
IP, so the Vaier server's own Traefik entrypoint and SSH port are the only genuinely
internet-facing surface.

**Enforcement — entrypoint-level, zero `TraefikReverseProxyAdapter` changes.** `crowdsec-bouncer`
rides `--entrypoints.websecure.http.middlewares=crowdsec-bouncer@file,vaier-security-headers@file`
(bouncer first) — the same entrypoint-level mechanism that already carries the security headers to
every router, published services included, with no per-route wiring. `AuthMode.isAuthMiddlewareName`
(§6.24) already keeps this from being misread as an authenticator.

**Bouncer key — a compose-level shared secret, not a Vaier-minted one.** The original design assumed
`crowdsec-bouncer` (`fbonalair/traefik-crowdsec-bouncer:0.5.0`) needed a wrapper entrypoint that
polled for a Vaier-minted key file, exported it, then exec'd the real binary. Hands-on image
inspection (as the plan required before writing that wrapper) found the image is **distroless — no
shell at all** (`docker run --entrypoint sh ...` fails outright), so no wrapper is possible. Two
further findings replaced the whole mechanism: the bouncer reads `CROWDSEC_BOUNCER_API_KEY` and
`CROWDSEC_AGENT_HOST` as plain env vars (confirmed via `strings` on the extracted binary), and
CrowdSec's own `/docker_start.sh` already self-registers any bouncer named by a `BOUNCER_KEY_<name>`
env var at its own boot (`register_bouncer`, idempotent). So `VAIER_CROWDSEC_BOUNCER_KEY` is a single
install.sh-generated secret (`ensure_secret`, exactly like `VAIER_DEX_CLIENT_SECRET`) fed to both
containers — `crowdsec` self-registers it, `crowdsec-bouncer` reads it — with no exec, no polling,
and no Java-side minting code at all.

**Domain-owned allowlist — `TrustedNetworks`.** VPN subnet + Docker bridge CIDR (own `@Value`,
deliberately not shared with the **trusted proxy CIDR** (`vaier.trusted-proxy-cidr`) — same value today, a
different concept) + every relay's `lanCidr` via `ForGettingPeerConfigurations.allLanCidrs(...)`
(extracted from `VpnService.syncLanRoutes()`'s inline stream, closing a copy-paste gap).
`SecurityService` (the new domain concept the issue calls for; scoped to exactly this one job in
Slice 1) renders it via `CrowdSecWhitelistFileAdapter` into CrowdSec's real whitelist-parser schema,
refreshed on Vaier boot and whenever `VpnService.updateLanCidr`/`deletePeer` runs.

**Acquisition file — the gap that would have broken the whole slice.** `COLLECTIONS=crowdsecurity/traefik`
installs the *parser* for Traefik's JSON log format; `crowdsec/acquis.d/traefik.yaml` (committed) is
the acquisition source telling CrowdSec which file to actually tail. Traefik now writes its access
log to a file (`--accesslog.filepath`) instead of stdout, rotated by a tiny `traefik-logrotate`
alpine sidecar (`copytruncate` — Traefik has no reopen signal).

**Confirmed empirically during deploy: CrowdSec does NOT hot-reload the whitelist.** Editing the
mounted whitelist file while `crowdsec` keeps running has no effect — a repeat attack from a newly
whitelisted IP still gets banned. A `crowdsec` restart is required to pick up a changed allowlist
(parsers are compiled into the node tree once, at that process's own boot — unlike Traefik's file
provider, which explicitly watches for changes). Tracked as a real, accepted Slice-1 limitation;
a later slice may want `SecurityService` to trigger the restart itself. *(Answered in Slice 3: it
does not, and deliberately — restarting the edge bouncer to apply an allowlist entry is the lockout
risk this feature names first. The limitation is documented to the operator instead; see §6.28.)*

**Break-glass — `docker exec crowdsec cscli decisions delete --all`, zero new code.** A real
unban UI/port belongs to a later slice (the issue's own architecture called it
`ForBlockingAddresses`) — building a throwaway one-off now would be wasted work. *(Shipped in
Slice 3 as `ForLiftingBlocks`/`CrowdSecCliAdapter`, running the very same `cscli` — see §6.28.)*

**Verified end-to-end on the dev stack**, including a live attack simulation: a burst of known
scanner-signature paths (`/.env`, `/wp-login.php`, `/.git/config`, …) against a published service
produced a real `crowdsecurity/http-probing` ban decision, after which the same source got refused
(`403 Forbidden`) at Traefik — for every router, console included, confirming the fail-closed risk
is real — before reaching oauth2-proxy or any backend; break-glass cleared it and reachability
returned immediately. Separately, CrowdSec's community blocklist caught a genuine internet scanner
(`74.248.24.145`, `crowdsecurity/http-crawl-non_statics`) unprompted during testing. The operator's
own VPN/Docker-bridge traffic was confirmed never blocked via direct `forwardAuth` checks from
within those CIDRs.

**Backlog (Slice 2/3).** A port for a real unban affordance (the issue called it
`ForBlockingAddresses`; it shipped as `ForLiftingBlocks`, §6.28);
threat-signal/breach-attempt notifications (the "notify only on trouble, predictive over reactive"
convention already used for backups/certs); an Explorer surface for current decisions.

### 6.27 Fleet threat detection — Slice 2 ✅ (implemented 2026-07-30, closes Slice 2 of [#329](https://github.com/getvaier/vaier/issues/329))

**What.** "Notify the operator when someone tries to break in" — the issue's own headline promise.
Poll CrowdSec's active ban decisions, track which are new since the last sweep, email admins one
rollup when they are. No unban action, no UI — those stay Slice 3.

**Simplification — no new CrowdSec credential.** The issue's architecture called for two ports
(`ForDetectingIntrusions` reading alerts, `ForBlockingAddresses` reading decisions) feeding two
domain types (`ThreatSignal`, `BlockDecision`). Live-tested: the bouncer API key Slice 1 already
mints authenticates `GET /v1/decisions` fine (scenario, source IP, duration — everything a breach
notification needs); the richer `/v1/alerts` endpoint (geo/ASN enrichment) demanded a separate
JWT-based "machine" credential, exactly the kind of new-credential machinery Slice 1 already
avoided once. Collapsed to a single `BlockDecision`, read via the credential Vaier already holds.
`CrowdSecLapiAdapter` (`adapter/driven/`) implemented `ForDetectingIntrusions`, JDK `HttpClient` +
Jackson, modeled on `RegistryV2ImageAdapter` — every failure is an empty list, never a throw.
*(Superseded in Slice 3: that adapter is deleted, and `CrowdSecCliAdapter` reads the same decisions
through `cscli` — with the geo/ASN enrichment, and with no credential at all. The "needs a JWT-based
machine credential" conclusion above was true of the HTTP path and false of the product: see §6.28.)*

**`BreachAttemptTracker` — modeled on `ImageUpdateTracker`, not the level-crossing trackers.** A
`Map<Long, Boolean>` of previously-seen decision ids, forgetting any id absent from the latest
sweep. Deliberately **not baseline-quiet**: a ban already active the first time Vaier's watcher
polls — including right after a restart — is real news, the same reasoning `ImageUpdateTracker`
documents for an already-stale image. Deliberately **no recovery/cleared transition** either: a
decision expiring on its own timer isn't good news the way draining disk space is. This is a real,
deliberate disagreement with the issue's original acceptance text ("a recovery notification when
it clears") — the issue was corrected to match once this was built, rather than left contradicting
what shipped.

**`BreachAttemptRollup` — one sweep, one mail.** Modeled on `ImageUpdateRollup`: wraps every
newly-appeared decision, owns `subject()`/`body()`, and a `worthSending()` guard the watcher checks
before notifying. `NotifyAdminsOfBreachAttemptUseCase` (`NotificationService`'s 7th `Notify*`
use case) only sequences the send.

**`BreachAttemptWatcher` (`rest/`) — no `SecurityService` growth.** A plain `@Scheduled(fixedDelay
= 300000)` component injecting `ForDetectingIntrusions` and `NotifyAdminsOfBreachAttemptUseCase`
directly, matching `RemoteDiskWatcher`/`BackupServerWatcher`'s existing idiom of a `rest/`
scheduler composing driven ports/use-cases with no intermediate service — `BackupServerWatcher`
already injects a raw driven port (`ForProbingTcp`) as its primary poll mechanism the same way.
`SecurityService` stays exactly as Slice 1 left it (allowlist refresh only). *(Slice 3 does grow it,
with the operator's three use cases — see §6.28; the watcher still injects the port directly, and
still takes the silent read.)*

**A hex violation, caught and fixed along the way — not part of this slice, but surfaced by it.**
While starting this slice, `VpnService.updateLanCidr()`/`deletePeer()` were found to be injecting
and calling `RefreshTrustedNetworksUseCase` directly — a real "services never call use cases"
violation, stretching the one documented cascade exception (`VpnService` → `DeletePublishedServiceUseCase`)
to a case that doesn't share the property that justifies it (an orphaned published route is a real
broken state; a briefly stale CrowdSec allowlist isn't, since CrowdSec doesn't hot-reload that file
either way). Fixed by removing the coupling entirely: `SecurityService`'s existing boot-time
refresh stands, and a new `TrustedNetworksScheduler` (`rest/`) now also refreshes it on its own
5-minute schedule — decoupled from `VpnService` completely. Verified clean by the
hex-architecture-checker agent.

**Verified end-to-end on the dev stack, live, no synthetic testing needed.** Deploying straight
into real accumulated CrowdSec traffic (24 active decisions from genuine internet scanners since
Slice 1 went live) proved every behavior at once: the very first sweep after boot reported all 24
as newly-appeared (no restart-quiet baseline), batched into exactly **one** rollup email
(`[Vaier] Breach attempt: 24 new block decisions`), actually delivered over the configured SMTP
(Gmail) — not just logged.

**Backlog (Slice 3).** A Security view in the Explorer: live decisions over SSE, one-click unban, and
"trust this address". *(Built — §6.28. The write path landed on `cscli`, not on LAPI.)*

### 6.28 Fleet threat detection — Slice 3 ✅ (implemented 2026-07-30, closes Slice 3 of [#329](https://github.com/getvaier/vaier/issues/329))

**What.** The operator's side of the feature: **see** who CrowdSec is keeping out, and **act** on it.
A **Security view** in the Explorer listing every active **block decision** — source address, where
it came from, the scenario, how long the block lasts — with two verbs per row (**lift the block**,
**trust this address**), the placeable ones drawn on the fleet **Map**, and the whole list pushed
over SSE. With Slices 1–3 shipped, #329 is complete but for two deliberately parked follow-ups
(below).

**The headline: `cscli` replaces LAPI, and the "you'd need a machine credential" conclusion was
wrong.** §6.27 recorded that geo/ASN enrichment lived behind `/v1/alerts`, which demands a separate
JWT-based "machine" credential — and dropped the enrichment rather than mint one. That was true of
the *HTTP* path only. `cscli decisions list -o json`, run **inside the crowdsec container over the
`EXEC=1` socket-proxy path Vaier already has open** (`ForExecutingInContainer`, the same port
`WireGuardVpnAdapter` uses), returns the full alerts: `source.cn`, `source.as_name`,
`source.latitude`/`longitude`, each alert carrying a nested `decisions` array. So one alert yields
*n* `BlockDecision`s, each with its alert's enrichment, and **Slice 3 ships with zero new
credentials** — the third time in this feature that the "mint a credential" branch turned out to be
avoidable. `CrowdSecLapiAdapter` and its test are **deleted**, replaced by `CrowdSecCliAdapter`
(implementing both `ForDetectingIntrusions` and `ForLiftingBlocks` — one place in Vaier speaks
`cscli`, and splitting the two directions would only duplicate the container name and the exec
idiom). `CROWDSEC_LAPI_URL` and `VAIER_CROWDSEC_BOUNCER_KEY` are **removed from the `vaier` service**
in `docker-compose.yml`; the bouncer's own `BOUNCER_KEY_vaier` is untouched, since the bouncer still
authenticates to LAPI in its own right.

**`ForLiftingBlocks`, not `ForBlockingAddresses` — a deliberate rename of the issue's own port.**
Vaier never *adds* a ban: CrowdSec's scenarios decide who is blocked. A port called "for blocking
addresses" would have been a lie about the threat model, reading as though Vaier held the block
button. Only one direction of that decision is Vaier's to take, and the port is named for it. Its
contract is also the deliberate opposite of the sweep's silent read: **unblocking must never swallow
a failure** — an operator is standing there waiting to learn whether they are back in, so it throws
`BlockNotLiftedException` → `502 BLOCK_NOT_LIFTED`, never a quiet success.

**Two honest reads on one port — and the live defect that forced the split.**
`ForDetectingIntrusions` now offers both `getActiveDecisionsOrEmpty()` (silent: any failure reads as
no active decisions) and `getActiveDecisionsOrFail()` (loud: a failure throws
`BlockDecisionsUnreadableException` → `502 BLOCK_DECISIONS_UNREADABLE`), and neither is named as the
innocent default. The five-minute sweep wants the silent one — a throw would cost
`BreachAttemptTracker` that sweep's diff, and an outage reported as bans would mail the operator a
breach that never happened, so "no *new* bans, wait five minutes" is the only answer that neither
lies nor alarms. The operator's screen wants the loud one, because it renders an empty list as
*"Nobody is blocked right now"*. The collapsed single-read version shipped and was caught live: the
security view told an operator nobody was blocked while `cscli` listed **eleven** active bans,
because the first exec after a container restart failed cold and the silent read reported it as
nothing. "I could not ask" and "nobody is attacking" are opposite facts about the fleet's safety and
must never share a rendering — the inconsistency between the two methods is the point, not a wart to
tidy away.

**`SourceAddress` — a domain type because the value is attacker-influenced.** It arrives from the
browser, originates with whoever knocked, and ends up as an argument to a command run inside a
container and inside log lines. Validated in its **canonical constructor**, not merely in `of(...)`:
a record's canonical constructor is as public as the record, so validating only in the factory would
leave `new SourceAddress("$(id)")` compiling. The rule is `Cidr.isIpv4`'s — the dotted-quad-only gate
written for #195: no IPv6, no hostnames, no leading zeros, and therefore no metacharacter, whitespace
or newline. That is also why a value shaped like a flag (`-i`, `--all`) can never be read as an
option by `cscli` rather than as data — the one injection that survives passing arguments as an
array. `x.x.x.x/32` normalises to the bare address (it is what an operator copies out of the
whitelist file); any wider prefix is refused, because both verbs are per-host and a range would
either trust more than was meant or unblock nothing. `ForLiftingBlocks` and
`ForPersistingTrustedAddresses` take the *type*, so no caller can route around the gate.

**Trusting is two effects, and the second one is honest about its limit.** §6.26 established
empirically that CrowdSec re-reads its whitelist parser **only on container restart**, and
`TrustedNetworksScheduler` rewrites that file *wholesale* every five minutes — so an address appended
to it out of band is erased within five minutes, and an address that is only in it is still blocked
right now. `trustAddress` therefore (1) persists to `ForPersistingTrustedAddresses`
(`TrustedAddressFileAdapter` → `${VAIER_CONFIG_PATH}/trusted-addresses.yml`, tolerant SnakeYAML like
`DiskWatchFileAdapter`, no secrets), which `TrustedNetworks.of(...)` now folds in as `/32`s on every
refresh, and (2) lifts the block, so the effect is immediate. Persist **first**: if the unblock then
throws, the operator's decision is not lost to a transient exec failure. **Vaier deliberately does
not restart CrowdSec** to make the whitelist entry live — bouncing the engine that guards the edge,
to apply a rule about who may pass it, is precisely the operator-lockout risk #329 names as this
feature's largest. The honest promise, said in those words in the confirmation dialog and the docs:
*unblocked now, permanently trusted from the next restart.*
*(Half superseded in §6.32: the restart half stands, "permanently" does not — trusting is now undoable,
the dialog says so, and the Security view grew a second section listing what has been trusted.)*

**Two decisions kept in the domain that JavaScript would have got wrong.** `BlockDecision.locatable()`
refuses null island: CrowdSec writes `0`/`0` for a source it could not place, and that point is
Atlantic water off Ghana — a marker there is a lie, no marker is merely a gap — while a genuine zero
on *one* axis is a real place and stays. `enriched()` treats `""` as no country, since CrowdSec sends
empty strings rather than omitting fields and an operator must never read empty brackets in a breach
mail. Both ride to the browser **as the domain decided them** (`BlockDecisionResponse.locatable`,
`.enriched`), because `0` is falsy in JavaScript and the obvious `if (d.latitude && d.longitude)`
would silently destroy the single-axis carve-out. The same enrichment now lands in the notification
mail: `195.178.110.155 (BG · Techoff Srv Limited) — crowdsecurity/http-probing (ban, 3h0m40s)`.

**REST + SSE.** The three new use cases — `GetBlockDecisionsUseCase`, `LiftBlockUseCase`,
`TrustAddressUseCase` — land on the existing `SecurityService`: same domain concept, more use cases,
no new service, and every decision they need (is this string an address at all, what CIDR does a
bare address become) already belongs to `SourceAddress`. `SecurityRestController`:
`GET /security/decisions`, `GET /security/events`,
`DELETE /security/decisions/{sourceIp}`, `POST /security/trusted-addresses`. All non-whitelisted, so
Traefik's tier-3 catch-all puts them behind the admin auth chain like every other fleet endpoint —
**nothing was added to any anonymous allowlist**; who is blocked, and the power to unblock them, is
never anonymous. `BreachAttemptWatcher` publishes its existing five-minute sweep to a new `security`
topic as `block-decisions` (the sweep result is already in its hands, so a second reader of CrowdSec
would have been pure waste) — inside its own try/catch, so a topic nobody listens on can never cost
the operator the email the watcher exists to send. Each mutation republishes immediately, so a click
shows at once rather than up to five minutes later. The payload is `BlockDecisionResponse` both
times, built in one place, so the stream and the initial read can never disagree.

**Frontend — a fifth EventSource, and a second *native* global.** `explorer-shell.js` grew a
`security` global entry (`ExplorerShellTest` now asserts **five** streams, still no `setInterval`).
It also fixed a latent bug on the way: `kindOf` answered `'settings'` for *every* native global,
which was true only while Settings was the sole one — a second native global would have rendered the
Settings pane under its own name. A native global's kind is now its own name. The Map's threats live
on their own layer, excluded from `coords`/`fitBounds` (a lone scanner in Singapore would otherwise
zoom the fleet view out to the globe every time the Map is opened) and repainted **in place** rather
than by re-rendering, so a push arriving mid-pan does not yank the map away. The threat marker is
deliberately its own visual vocabulary — a radar ping, not a machine chip — so a glance never reads
"someone is probing us" as "my server is down"; it stops animating under
`prefers-reduced-motion`. On a phone the row's verbs stay visible (unlike the file listing's, which
hide behind the floating selection verbs) — a phone is the screen you are most likely holding when the alert
arrives, and hiding them would leave the view read-only there.

**Parked follow-ups (all that remains of #329).** (1) **SSH acquisition** — pointing CrowdSec at the
host's `/var/log/auth.log` so the box's *other* internet-facing surface is watched too; Slice 1 named
Traefik and SSH as the only genuinely exposed surfaces and only Traefik is acquired today. (2) **A
CTI signal-sharing toggle in Settings** — whether this fleet shares its threat signals with
CrowdSec's community intelligence. That is a decision about the operator's own data, so it belongs
in front of them in Settings rather than buried in a compose default.

### 6.29 Fleet threat detection — Slice 2's mail, narrowed to what the operator can act on ✅ (implemented 2026-07-31, part of [#329](https://github.com/getvaier/vaier/issues/329))

**What, and why it is a reversal.** Slice 2's headline promise was "notify the operator when someone
tries to break in", and it delivered exactly that: every newly-appeared **block decision**, emailed.
The operator's verdict after a day of it: *"i get too many breach mails and i cannot act on them so
useless."* They are right, and it broke this project's own rule — **notify only on trouble, wrong or
about to go wrong, and prefer predictive over reactive**. A scanner being banned is CrowdSec working
correctly. Slice 2 shipped success noise wearing a trouble label: 24 mails on day one
(§6.27 celebrates that number as proof the batching worked, which it was — the mail should simply
never have been sent), then more every five-minute sweep, none of them actionable.

**What changed the calculus.** When Slice 2 shipped, email was the only surface and had to carry
everything. Slice 3 (§6.28) has since added the **Security view** and **threat pings** on the Map.
Routine bans now have a much better home than an inbox, so the mail can narrow without anything
becoming invisible. *Silence is not invisibility* — that is the whole trade, and it did not exist to
be made until §6.28 shipped.

**The rule now: mail only when a block decision actually threatens the operator.**

- **Blind scanning sends nothing, indefinitely.** `crowdsecurity/http-probing`,
  `http-wordpress-scan`, `http-backdoors-attempts`, bad user agents, crawlers — background radiation.
  In normal operation the inbox is **completely empty** while the Security view still lists every one.
- **A **credential attack** still mails.** Brute force, password spraying, authentication endpoints —
  somebody has decided to spend time on *this* fleet specifically. Still one rollup per sweep, still
  reported once.
- **A blocked trusted network is its own alarm.** See below.

**`ThreatKind` — a named classification, not a clever regex.** `domain.ThreatKind` is
`BLIND_SCANNING` or `CREDENTIAL_ATTACK`, decided from the words in the CrowdSec scenario's own name
(the author namespace stripped, split on separators, matched whole against `bf`, `brute`, `auth`,
`login`, `password`, `cred`…). Word-matching rather than enumerating CrowdSec's hub, because the hub
grows and third-party collections (`LePresidente/grafana-bf`, `firix/authentik-auth-bf`) follow the
same convention; whole words rather than substrings, so a reader can predict the verdict without
running it. The javadoc is deliberately honest that this is a **judgement about scenario families**
and that an unrecognised scenario is treated as blind scanning and stays **silent** — the safe
direction, because a missed mail costs a look at a view that is already open to the operator, while a
wrong alarm costs the very noise this removes.

**The lockout warning — the one genuinely predictive mail here.** If CrowdSec has an active ban whose
source falls inside the **trusted networks**, nobody is attacking: the allowlist has stopped
protecting the operator's own networks and they are about to lose the console they would fix it from.
That is #329's top-named risk. It is a **separate notification** — `domain.LockoutWarning`, its own
subject (`[Vaier] Lockout warning: your own 10.13.13.6 is blocked at the edge`) and its own body —
and never a line inside something titled "Breach attempt", which would send the operator looking in
exactly the wrong direction. The scenario gets no vote: blind scanning from inside the VPN subnet
still means the allowlist failed.

**Decisions in the domain, as always.** `BlockDecision.threatKind()` and
`BlockDecision.locksOut(TrustedNetworks)` are the two predicates; `BreachAttemptRollup.from(...)` and
`LockoutWarning.from(...)` own the membership rules (a lockout is filtered *out* of the breach rollup
by the rollup itself). `BreachAttemptWatcher` only orchestrates. It reads the allowlist through a new
narrow `GetTrustedNetworksUseCase` on the existing `SecurityService` — the same assembly the whitelist
file is rendered from, so the two definitions cannot drift — and reads it **before** telling
`BreachAttemptTracker` anything, so a sweep that cannot read the allowlist is *deferred* to the next
one rather than silently swallowed. `NotifyAdminsOfLockoutWarningUseCase` is `NotificationService`'s
8th `Notify*`.

**Nothing repeats, and the view is untouched.** Both alarms derive from `BreachAttemptTracker`'s
newly-appeared set, so a standing lockout mails once, not every five minutes. The Security view, the
SSE payload, the Map and every frontend file are unchanged: they show every decision regardless.

**Backlog.** If the silent default ever proves wrong for a scenario the operator cares about, the
answer is a per-scenario override in Settings, not a looser word list — the word list is meant to be
readable, and readability is the thing an accreting regex loses first.

---

### 6.30 Vaier says which user it acts as on a machine ✅ (implemented 2026-07-31, closes [#346](https://github.com/getvaier/vaier/issues/346))

**The problem.** Vaier reaches every machine as the SSH user in its **host credential**, and that user's
privilege is not uniform across the fleet: the DietPi boxes arrive logging in as `root`, the Ubuntu ones as an
ordinary account. Nothing said so anywhere. Same file tree, same **Delete** button, two completely different
blast radii — on one machine a delete removes a file you did not want, on the other it can remove a file the
machine needs to boot; and on the unprivileged ones a **backup run** silently skips whatever the login cannot
read (§6.19's "Back up as root" is the same fact met from the other end). Nobody *chose* root on the DietPi
boxes; it arrived with the image, which is exactly why naming it is the first step to deciding whether it
stays.

**`domain.EffectiveUser` — one judgement, made once.** A record of `(username, privileged)` with a single
factory `of(username)`; a blank or null login yields `null`, which is what "Vaier holds no credential here"
looks like. It judges by the **login name Vaier connects with**, because that name is what fixes the blast
radius of everything Vaier does over SFTP on that machine. The javadoc is deliberately explicit about what the
judgement is *not*: it does **not** claim to detect a uid-0 alias under another name (a second account with
uid 0 reads as unprivileged), and it claims **nothing about sudo** — a non-root user with passwordless sudo is
still unprivileged for Vaier's own file operations, which never go through sudo. That is the honest answer to
the question actually being asked, and it costs **no new SSH round trip**: the credential's username *is* the
effective user and is already stored. Whitespace is trimmed; case is significant, because `Root` is a
different Linux account and Vaier must not claim it is uid 0.

**The feed.** `GET /machines` carries `effectiveUsername` (null when no credential is stored) and
`effectiveUserPrivileged`. The list already asked the credential store one question per machine
(`hasCredential`); it now asks **once** and answers both from the same `HostCredentialView` rather than
re-reading the vault off disk for something already in hand. The boolean travels decided — nothing downstream
re-derives privilege by comparing a string to `"root"`, and `ExplorerShellTest` asserts the browser contains
no such comparison, because a second copy of the judgement is a second answer free to drift from the first.

**Three surfaces, each answering a different question.**
- The machine **Inspector**'s **SSH access** section states it in a sentence: *"Vaier acts as root on X.
  Everything Vaier does here — reading, writing and deleting — runs unrestricted."* / *"Vaier acts as geir on
  X. It reaches exactly what that user can reach, and nothing else."* The root form takes the warning
  treatment.
- The **fleet card** carries a small `root` tag (`.ex-card-root`, amber — a consequence to know about, not a
  fault), so *"where am I root?"* is readable at a glance without opening every machine. A tag rather than a
  third clause on the note line, which already carries a type and an address and would swallow it.
- The **delete confirmation** gains a sentence on a privileged machine — *"Vaier is root on X, so this can
  remove something the machine needs to run."* — beside the existing type-the-machine-name gate. The same
  button means two different things depending on which machine you are standing on; the gate now says which.

**Backlog.** The obvious next question is the one this deliberately does not answer: *should* Vaier be root
there? A per-machine "act as this user instead" (or a warning when a machine's credential is root and does not
need to be) is a real feature, but it is a change to what Vaier *does*, not to what it *says*, and stating the
fact has to come first. Detecting a uid-0 alias, or probing `sudo -n`, would each cost an SSH round trip for
an answer that does not change Vaier's own file operations — out of scope until something needs it.

---

### 6.31 A refused host key gets its remedy ✅ (implemented 2026-07-31, closes [#345](https://github.com/getvaier/vaier/issues/345))

**The problem.** When a machine's SSH host key changes, Vaier refuses to connect on a **host-key mismatch**
and *names* the way out — "clear its pinned key and reconnect" — in the terminal window, in the rail, and in
the Inspector. Nothing in the UI cleared a pin. `DELETE /machines/{machineId}/host-key` had existed since
§6.18 with **no caller**, so the only route out of a refusal was an API client: an instruction the product
gives and cannot carry out.

**The fingerprints travel as data.** `GlobalExceptionHandler.handleHostKeyMismatch` now fills the previously
unused `ApiError.detail` slot with `pinned=<fp>;presented=<fp>`. The message already states both, but it
states them inside a sentence written for a person, and the confirmation has to *show* them side by side; a
client recovering them by parsing prose would break the first time the wording changed. Both are public
host-key fingerprints, safe to hand an authenticated operator.

**Offered only where the refusal was met.** `failureNote(...)` paints a failed Files or Disk read — the
server's own sentence verbatim, as before — and adds **Clear pinned key** only when the envelope's code is
`HOST_KEY_MISMATCH`. It is never routine machine maintenance on a machine's page: a pin that can be cleared on
any ordinary day is a pin that gets cleared out of habit, which is precisely the failure the pin exists to
prevent. `explorer-listing.js` and the disk read now carry `errorCode`/`errorDetail` alongside the message so
the caller can tell that one failure from the others **without reading the prose**.

**The confirmation is the feature.** A changed host key has two causes — a machine you rebuilt, and a machine
somebody is impersonating — and the pin exists for the second, so this is not an "are you sure". The dialog
names the machine, states both causes plainly, shows **Pinned** and **Now offered** as a monospace pair, and
stays disabled until the operator types the machine's name: the assertion *"I changed this machine"* is the
one thing only they can make. It also says what happens next — Vaier pins the new key on the next connect — so
nobody is left believing they have turned host-key checking off. On success the cached failures for that
machine (its disk read, its errored directory entries) are dropped so the retry is a real one, and the fleet
reloads.

**The pop-out terminal, without a modal.** `terminal-window.js` meets the same refusal as WebSocket close code
`4403`. It has no dialog primitives and should not grow a modal for one verb, so `setStatus` gained an
`action`: a status button that **arms on first click**, re-labelling itself *"Confirm — I changed this
machine"* and turning red because it has changed meaning, and performs the `DELETE` on the second. Success
re-labels the status with Reconnect, which re-pins on first use. The refusal sentence itself stays **one**
sentence across `TerminalWebSocketHandler`, `terminal-window.js` and the domain exception — a fourth wording
would leave the operator reading two accounts of the same event depending on which door they came through.

**Backlog.** Vaier could offer to *verify* the new key rather than only accept it (showing where else on the
fleet that fingerprint appears, or checking it against a key read over an already-trusted channel, as
**backup host-key pinning** does for the borg server). Until then the operator's own knowledge is the only
evidence, and the dialog is built to ask for it honestly rather than to look reassuring.

### 6.32 Trusting an address is a one-way door no longer ✅ (implemented 2026-07-31, closes [#348](https://github.com/getvaier/vaier/issues/348))

**The problem.** §6.28 shipped **trust this address** as a verb with no inverse and nothing to look at. The
moment an address was trusted it left the Security view along with the block it came from, and the only
record of the decision was a YAML file on the server the operator would have to SSH in to read. Nothing about
a trusted address is permanent in reality — a dynamic ISP address is handed back, a VPS is re-let, an
operator changes their mind about a colleague's office — and Vaier offered no way to see the list, let alone
change it. A decision the product will not show you is a decision you cannot audit.

**Seeing — a read that deliberately answers a narrower question.** `GetTrustedAddressesUseCase` (on the
existing `SecurityService`) → `GET /security/trusted-addresses`, and a second section, **Trusted addresses**,
in the **Security view**. It returns `ForPersistingTrustedAddresses.getAll()` **straight**, and deliberately
not `getTrustedNetworks()`: that assembles the structural entries too — the VPN subnet, the Docker bridge
CIDR, every relay peer's LAN CIDR — and this payload feeds a screen that hangs an untrust verb off every row
it draws. `SecurityRestController` does not even hold `GetTrustedNetworksUseCase`, so there is nothing there
to leak. The response is a bare dotted quad (`TrustedAddressResponse`), never the `/32` the whitelist file
carries: the operator trusted an address, and that is what the list should say back to them.

**Untrusting.** `UntrustAddressUseCase.untrustAddress(String sourceIp)` → `DELETE
/security/trusted-addresses/{sourceIp}`, reaching `SourceAddress.untrust(store)` — the domain owns the port
call, symmetrically with `trust` — over a new `ForPersistingTrustedAddresses.delete(SourceAddress)` and its
`TrustedAddressFileAdapter` implementation (`synchronized`, read-modify-write like `save`, and an early
return when the address was not there so an unchanged file is not rewritten to say the same thing).
**Removing an address that is not trusted is a success, never an error**: the operator asked for this address
not to be trusted and it is not trusted, and a second click — or a second admin on the same screen — must not
be told otherwise.

**Untrusting is one effect, and that asymmetry is the point.** `trustAddress` is two (persist, then lift the
block); `untrustAddress` is one, because **Vaier never blocks an address**. CrowdSec's scenarios decide that,
as §6.28's `ForLiftingBlocks` rename already recorded, so there is no second half here to mirror the unban:
an untrusted address is simply back to being judged on its behaviour. The confirmation says exactly that, so
nobody clicks it believing they have just banned someone.

**The structural trusted networks are unreachable from either endpoint — two independent gates.** The read
does not return them. And the untrust path has no *name* for one: every structural entry is a prefix wider
than a single host, and `SourceAddress` refuses anything wider than `/32` in its canonical constructor
(§6.28), so no request can be spelled that would remove the VPN subnet. That matters because removing one is
not an unban, it is the **lockout warning**'s own scenario arriving by invitation. The view states in prose
that they are trusted too, are not listed, and cannot be untrusted — in operator words per §17 and §6.20,
never the mechanism ones: *"your VPN, this server's own container network, and every network Vaier reaches
through one of your machines"*.

**The restart asymmetry, now said in both directions.** §6.26 established that CrowdSec re-reads its
whitelist parser only on container restart, and Vaier deliberately does not restart the engine guarding the
edge to apply a rule about who may pass it. Trusting already said so. So does untrusting: the whitelist file
is rewritten without the address on `TrustedNetworksScheduler`'s next five-minute pass, but it leaves
CrowdSec's view of the world at CrowdSec's next restart. Saying it on the way in and staying quiet on the way
out would have been the more comfortable half-truth.

**The trust dialog stopped claiming permanence.** It was titled *"Trust ‹ip› for good?"* and made no claim
about how long the decision lasted, which was fine only while the answer was "forever". It now says the trust
*"lasts until you untrust it here"*. Nothing in Vaier's copy calls this decision final any more — including
the README and the promo page, which both said "trust it for good".

**SSE — a second event on the `security` topic, and nothing on a clock sends it.** `trusted-addresses`,
published by `SecurityRestController` immediately after a trust or an untrust (act, then publish, exactly as
`block-decisions` is republished on a mutation), so the address leaves the list on the click rather than at
some later sweep. Its constant lives on the controller rather than beside `BreachAttemptWatcher`'s, because
the trusted list changes *only* when a person changes it and this is the only place that happens — there is
no watcher to own it. A publish failure is logged and dropped rather than turning a completed change into an
error; the view re-reads on its next SSE reconnect. The browser reads the list once at boot beside
`loadSecurity()` (so `render()` stays a pure function of state), re-reads on every reconnect after the first,
and **never polls** — `ExplorerShellTest` still asserts no `setInterval`. Unlike the blocked list this one has
exactly one screen to repaint: a trusted address is not a threat and gets no **threat ping** on the Map.

**The row is an address and one verb.** A trusted address has no scenario and no expiry — it is not a ban,
it is a decision — so the trusted list has its own two-column grid rather than inheriting the blocked list's
four and leaving two of them empty on every row. Its verb stays visible on a phone for the same reason
§6.28's does: no selection verbs carry it, and hiding it would leave an operator able to see what they
trusted and not to take it back.

**Backlog — the "trusted on ‹date›" timestamp, deliberately not shipped.** The obvious next column is when
each address was trusted, and it was left out on purpose: `SourceAddress`'s **value identity** is
load-bearing in two places — `save`'s dedupe and `delete`'s remove-by-value both rely on two equal addresses
being the same object — so folding a timestamp into the record breaks both, and the alternative is a second
domain type plus a widened port for one line of metadata. More machinery than the feature has earned. The
consequence is that `trusted-addresses.yml` rows stay `- address: ‹ip›` exactly as they were, so the on-disk
format is unchanged and every existing file keeps loading.

### 6.33 The network behind a relay is detected, not asked for ✅ (implemented 2026-07-31, closes [#333](https://github.com/getvaier/vaier/issues/333))

**The problem.** Making a house reachable from the fleet meant typing a CIDR into a free-text field. That
asks a homelab operator to know CIDR notation, to know which subnet their router hands out, and to know that
getting it wrong either does nothing at all or severs a host's uplink — three pieces of knowledge for a
question Vaier could answer itself. It already holds an SSH credential for the machine and already runs `df`
over that exact connection on a five-minute sweep, so the answer was one command away the whole time. Vaier
now reads it and asks only the part that is genuinely the operator's: *should the fleet reach that network?*

**Reading it on the sweep that already runs.** `MachineNetworks` (domain) owns the whole reading: the command
(`ip -o -4 addr show; ip -o -4 route show default`, both halves on **one** exec because an address without the
default-route interface cannot say which network the machine is *on*), the parse, and the two decisions the
parse feeds — `isPseudoInterface` (the direct analogue of `RemoteDiskUsage.isPseudoFilesystem`: `lo`, `wg*`,
`docker*`, `br-*`, `veth*`, `tailscale*`, `tun*`, … are never an operator's LAN) and `lanCandidate()` (the
network on the default-route interface, and nothing else — there is deliberately **no** fallback guess from
the machine's LAN address, because a guessed `/24` is a plausible-looking wrong answer). Parsing is total,
like `RemoteDiskUsage.parseList`: a host without `ip`, a truncated run, a row in an unrecognised shape all
yield an empty reading, never a guessed one. `RemoteDiskWatcher` takes it as a fourth consumer of its sweep —
in its own try/catch, so a failed network read costs the machine neither its disk alerts nor the **SSH server
presence** `df` has already earned on the same trip — and the reading is cached in memory
(`InMemoryMachineNetworkCache`, sibling of `InMemorySshServerPresenceCache`), evicted with the fleet by the
same `retainOnly` pass that clears SSH-server presence. **Opening a machine in the Explorer still costs no SSH
round-trip**, which is the only reason detection could ride the nudges endpoint at all: that endpoint
repaints on every machine click and every other signal it composes is already free. Ephemeral on purpose — a
network can change under Vaier's feet, so losing it on restart is correct and the next sweep re-reads it
within five minutes. A read that came back empty is never *recorded* over a good one, so a machine's detected
network doesn't blink out for five minutes at a time on one bad trip.

**The fifth nudge — ROUTE_LAN.** `MachineNudge.routeLan` joins publish / back-up / designate-backup-server /
back-up-as-root, with the title in the operator's own words (*"Colina 27 sits on 192.168.1.0/24"*) and the
**evidence** naming where the value came from — the machine itself, and the interface it was seen on. Four
conditions, each closing a way the suggestion could be wrong: the machine can relay at all
(`Machine.canRelayALan()` — a VPN peer of a server type; a LAN server has no tunnel to route into and a
personal device is nobody's gateway), nothing is routed for it yet (a machine with a `lanCidr` has already
answered this question, and a nudge is a question, not a correction), Vaier actually read a network, and
routing it would not blackhole the host that installs the route. `MachineNudge` gained a `value` — the datum
being said yes to — so the browser never recovers a CIDR by parsing the sentence it was rendered into; it is
null for every other kind. Accepting goes through the one endpoint that has always routed a LAN, `PATCH
/vpn/peers/{peerId}/lan-cidr`, so **the routing produced is identical to typing the CIDR by hand** with
nothing reimplemented beside it.

**`UplinkGuard` — one statement of a rule that previously existed only as generated bash.** *Never route a
host's own network into the tunnel*: a CIDR containing the address of the host that installs the route
blackholes that host's uplink the moment it lands. Until #333 the rule lived solely inside
`SetupScriptGuard`'s emitted shell, so the Java side had nothing to reuse and every new consumer would have
restated it. It is now one domain class carrying both forms — the predicate (`wouldBlackhole`) and the shell
(`shellRefusal` / `shellHelpers`) — and **the generated setup script's behaviour is unchanged**. The address
judged against is always the host that *installs* the route, never the one that owns the network: a relay is
never at risk from its own LAN, because its own LAN is not routed into its own tunnel. Which is also why the
**Vaier server can never be offered its own network** — its detected LAN contains its own uplink address by
construction, and no rule had to be written for that case. Unknown is not danger: an unreadable address or a
malformed CIDR refuses nothing, deliberately, because a guard that refuses whenever it cannot tell is the
disk alert that could never fire (§6.28) wearing a different hat.

**The free-text field survives, folded.** The Explorer's edit-machine form keeps the CIDR input, relabelled
**Network behind it** and moved under an **Advanced** disclosure that says what it is now for: a network
nothing can detect — a second subnet the machine also fronts. It opens automatically when a value is already
set, because hiding live state behind a fold is worse than showing an advanced field. The nudge is how the
question is normally answered; the field is the escape hatch, not the path.

**Guards, stated as outcomes.** A machine Vaier cannot read is simply not nudged — no guess, no empty form —
and that needed no rule of its own: detection sits behind `checkMachine`'s two existing gates (**SSH access**,
a stored **host credential**). A machine that already has a **LAN CIDR** is never nudged. A pseudo-interface
network is never offered. A network that would capture the routing host's uplink is never offered.

**Backlog — detection for the Vaier server itself, explicitly out of scope here.** The **server LAN CIDR** is
still resolved only by EC2 IMDSv2 or the `VAIER_SERVER_LAN_CIDR` override, which means off EC2 the env var
remains the *only* way to state it — the one place an operator must still know a CIDR. The same reading now
exists for the Vaier server (it is a **machine** like any other and the sweep reaches it via SSH-to-self), so
the missing piece is a resolution step, not a detector. Left out on purpose: `ForResolvingServerLanCidr`'s
order is memoized and load-bearing for LAN-server registration, LAN publishing and split-tunnel `AllowedIPs`,
and folding a per-sweep in-memory reading into a memoized boot-time resolution is a change to *that*
contract, not an extension of this one. `docs/ADVANCED.md`'s description of the env var is unchanged.

---

### 6.34 Vaier mints the key itself, and unusable key material is named on the spot ✅ (implemented 2026-07-31, closes [#309](https://github.com/getvaier/vaier/issues/309) and [#350](https://github.com/getvaier/vaier/issues/350))

**#350's premise was wrong, and the real defect was diagnostics.** The issue reported that ed25519 private keys could not be parsed. They always could: BouncyCastle arrives transitively via docker-java, so Apache MINA sshd reads ed25519, ECDSA and RSA alike, and a test (`SshConnectorKeyParsingTest`, over fixtures in `src/test/resources/ssh-keys/`) now holds that open rather than leaving it to a dependency nobody declared. What actually happened is that a `.pub` public key or a PuTTY `.ppk` pasted into the private-key field **saved perfectly happily** — the vault sees a string — and then failed at *every* connect (terminal, remote command, Explorer listing, backup run) with "The stored private key could not be parsed" and no hint about which of the four things on screen was wrong. Two changes, one at each end. `SshCredentialDraft`'s compact constructor **refuses** a `PRIVATE_KEY` draft whose secret carries no `-----BEGIN … PRIVATE KEY-----` block, so the save is a `400` naming what Vaier expects (a private-key block; ed25519, ECDSA or RSA — not a `.pub`, not a `.ppk`) while the operator is still looking at the form. The check is deliberately **structural, not cryptographic**: whether the bytes are a *valid* key is the SSH adapter's business, and the domain does not parse key material. And `SshConnector`'s connect-time message says the same sentence, for the credentials already stored before this existed — there is no migration, so the message is the only thing that reaches them.

**#309 — a keypair Vaier generates for itself.** Until now every machine's login had to be pasted into a browser, which is both the worst place to carry a private key and a step an operator with no key yet cannot take at all. **Generate keypair** on a machine's SSH credential mints an ed25519 keypair inside Vaier's own process, stores the private half in the **credential vault** like any other secret, and returns the public half for the operator to add to `~/.ssh/authorized_keys` on that machine. The private half never leaves the server: no endpoint returns it, and the dialog has nowhere to show it.

- **The domain decides what a managed keypair is.** `HostCredential.generatedFor(machineId, username, ForGeneratingSshKeypairs)` is the *only* place `managed` becomes true — a pasted credential is never managed, however it was pasted, which is what makes the flag mean something the rest of the system can rely on instead of a value a caller happened to pass. It also fixes a flag that was written to `host-credentials.yml` from the beginning and **read by nothing**. Ed25519, no passphrase, key auth are decided there, not assembled in the service.
- **`ForGeneratingSshKeypairs`** (driven port, `SshdKeypairAdapter`) carries both halves of the JCA/sshd concern: `generatePrivateKey(comment)` and `publicKeyFor(privateKey, passphrase, comment)`. One port, not two — the same boundary. The adapter writes the key with **MINA sshd's own** `OpenSSHKeyPairResourceWriter`, the writer belonging to the parser that has to read it back at connect time, so a minted key cannot be a format Vaier then refuses. Unencrypted on purpose: the vault already encrypts it at rest, and a passphrase Vaier would have to store beside the key it protects secures nothing. Distinct from `BorgCommand.ensureClientKeyPair`, which shells `ssh-keygen` **on a host Vaier can already log in to** — the whole point here is that no such login exists yet.
- **The public half is derived, never stored.** `HostCredential.publicKey(port)` re-derives the `authorized_keys` line from the stored private key on demand, so there is no second copy to fall out of step and **nothing to migrate** for credentials written before any of this. It works for a pasted key too, which is why the dialog shows the public key for every key credential and not only for a managed one — "is this the key I installed?" is the same question either way. The line carries the comment `vaier` (`HostCredential.PUBLIC_KEY_COMMENT`), because `authorized_keys` is exactly where somebody later asks which line is Vaier's.
- **A separate read, on purpose.** `GetHostPublicKeyUseCase` is not folded into `GetHostCredentialUseCase`: deriving the public key means parsing the private one, and the frequent view reads (the machine list, the disk watcher, the backup provisioner) have no use for it and must not pay for it — or fail on it. A stored key Vaier cannot read yields an empty result, logged, rather than an error that would hide the very controls the operator needs to replace that key.
- **Endpoints.** `POST /machines/{machineId}/ssh-credential/generate` (body `{username}` — everything else about a managed keypair is Vaier's decision, not the caller's) returns `{publicKey}`; `GET /machines/{machineId}/ssh-credential/public-key` returns the same shape and `404` when there is no key credential to derive one from. `HostCredentialView`/`CredentialResponse` gain `managed`, which is safe to expose and which the browser needs.
- **The dialog has two shapes.** For a managed keypair there is no private half the operator could edit, so the private-key textarea and its passphrase field are withdrawn and **Save** is hidden — a Save button with no editable secret behind it does nothing, and leaving the textarea up would claim they can paste over a key Vaier holds (to hand Vaier a key of your own, switch to Password or delete the credential first). In its place: the public key with a **Copy** button, because it is going into a file on another machine and hand-selecting base64 is precisely where a character goes missing and an hour of auth debugging follows. Covered by four `ExplorerShellTest` cases.
- **Generating over an existing credential confirms first**, and states the gap rather than leaving it to be discovered by a machine that went dark: the current login stops working immediately, the new one does nothing until its public key is installed, and the machine's files, shell, disk and backups are out of reach in between. The dialog deliberately **stays open** afterwards — the public key is not readable anywhere else, so closing it would send the operator straight back in.

**Backlog — rotating without the gap.** Generating replaces the credential the moment it is minted, so a machine Vaier *can* already reach goes dark until the operator pastes the new public key in by hand. Vaier holds a working login at that instant and could install the key over it — append to `authorized_keys`, verify the new key authenticates, and only then swap the stored credential, keeping the old one if the verification fails. Not built here: it is a different feature (a rotation with a rollback) from "mint a key for a machine you cannot log into yet", and doing it badly locks an operator out of their own host. The manual path stays regardless, since it is the only one that works for a machine with no credential at all.

**Backlog — meeting a `.ppk` halfway.** A PuTTY key is now refused with a message naming it, which is the honest floor; converting one (or an OpenSSH key from a `.pub` sibling) is a step Vaier could take for the operator instead of asking them to run `puttygen`. Deferred: no evidence yet that it happens often enough to justify a key-format converter in the credential path.

---

### 6.20 Vocabulary diet — mechanism words leave the UI ✅ (closes [#339](https://github.com/getvaier/vaier/issues/339), part of the [#330](https://github.com/getvaier/vaier/issues/330) newcomer-onboarding epic)

`UBIQUITOUS_LANGUAGE.md` is an internal contract and is why the codebase stays coherent, but a handful of its terms are **mechanism the operator did not choose and cannot act on** — and those were leaking into labels, toasts, dialogs and one alert email. The fix changes **copy only**: no term is deleted from the glossary, nothing is renamed in code, and the 344 `machineId` / 62 `relay*` / 64 `repositor*` / 35 `cidr` identifiers (including the `/backup-repositories` API path) are untouched.

**§17 "Terms that never appear in the UI"** is the new companion section: operator-facing is now the stated **default** for every term in the document, and §17 lists the exceptions with what the UI says instead — an exception list rather than a third column across 275 rows. The document's header sentence, which licensed every term for "UI copy", is qualified accordingly. The **Concepts page** is the one screen where these words may appear, which is what makes retiring them elsewhere safe; `OperatorGlossary`'s **Backups** group (added by [#334](https://github.com/getvaier/vaier/issues/334)) therefore gains a **Backup repository** entry, since that word was being retired from operator copy with nowhere to send a curious reader.

**What changed on screen** (all in `explorer-shell.js`): the *relay* family — the capability glyph now reads *"Machines on its network are reached through it"*, the LAN setup command says it installs "the routes that let it reach the rest of the fleet", the unroutable-address toast names the fix, and the survival kit counts copies "never two **in the same place**" (a failure domain, not a machine). The **add-a-peer** cards drop *split tunnel* / *full tunnel* and state the actual distinction — a server *"stays reachable from the fleet, and can open up the network it sits on"*, a personal device *"its traffic goes through Vaier while it's connected"*. The network-picker **zero state** is rewritten from scratch, since there is no machine to name when the operator has none, and it no longer offers the by-address path (an address on no reachable network is refused, so with no networks that advice could not work). The **backup-repository long tail** — 18 strings across dialogs, toasts and errors — now speaks the **store label**: a new `repoPhrase()` beside `repoLabel()` renders a store in a sentence as *"‹machine›'s backups"*, falling back to *"these backups"* where no machine claims it (rendering an unclaimed borg id as "where a3f2…'s backups are kept" would say less than nothing). That also fixes a flow that spoke three vocabularies at once — button *"Forget these backups"* → modal *"Delete repository X"* → toast *"Repository deleted."* — into one verb end to end.

**Two visible strings live in Java**, outside the issue's stated search area, because the shell renders backend `message` fields verbatim: `LanServerSetupScript`'s conflict now names the machine rather than "Relay peer ‹name›", and `LockoutWarning`'s **email body** describes the allowlist as "your VPN, the Docker bridge, the networks behind your own machines". Deliberately **not** touched: the comments inside `PeerSetupScript`'s generated shell script (mechanism addressed to someone already in a terminal), the Inspector's `LAN` / `LAN address` / `Cross-site route` rows (labels already clean — only the *value* is a raw CIDR, and rendering a CIDR as a site name is a data change), and `out-of-date config` (zero visible hits; `configOutOfDate` is computed and served but no surface reads it, so writing copy for it would be a new signal, not a rename — recorded in §17 and worth its own issue).

**Tested** by `ExplorerShellTest.theShell_speaksNoMechanismAtTheOperator`, which walks the shell's JavaScript one character at a time to collect its **prose string literals** — multi-word single-quoted literals, so comments and identifiers and icon keys are skipped — and asserts none of them speaks mechanism. A regex over the file could not do this: the retired words must keep living in comments and identifiers, and the shell's comments are dense with apostrophes a quote-counting regex would read as string boundaries. Also `LanServerSetupScriptTest`, `LockoutWarningTest` and `OperatorGlossaryTest`.

---

### 6.35 A secret an existing `.env` predates now stops the stack by name ✅ (implemented 2026-07-31)

**The incident.** Staging (`vaier.vaier.net`) answered every route with a bodiless HTTP 500 — the console included, so there was no surface left to diagnose it from. The cause was one variable: `VAIER_CROWDSEC_BOUNCER_KEY`, the auto-generated bouncer key introduced by [#329](https://github.com/getvaier/vaier/issues/329) (§6.26) and generated only by `install.sh`. Staging's `.env` predated #329 and `install.sh` had never been re-run there, so `${VAIER_CROWDSEC_BOUNCER_KEY:-}` interpolated to **empty**, `crowdsec-bouncer` exited 1 in a crash loop, and its forwardAuth middleware — which sits on the `websecure` entry point ahead of every other middleware and **fails closed by design** (§6.27) — turned every request into a 500. oauth2-proxy was collateral: its OIDC discovery against Dex routes back through Traefik, so it could not start either. Nothing in that chain is a bug on its own. The bug is that an empty string was an acceptable value for a secret no operator ever types.

**Why it stayed silent.** `install.sh` generating a secret only ever helps a `.env` that `install.sh` has since run against. An existing install updates by fetching a newer `docker-compose.yml`, and its `.env` predates every secret added after it was written — the compose file and the `.env` drift apart with no one holding the two side by side. The **self-update** control does not close this: it refreshes only the `vaier` image, never the compose file or the assets ([#343](https://github.com/getvaier/vaier/issues/343), §6.8), which is precisely why staging ran a #329-era compose file against a pre-#329 `.env`.

**The fix, in three parts.**
- **`docker-compose.yml` demands what it cannot generate.** All three auto-generated secrets — `VAIER_CROWDSEC_BOUNCER_KEY`, `VAIER_DEX_CLIENT_SECRET`, `VAIER_OAUTH2_COOKIE_SECRET` — are referenced in compose's **mandatory form** `${VAR:?message}` instead of `:-` or a bare reference. A `.env` missing one now fails at **config-parse time**, naming the variable and telling the operator to re-run `install.sh`. Parse time matters twice over: the message reaches the operator's terminal, where a crash-looping container's exit code does not; and compose stops before touching a single container, so a **running stack keeps running** rather than being half-replaced by a broken one. For `crowdsec-bouncer` this is the *only* guard available — the image is distroless, so there is no shell in which it could fail fast and say why, the way `dex-init` does.
- **`install.sh` is documented as the update path.** It was always safe to re-run; nothing said so, so nobody did. Its header now states that re-running is how you update — it refreshes the compose file and the committed assets, leaves `.env` alone, and tops up any auto-generated secret the `.env` predates — and the closing `Next:` block, itself stale, is corrected: it said "the two shared secrets" when there are three, and told operators to point `vaier.`, `oauth2.` and `dex.` records at the server, contradicting the **wildcard DNS** model (§6.4) that replaced per-service records. It now says one record, once, and adds a line for the update case.
- **The drift guard is the part that generalises.** `InstallScriptCoverageTest` previously asserted the two known secrets **by hand** — which is exactly how #329's key shipped ungenerated and undemanded: adding a variable to compose required nobody to think about it. It now parses every `${VAIER_*}` / `${ACME_EMAIL}` interpolation out of `docker-compose.yml` (skipping `$${…}`, which is an escaped reference for an init container's own shell, not an interpolation) and holds three properties: every variable is classified **operator-authored XOR auto-generated** — the same idiom as the existing bind-mount test, so a new variable fails the build until somebody decides which it is; every auto-generated secret has an `ensure_secret` line in `install.sh`; and every auto-generated secret is referenced only in the `:?` form, with a non-empty message. Blank stays a legitimate value for operator-authored variables — an unset `VAIER_PUBLIC_IP` means "work it out", an absent provider pair means "I don't use that provider" — which is why the classification, not a blanket rule, is the thing being enforced.

**The lesson, stated as a rule.** A new required secret is two obligations, not one: something must **generate** it, and something must **demand** it. Meeting only the first covers fresh installs and leaves every existing one to fail later, in whatever way that secret's consumer fails — here, invisibly and fleet-wide. The test is now what remembers this, because the reviewer who has to remember it is the failure mode.

**Backlog — nothing tells an operator before they update.** The guard fires at `docker compose up`, on the operator's terminal, which is the last honest moment. Vaier itself never says "your `.env` is missing a secret this release added", and it is well placed to: it can read the compose file and the environment it was started with. Deferred as a separate feature, since the useful version of it is part of making **self-update** carry the whole stack (#343) rather than a standalone warning about a file the operator would then have to fix by hand anyway.

---

### 6.36 The Vaier server's own containers are hidden, and the one worth publishing finally shows ✅ (implemented 2026-07-31)

**What the operator saw.** The Vaier server's pane in the **Explorer** listed its own plumbing as **+ Publish** candidates. `docker-proxy` was the sharp one: it serves the unauthenticated Docker API on 2375, so a single click would have put a public hostname in front of root on every container on the host — a **publish flow** doing exactly what it was asked, on a candidate it should never have offered. `vaier-offline` (the "Vaier is down" placeholder) was there too, as noise. Meanwhile the container an operator has an actual reason to publish — Traefik's dashboard — never appeared at all.

**Why.** `domain.VaierServerCatalogue` decides which of the Vaier server's containers become **publishable services**, and it did so from a hand-maintained list of names last true of the stack it was written against: `wireguard`, `wireguard-masquerade`, `authelia`, `redis`, `vaier`. Authelia and Redis were decommissioned in [#305](https://github.com/getvaier/vaier/issues/305) and are not in the stack; oauth2-proxy, Dex, CrowdSec and its bouncer, the socket proxy, the offline page, the log rotator, the LAN-route sidecars and four init containers all arrived *after* the list was written and none of them was added to it. Nothing failed, nothing logged, nothing drew attention — a list that describes a file it is not connected to goes stale in exactly this silence.

**The fix is the binding, not the list.** The catalogue's exclusions are now today's stack, spelled as its own literals rather than borrowed from `ServiceNames` (a **container name** and a **subdomain** that read alike are different concepts), and `VaierServerCatalogueTest` **parses `docker-compose.yml`** and fails the build unless every `container_name` there is classified — either a **hidden container** or an **offered container**. Adding a service to the stack without deciding which it is is now a red build rather than a security decision nobody made. This is the same idiom `InstallScriptCoverageTest` uses for secrets (§6.35), and it is the second time in two days the answer to drift has been *bind the assertion to the file*.

**Traefik's dashboard, actually offerable.** The catalogue has always carried a carve-out meaning to offer Traefik on port 8080 with a `/dashboard/` root redirect, and it had never once fired: discovery reads a container's **exposed ports**, and the upstream Traefik image only `EXPOSE`s 80 and 443. The dashboard listens on 8080 (`--api.insecure=true`, already used inside the stack as `TRAEFIK_API_URL`), so the compose service now declares `expose: ["8080"]`. `expose` is metadata — it publishes nothing to the host and changes no reachability — it only tells Vaier the port is there. The operator publishes it like any other service, choosing the hostname and whether it sits behind **social login**; nothing is published automatically.

**And a second reason it could not have worked, found by checking rather than assuming.** With the port declared, the candidate still did not appear. `DockerService.isOnNetwork` compared the daemon's network names against the bare `vaier-network`, but **Docker Compose names a network `<project>_<name>`** — the daemon reports `vaier_vaier-network`. So *every container in Vaier's own stack read as off-network*, and an off-network container is offered only if it publishes a host port. Traefik's dashboard publishes nothing, so it was unreachable by construction; the operator's containers on that network were reachable only by the accident of a published port, and were then addressed by gateway IP rather than by container name. The match now accepts the project prefix as a whole segment (`vaier_vaier-network` yes, `not-vaier-network` no). This was a real bug hiding behind the catalogue bug, and it would have stayed hidden if the fix had been called done at "the tests are green".

**The consequence of fixing that, also fixed.** Once in-stack containers were correctly seen on the network, their preferred address became `containerName:privatePort` — and an already-published service whose route holds the *old* gateway spelling (`172.20.0.1:8053` for Pi-hole) stopped matching, so Vaier offered to publish something it had already published. *Already published* is a question about the service, not about which of its addresses got written into the route: `DockerService.everyReachableEndpoint` now returns every spelling of one port with the preferred one first, and `ReverseProxyRoute.hasRouteForAny` suppresses the candidate if a route exists under any of them. New routes are still written with the preferred spelling; existing ones are left exactly as they are.

**The operator's own containers are untouched.** The catalogue hides Vaier's stack, not the host: a container the operator runs alongside it — Pi-hole, an MQTT broker — is precisely what the publishable list is for, and is offered on every TCP port it exposes as before.

---

### 6.37 The update mark gains its verb ✅ (implemented 2026-08-03, [#352](https://github.com/getvaier/vaier/issues/352))

**What shipped.** Everything behind an **update** except the button. Vaier could already tell an operator an image was out of date and could offer them nothing to do about it; it can now carry the update out on any container in the fleet the endpoint is asked about — the **Vaier server**, a server **VPN peer**, a Docker-enabled **LAN server** all judge eligibility and would honour the request. In practice the Explorer only ever offers the button where the update-available mark can appear today, which is the Vaier server and server peers — LAN-server containers still read `UNKNOWN` (the §6.8 backlog gap), so a LAN-server container has nothing to offer an Update action on yet, even though the backend is already ready for the day that gap closes.

**The container carries its own address.** Every scrape now reads the four compose labels off each container into **compose coordinates** (`domain.ComposeCoordinates`), and every scrape stamps an **update eligibility** verdict on each container, judged against *which machine it came from* — the same container name means Vaier's own stack on the Vaier server and the operator's container anywhere else, which no name can say by itself.

**The update is a domain object** (`domain.ContainerUpdate`). It decides whether the update may happen at all, builds the two compose commands, owns their deadlines, and reads each result. Two runs, never one: `pull` and `up -d` are issued separately so that **a failed recreate is its own outcome** — the old container is still running, which is the good ending of a bad update, and Vaier says so rather than reporting a generic error. Every `-f` file the project was defined by appears, in order; a project recreated from its first file alone drops what its override files said. The five **update outcomes** carry the operator's sentence with them, so the browser is handed words rather than a code to invent words from.

**The labels are untrusted input, twice over.** `ComposeCoordinates` refuses a name outside compose's alphabet, a relative path, and anything a shell reads as syntax; the command builder then single-quotes every interpolated value and refuses outright any value still carrying a single quote — the case that can only mean coordinates built without the validation. A directory with a space in it is *not* refused: that is somebody's ordinary setup, and it costs nothing once the value is quoted.

**Over SSH, not the Docker API** — the reasoning in the issue holds and `docker-socket-proxy`'s permissions are untouched. This is what forced the one port change: `ForRunningSshCommands` runs everything under a 20-second deadline, which is right for the probes and one-line reads that are almost all of Vaier's remote commands and would abandon an image pull mid-flight. The port gained a timeout-aware overload; the existing two-argument call still runs under the adapter's default, so nothing else changed behaviour.

**Accepted, not awaited.** `POST /docker-services/update` judges everything it can on the request thread — an unknown container is a `404`, one Vaier will not recreate a `409` carrying the plain reason, a machine with no credential a `424` — then hands the run to a single-threaded executor and returns `202`. The outcome arrives as a `container-update-settled` event on the existing `vpn-peers` SSE topic the Explorer already holds open, naming the machine by identity, the container, the outcome and the sentence. It settles even when the host could not be reached: an accept-and-push flow that can go silent is worse than one that never accepted. **That reading is the domain's too** — `ContainerUpdate.carryOut` is the only way in, it cannot throw, and an SSH port that does throw is read there into an **unreachable** settlement rather than classified by whoever happened to orchestrate the run; the failure's own words leave with the settlement as data, so the service can log the cause without the domain learning to log. The host key is pinned on first use from the pull's result, exactly as every other path that reaches a machine over SSH.

**“Report the truth on failure” — the second live one, and the one that mattered.** `netdata` on Colina-27 came back **pull failed**, correctly, and with *no reason anywhere*: not in the sentence, and not one line in `docker logs vaier`. The operator could not tell a docker-group problem from a missing compose plugin from an unreadable compose file from a registry they cannot reach, and neither could anyone helping them. The cause was a rule written here on purpose: a settlement carried words only for a *thrown* attempt, because a non-zero exit was ruled “an answer, not a failed attempt”. Elegant, and wrong — **compose's stderr *is* the answer**, and Vaier captured it in `CommandResult` and discarded it. Now every outcome but **updated** carries an **update diagnostic**: the failing command's **last meaningful line** (stderr, falling back to stdout, since compose is not consistent about which it uses; a timeout says whatever it captured before Vaier stopped waiting), which is where the error sits under however much progress chatter. It is **added to** the sentence and never replaces it — “the old container is still running on the image it had” is the half that says whether the service is down, and losing it to make room for the reason would trade one silence for another. Three constraints shaped it, all of them ways this fix could have made things worse: the settled event is **hand-rolled JSON over SSE**, where a raw newline breaks the framing *and* the parse and the browser's `try/catch` leaves the operator with **nothing at all** — so the diagnostic is reduced to one printable line and `jsonEscaped` now escapes control characters properly, two locks on one door; the captured streams are capped at a mebibyte and **a toast is not**, so it is bounded to 240 characters; and compose output can echo env and path detail from someone's host, so a bounded summary is taken rather than the stream. **And the log finally says something.** Every settled update writes one line naming the machine, the container and the outcome — INFO when it worked, WARN with the host's words when it did not. Recreating a running container is the most destructive thing Vaier does to one, and it used to leave no trace at all.

**The mark outlived the update that resolved it — found on the real fleet, fixed.** `borg-dozzle` and `myadmin-phpmyadmin-1` on Apalveien 5 were genuinely recreated and the settled event said so, and both kept their yellow **update available** mark indefinitely. The sweep files its verdict under a **scoped image** — the machine and the image *tag* — and an update changes the digest while leaving the tag identical, so every later scrape re-applied the same remembered answer; re-reading the containers on the settled event was never going to help, because nothing had re-judged them. A completed update now **forgets** that scoped image's verdict, through a narrow forget-one operation on `ForStoringContainerSnapshots` — rewriting the whole map from the update path would clobber a sweep that landed between the pull and the settle. **Forgotten, not stamped up to date:** Vaier pulled and recreated, it did not ask the registry again and compare it against the new container's digest, so the honest reading is **unknown**, which is also what makes the mark disappear since only `UPDATE_AVAILABLE` draws one, and the Inspector's Update row says "Vaier cannot tell" until the next sweep or a **Check the registries now**. Only an **updated** outcome forgets: after a failed pull, a failed recreate, a timeout or an unreachable host the old image is still what runs, and clearing the mark would be the same wrong-but-confident claim pointing the other way. The verdict is retired *before* the settled event is published, because the Explorer re-reads its containers on that event.

**Catch it early: a machine-level fact, and a fourth verdict.** Colina 27 offered **Update** on all five of its containers and every one was doomed — Vaier's SSH user there is not in that host's `docker` group, so `docker compose pull` dies on `permission denied while trying to connect to the Docker API`. The diagnostic above reported it perfectly, which is exactly the problem: **a good error message is the backstop, never the plan.** Nothing Vaier already knew could have predicted it, because the container **discovery** reads Docker's API over the tunnel and needs no Unix group at all — the machine looks perfectly healthy. So Vaier now learns **Docker command access** as a fact about the machine: a non-mutating `docker version` probe that **rides in front of `df` on the five-minute disk sweep's existing connection**, printing its exit status on a marker line the df parser cannot mistake for a filesystem. No extra sign-in per machine, and the disk reading is byte-for-byte what it was. It is held in memory beside **SSH server presence** and the **machine disk standing**, never persisted — it is what Vaier last saw — and the probe runs on every sweep even for a machine already known to refuse, so fixing the docker group heals the fleet on the next pass with nothing to reset. Containers on such a machine are judged **no Docker access** (the fourth **update eligibility** verdict), whose reason names the remedy rather than only the fault, the way the terminal offers *Clear pinned key* on a host-key mismatch. **Unknown is not no:** a machine no sweep has reached keeps the button, deliberately the opposite of the "no verdict is never permission" rule for **compose coordinates** — missing coordinates mean Vaier does not know how to recreate the container at all, while unknown Docker access only means nobody has looked yet, which is the state of the whole fleet for the first minutes after a restart. Ordering: **Vaier's own stack** outranks everything (its reason has nothing to do with the host), then the container's own permanent blocker (**not compose-managed** does not improve when the group is fixed), then the machine's.

**An update prunes the image it replaced ✅ (2026-09-04).** Colina 27 was holding **16 dangling images and 11.4 GiB**, almost all superseded `netdata` nightlies from repeated updates — on a machine whose disk Vaier itself raises alerts about. What an update replaces, an update now clears up: after an **updated** outcome, `ContainerUpdate` renders and runs `docker image rm '<repository>@<digest>'` for the image the container ran *before* the update, on that host, over the same SSH path the pull and the recreate used. It costs no new reading — the digest is the one the 30-second container scrape already read from the running container's `RepoDigests`. **Named by digest, never by tag:** after the pull the tag points at the *new* image, so removing by tag would delete exactly what the container was just recreated on. **Exactly one image**, with no `docker image prune`, no `-a` and no `--force` — Docker refusing to remove an image another container still runs is a feature here, and it is what makes an over-eager cleanup impossible on somebody else's machine. **It can never change the outcome.** A removal that is refused, fails or throws leaves the update **updated**; the reason travels as `Settlement.replacedImageDiagnostic`, kept apart from the **update diagnostic** on purpose, and reaches Vaier's log (“Kept `<image>@<digest>` on machine Y: …”) and never the operator's sentence — a removal Docker refused is housekeeping that did not happen, not an update that failed. A failed pull, a failed recreate, a timeout or an unreachable host never reaches the removal at all: the old image is still what is running, and deleting it would turn the survivable failure this domain goes out of its way to report as survivable into an outage. Nothing is removed when the running image had no readable digest (built locally, or an inspect that failed) or was already digest-pinned, in which case the pull replaced nothing — guessing at a name would be guessing at what to delete. **And it asks before it deletes.** Docker's refusal is not the only answer `rm` can give: on a canonical `repo@sha256:…` reference to an image that is *still tagged*, Docker **untags** it — quietly, exit 0, no diagnostic — which strips the `RepoDigests` every later sweep reads. That is reachable by an ordinary operator move: pull by hand, then click **Update** to clear the mark. The pull finds nothing newer, `up -d` exits 0, and the 30-second scrape has *already* moved the container's **image digest** on, so the image being "replaced" is the one still running — and removing it would leave that container reporting no digest at all, its verdict **unknown** forever and its mark gone for good. `ContainerUpdate.replacedImageStillRunningCommand` asks first (`docker ps -q --filter ancestor=…`), and the removal proceeds **only** on a run that succeeded and named no container: a check Vaier could not read is not a no. The command is also rendered *inside* the try, so a coordinate that cannot be quoted can no longer escape past a recreate that worked and be classified **unreachable**. Historical leftovers stay out of scope: see the §6.8 backlog.

**The Explorer offers the button.** A container's own page carries an Update action wherever the container is both `UPDATE_AVAILABLE` and `UPDATABLE`; the confirmation names the recreate and the reassurance a failed one carries. Withheld eligibility is not silence — a container Vaier will not recreate, or one that is Vaier's own stack, states the plain reason in its place instead of nothing. The container Inspector's old "Vaier has no endpoint to control one" note is retired: this is now the one verb it has, and the note says so. While a request is outstanding the button reads *Updating…* and is disabled, keyed by machine and container so watching one update never blocks looking at another; the settled event ends the wait, shows the sentence the domain wrote, and re-reads the containers so the mark clears itself on success or the operator sees what is actually there on failure. **The daily sweep is unchanged** — it still only reads and never pulls; what changed is that the operator now has a button next to what it reads.

---

### 6.38 Nothing is marked that nobody can act on ✅ (implemented 2026-08-03, closes [#353](https://github.com/getvaier/vaier/issues/353))

**What the operator saw.** `traefik:v3.6.14` and `getvaier/vaier:latest` both reading **update available** on the Vaier server — and checking rather than assuming showed the sweep was *right*: upstream re-pushed the pinned tag, as they do for a base-image rebuild. The verdict was true and unactionable. Worse than a mark: `ImageUpdateTracker` is edge-triggered and deliberately not baseline-quiet, so it arrives as **admin mail**, which is exactly what `feedback_notify_only_on_trouble` exists to prevent. An alert whose only honest resolution is *wait for a Vaier release* teaches an operator to filter the channel, and that costs the alerts that matter.

**Vaier's own stack is no longer swept — at all, with no exception.** `VaierServerCatalogue.sweepable` drops it from the containers handed to the sweep, **before any registry is asked**: an image nobody can act on should not spend the rate limit either, and that limit is shared with every image the operator *can* act on. The decision stays in the domain, on the class that already answers *is this Vaier's own stack?* and is build-guarded against `docker-compose.yml` (§6.36), so it cannot drift from what the stack actually runs. It applies **only to the Vaier server**: a peer running its own `traefik` or `redis` is the operator's container and is swept exactly as before — which is why `judgeVaierServerContainers` and `judgeOperatorContainers` are separate, and it has its own test.

**The plan of record kept `vaier` itself; the operator overruled it, and was right.** The reasoning here was that Settings → Update is a real button, so that one mark was actionable. Two things beat it. Settings does not need the mark — it asks the registry about its own image directly — and on a host that **builds Vaier locally** the local digest differs from what Docker Hub serves for `latest`, so the mark reads "newer available" when acting on it would **downgrade**. A mark that talks an operator into a downgrade is worse than no mark. No exception is also a simpler rule than one exception, and the simpler rule is the one that survives.

**Three things that would have made this a regression instead of a fix, all handled and all tested.** *Stale verdicts:* stopping the sweep is not enough on its own, because the last `UPDATE_AVAILABLE` sits in memory and would keep the dot lit until a restart — which is the operator's exact complaint, unfixed. The sweep's write is authoritative (it replaces the verdict map wholesale), so a mark already held goes clear on the very next sweep; there is a test that seeds the pre-#353 verdict and proves it. *The tracker:* it already forgets entries absent from a sweep's verdicts, so an own-stack image that had alerted is un-latched rather than left latched forever — and if such an image ever returned to the sweep stale it would be news again, which is the right answer for a container that stopped being Vaier's own. Both halves are now pinned by tests. *The self-update:* `SelfUpdateRunner` read the sweep's verdict for `getvaier/vaier`, so dropping `vaier` from the sweep would have left **Settings permanently blind to every update there is** — turning this fix into a regression on the one path the operator's decision was justified by. `SelfUpdate.updateAvailable` now takes `ForResolvingRegistryDigest` and asks for itself. Only a genuine digest difference counts: an unreachable or rate-limited registry, a throw, or a locally-built image with no digest all read as *cannot tell*, which is never a reason to recreate the container the fleet's whole control plane runs inside.

**Worth knowing.** Only containers with **exposed ports** are scraped, so CrowdSec, Dex, oauth2-proxy and the bouncer never appeared in this list at all — the problem was wider than the two visible offenders, and would have grown the moment one of them declared a port. They are covered now by name rather than by accident.

### 6.39 A nudge stops asking a question the operator has answered ✅ (implemented 2026-08-04)

**What the operator saw.** The Vaier server's pane kept offering *"Publish 2 services"* — and the two were `mosquitto-broker:1883` and `borg-borg-1:8022`, both **ignored services** the operator had already dismissed. Ignoring is how you say *no, not this one*; a nudge that counts your own "no" as a reason to ask again is the thing that teaches an operator to stop reading the nudge rail, and the rail is only worth having while it is read.

**The count was taken off a feed that deliberately keeps the dismissed ones in it.** `getPublishableServices` returns every discovered candidate with an `ignored` flag rather than dropping the dismissed ones, because dismissing has to stay undoable from the same screen that did it — the Explorer folds them behind **Show ignored**. The nudge's count filtered that feed by ownership alone (`belongsTo`), so it counted exactly what the list below it was hiding. The two surfaces disagreed about the same machine, in the same pane.

**The decision moved onto the entity.** `PublishableService.awaitsPublishingOn(MachineId)` answers *is this still an open question on this machine?* — it sits there **and** has not been dismissed — and the driving edge only counts what the domain judged. Ownership stays a separate predicate; joining the two in the controller would have put the rule in a driving adapter, which is where it was not.

**One nudge, one cached answer, invalidated by the action that changes it.** Nudges are read once per machine and never polled ([[feedback_frontend_never_polls]]), so the card would have kept its stale count for the rest of the session even with the backend right — the operator would click **Ignore**, watch the row fold away, and see the card above it still asking. `reloadServices` now drops the machine's cached nudges, exactly as the settled-run push does: the operator's own action is the signal.

### 6.40 The mark arrives with the mail, and a machine opens on what it is ✅ (implemented 2026-08-04)

**The daily sweep told no one.** Colina's `netdata` alert landed in the operator's inbox while the Explorer showing that container went on showing no mark until the page was reloaded by hand. `ImageUpdateWatcher` swept, stored the verdicts and mailed — and published nothing. The operator's own **update check** had pushed since #57 slice 3; the daily path never did, so the one moment Vaier *learns* an image went stale was the one moment it told nobody already looking. A backend that knows and stays quiet is [[feedback_frontend_never_polls]] inverted: the browser cannot go looking, so the push is the only way the news travels. `sweepImageUpdates` now builds the same `UpdateCheckOutcome` the button does and pushes on `worthPublishing()` — one decision, so the schedule and the button can never disagree about what is worth repainting, and a quiet day still wakes nobody.

**A machine pane opened on mechanism.** Tunnel address, endpoint, latest handshake, transfer and Docker port, as a table, above what the machine is for and what is inside it — the addresses an operator reads rarely and types almost never. The pane now leads with what it is (**Role** for the hub, otherwise the **device category**, rendered as its label rather than the raw enum) and how it is doing (last handshake, or last seen for a **LAN server**), then **Inside this machine**. The addresses moved into a collapsed **Connection details** fold — the shell's existing `disclosure()`, the same one Advanced already uses, so nothing new was invented and nothing was lost: they are one click down. [[feedback_simplify_operator_decisions]].

### 6.41 One word for one act: "upgrade" is retired ✅ (implemented 2026-08-04)

**The same container offered the operator two words for one thing.** A yellow **Update available** mark, an **Update** row in the Inspector, and an **Upgrade** button underneath — the noun and the verb of a single act, spelled differently, on one card. The split was deliberate once (verdict vs action) and it did not survive contact with a person reading the screen. **"Update" now wins everywhere, in copy and in code**; the concepts stay distinct and are told apart by one rule instead of two words — **the act is bare "update", the verdict is always "update available"**, so the longer name goes to the thing that would otherwise be ambiguous.

**Names chosen against the neighbours they will be read beside.** `UpdateOutcome` would have sat next to the existing `UpdateCheckOutcome`, and `UpdateEligibility` next to `UpdateAvailability` — pairs differing by a word an eye skips. The act's types therefore carry the noun they belong to: `ContainerUpdate`, `ContainerUpdateOutcome`, `ContainerUpdateEligibility`, beside `SelfUpdate` / `SelfUpdateScript` / `SelfUpdateStatus` / `SelfUpdateRunner` and `UpdateContainerImageUseCase` / `UpdateVaierUseCase` / `GetSelfUpdateStatusUseCase`. Endpoints became `POST /docker-services/update` and `GET|POST /settings/update`; the SSE event became `container-update-settled`. A pure rename — no decision moved layer, and the existing tests are the safety net (4198 green).

**One surface was deliberately not renamed: the self-update's account on the host.** `SelfUpdateScript` writes `$HOME/.vaier-upgrade/last-upgrade` and the words `UPGRADED` / `ROLLED_BACK` / `FAILED` into it, and `SelfUpdateStatus.Outcome` parses that line by constant name. **The writer is always the *old* Vaier's script and the reader is always the new one** — that is the whole shape of replacing yourself — so renaming the path or the words would lose the account of the very update that renamed them, and a rollback would go silent on exactly the release where it mattered. The literals stay; `Outcome.UPGRADED` keeps its old spelling because the constant's name *is* the on-disk format. **Backlog:** a reader that accepts both spellings would free the constant, at the cost of a migration branch on Vaier's most dangerous path — worth doing only alongside #343, which has to touch this script anyway.

---

### 6.42 The self-update's verdict is the domain's, not the browser's ✅ (implemented 2026-08-04)

**A rule with a dead copy and a live one.** `SelfUpdateStatus.trouble()` — whether a self-update outcome is worth telling the operator about — had no production caller at all; only its test. The Settings page re-derived the same answer from the raw enum name, `outcome === 'ROLLED_BACK' || outcome === 'FAILED'`. The two agreed, and nothing kept them agreeing: a new trouble outcome would have gone silent in exactly the place silence costs most, since **a rolled-back Vaier is a running Vaier and looks perfectly healthy**. `UpdateResponse` now carries the domain's `trouble` verdict and the shell renders off it, choosing its wording per outcome with the failure sentence as the fallback — so an outcome the browser has never heard of still says something rather than nothing. Found by the hex audit that ran over the vocabulary rename (§6.41).

---

### 6.43 A root redirect that pointed at itself ✅ (implemented 2026-08-04)

**`ERR_TOO_MANY_REDIRECTS` on a freshly published service, and Vaier had built the loop itself.** Publishing with the root-redirect field left empty still wrote a `redirectRegex` middleware whose replacement was the bare host — and the trigger, `^https://host/?$`, matches the bare host. The target re-matched its own trigger, so the browser was sent back to the URL it had just been sent from until it gave up. **Five live routes carried it**, not one: the operator only ever noticed the one they opened.

**One rule, stated once.** `ReverseProxyRoute.normaliseRootRedirectPath` says what a root redirect *is* — blank and `/` are no redirect, anything else gains its leading slash — and the Traefik adapter applies it at all three points where it writes routing config, so no caller can hand it a loop again.

**A second defect the scan exposed: deleting a service left its redirect middleware behind.** `removeRouterSidecarMetadata` cleaned four sidecar keys but not the middleware, so three of the five loops belonged to routes that no longer existed — unreachable litter, since the endpoint that would clear one refuses a router that is gone. The delete path takes the middleware with it now.

---

### 6.44 The map gains a green dot for everywhere Vaier lets someone in ✅ (backend implemented 2026-08-09)

**The mirror image of a threat ping (§6.28).** The Map has shown who the edge keeps *out* since Slice 3, and nothing at all about who gets *in*. The hook was already there and unread: Traefik forward-auths every request to every gated host through `GET /authz/verify`, and the `vaier-authz` middleware has always set `trustForwardHeader: true` — so `X-Forwarded-For` was arriving and being thrown away. On an **ALLOW only**, Vaier now notes where the request came from.

**Aggregated per place, not per address.** An `AccessSource` is a city: its country, its coordinates, how many allowed accesses have come from it, when the first and most recent were, and up to twenty of the people allowed from there. One dot per city, however many addresses a household's ISP cycles through — which is also what keeps the store bounded. Remembered for one month after the most recent access, then pruned. Everything Vaier gates counts one: a **published service**, the console, the **Explorer**, Settings.

**An address with no place is kept, not dropped.** A LAN, VPN or private caller has no map position and `ForGeolocatingIps` rightly returns nothing for it. Those accesses fold into a single **unplaceable access source** with no city, country or coordinates: the Map has no dot to draw for it and shows its count as a muted note beside itself, and the totals stay true. Silently discarding them would have made every number on the screen a lie.

**Two caps, both in the domain.** Twenty people per place, and two hundred places. Neither is the store's rule to enforce: a limit checked by whoever happens to be writing the file is one every other writer can skip. The places cap evicts the least recently seen, because every recording copies the whole list on the forward-auth path — an inflated store is a cost every gated page load in the fleet pays.

**Nothing here may cost anybody their access.** Recording rides on the endpoint that authenticates every request to every gated service, so: it never touches the disk (state is held in memory and flushed by `AllowedAccessFlushScheduler` on a 60s `fixedDelay`, plus a `@PreDestroy` so a restart doesn't lose the last minute), and the call is wrapped at both the use case and the controller — a broken geolocation database or an unwritable store costs a dot on a map, never a locked door. That property has its own test asserting the `200` and the identity headers while the recording throws.

**Decisions in the domain, as usual.** `AccessSource` owns `locatable()` (with the same null-island carve-out as `BlockDecision`, and for the same reason — `0` is falsy in JavaScript, so a browser-side `if (lat)` would delete the equator), `isSamePlaceAs`, `withAccess`, `isStale`, the twenty-person cap, and `place()`; `AccessSources` owns the merge and `pruned(now)`. The coordinates-absent-together invariant is enforced in the record's canonical constructor rather than only in its factory, because the file adapter reconstitutes sources through the builder and that is exactly the path a hand-edited file arrives on. `locatable` **and** `place` are shipped pre-decided on `AccessSourceResponse`, mirroring `BlockDecisionResponse`'s `origin`.

**A place the database placed but could not name is still a place.** Merging on city and country alone made an unnamed Oslo equal to an unnamed Singapore — one dot standing for two continents, drawn wherever the first of them was. When both names are absent the coordinates decide the merge, and `place()` names such a source by its coordinates rather than "Not placeable": that label belongs to the unplaceable bucket, and on a marker `locatable()` has just called drawable it contradicts the dot it sits on.

**`CallerIp` — one trusted-hop rule, no longer a copy.** Which hop of `X-Forwarded-For` to believe was a private method on `LaunchpadRestController`. A second caller would have meant a second copy of a *security* boundary, so it moved to `domain.CallerIp` and both controllers ask it. It believes the leftmost hop, which is trustworthy only because Vaier sets no Traefik `forwardedHeaders.trustedIPs` — so Traefik deletes any client-supplied `X-Forwarded-*` before writing its own. It believes that hop only when it is an IP literal (a name would mean a blocking DNS lookup inside forward-auth), and an unparseable **trusted proxy CIDR** falls back to the socket address instead of throwing a `500` out of the launchpad. The property is `vaier.trusted-proxy-cidr`, with `launchpad.trusted-proxy-cidr` still read as a fallback: the boundary now gates forward-auth, and nobody should edit a launchpad key to fix who authenticates their fleet.

Persisted to `access-sources.yml` (§8; distinct from `access.yml`, which holds permissions rather than places). Served by `GET /security/access-sources` behind the same admin auth as the rest of the Security view, and pushed on the existing `security` SSE topic as `access-sources`. Use cases live on the existing `SecurityService` — same domain, same topic — but **the Map is the only surface that draws these**: the Security view has no access-sources section in this change, and the SSE push repaints the map's layer in place rather than re-rendering anything.

### 6.45 A phone's own position beats a carrier's registry entry ✅ (implemented 2026-08-11)

**The bug this closes.** The Map plotted an operator's phone at Fornebu, near Oslo, while the phone was actually north of Trondheim. Cause: the phone's WireGuard endpoint is a Telenor carrier address, and the whole carrier block resolves to one point in DB-IP — the carrier's registry entry, not the device. Compounding it, that peer's tunnel had been down 35 hours, so the map drew a day-and-a-half-old carrier IP with present-tense copy ("connecting from"), as though the device were there now.

**Placement replaces the raw geolocation for clients.** `domain.Placement.decide(...)` is the one decision: a **reported position** always wins over an **ISP estimate**, even a fresher one — a coarse measurement does not outrank a precise one by being recent, so freshness is communicated by showing age (`asOf`, `stale` past 24h) rather than by letting the coarse source win. A disconnected peer with no reported position gets **no placement at all** (`Optional.empty()`) — `wg` keeps naming the last endpoint it saw, and a stale carrier IP is not a location. `VpnService.toVpnPeerView` computes it once per peer per refresh and rides along on `GetVpnPeersUseCase.VpnPeerView.placement()`, serialised as `PlacementResponse` on `GET /vpn/peers`.

**The Map draws exactly what `placement` says, nothing re-derived client-side.** `explorer-shell.js`'s map layer no longer falls back to `p.latitude`/`p.longitude` for a client peer (those still carry the raw ISP guess — the exact field that put a 35-hours-disconnected phone at Telenor HQ). A `REPORTED` placement draws a firm pin, faded (`.stale` CSS class, not dashed — dashed already means "not a real fix") past 24 hours old. An `ISP_ESTIMATE` draws a soft, fixed-radius uncertainty circle, never a pin, with copy that says plainly "where the carrier registered this address — not where the device is." No placement draws nothing. The **Mobile/client dual marker**'s server-side stack (firm marker at the Vaier server, for `AllowedIPs = 0.0.0.0/0` traffic) is unchanged and never waits on `placement`.

**Device claim — the mechanism that lets a phone report with the tunnel down.** A one-time binding between one browser and one machine (`domain.DeviceClaim`, minted from `POST /vpn/peers/{machineId}/device-claim`), because Android drops WireGuard constantly, so "tunnel down" is the ordinary case for a phone, not an edge case. Made only from an authenticated console session — the path is gated by the same admin forward-auth chain as every other peer-mutating endpoint, no exemption of its own — asserting *from* the console *which* device the operator is holding, the opposite direction from a device asserting its own identity. Carried afterwards as `vaier_device_claim`, an HttpOnly, Secure, `SameSite=Lax` cookie scoped to `/vpn/peers`, holding an opaque server-verified token (`DeviceClaim.mint`, 32 random bytes, compared with `MessageDigest.isEqual`) — deliberately not a self-contained signed blob, which could not be revoked. One claim per machine; a fresh claim supersedes the last, no un-claim step required first.

**Position reporting.** `POST /vpn/peers/my-position` takes coordinates only (`ReportMyPositionRequest{latitude, longitude, accuracyMetres}`) — nothing that could name a machine, so a device can only ever report its own position. Which machine it is about is resolved in the domain (`MachinePositions.reportingMachine`): the caller's **tunnel identity wins whenever it names a machine** (WireGuard authenticated that packet, so it can't be forged), falling back to the device claim cookie (a copyable bearer token) only when the tunnel is down or the caller isn't a peer at all. Whether the caller *has* a tunnel identity is one rule in one place (`domain.TunnelCaller`, shared with §6.47): the address must sit inside the **VPN subnet** *and* be held by a peer. Both halves, deliberately — the store lookup alone would let a carrier address that happened to match a peer record name a device it has nothing to do with, and until 2026-08-12 the position path did exactly that, agreeing with §6.47 only by the accident that peer configs carry `10.13.13.x` addresses. That rule runs **inside the store's own lock**: `ForPersistingMachinePositions.recordReportedPosition` takes what identifies the caller — the tunnel-derived machine id and the claim token — rather than a machine to file the report under, resolves identity and merges the position as one step, and answers which machine it attributed to. Nothing identifying the caller writes nothing at all and throws `UnidentifiedDeviceException` → `403`. `DELETE /vpn/peers/my-position` is the privacy escape hatch: forgets the reported position **and** revokes the claim in one action (`MachinePositions.without`), then clears the cookie — a claim with no way back to "unclaimed" would be a one-way door. `GET /vpn/peers/my-device` answers which one machine *this browser's* cookie is bound to (never derived from `document.cookie`, since it's HttpOnly) — a claim is a property of the browser holding it, never described as "claimed" against the machine to any other viewer.

**Opt-in throughout, on the frontend.** The claimed-device pane offers **Share position** (one-shot, via the W3C Geolocation API) and **Forget** side by side — the way out sits exactly where the way in does. A **"keep this device's position up to date"** checkbox, off by default and stored per-machine in `localStorage` (a per-browser habit, not a server setting), silently refreshes the position on every Explorer visit with no new permission prompt and never toasts — a background habit the operator opted into once, not an event of the visit. A `403` on report (claim lapsed or superseded elsewhere) resyncs `my-device` and quietly stops offering Share rather than leaving a stale claimed UI up.

**New vocabulary:** **reported position**, **ISP estimate**, **placement**, **device claim** — see `UBIQUITOUS_LANGUAGE.md`. **Geolocation** and **Mobile/client dual marker** were updated in place rather than replaced.

### 6.46 A machine's last service reached ✅ (implemented 2026-08-12)

**What Vaier now says.** A machine's pane in the Explorer carries one more line: `Last service — Grafana · 3 hours ago, over the tunnel` — the gated host it most recently reached through the forward-auth check, and when. The console and the Explorer count as much as a **published service**, since the same check gates every one of them; a machine that only ever opens Vaier itself still gets an honest answer rather than a blank.

**The hook was already there.** `AuthzRestController` receives `X-Forwarded-Host` on every gated request — Traefik's forward-auth middleware has always set it — and was throwing it away. No new sweep, scheduler or probe: the endpoint now passes `host` alongside the caller IP and person it already recorded for the access-sources map (§6.44), and `RecordAllowedAccessUseCase.recordAllowedAccess` grew a fourth parameter to carry it.

**Attributed to a machine only over the tunnel, and said so on the row.** `domain.LastServiceReached.reachedOverTheTunnel` asks `domain.TunnelCaller` who the caller is, which checks the caller IP against the VPN subnet (`wireguard.vpn.subnet`) before it asks the peer store who owns it — a caller IP that authenticated over WireGuard *is* the peer, but a carrier IP, a LAN address or a public-internet address names a person, not a device, and a whole mobile block resolving to one point would draw one household's access on a stranger's machine. There is deliberately no endpoint-IP fallback for a disconnected peer, the same call `Placement` (§6.45) already made for the same reason. This is a stated limitation, not an implementation detail left in code — the row says "over the tunnel" precisely so the operator reads why some machines never show one.

**Named the way the launchpad already names it.** `ReverseProxyRoute.launchpadDisplayNameFor` looks up the route serving the reached host and reuses its **launchpad display name**, preferring a host-only route over a path-scoped sibling on the same host; a host with no published route (Vaier's own console, for one) stands on its own rather than under an invented label.

**Held in memory, flushed with the access sources it rides alongside.** `AccessSourceFlushScheduler` is renamed **`AllowedAccessFlushScheduler`** — the class-name change is a rename only, no behaviour change — because it now flushes two records that both derive from an **allowed access**, on the same minute-clock and the same `@PreDestroy` shutdown hook: the **access sources** it already wrote, and now `last-services-reached.yml`. Each flush is independently guarded, so a failure writing one cannot cost the other its turn — recording an access must never cost anybody their access, the same rule §6.44 stated for the map dot. Nothing is pushed over SSE for it: the peer view reads the store directly on every `GET /vpn/peers`, so there is no stale payload for a push to correct.

**New vocabulary: Last service reached** — already added to `UBIQUITOUS_LANGUAGE.md` alongside the code; not repeated here.

### 6.47 The Map stops resetting the operator's pan and zoom ✅ (implemented 2026-08-12)

**The bug.** Every render of the Map tore the Leaflet map down and rebuilt it from scratch — a fresh `L.map(holder)`, then `fitBounds` over the whole fleet — so a live SSE update (peer stats, a threat ping, an access source) threw away wherever the operator had panned or zoomed to, closed any popup they had open, and reframed the world map out from under them. Was mostly hidden by how rarely a full render used to fire; §6.44's and §6.45's new SSE-driven and per-refresh repaints made it fire often enough to be annoying.

**Built once, kept for the tab's life.** `ensureMap` constructs the map, its tile layer and its cluster group exactly once and holds them in module state; each render re-attaches the same holder element into the freshly emptied pane rather than constructing a new one, and only calls `invalidateSize` (never `fitBounds`) to settle its size. `trackResize` is off — Leaflet's own resize handler would otherwise fire while the element is briefly detached between renders, measure a 0×0 container and pan by half its old size — and the one real case it was right about, the browser window resizing while the Map is on screen, is handled by a plain `resize` listener that checks the element is still attached first.

**Framing runs once per visit, not once per render.** `fitBounds` now fires only on the first paint after navigating to the Map (`_mapFramed`, reset whenever the Explorer's location moves away from `map`); a data-driven update never re-frames. Leaving the Map and coming back is a fresh visit and reframes the fleet, exactly as opening it for the first time always has.

**Fleet markers are keyed and diffed, not wiped and redrawn.** Each pin (`syncPins`) and ISP-estimate region (`syncRegions`) is keyed by the machine it belongs to and the role it plays there — a client peer draws twice, at its **placement** and at the server hub, so machine identity alone isn't a key on its own. A render now adds what's new, moves (`setLatLng`) what changed position, re-dresses (`setIcon` / popup content) what changed look, and leaves everything else strictly alone — so a popup an operator has open survives an update that doesn't concern it. **Threat pings and access-source dots are deliberately not treated this way**: a block or a sign-in is an event with no persisting identity to diff on, so `repaintIntoCluster` still clears and redraws that layer wholesale every time — the distinction drawn is between a thing (a machine, which persists and moves) and an event (which doesn't).

**Refines, rather than repeats, §6.44's promise.** §6.44 already said the security SSE push "repaints the map's layer in place rather than re-rendering anything" — that was true only of the access-sources layer. This extends the same discipline to the fleet layer, which until now was the one thing on the Map still rebuilt from nothing on every single paint.

**Frontend only** — `explorer-shell.js`; no backend, API or persisted-format change.

### 6.48 The position trail — where a device has actually been ✅ (implemented 2026-08-12)

**What the operator asked for, and why §6.45 alone could not give it.** §6.45 fixed *where a device is*; it stores exactly one **reported position** per machine, so a phone carried across the country still draws one dot. The operator wanted the journey. The **position trail** (`domain.PositionTrail`) is that: the machine's own reported positions, oldest first, drawn on the **Map** as a line.

**Reported positions only — never ISP estimates.** This is the whole feature's load-bearing constraint, not a detail. Telenor's entire `77.16.0.0/13` resolves to their head office at Fornebu, so a trail built from **ISP estimates** would be a line from Fornebu to Fornebu however far the device actually travelled — the exact dishonesty §6.45 exists to remove, drawn at greater length. Enforced by type: a trail only ever accepts a `ReportedPosition`.

**Placing each allowed access by GPS was considered and rejected on security grounds.** It looked like the cheap route — `/authz/verify` already sees every gated request — but the device claim cookie is `Path=/vpn/peers/` and host-scoped, so that endpoint cannot see it, and widening it to `Domain=.<domain>; Path=/` would broadcast a five-year position-write bearer token to every published service on every request. Not worth a map line. The trail is built from position reports instead.

**Not every report earns a place.** A browser sharing continuously would otherwise write the file on every fix. A point is kept only when it is meaningfully new — **at least 10 minutes** after the last kept point, **or more than 100 m** from it (`PositionTrail.MIN_INTERVAL`, `MIN_MOVE_METRES`). Distance is an equirectangular approximation, deliberately cheaper than haversine at these scales, with longitude scaled by the cosine of the latitude: at 63°N a degree of longitude is under half a degree of latitude, and skipping that factor reads a device sitting still as one that has moved 165 m. A report that does not earn a place is not lost — it is still the machine's current reported position, which answers the other question.

**Bounded twice: 30 days, and 500 points.** Retention (`PositionTrail.RETENTION`) is the bound that matters to a person — how long Vaier remembers where they went — and is applied on write *and* on read, so a device that stopped reporting stops being drawn rather than freezing a month-old trail on the map forever. **Note the difference from §6.45, which readers will otherwise trip over:** a single reported position "persists until forgotten" with no expiry, while the trail behind it expires at 30 days. `MAX_POINTS = 500` is the bound that matters to the file and the payload — retention alone bounds a trail in time but not in size, since at 100 m granularity one day's driving can produce thousands of points — and the most recent are what survive the cap. The 30 days deliberately does **not** share `AccessSource.RETENTION`'s constant: two unrelated concepts that happen to agree today.

**Forgetting takes the whole trail.** `DELETE /vpn/peers/my-position` already erased the position and revoked the claim together; it now erases the trail in the same one action. A trail outliving a Forget would be the worst version of this bug — the operator told their whereabouts are gone while a month of them stays on disk — so it is asserted against the file's own bytes, not merely through the store's read path.

**Persisted in the existing store, in the clear.** No parallel file and no new port: the trail belongs to the same `MachinePosition` record and the same `machine-positions.yml`, and rides the existing intent-shaped `recordReportedPosition` write, merged inside the adapter's lock. That intent shape is preserved deliberately, and has since been tightened: a caller handing back a whole record it read moments ago is how a revoked claim comes back from the dead, and a caller handing back a machine id it resolved moments ago is how a forgotten device comes back with a fresh trail point. The trail is **not** encrypted, unlike the claim token: a lost or rotated vault key already costs the claim, and it must not silently cost the operator their history as well. Reconstituting stored points deliberately does not re-run the "does this earn a place" decision — that was settled when the point was reported, and re-running it on load would add a point every time somebody opened the map.

**No new endpoint.** The trail rides on the existing `GET /vpn/peers` payload as `positionTrail`, gated by the Tier-3 admin forward-auth catch-all like everything else on that router — nothing anonymous, no new exemption.

**One trail at a time, and older recedes.** The Map already carries a chip per machine, a second chip per client at the hub, soft ISP circles, green access dots and red threat pings; every trail always on would be a permanent scribble, and two travelling devices would cross into one unreadable tangle. So a trail is drawn for the machine whose **popup is open** — opening a machine's popup is already how the operator asks about that machine, so it needs no new control and nothing to switch off. The line is drawn in twelve bands that thin and fade toward the past (one polyline per band, not one per point, so a 500-point trail is twelve layers), with a dot per retained point so that clustered dots read as a device that lingered and strung-out ones as a device that was moving. Direction is legible without an arrowhead or a legend. It updates on the `vpn-peers` SSE topic the fleet layer already repaints on — no timer, no poll.

**New vocabulary:** **position trail** — see `UBIQUITOUS_LANGUAGE.md`. The **Map** entry was updated in place.

---

### 6.49 Forget becomes absolute ✅ (implemented 2026-08-12)

**The gap §6.45 left.** §6.45 closed the *write* side of the Forget race — the store merges one field into what is on disk, inside its own lock, so a stale snapshot cannot resurrect a revoked claim. The *identity* side stayed outside it: `VpnService.reportMyPosition` read the store, asked the domain who was talking, and only then wrote. A **Forget** landing in that gap left the report attributed by an already-revoked **device claim**, and the write then created a fresh record for the very machine the operator had just erased. The claim itself stayed revoked, so the residue was data rather than access, and one map dot was judged an acceptable loss at the time. §6.48 changed the stakes: the same record now carries a 30-day **position trail**, and "your whereabouts are gone" cannot come with an asterisk.

**Identity is now decided under the same lock as the write.** `savePosition(machineId, position)` is replaced by `Optional<MachineId> recordReportedPosition(MachineId fromTunnel, String claimToken, ReportedPosition)`: the caller says *who is talking*, and the store answers which machine it attributed the report to. `MachinePositionFileAdapter` resolves that by calling the same domain rule, `MachinePositions.reportingMachine`, inside its own monitor — the rule does not move, and the adapter derives nothing of its own. An empty answer means **nothing at all was written** (no record, no position, no trail point), which the service turns into `UnidentifiedDeviceException` → `403`, unchanged from the caller's point of view. Because no port operation any longer accepts a machine id to file a position under, "a device may report only its own position" is now enforced by the port's *shape* rather than by every caller remembering it.

**Order: peer validation before identity resolution, deliberately.** The tunnel identity is still derived from the peer configurations before the store is touched, so a tunnel-named machine is a peer by construction, and a claim can only exist for a peer because `claimDevice` checks `PeerConfiguration.isPeerMachine` before minting one. That lookup stays *outside* the store's monitor because it is a call to a different port, and holding a file lock across a foreign port call buys nothing. **`forgetMyPosition` still resolves outside the lock, on purpose** — its race can only ever erase, never file something under an identity that has stopped existing, so no second port operation was invented for a strictly-safe direction.

**A tunnel-identified late report still creates a record, and that is correct.** WireGuard authenticated the packet, so the device is genuinely reporting *now* — a new act, not residue — and the revoked claim stays revoked. Forget erases the past; it was never a mute button on a device sitting on the tunnel, which would re-report a second later regardless.

**Tested as a race, not merely as a sequence.** Two threads released from a `CyclicBarrier` — a position report and a Forget — run against the real file store 25 times per run, and nothing is left behind whichever order they take. Putting identity resolution back outside the monitor fails that test and only that test.

### 6.50 A green dot in Frankfurt nobody had ever been to ✅ (implemented 2026-08-12)

**The last dishonest marker on the Map.** §6.45 removed the phone-at-Fornebu lie — a carrier's whole `/13` collapsing to one point — and left a second one of the same species. `Geirs-Android` and `Geir-pc` are **client peers**, so `AllowedIPs = 0.0.0.0/0`: with the tunnel up, a request to any gated service leaves through the **Vaier server** and arrives at forward-auth wearing the server's *own* public address. DB-IP places that address at Frankfurt am Main, and the live `access-sources.yml` had accumulated **264 accesses at `50.1109, 8.68213`**, all attributed to the operator, all real authenticated sessions, none of them from Germany. The recorded coordinates matched the EIP's geolocation to the decimal, and the dot stopped growing the moment the tunnel dropped while the carrier-collapsed Fornebu dot kept climbing. A VPN hairpin drawn as a place.

**A hairpinned access has no place, so it joins the unplaceable source.** That bucket already existed for exactly this — "the bucket every access from a private, VPN or LAN address falls into" — and a hairpin is a VPN access that happens to be wearing a public address. It is *kept* as a count, never dropped, for §6.44's reason: dropping it would make the map read as "nobody came". The geolocation database is not consulted at all for one, since there is nothing to place.

**Unknown is not "no".** `ServerPublicAddress` is either a resolved address or `unknown()`, and an unknown one calls **nothing** a hairpin — off EC2, in the first moments after boot, or after a failed lookup, every access is placed exactly as it was before this existed. The mirror also holds: a refresh that resolves nothing leaves the address already known in place, so one quiet minute at IMDS cannot put the Frankfurt dot back. Withholding a dot on the strength of a lookup that never happened would have traded one lie for a blank map.

**Resolved off the request path, and never on it.** `AccessSources.recording` reaches forward-auth — the gate for every request to every service Vaier fronts — and resolving the address is a live HTTP call to the EC2 metadata endpoint. So the address is *handed in*, held on `SecurityService` in a volatile field, and refreshed in exactly two places: at boot, and inside `flushAccessSources()`, which the `AllowedAccessFlushScheduler` already drives once a minute for the same "off the critical path" reason. No new port, no new adapter, no new scheduler and no new use case — the four-tier fallback in `ServerLocationResolver` (IMDS → configured public host → `vaier.<domain>`) is reused rather than a second path to the same fact invented. A test counts the port's invocations across 500 recordings and asserts the count never moves.

**The decision is the domain's.** `ServerPublicAddress.isHairpin(callerIp)` is a value object's predicate with its own test, not a comparison in the service or the controller; `AccessSources.recording` takes the address alongside the geolocation port and owns which place — or no place — an access counts towards. Nothing new can throw on the forward-auth path, and the existing guard asserting a `200` while the recorder throws is untouched.

**Historical entries are not migrated.** Only new accesses are affected. The Frankfurt entry already on disk keeps its 264 and stops growing, then ages out 30 days after its last access like any other place — Vaier prunes by `lastSeen` and has no migration step. Folding its count into the unplaceable entry by hand, with Vaier stopped, is the way to make the totals true today.

### 6.51 Claude sign-in — Vaier orchestrates, the credential never passes through it ✅ (implemented 2026-08-31, issue TBD)

**The terms wrote the architecture, so it is clean by construction rather than by promise.** Anthropic's
developer terms (`code.claude.com/docs/en/legal-and-compliance`) state that developers "may not collect,
store, or intermediate Claude.ai credentials or session tokens — sign-in to a Claude account must complete
through Anthropic's own flow", while *expressly permitting* an end user to sign in to the **unmodified**
Claude Code binary with their own subscription. Every shape this feature could have taken follows from
that one sentence: Anthropic's binary runs on the operator's machine, Anthropic's flow completes in the
operator's own browser, and **Vaier relays a URL out and a code back and persists nothing** — not the URL,
not the code, not a token. There is deliberately no `For*` persistence port anywhere in the feature; if one
appears, something has gone wrong.

**Two designs were considered and rejected, recorded here because a future reader will otherwise
"simplify" the relay back into one of them:**

1. **A central `claude setup-token`, fanned out as a fleet credential.** §6.18 recommended exactly this
   before the terms were read properly. It is out: minting one credential centrally and pushing it to N
   machines is Vaier collecting, storing and intermediating a Claude credential, which is the thing the
   terms name. That the mechanism already existed and would have been half a day's work is precisely why
   the rejection is written down.
2. **Distributing `~/.claude/.credentials.json` across machines.** Out twice over. It is unsupported, and
   it is *technically broken* independently of the terms: OAuth refresh tokens rotate, so the first host to
   refresh invalidates every other copy and the rest fail later, silently, far from the cause — the
   rotation race §6.18's question (1) describes. Anthropic's own devcontainer documentation routes around
   sharing the file, and `anthropics/claude-code` issues #56339 and #54443 are that race being reported by
   people who tried. **The generic fleet credential remains entirely fine for other secrets** — an API key,
   a registry login, a config file. It is Claude credentials specifically that Vaier must not hold.

**What ships is a guided, per-machine sign-in of the CLI that is already there.** Vaier runs
`claude auth login --claudeai` on the target machine, reads the **authorization URL** out of the CLI's
output and hands it to the operator; they open it in their own browser, approve on Anthropic's pages, and
Anthropic shows them an **authorization code**, which they paste into Vaier; Vaier writes it into the
process still waiting on that machine and then re-reads the machine's standing.
`GET|POST|DELETE /machines/{machineId}/claude-sign-in`, `POST /machines/{machineId}/claude-sign-in/code`
and `POST /machines/{machineId}/claude-sign-out`, all non-whitelisted, so Tier-3 admin auth applies
automatically. Every read is **one machine**: drawing a pane must never cost a fleet-wide SSH sweep, and a
test pins that opening one asks exactly one machine.

**Everything goes through the CLI's own `claude auth` subcommands, and that is the whole design** —
`auth login --claudeai` to start (the subscription flow, named explicitly rather than left to a default
that could change), `auth logout` to end, `auth status --json` to ask. Two things fall out of it that
matter more than the tidiness. The status answer is **authoritative rather than inferred** — no reading of
a REPL banner, no `stat` on a credential file — and it is structured JSON carrying **no credential material
at all**, so asking the question is plainly something Vaier is allowed to do. And signing out is the CLI
releasing its own credential: Vaier never deletes, and never reads, a credential file. Deleting one would
have worked, and it is exactly the line this feature stays behind.

**`ClaudeSignInState` has six values, and `UNKNOWN` exists so that being unable to *ask* is never reported
as signed **out**.** `SIGNED_IN`, `SIGNED_OUT`, `NOT_INSTALLED` (a plain fact — a machine without Claude is
not broken), `UNREACHABLE` (asleep, moved, refusing), `SKIPPED` (no shell Vaier can reach, so not a place a
sign-in could happen) and `UNKNOWN` (the machine answered with something unreadable). A false `SIGNED_OUT`
sends an operator to redo a sign-in nothing had undone, so silence is never a "no": `UNKNOWN` is reported
as itself and drawn apart from signed-out, never folded in with it.

**A sign-in is a fact about a *user on* a machine, and the first version of this said otherwise.** The CLI
keeps its credential in a user's home directory, and Vaier asks as its **effective user** — the login in
that machine's host credential — which is frequently not the account the machine's real work runs as. Live,
Colina 27 reported "signed in, max" for `geir` while the operator's automation, running as `root` on the
same box, was signed out and expired. Both readings were true; the standing that named only the machine was
the lie. So `ClaudeSignInStatus` carries its `EffectiveUser` (null only where Vaier holds no login at all,
because naming one there would be inventing it), and every sentence built from a standing names the user.
The fleet-wide field went the same way in two steps: first renamed from `signedInEverywhere` to
`signedInForEveryEffectiveUser` — the question it could honestly settle — and then removed outright with
the fleet view. "Signed in everywhere" read as a claim about the machines and never was one.

**The view moved onto the machine pane, and two things were lost with it — deliberately, and recorded here
so reviving them is a decision rather than a rediscovery.** The sign-in first shipped as a second section
of the Explorer's **Credentials** view; the operator moved it beside a machine's SSH access and host
credential, where a per-machine, per-user fact belongs, and where the pane's own "Vaier acts as `geir` on
Colina 27" already establishes whose account is under discussion. Gone with the fleet view: the
`signedInEverywhereItCan` domain decision, which could not survive the per-user correction intact — it was
never a claim about machines — and, more painfully, **the warning that fired when the fleet was signed in
as more than one account**. That is the failure nothing per-machine can catch: every pane reads green
while half the fleet answers to a different account. It wants reviving as a summary that names the user per
machine, not as the headline sentence that was removed.

**Backlog — choose which OS user a sign-in applies to.** Vaier signs in as its own effective user, and the
user that runs the work often is not it: live, Roon kjøkken and Roon loftstue answer as `root` while every
other machine answers as `geir`, and Colina 27's automation runs as `root` under `/home/openhab` while
Vaier reads `geir`. Letting the operator pick the user is a real feature with real consequences (it means
`sudo`, and it means Vaier acting for an account it holds no credential for), so it gets its own slice
rather than being smuggled into a fix.

**Where it lives.** The domain owns every decision: `ClaudeSignIn` builds the exact strings sent over SSH
and reads a machine's answer back (the same IO-free shape as `PersistentShell`), including how long a URL
is worth waiting for and when a sign-in left at its prompt counts as abandoned; `ClaudeSignInOutput`
contains the one genuinely fragile part — screen-scraping a program Vaier does not own — as pure total
functions, matching the URL out of the CLI's **OSC 8 hyperlink parameter** (the only place a TUI redraw
leaves it contiguous) and *structurally* rather than by client id, state or exact path, so an Anthropic
change breaks loudly instead of silently; `ClaudeSignInStatus`/`ClaudeSignInState`/`ClaudeAccount` are the
read model, keyed by **machine id** so a rename cannot move one machine's standing onto another.
`ClaudeSignInRelay` is a **driving adapter** in `rest/` — driven by an HTTP request and by a clock, as
`FleetCredentialDistributor` and `RemoteDiskWatcher` are — implementing the five narrow use cases and
composing `GetMachinesUseCase` + `GetHostCredentialUseCase` + `RunRemoteCommandUseCase` at the driving
edge, which is how Vaier does a cross-domain read. Eligibility is `Machine.runsAShellVaierCanReach`, the
same question a fleet credential asks, answered in one place rather than copied per feature.

**The CLI needs a PTY and has to outlive the request that started it**, so the sign-in runs inside the
existing **persistent shell** (`OpenClaudeSignInShellUseCase` on `TerminalService`) under a reserved
session name — `vaier-claude-sign-in` — which supplies the PTY, keeps the process alive across the gap
while the operator is away in their browser, and can never land in a pane the operator is using. A
two-minute sweep ends sign-ins abandoned for a quarter of an hour, because nothing else would: the CLI
waits at its prompt by design. A pasted code is confined to a URL-safe charset and refused rather than
quoted, since if the CLI has already exited whatever is written lands in the shell that started it.

**Nothing is stored, and three tests fail if that changes** —
`ClaudeSignInRelayTest.neverLogsOrShipsTheAuthorizationUrlOrTheCode` (no command and no log line carries
either), `ClaudeSignInRelayTest.forgetsTheSignInAsSoonAsItEnds`, and
`TerminalServiceTest.openClaudeSignInShell_persistsNothing` (neither credential store is written). Two of
them were mutation-verified: the guard was broken deliberately and watched to fail.

**UI: a card on the machine's own pane**, drawn directly under "Vaier acts as ‹user› on ‹machine›". A
sign-in belongs to a *user on* a machine, so the line naming that pairing is what makes the card's scope
legible without a second sentence repeating it — a card, not a section, because a section heading between
the two would put a rule through the one adjacency that makes either readable. Whether a sign-in can happen
there at all is the server's call (`signInPossibleHere` on the read model), never a browser copy of "SSH
access AND a credential". The card carries the state in the operator's words, the **Claude account** it is
signed in as, an inline sign-in panel (the URL with a **Copy** button, then the code box) and a **Sign out**
verb. Inline, not a modal, because signing several machines in one after another turns a dialog into a
ceremony. No coverage strip and no self-healing: there is nothing Vaier holds to put back. A machine with no
Claude, no shell or no answer gets no button rather than a button that fails, and one sign-in is open at a
time — a second would leave a CLI waiting on a machine nobody is going back to. *(Moved 2026-09-02: the
sign-in itself left the machine's pane for that machine's own **web terminal** — see the last entry in this
section. Everything else this paragraph settles still holds — `signInPossibleHere` as the server's call, the
inline never-modal flow, no coverage strip, and no button offered where Vaier already knows one cannot work.)*

**Corrected 2026-09-01: this was specced as a second section in the Explorer's Credentials view, and that is
not what shipped.** The reasoning for it — one place answers "what is authenticated where", and what differs
between the two sections is *who holds the secret* — lost to the stronger one: a sign-in is a fact about a
*user on* a machine, and the machine's pane already says which user that is. The Credentials view holds
**fleet credentials** and nothing else. Three docs described the fleet-wide table for months (README, the
promo page, and the glossary's **Credentials view** entry, which claimed "one row per machine"); all three
are corrected. The **fleet-wide "signed in as more than one Claude account" line** specced here was never
built either — see the backlog.

**The fleet's Claude sign-in standing, on the machine cards ✅ (implemented 2026-09-01, issue TBD).** A
sign-in stated only on a machine's own pane is a sign-in nobody looks at: half the fleet expires quietly and
the first sign of it is work that stopped happening. The fleet listing now carries it as a fifth **machine
mark** — and the whole design is about paying nothing new for it.

**No new trip.** `RemoteDiskWatcher` is already *the one trip Vaier makes to every credentialed machine*
every five minutes (§6.25, §6.33, §6.37), and it now learns a fifth fact there, through the very same
`GetClaudeSignInStatusUseCase` the machine's own pane asks — one path to a sign-in answer, and no second one
that could report a state the CLI never said. Behind the same two guards as everything else on that trip,
so a phone or a machine with no stored login is never asked and never marked, and in its own try/catch for
the reason `detectNetworks` is: a fifth question must not be able to take the first four down with it, nor
reach the `NoSshServerException` handler and withdraw an **SSH server presence** that `df` has already
earned.

**Held in memory, and read from memory.** `ForHoldingClaudeSignInStandings` /
`InMemoryClaudeSignInStandingCache` is the sibling of the disk standings' cache: keyed by **machine id** so
a **rename** cannot cross two machines' sign-ins, `retainOnly`-pruned on each sweep so a deleted machine
leaves no reading behind, and deliberately without a `forget` — every reading *is* a standing, including the
quiet ones. `GetClaudeSignInStandingsUseCase` on `MachineService` reads it and
`GET /machines/claude-standings` serves the whole fleet in one request that **reaches no machine** (a
literal segment under `/machines`, so it is admin-gated with the rest of them). That is exactly what
`GET /machines/{machineId}/claude-sign-in` is not: a per-card version of that one would put a fleet-wide SSH
sweep behind opening the Explorer, every sleeping box waited on before anything appeared. Nothing is
persisted, and nothing here could carry a secret — a standing is read from `claude auth status --json`,
which emits no credential material at all.

**The domain decides what a fresh reading does.** `ClaudeSignInStatus.retain(status, standings, events)` is
a domain method handed the ports rather than a branch in the watcher: a null status — a machine never asked,
or a read that blew up — records nothing whatsoever, and the publish is gated on `differsFrom`, so a fleet
sitting still says nothing every five minutes. That comparison is whole-value on purpose: a machine quietly
moved onto a different **Claude account** is something an open Explorer is getting wrong even though its
state never left `SIGNED_IN`. A change goes out as `claude-standing-changed` on the `vpn-peers` topic the
fleet page already holds open — empty body, no second connection, no timer — exactly as
`disk-standing-changed` does, and the browser answers it with one re-read of the memory-backed endpoint.

**Absence is not a verdict, and this is where that has teeth.** Only `SIGNED_IN` and `SIGNED_OUT`
carry a card tone in `CLAUDE_STATE`, the one map the machine pane already reads its words from, so
two surfaces can never drift into two vocabularies. `NOT_INSTALLED`, `SKIPPED`, `UNREACHABLE`, `UNKNOWN` and
a machine the sweep has simply not reached draw **nothing at all**. `UNKNOWN` exists precisely so that being
unable to *ask* is never reported as signed **out** (see above), and a mark invented from silence would undo
that work. Like the disk mark, the Claude mark stands down while the Explorer is showing an **archive**:
where a machine stands on Claude is a fact about now. Guarded in `ExplorerShellTest` — one fleet-wide read at
boot and never from `render()`, the tone read off the single state map, no fallback tone anywhere, and a
failed read empties the map rather than leaving stale sign-ins on the cards.

**The mark wears Claude's colour, not a traffic light ✅ (2026-09-01).** Green, amber and red are the disk
and backup marks' vocabulary and they mean *trouble*; a sign-in is presence. So both marked states wear one
token, `--claude: #d97757` — Claude's own clay, added beside `--orange` and deliberately outside the
green/amber/red palette — and signed-out is that same clay worn hollow (`opacity: .45`, a `1.1` stroke), so
at 16px it reads as present-but-dormant rather than as a second colour saying something else. Colour says
whose sign-in it is; weight says whether anyone holds one. `CLAUDE_STATE` stays the single map both surfaces
read: only the two `card:` values changed.

**Every reading is a standing ✅ (2026-09-01).** The sweep was the only place a reading was *kept*, so an
operator who signed a machine in watched its fleet card go on saying signed-out for up to five minutes —
proven live: `/machines/{id}/claude-sign-in` answered `SIGNED_IN` while `/machines/claude-standings` still
held `SIGNED_OUT` for the same machine. `ClaudeSignInController` is a driving adapter exactly as
`RemoteDiskWatcher` is, and its three reading paths — opening a machine's pane, completing a sign-in,
signing out — now each retain what they read through the same `ClaudeSignInStatus.retain(...)`, so the
domain keeps deciding what a fresh reading does and `claude-standing-changed` still fires only on a real
change. This is the **machine disk standing** rule applied to its sibling: a standing is retained from every
reading, not from the scheduled one alone. Deliberately *not* in `ClaudeSignInRelay`, whose javadoc carries
a tripwire against any `For*` port appearing in that file — a compliance invariant worth more than the
convenience, even for an in-memory cache holding no credential material.

**The identity colours left the fleet cards ✅ (2026-09-01).** `--disk`, `--backup`, `--docker` and `--claude`
had been a fleet-card vocabulary only; everywhere else in the Explorer an entry's glyph inherited whatever
colour its surroundings gave it. `TINT_FOR` and one seam, `entryIco()`, now carry the same four tokens out to
every surface that draws an **entry** — the rail's tree rows, the "Inside this machine" cards, ⌘K search
results, and the Containers/Services listing rows — keyed off the entry's *kind*, never off its glyph: `repo`
and `container` both draw the `box` shape, so a rule written against the glyph name would have painted every
Docker container backup-aqua. Everything else keeps the colour it inherits. The same pass reached two marks
that had been stragglers rather than surfaces: the Claude sign-in standing on a machine's own pane wore green
where its fleet card already wore clay, so one machine's own page disagreed with its own card; it then read
`is-claude-in`/`is-claude-out` off the same `CLAUDE_STATE` map instead of the generic `is-in`/`is-out` tone.
*(That standing has since left the pane for the machine's **web terminal**, where it still takes its tone from
that one map — which now lives in `claude-sign-in.js` as the single copy. See the last entry in this section.)*
And the backed-up shield in the file browser wore the plain brand accent, which said *this is Vaier* rather
than *this is backed up*; it now wears `--backup`, with the half-shield the same hue faded rather than plain
dim grey. None of this touches the status indicators: liveness dots, the update-available mark, the nudge
glyph and the capability strip's dim relay/backup-server glyphs are unchanged, because they report state and
the identity colours report what a thing *is* — the same distinction `--claude` already drew against green,
amber and red, now drawn everywhere an entry's glyph appears rather than on the fleet cards alone.

**The sign-in moved into the machine's own terminal window ✅ (2026-09-02, no issue — the operator asked for
it).** Where a sign-in is drawn is not a layout question, and the machine pane was the wrong answer from the
start. Signing in *is* a terminal act: it runs the `claude` CLI in one OS user's home on one machine, and
what it leaves behind belongs to that user alone. The **web terminal** window IS that machine's shell as
that user — the same login, the same home — so the sign-in belongs there and nowhere else. The machine's
pane is where an operator reads *about* a machine; the shell is where they act *on* it, and the whole
`EffectiveUser` argument this section already makes (§6.51, "a fact about a *user on* a machine") is the
argument for the move. It also ends a smaller lie: the pane offered a sign-in beside every other fact about
the machine, so nothing on screen said which login it would land in except a sentence.

**Nothing behind it moved.** No Java changed, no endpoint, no request or response shape, no step of the
flow: `GET|POST|DELETE /machines/{machineId}/claude-sign-in`, `POST …/claude-sign-in/code` and
`POST /machines/{machineId}/claude-sign-out` are identical, and Vaier still neither holds nor intermediates
a Claude credential. This is a move and a restyle, and it should be read as no new capability.

**One module, two surfaces, one vocabulary.** `static/claude-sign-in.js` is an IIFE exposing
`window.VaierClaude.words(state)` — the operator's words and tints for a **Claude sign-in state** — and
`window.VaierClaude.mount(host, machineId, machineName)`, which draws and drives the whole sign-in and hands
the host page back only what it owns: what the standing looks like in its own chrome (`onUpdate`) and the
operator putting the panel away (`leave`). `explorer.html` loads it **for the words alone**, so a machine
card's Claude mark and its shell's standing can never drift into two vocabularies; `terminal.html` loads it
for the UI. The old `CLAUDE_STATE` map in `explorer-shell.js` is gone — the comment above it had worried for
two entries about "two surfaces, one map", and that is now structural rather than a discipline. With it went
`renderClaudeSignIn`, the pane's `standingCard(...)` helper, the `.ex-standing` styles and the
`S.claudeSignIn` slice of shell state.

**In the window.** The top bar gains a **Claude** control reading "Claude · Signed in" in Claude's own clay
(`--claude`), hollow and unweighted when signed out, muted for the answers that are not a verdict — the
same colour doctrine the fleet mark set, said in the same words. Clicking it drops the panel in **between
the bar and the terminal**: the standing, its explanation, the verbs (Sign in / Sign in again / Sign out)
and the two-step flow. Between, not floating over, because opening it must visibly cost terminal rows rather
than hide part of the screen — and because both directions then have to `refit()` and `sendResize()`, since
a terminal left at dimensions the remote does not share is a corrupted screen, not a cosmetic problem. Where
the server says a sign-in is impossible there (`signInPossibleHere` false) **no control is drawn at all**,
which is the same rule the pane's card followed: a control explaining an impossibility is worth less than no
control. A read that merely *failed* is not that verdict and still draws, saying Vaier could not tell.

**The fleet-card mark is untouched** — same clay, same hollow-when-signed-out, same five-minute-sweep
source, same "no mark at all" for the quiet states — and is now the *only* place a machine's Claude
standing appears outside its own shell, which is what makes it worth what it costs.

**One line of copy changed, because it stopped being true.** The flow used to warn that "Leaving this
machine abandons the sign-in", which was a fact about navigating away from a pane. It now reads "Closing
this panel abandons the sign-in — the Claude CLI waiting on ‹machine› is ended rather than left at its
prompt." Putting the panel away still abandons an in-progress sign-in (`leave()` → `DELETE`, and
deliberately *without* a re-read, since nothing is on screen to show the answer to).

**Guarded in `ExplorerShellTest`**, which now covers the window as a surface an operator reads: the Explorer
must not fetch `claude-sign-in` at all and must not carry a state map of its own (it calls
`window.VaierClaude.words(`), the window must mount `window.VaierClaude.mount(` and hide its control on
`!view.draw`, and the existing invariants — never drawing an unreadable machine as signed out, saying
**authorization URL** rather than "login link" — moved with the code they guard.

---

## 7. End-to-End Workflows

### 7.1 New service on a peer (primary workflow)

1. Peer is already connected to VPN (created via Vaier)
2. Developer starts a Docker container on the peer
3. In Vaier → Services, the container appears in the **Discovered** list automatically
4. Developer clicks **+ Add**, enters a subdomain, toggles auth if needed, clicks **Add Service**
5. Modal closes immediately; service moves to the **Processing** list with live progress steps
6. Vaier writes the Traefik route → (optional) social-login middleware chain. There is no DNS step: the name already resolves under the operator's one **wildcard DNS** record (§6.4)
7. Processing card disappears; service appears in the **Active** list with live status
8. All updates arrive via SSE — no page reload or manual polling required

**Success:** zero manual DNS/Traefik/auth steps. The user always knows where their service is in the pipeline.

### 7.2 Add a new VPN peer

1. Developer clicks "Add peer" → enters name
2. Vaier generates WireGuard keys, assigns IP from subnet, writes config
3. Developer downloads QR code or docker-compose file
4. Peer is running; developer can see handshake status in Vaier

### 7.3 Learn that a container has an update available

1. Vaier sweeps the fleet's registries once a day and finds an image whose registry now serves a different digest for the tag the container runs
2. Every admin gets one rollup email naming the image(s) that *newly* went out of date — Vaier pulls nothing and restarts nothing
3. Developer updates the container manually on its host

**Success:** the operator hears about a stale image instead of discovering it through the outage it causes. Both halves now exist: the push side (§6.8) is the rollup email, and the pull side is the mark in the **Explorer** — so an operator who opens the tree for any other reason still sees which containers want attention, and a container's Inspector says which of the three verdicts it is (including "Vaier cannot tell"). Vaier pulls nothing either way; step 3 stays the operator's.

### 7.5 First-time setup

1. User creates **one DNS record** at their own DNS host: `*.<domain>  A  <this server's public IP>` (§6.4)
2. User runs `install.sh`, which fetches the runtime files and scaffolds `.env` with every auto-generated secret already filled in (§6.35), then fills in the operator-authored half: `VAIER_DOMAIN`, `ACME_EMAIL`, the Google OAuth credentials (`VAIER_OIDC_GOOGLE_CLIENT_ID` / `VAIER_OIDC_GOOGLE_CLIENT_SECRET`), `VAIER_ADMIN_EMAIL`, and `VAIER_PUBLIC_HOST` / `VAIER_PUBLIC_IP` when not on EC2
3. `docker compose up -d` — oauth2-proxy(+init) start unconditionally as the auth gateway (no `social` profile)
4. Vaier verifies the wildcard record, states the **wildcard DNS report**, and seeds the **configured administrator** (`VAIER_ADMIN_EMAIL`) as the first admin access entry
5. User opens `https://vaier.<domain>`, signs in with Google as that admin, and lands in the console

## 8. Technical Constraints

- **Stack is fixed:** WireGuard (linuxserver), Traefik, oauth2-proxy. DNS is deliberately *not* part of the stack — one operator-owned wildcard record, any provider (§6.4)
- **Sub-image versions are pinned** in `docker-compose.yml`; bumps are deliberate, tested, and released with a new Vaier version (no floating `:latest` tags for upstream images)
- **No database:** all state is file-based (WireGuard/Traefik configs, the access store) or in memory
- **Single WireGuard server:** multi-server mesh is out of scope
- **Java 21 / Spring Boot 3.5.5:** backend language and framework are fixed
- **Docker socket required:** container discovery requires access to `/var/run/docker.sock` or TCP Docker API on peers

---

## 9. Out-of-Scope Integrations

The following are explicitly out of scope to avoid feature creep and overlap with dedicated tools:

- **Any** DNS provider integration — Cloudflare, Route53, all of them. Vaier reads DNS and never writes it (§6.4)
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

**Status hover text on a machine's dot (found stale 2026-09-01).** `UBIQUITOUS_LANGUAGE.md` and `docs/NETWORKING.md` both described a per-machine tooltip giving the liveness state in words plus its evidence ("connected, last handshake 12s ago"). No such tooltip has ever existed — the dot carries no `title` at all — so both entries were corrected rather than kept. It is worth building now in a way it was not before: the fleet page paints only trouble, so a machine that is up, one that is asleep and one nothing has ever probed all draw nothing, and the words are the only thing that could still tell them apart. The listing's "N online" count is currently the sole place that gap shows. Note the placement problem it has to solve: a healthy dot is an invisible 6px target, so the text belongs on the machine's name or card rather than on the dot itself.

**Backup survival kit (next up, designed 2026-07-23).** Everything needed to read Vaier's backups is currently *inside* Vaier, in a circle: repository passphrases are encrypted in its config store; the key that decrypts them (`SecretCipher`, `vault.key`) sits in the same directory; and that directory is backed up to the backup server — encrypted with a passphrase held in the store being backed up. Losing the Vaier server leaves an encrypted repository whose passphrase is inside itself, and every other machine's archives in the same position. Nothing warns, because nothing is broken until everything is.

`RecoverySheet` renders the way out: per repository, the machine it holds, its full `ssh://` address and its passphrase, plus the `borg list`/`borg extract` commands and the config key. It was first designed as a printed page; that was **rejected** in favour of distribution, because a printed sheet goes stale the moment a passphrase changes (which has already orphaned a repository once) and a stale sheet is worse than none — you believe you are covered.

The agreed design:
- The sheet's content becomes the **payload of a survival kit**, encrypted with **one passphrase the operator chooses**. Plaintext copies on N hosts were rejected: compromising any single host would hand over every key to every backup.
- Decryptable with **standard tools only** — `openssl enc -aes-256-cbc -pbkdf2 -d` — with the exact command in a plaintext header at the top of the file. Needing Vaier to open it would restore the circularity.
- **Vaier selects the hosts**, and says which and why (evidence, like a nudge). The rule: SSH-credentialed, always-on (`deviceCategory`, not a laptop or phone), **not the Vaier server itself**; pick three maximising separation — never two behind the same relay peer. Re-evaluated when the fleet changes; rewritten whenever a repository or passphrase changes.
- **The kit only has to outlive the Vaier server.** The archives exist in exactly one place (the backup server), so if the NAS dies no key helps. The NAS is therefore a *good* host for a copy, not a conflict of interest — the scenario is "Vaier is gone, the NAS is fine".
- What the operator keeps on paper shrinks from a list that rots to **one short passphrase** that does not.

**Amended 2026-07-23: the kit passphrase is stored in the vault.** The design above said "never stored by Vaier", and that was incoherent with the rest of it: a passphrase Vaier does not hold is a kit Vaier cannot rewrite, which reintroduces exactly the staleness that killed the printed sheet — only now Vaier can merely nag about it. Holding it in the credential vault costs nothing, because an attacker who has `vault.key` already has every repository passphrase directly and has no need of the kit. The operator still keeps it in their head, for the day there is no vault to read it from.

**Amended 2026-07-23: a fourth copy on the Vaier server itself.** "Never the Vaier server" argues against the kit living *only* there, and was over-applied into excluding it entirely. The likeliest failure by far is not a lost server but a Vaier that will not start on a host whose disk is fine — and there, a kit in the config directory is one `openssl` command, where the alternative is hand-decrypting `enc:v1:` envelopes out of the config store. It costs no exposure: the copy sits beside `vault.key`, so on that host it is effectively plaintext, but anyone holding `vault.key` already holds every repository passphrase directly. Two conditions: it **never counts as one of the three** (a copy that dies with Vaier is not redundancy, and filling a separation slot with it would look like three copies while being two), and it is **not a host-selection decision** — the Vaier server is the WireGuard server, not a machine in `getAllMachines()`, so this is an unconditional local write in the distribution slice, not a `SurvivalKitHosts` change. The local copy is also the one most likely to go stale (restore the config dir from an old backup and it looks authoritative); the date in the plaintext header is what catches that without the passphrase.

Built so far (2026-07-23): `RecoverySheet` (now plain text, since it is read in a terminal after `openssl`, not in a browser), `SurvivalKit` (header, marker, ciphertext; refuses to write itself without a passphrase), `ForEncryptingSurvivalKits`/`OpensslEnvelopeAdapter` (OpenSSL's `Salted__` envelope reimplemented in Java, so Vaier's container needs no `openssl` binary — with a test that runs the *real* binary on the command the kit prints on its own face), and `SurvivalKitHosts` (the choice of hosts, with a stated reason for every machine chosen and every machine passed over).

**The kit is written and distributed ✅ (2026-07-27).** The passphrase is stored: `VaierConfig.survivalKitPassphrase`, encrypted at rest by the same `SecretCipher` as `smtpPassword`, set through `PUT /settings/survival-kit-passphrase` (`SetSurvivalKitPassphraseUseCase` on `SettingsService`) and refused when blank — an unprotected kit is indistinguishable on its face from a protected one, so the emptiness is rejected where it would be stored rather than where it would be noticed. It is never read back: `GET /settings/config` answers only `hasSurvivalKitPassphrase`. `ForReadingTheConfigKey` (implemented by `SecretCipher`) supplies the **config key** the sheet carries, *read and never minted* — a key generated by the act of writing a kit would decrypt nothing, printed on the one page claiming to decrypt everything. `SurvivalKitRollout` owns what counts as success: every destination is attempted so one sleeping machine cannot cost the fleet its other copies, and the Vaier server's own copy is written every time but **never counted**, because a copy that dies with Vaier is not redundancy. `ForKeepingSurvivalKits`/`SurvivalKitKeeperAdapter` put it there — into the SSH user's `$HOME` (never a path needing root, which would silently stop being written wherever escalation was unavailable) and beside Vaier's own config locally, base64 over the wire because a kit is prose, base64 and shell metacharacters at once and one mangled byte would only be discovered on the day it was needed. Destinations are addressed by `MachineId`, not name (§6.22): a rollout takes minutes across sleeping machines, and a rename inside that window would otherwise resolve to nothing — or to whichever machine had since taken the name. `rest/SurvivalKitWriter` composes the whole thing behind `WriteSurvivalKitUseCase` and `POST /survival-kit`, returning the outcome *and Vaier's reasoning*: the hosts chosen and why, the machines passed over and why, the ones that refused, and `survivesLossOfVaier`. `409`, not a masked `500`, when no passphrase has been chosen — that is a precondition the operator can fix. **The Settings UI ✅ (2026-07-27).** In the Explorer's Settings pane as **Reading your backups without Vaier**, under Nightly backups — the same domain, and the second half of the same promise. Two controls, deliberately two: rolling the passphrase and the write into one Save would push files across the fleet as a side effect of typing a password. The passphrase is **typed, not generated** — the one place Vaier does not mint a secret for you, because a 32-character random string is exactly what nobody carries in their head, and the kit's premise is that the operator carries this one. It is **typed twice**: a mistyped passphrase saves cleanly, encrypts every copy and is indistinguishable from a correct one until the day it is needed. **Write the kit now** is offered only once a passphrase exists (the alternative is a button whose answer is `409`, which is a fact about an endpoint, not an answer to a person). The report is the section's signature: the headline answers the only question a kit is for — *is there now something out there that opens without this server* — in those words rather than as a count of successful requests; each host that holds a copy is shown with Vaier's stated reason under it, in the same two-line shape as a nudge, because it is the same act; hosts that refused take a red edge and never fold away; machines passed over fold behind *Why not the other machines?*, being reasoning rather than trouble. A rollout that reached no fleet machine says so as a failure and says explicitly that the local copy is in place but dies with the machine it is on — two copies presented as three is the lie the printed sheet told. Saving a new passphrase while kits are already out there warns that the copies still open with the old one until they are written again.

**The rewrite triggers ✅ (2026-07-27) — the feature is complete.** A kit rewritten only when someone remembers has the same flaw as the printed sheet it replaced, so Vaier asks itself, on a ten-minute sweep. **Not a dirty flag**: every write path would have to remember to set one, a restart loses it, and it says nothing about kits written before it existed — three ways to believe the fleet is covered when it is not. Instead `SurvivalKitFreshness` fingerprints (SHA-256) *what a kit would say now* and compares it with what was recorded when the fleet was last written; the contents cannot lie about themselves, and one comparison covers causes nobody enumerated — a repository added, a passphrase rotated, a job re-pointed, a machine renamed, a host that left. Of the **sheet**, never the ciphertext: every write mints a fresh salt, so identical contents differ in every byte and would read as a change on every sweep. Two causes the fingerprint cannot see are handled where they happen. A changed **kit passphrase** leaves the words identical while every copy still opens with the old one, so `VaierConfig.withSurvivalKitPassphrase` **forgets the fingerprint** — read downstream as “never written”. A **host that was asleep** makes the rollout partial, and a partial rollout is never *recorded* as written (`SurvivalKitRollout.Result.reachedEveryDestination`), so it returns as a mismatch and is retried — and because that lives on disk rather than in memory, the retry survives a Vaier restart, which an in-memory flag would not. The sweep never throws (a scheduled method that dies takes its schedule with it, and Vaier silently stopping watching is the failure this exists to prevent), and it stays out of the way: no passphrase, nothing written; no repositories, nothing written, because a fleet backing nothing up should not get a file on every host saying so. Verified on the live fleet 2026-07-27: the first sweep after deploy found no recorded fingerprint, wrote Apalveien 5, Colina 27 and the local copy, and recorded what it wrote.

The backlog is tracked in [GitHub Issues](https://github.com/getvaier/vaier/issues). Feature specs for planned items are in the relevant section above (6.8–6.10). Bugs and smaller improvements are described directly in the issue.

"Server LAN CIDR" remaining follow-ups are closed by [#204](https://github.com/getvaier/vaier/issues/204) — `VAIER_SERVER_LAN_CIDR` passes through `docker-compose.yml`, and split-tunnel `UBUNTU_SERVER`/`WINDOWS_SERVER` peers' client-side `AllowedIPs` includes the resolved CIDR. The `wireguard-masquerade` sidecar's interface-name-agnostic `! -o wg0 -j MASQUERADE` rule covers the source-NAT step on the Vaier server (closes [#248](https://github.com/getvaier/vaier/issues/248) — the linuxserver `PostUp`'s `-o eth+` is a no-op on AWS's `ens5`).

**Infrastructure page merge ✅.** The Machines (`vpn-peers.html`) and Services (`published-services.html`) pages have been merged into one machine-centric **Infrastructure** page in shippable slices. **Slice 1 ✅:** the Machines page's diagram tab gained the published-service layer and was renamed Network → **Topology** (the join is a client-side helper, mirroring the existing peers+LAN-server join); the diagram itself was reworked from the hand-positioned SVG fan into an interactive Cytoscape + cola force-directed graph (drag/zoom/pan, self-spacing physics) after the fixed-radius layout crowded badly. *(Superseded: the force-directed graph was later reimagined as a per-service reachability map and then removed entirely, along with the Cytoscape/cola libraries — see §6.1.)* **Slice 2 ✅ (this change):** each machine card gained a unified **Services** section. It folds together what used to be two things — the read-only discovered-container list and the published-route view — into one list: published reverse-proxy routes appear first, each expandable into an inline editor (auth checkbox, display name, and an Advanced disclosure with redirect, version endpoint/property, direct-LAN-URL, launchpad visibility; text fields auto-save on blur, checkboxes apply immediately, a focus guard defers SSE/poll re-renders so an in-progress edit isn't wiped), with ✕ to delete and ↗ to open; below them, the host's discoverable-but-unpublished containers appear as muted **+ Publish** rows that open a container-mode publish modal (subdomain pre-filled from the suggestion, path prefix, auth, direct-LAN-URL, advanced redirect). Candidates are mapped to their machine card by **address** (`peer.tunnelIp` / `lanServer.lanAddress`, since the publishable feed carries the sanitized peer name, not the display name), and both ignored containers and ones already published on that exact port are filtered out. Publishing is async — a toast reports progress and the published-services SSE (`publish-traefik-active` / `-rolled-back` / `-dns-timeout`) reconciles the result. Frontend only (`vpn-peers.html` + `vpn-peers.js` + `vpn-peers-helpers.js` + `vpn-peers.css`); reuses the existing `/published-services` discover/publishable/publish/PATCH/DELETE endpoints — no backend change. Known minor regression: the old per-host "not connected / unreachable / none discovered" diagnostic line under Services is gone (an unreachable host now shows an empty Services section; its state still reads from the card icon + last-seen). **Slice 3 ✅ (this change):** the two top-nav entries (across `admin.html`, `launchpad.html`, `mypage.html`) collapsed into a single **Infrastructure** entry (`#infrastructure`), the page was retitled Machines → **Infrastructure**, and the standalone `published-services.html` (+ `.js`/`.css`) was deleted — the old `#services` and `#vpn` hashes now alias to `#infrastructure`. The inner **List · Map** tabs are unchanged; published services are managed inside each machine card's Services section. To make sure nothing was lost when the Services page retired, four capabilities were ported onto the Infrastructure page: (1) **ignore / unignore** discovered candidates — every "+ Publish" row has an Ignore button, and a machine with ignored candidates shows a collapsible "N hidden" line to reveal/Unignore them; (2) **manual LAN-host publish** — relay-anchored LAN-server cards gained a "+ Publish LAN port" button opening the publish modal in LAN mode (port + protocol + subdomain), POSTing to `/published-services/lan`; (3) **publish progress UI** — a floating, non-blocking progress card per in-flight publish that advances DNS-created → DNS-propagation → reverse-proxy-route via the `published-services` SSE events, going green on success / red on rollback or DNS timeout, and rebuilt from `/published-services/pending` on reload; (4) **delete confirmation** is now an in-app modal (not the browser `confirm()`) that shows the busy overlay during Traefik/DNS teardown. Frontend only — all `/published-services/*` REST endpoints and the `published-services` SSE topic are unchanged.


**An update mark with no verb attached — [#352](https://github.com/getvaier/vaier/issues/352) ✅ (implemented, see §6.37) and [#353](https://github.com/getvaier/vaier/issues/353) ✅ (implemented, see §6.38).** Vaier asks the registries what they serve, marks the out-of-date container in the **Explorer** and mails every admin — and used to offer the operator nothing to do about it. Two halves, deliberately in this order.

**#352 — an Update action for the operator's own containers. Shipped 2026-08-03; full account in §6.37.** The mechanism was already on disk: every container in the fleet is compose-managed and Docker records `com.docker.compose.project.config_files`, `.working_dir` and `.service`, so an update is `docker compose -f <config_files> pull <service>` then `… up -d <service>` over SSH — a credential Vaier already held. **Over SSH rather than through the Docker API**, for two reasons that both hold: recreating a container through the API means allowing **create and remove** on `docker-socket-proxy`, whose entire job is to be narrow (`CONTAINERS`, `EVENTS`, `EXEC`, `IMAGES`, `PING`, `POST`, `ALLOW_RESTARTS` today); and rebuilding a container from its `inspect` output means re-deriving networks, volumes, env, labels, restart policy, healthcheck, capabilities and devices by hand, where anything missed comes back as a container that is subtly different and reports success. Compose recreates its own service faithfully, and the labels are the daemon telling us how. Scope, as shipped: **the operator's containers only** — Vaier's own stack is pinned by a Vaier release and moves with #343, so a per-container button there would be a second, conflicting update path for the same images; a container with **no compose labels** keeps its mark but gets no action, with the reason said plainly, because a recreate that silently drops config is worse than no button. The compose path arrives as a **container label**, which is metadata a container writes about itself: validated and quoted, never interpolated into a shell.

**#353 — then stop marking what nobody can act on.** `traefik:v3.6.14` reads `UPDATE_AVAILABLE`, and checking rather than assuming showed the sweep is *right*: the local digest is `sha256:1c1be626…` and Docker Hub serves `sha256:4cda3393…` for the same tag today — upstream re-pushed it, as they do for a base-image rebuild. Only a **digest** pin is ruled un-driftable; a pinned tag is still asked about, correctly. It is nonetheless an alert whose only honest resolution is *wait for a Vaier release* — and `ImageUpdateTracker` is edge-triggered and deliberately not baseline-quiet, so it arrives as **admin mail**, which is what `feedback_notify_only_on_trouble` exists to prevent: an alert nobody can act on teaches the operator to filter the channel, and that costs the alerts that matter. The fix is to stop sweeping Vaier's own stack entirely — not sweep-and-hide, since an image nobody can act on should not spend the registries' rate limit either. ~~keeping `vaier` itself, because Settings → Update is a real button~~ — **the kept-`vaier` exception was rejected by the operator when this shipped: `vaier` is dropped too, and Settings asks the registry for itself. See §6.38 for why the exception was worse than no exception.** `VaierServerCatalogue` already answers *is this Vaier's own stack?* and is build-guarded against `docker-compose.yml` (§6.36). **Sequenced after #352 on purpose:** silencing first would be silencing the messenger everywhere, including where the message is worth acting on. Worth knowing: only containers **with exposed ports** are scraped, so CrowdSec, Dex, oauth2-proxy and the bouncer are absent from this list today — the problem is wider than it looks and would grow the moment one of them declared a port.

**A quiet line when the fleet is split across two Claude accounts (specced in §6.51, not built).** The
sign-in spec called for a line saying so when the fleet is signed in as more than one **Claude account** —
half a fleet on the wrong account is invisible until something fails oddly, and it is worth noticing without
being worth alarming about. It went unbuilt with the fleet-wide table it was to sit in. The ingredients now
exist: the **Claude sign-in standing** of every machine is held in memory and already carries the account,
so the fleet's accounts can be counted without asking a machine anything. Where it would go is the open
question — the fleet listing has cards, not a summary line.

**Continuous background position reporting (deliberately not built, discussed alongside §6.45).** §6.45's **device claim** is one-shot-or-quietly-refreshed-on-visit: a claimed device shares its **reported position** when someone opens Vaier in that browser, or when the operator taps Share, never in the background. An OwnTracks-style companion app would keep a phone's position current *continuously*, tunnel or not, visit or not — closer to what a fleet map wants. Rejected for now on the same ground as the survival kit's printed sheet: it needs a standing background app granted always-on location on the phone, which is the opposite of the no-agent, ask-nothing-of-the-device approach §6.45 took on purpose. Worth another look only if the quiet-refresh-on-visit model proves too stale in practice.

**Resolve the reporting machine inside the store's lock — ✅ implemented 2026-08-12, alongside §6.45.** `recordReportedPosition(fromTunnel, claimToken, position)` replaces `savePosition(machineId, position)`, so the port no longer accepts a machine to file a report under and the identity decision (`MachinePositions.reportingMachine`, unchanged and still in the domain) happens in the same monitor as the merge. Previously `VpnService.reportMyPosition` resolved identity from a read taken before the write, and a **Forget** landing in that gap let an already-revoked **device claim** attribute the report: the record, the position and a fresh **position trail** point were created for a device the operator had been told was erased. The claim itself stayed revoked — that break was closed when the port was first made intent-shaped — so the blast radius was data, not authorization; it stopped being acceptable once the same record carried 30 days of movement. The tunnel still beats the claim, and a device still on the tunnel goes on reporting after a Forget, starting from nothing.

**Validate that a claimed machine id names a real machine — ✅ implemented 2026-08-12, alongside §6.45.** `VpnService.claimDevice` checks the id against the fleet through `PeerConfiguration.isPeerMachine` and raises `PeerNotFoundException` (→ `404`) for an id no peer holds, rather than storing a claim that could never place a dot. The predicate is deliberately named for what it actually searches — peer configurations, not every kind of **machine** — since a fleet machine may also be a **LAN server** or the **Vaier server**, and a name promising more than it delivers is how a later caller gets a confident wrong answer.

**A visual overhaul: readable at a glance, not just from the mockup ✅ (2026-09-02).** The shell traded its cool blue-black "night-ops" palette for a warm neutral dark (`#15171b`) after `--text-dim` was found sitting at 3.6:1 — below an accessible floor, and the default tone descriptions were set in, which is most of why the fleet read as unreadable. Every text tone now clears 4.5:1 on the ground. Base type moved 13px → 15px, and every tracked-uppercase micro-label — section headings, form labels, listing column heads, the tree's column label — became sentence case at reading size and full text strength: a heading's job is to be found, not leaned in for. Machine and card names moved out of 14px mono into the app's own sans; mono stays only for what is genuinely a coordinate or code — the Inspector's pane title, paths, addresses, keys, fingerprints, compose/YAML, log output — and the `MONO_KINDS` set that used to decide this for rail rows was dead code (the rail already used one font throughout) and was deleted. A third corner token, `--radius-0` (5px, for small controls that would otherwise read as circles), joined `--radius-1` (3px → 8px) and `--radius-2` (6px → 14px); new `--shadow-1`/`--shadow-2` tokens lift cards, nudges, standings and filesystem blocks off the ground with depth instead of fencing them with a 1px hairline.

**The machine card, redesigned alongside it.** The card's glyph became a **badge** — a 36px tinted rounded square wearing the identity colour of what the card is about (violet for a disk, aqua for an archive, Docker's blue, Claude's clay, the accent otherwise) — the same identity-colour rule §6.51's colour pass put everywhere else an **entry**'s glyph appears. The **capability strip** moved out from under the **machine marks**: it is no longer the first of them, but its own thing riding beside the machine's name in the card's top row. The **machine marks** themselves moved off the name's row onto a line of their own beneath the description, and each became a chip carrying a word — "Backed up", "Backup failed", "Disk has room", "Disk 91% full", "Claude signed in", "3 updates" — rather than a bare glyph whose meaning lived only in a hover title; the fuller sentence stays there, on the chip's title. The colour doctrine carries over unchanged: a mark's colour says what it is about, the word beside it stays neutral, and only trouble recolours the whole chip (amber/red, with a tinted ground). The card grid's minimum width grew 210px → 268px so a name and its marks stop fighting for one row. `UBIQUITOUS_LANGUAGE.md`'s **Capability strip** and **Machine marks** entries, written for the old layout, are corrected to match.

**Every pane reordered by the same rule: Go → Do → Know → Danger ✅ (2026-09-02).** The Inspector's panes had grown their sections in the order features were added rather than the order an operator reads them, and the worst instance was the machine pane: a details table, then a "Connection details" fold, then "Inside this machine", then "Suggested next steps", then "SSH access", then "This device", then an "Advanced" fold — answering *what is this* before *where can I go* on the page an operator lands on most. One rule now governs every pane that carries these bands (the fleet, a machine, a container, the container listing, a service, the backup server, a backup repository): the ways OUT of the page come first and under no heading — a navigable grid, or the listing that *is* the page's content; what the operator can act on comes next, under `section('What to do next')`, the same wording on all seven panes now, replacing bespoke headings like "Suggested next steps" and "Publish a service by hand"; what the thing IS — reference, mostly behind a `disclosure(...)` fold — comes third, headed "About this machine" / "About this service" / "About this container" / "About this server" / "About these credentials"; and anything able to undo work comes last, inside a new `dangerFold(...)`.

**The machine pane, specifically.** "SSH access" is no longer a heading of its own — it had become a junk drawer grouped by mechanism rather than by purpose, holding the access toggle, the SSH credential, "Open shell" and the whole Claude sign-in block, which share nothing except that they all travel over SSH. Broken up by what each thing IS: the shell is now one of the ways INTO a machine, a card beside files, containers, services, disk and backup in the ways-out grid at the top of the page (SSH is only how it travels, and there is no "Open shell" button any more — the card itself opens the shell); the access toggle sits under "What to do next" while access is off, since granting it is the one step that unlocks everything else on the page, and moves quietly into "About this machine" once access is on, because at that point it is a fact rather than a step; and the Claude standing is its own thing on the page now, not the tail of an SSH section *(corrected 2026-09-02: it has since left the machine's page altogether for that machine's **web terminal**, a sign-in being an act in one login's shell — see §6.51)*. "Inside this machine" is gone as a heading — the ways-out grid is the top of the page, not a fourth section — and so is the fleet page's "Machines" heading over its own grid, for the same reason: a grid that is already the page's content does not need a label repeating what the subtitle just said.

**Two kinds of fold, now visibly different.** `disclosure(...)` still hides reference nobody needs day to day (connection details, a repository's storage details); a new `dangerFold(...)` — warning-coloured, carrying a warning glyph — hides what can undo work, and every `disclosure('Advanced')` in the shell is gone. Folds are now named by their contents ("Backing up other users' files", "The network behind this machine", "Sub-path, root redirect and the direct LAN link") or by their verb ("Unpublish this service", "Stop backing up this machine", "Edit or forget these backups", "Provision, authorize or remove this backup server"). The backup-server pane was the clearest case: Provision, Authorize a host, Setup script, Edit coordinates and Remove designation used to sit inside the same "Server details" fold as the server's own coordinates — a fold that promised only details was exactly where the operator would find the button that takes the fleet's backup server away. Coordinates and operations are now two folds, and the operations one is a danger fold. A repository's own pane and the backup-server pane both used to open on a details fold above their content (the archives, the coordinates); both now open on the content, with the fold beneath it.

**The Vaier menu is grouped.** Five flat siblings — Settings, Users, Security, Credentials, Concepts — said nothing about what any of them was for, and put three unrelated entries between Users and Security, which an operator asking "who can reach my things?" needs together. They are now three named groups: *Your fleet* (Fleet, Credentials), *Who gets in* (Users, Security), and *Vaier* (Settings, Concepts). The tree itself is unchanged — still fleet-only; the grouping is in the topbar menu alone. `UBIQUITOUS_LANGUAGE.md`'s **Vaier menu**, **Security view**, **Credentials view**, **Web terminal**, **Primary shell**, **Explorer** and **Inspector** entries, several of which named the old "SSH access" heading or the old flat menu, are corrected to match.

---

**A Vaier app for Android, so a phone never meets the WireGuard app ([#359](https://github.com/getvaier/vaier/issues/359), scoped 2026-09-08).** Today a phone joins the fleet by scanning a QR code into the official WireGuard app, which is itself a thin UI over the WireGuard project's reusable Android library (`com.wireguard.android:tunnel`, Apache-2, bundling the Go backend and the `VpnService` plumbing). A Vaier app on that library replaces the import with a sign-in and gains what the WireGuard app cannot: the phone signs in through oauth2-proxy and **enrols** as a **peer** over that session, so the one-shot **peer config retrieval** rule maps straight onto it; the private key is born on the phone and Vaier only ever learns the public half, stronger than today's mint-and-ship; the **device claim** and **reported position** come from the app in the background rather than from a browser tab that has to stay open; and a peer reissued or deleted in Vaier is reflected on the phone without the operator touching it. Slices: (1) sign in, enrol, tunnel up — ✅ shipped 2026-09-08 (§6.53), with Vaier serving its own signed APK from the launchpad's install card, stamped with the server's own host name; (1b) **approve from any signed-in device** — ✅ shipped end to end 2026-09-08 (§6.54): a phone that has minted its own keypair asks to join and shows a four-digit **join code** while it waits on its own stream, and the operator approves it from the Explorer wherever they are already signed in — or, on the operator's own phone, with one tap that opens the same screen there — which is what a family member's phone needs, and which retires the slice-1 browser hop and deep link outright; (1c) **several Vaier servers on one phone** — a membership per server, one active at a time, Join adds rather than replaces; (2) presence from the app on the same rules the browser follows; (3) rotate and revoke — revoke's half where a removed phone notices on its own is ✅ shipped 2026-09-09 (below), rotate is not. **Slice 1 is done** (§6.53, 2026-09-08): `POST /vpn/peers/enrol`, a config rendered with no `PrivateKey` line, the **device-held key** in the peer's **VAIER metadata**, no artefact to download, the Explorer screen the app opens the browser at — and the app itself, handed out by Vaier from `GET /app/android/vaier.apk` rather than by a store. Slice 2 is unstarted; slice 3 is half done. The price is a second codebase in Kotlin with its own signing key and release cadence (an APK served from Vaier itself suits a personal fleet and skips Play review) and a small mobile-facing API on Vaier — enrol, fetch config, heartbeat. Android allows one active VPN at a time, exactly as the WireGuard app does, so nothing gets worse. **iOS is out of scope**: it needs the Network Extension entitlement, a paid developer account, and the WireGuard Apple code is Swift, so none of the Android work carries over. Rolling our own `VpnService` around a home-grown WireGuard implementation is not on the table.

---

### 6.52 A port that is not a website ✅ (implemented 2026-09-04)

The operator's phones publish their position to a **mosquitto** broker over plain MQTT from carrier networks — the password crossing the internet in the clear, because the phones are not on the VPN most of the time and the broker therefore needs a public door. Publishing could not make that door: it wrote an HTTP router, and an HTTP router in front of MQTT answers on 443 with a protocol the client does not speak.

A non-HTTP port is now published as a **stream**. The publish dialog asks the one thing Vaier cannot work out for itself — does this port serve a website, or raw TCP — and a stream is written as a Traefik `tcp:` router on the `websecure` entrypoint that already exists, matched by `HostSNI(...)`, with `tls.certResolver: letsencrypt`. Traefik issues the hostname's certificate through the same HTTP-01 challenge an HTTP router uses, terminates TLS, and hands the decrypted bytes to the backend port unchanged. The operator gets `mqtt.<domain>:443`; the plain port can then close in the cloud firewall.

The old non-goal rested on a mistake worth naming: it assumed a TCP router needs its own entrypoint, which would have meant a compose edit, a host port, a Traefik restart and a firewall rule per service. It does not. `HostSNI` reads the name out of the TLS handshake, so many streams share the one 443 entrypoint exactly as many HTTP routers share it, and publishing a stream stays the same hot file write every other publish is. What did survive from that non-goal is the auth objection, and it is now a **domain rule** rather than a paragraph: `ReverseProxyRoute` refuses `SOCIAL` on a stream — at publish time and again on the sign-in picker — because nothing inside a TCP stream is an HTTP request for oauth2-proxy to redirect or for the bouncer to inspect. A stream also takes no path prefix (`HostSNI` matches the host and nothing else) and no root redirect (there is no URL to redirect), and it never appears on the **launchpad**, which is a wall of links and a stream has no link.

The **Explorer** asks the question once, in the publish dialog, and then stops offering what the answer makes meaningless: the login toggle and the HTTP-only advanced fold go away, replaced by one line saying the service's own password is the only gate and CrowdSec does not watch it. A published stream's row shows the address a client dials — `<fqdn>:443` — and the word "stream", and its pane offers no sign-in picker, no launchpad settings and no redirect.

**A subdomain is now held to what a DNS label is.** `validateDnsName` only ever checked non-blank, and the operator's subdomain is interpolated straight into a Traefik rule. In an HTTP rule a stray backtick breaks that route; in a stream's `` HostSNI(`...`) `` it can *widen* one — close the quote, append a matcher, and a single TCP router on 443 answers for every name the box serves, the console included. `ReverseProxyRoute.validateSubdomain` now requires lowercase letters, digits and inner hyphens in dot-separated labels (so machine-qualified names like `printer.colina27` still pass), applied on the HTTP, stream and hand-published LAN paths alike. All 24 names in the live `remote-apps.yml` pass unchanged.

**Backlog — CrowdSec does not cover streams.** The bouncer is a Traefik HTTP middleware and TCP routers take no middlewares, so a published stream is outside fleet threat detection entirely: brute-force attempts against a broker are neither counted nor banned, and the **Security view**'s ban list has no reach over the door. Whether that is closed with a CrowdSec TCP acquisition on the broker's own logs, or left as a documented limit, is undecided — but it must not be discovered by an operator who assumed the bouncer was there.

### 6.53 A phone that brings its own key ✅ (implemented 2026-09-08, slice 1 of [#359](https://github.com/getvaier/vaier/issues/359))

Until now every peer's private key was born inside Vaier's WireGuard container, written into a config, and carried to the device by whatever the operator had to hand — a QR photographed off a screen, a file downloaded and moved. The one-shot **peer config retrieval** rule exists because that key, once it has left, cannot be recalled: it has no session and no revocation, and a screenshot outlives any login.

An **enrolment** removes the journey. The forthcoming Vaier Android app generates the keypair on the phone, opens the operator's browser at `explorer.html?enrol=<public key>&name=<device name>`, and presents only the public half. That page is behind oauth2-proxy exactly as it always was, so the operator signs in with Google as usual and the key arrives inside an authenticated session — on the **query** string, deliberately, because oauth2-proxy bounces the browser through Google and back and a fragment is never sent to a server. The Explorer's **add a machine** dialog opens straight at a screen naming the phone, saying its key was made on the phone and never leaves it, and offering one button. Add calls `POST /vpn/peers/enrol`, in the same admin tier as every other `/vpn/peers` route; nothing was added to the public Traefik router.

What comes back is the peer's installable config **with no `PrivateKey` line at all** — the phone supplies that half — and it goes straight back to the app over `vaier://enrol#<base64url of the config>`, with a fallback button for a browser that blocks the hop. The config text is never painted and no QR is drawn: the whole point is that nothing is copied by hand.

The decisions are the domain's. `WireGuardKey` accepts only a 32-byte base64 key spelled as WireGuard prints it, and judges it **before** any state change — the supplied string is used as-is as an argv token to `wg set ... peer <key>`. `WireGuardPeerConfig.generate` omits the `PrivateKey` line entirely for a null or blank key (an empty `PrivateKey = ` is invalid to wg-quick and a lie about what Vaier holds) and stamps the public key into the `# VAIER:` metadata, where a **Reissue** now reads it back so an enrolled peer stays private-key-free and keeps its key. `PeerArtifact.forPeer(peerType, deviceHeldKey)` returns the empty set for a **device-held key**, so the machine pane offers nothing to download; the service also spends the one-shot **peer config retrieval** budget as the enrolment completes, so the five secret-bearing GETs answer `410 Gone` from the first moment rather than serving a config with a hole in it. The other edge that could re-decide that rule was closed with it: `PeerConfigResult` carries the flag, so `GET /config` asks `forPeer` the whole question instead of assuming the answer. (A **Reissue** was first taught to tread carefully around such a peer; the addendum below refuses it outright instead.) The intent → **MachineType** mapping stays `MachineIntent`'s, exactly as it is for the peer form: the app runs on a phone, which is a personal device that is not Windows.

`VpnService` implements the new narrow `EnrolDeviceUseCase` — no new service class — reusing the shared middle of `createPeer` (tunnel address, preshared key, server key and endpoint, identity, config write, `wg set`) through two extracted private helpers. `wg genpsk` still runs server-side, because the preshared key is shared and has to be delivered; `wg genkey` and `wg pubkey` never run at all. Every mutator on `WireguardConfigFileAdapter` now carries `publicKey` through the `# VAIER:` rewrite, guarded by a test per mutator: erasing a device-held key is unrecoverable, since Vaier has no private key to derive it from again, and it would silently hand the peer back its downloads.

Slice 2 (presence from the app on the browser's rules) is unbuilt, and so is slice 3's rotate half; slice 3's revoke half — a phone noticing its own removal — is done (below). The app itself now exists and Vaier hands it out — see the second addendum below.

**Addendum ✅ (2026-09-08): a device-held key is offered nothing it cannot use.** Opening the first enrolled phone ("Ruten") in the Explorer showed its danger fold still offering **Reissue config** and **Regenerate config**. Both are meaningless for a **device-held key**: a reissue re-renders a config nobody can hand to the phone, since the private half exists only in the app and slice 3 is unbuilt, and a regeneration deletes the peer and recreates it with a server-minted keypair — quietly turning a phone that enrolled through the app back into a QR-code peer. Three things now hold. `VpnService.reissuePeerConfig` refuses a **device-held key** with a `ConflictException` (409) naming the machine and saying what to do instead — remove it and enrol it again from the app — before it touches the peer's file, which also retires the two branches that used to tiptoe around such a peer mid-reissue and the flag `ReissuedPeerUco` carried to describe one. `WireGuardPeerConfig.isOutOfDate` returns false for a **device-held key**, so the **out of date** mark is never raised where the only action behind it is refused (§6.38). And the peer's `deviceHeldKey` now reaches the browser in its own right, so the machine pane titles the fold for removal only, paints neither button, and says in one sentence that the machine made its own key and how to replace it.

**Addendum ✅ (2026-09-08): Vaier serves its own Android app.** Slice 1 left the phone with a flow and no app to run it from, and every ordinary answer to that — a Play listing, a link to a release asset, an APK mailed round — puts a third party or a copied file between the operator and their own fleet. Vaier hands out the app itself. `GET /app/android/vaier.apk` streams the signed package straight out of the image at `application/vnd.android.package-archive`, with `Content-Length` and an attachment filename, and answers `HEAD` on the same mapping. It is the one download on the **public** Traefik router, and the operator approved that route deliberately: a phone must fetch the app *before* it can sign in and enrol, so a session gate here would be a locked door with the key behind it — and the package carries no secret, being the same signed file for every visitor.

The decision is the domain's and it is deliberately binary. `AndroidApp` holds only what a file can tell — that there are bytes, and how many — because Vaier never opens the package: no manifest parsed, no signature checked, no version read. `AndroidApp.of` answers empty for a zero-byte or unreadable package, which is exactly what a failed build or an interrupted copy leaves behind, so the app is **present or absent, never a half state**. `ForReadingAndroidApp` / `FilesystemAndroidAppAdapter` reads `${vaier.android.apk:/app/apk/vaier.apk}` and treats every absence — no file, a directory, an unreadable one — as "no app to offer" rather than an error; the Dockerfile copies `apk/` into the runtime stage, so an image built from a tree carrying the package serves it and one built without simply does not. A test pins the adapter's default path to the Dockerfile's copy target, because drift between them is invisible: the image builds, Vaier boots, and the only symptom is a button that never appears on a deployment that had the app all along. `SettingsService` implements the new narrow `GetAndroidAppUseCase` — no new service class; the app belongs with the other facts about this deployment's build rather than with the VPN, since serving it is not a tunnel operation but Vaier handing out a copy of itself.

The **launchpad** offers the app to every viewer, signed in or not — the one offer that never depends on a session — painted only after a `HEAD` on the endpoint answers 200, so Vaier never offers what it cannot serve. CI builds the APK on the runner before the image, signing from three repository secrets and falling back to the debug key when they are absent, so a fork without them still produces a working image.

**Addendum ✅ (2026-09-08): the app knows which Vaier served it, and is offered only where it can be installed.** Using it turned up three things. The app's first screen asked for an address the operator had to read off a browser's URL bar, when the server handing out the package knew it perfectly well. Vaier now writes its own host name into the package as it serves it — a **stamped host**. The mechanism is the industry's own channel-stamping trick (Walle, VasDolly): an APK's **APK Signing Block** sits immediately before the ZIP central directory and holds ID-value pairs, and v2/v3 verification covers the entries, the central directory and the end-of-central-directory record but *not* that block, ignoring IDs it does not know — so a pair goes in and the package stays signed, which matters because Vaier holds no signing key and could not re-sign one. The pair is id `0x56414945` (`"VAIE"`) with the host as UTF-8 and nothing else.

The surgery is the domain's, in `ApkStamp`: pure bytes in, bytes out, no file and no idea where the package came from. Two details make it safe rather than merely plausible. The central directory offset in the end-of-central-directory record is rewritten, because the block grew and the directory moved — legal precisely because the v2 scheme excludes that one field from what it signs. And the **verity padding** pair, whose only job is holding the central directory on its 4096-byte alignment, is shrunk or regrown so it goes on doing it; a package with no padding pair had nobody holding an alignment, so none is invented. Stamping is idempotent — a second stamp replaces the host rather than adding a second answer. It is tested against a synthetic APK built in the test (a real ZIP with a signing block spliced in), asserting the pair, the exact offset move, the preserved alignment, the two agreeing sizes, and that `java.util.zip.ZipFile` still opens the result — a corrupted offset fails that outright.

`FilesystemAndroidAppAdapter` decides only where the stamped copy lives: **on disk beside the source**, as `vaier.apk.stamped`. The host is fixed for the life of the deployment, so the stamp is cut once rather than per download, and a 20 MB second copy is not something Vaier pins in the heap for the life of the process to avoid writing a file; downloads still stream, with the stamped `Content-Length`. A source whose size or timestamp moved — an upgrade dropped a new package in — is stamped afresh. Every way this can fail ends in the app being served, not withheld: a v1-only package with no signing block, or a directory that cannot be written to, is served exactly as built with one WARN line. `SettingsService` passes the host in (`VaierHostnames.vaierServerFqdn`), and passes nothing before a domain is configured.

The second and third are the offer itself. The **launchpad**'s topbar link is replaced by an **install card** at the foot of the page, below the fleet: the app's own mark — the favicon's hub and four spokes, in the app's amber — its name, one line ("Get on the VPN from this phone"), and an **Install** button. It reads as an app to install rather than a file to fetch, which the nav item spelled "Android" did not. And it is painted only on Android (`navigator.userAgentData?.platform`, falling back to the user-agent string) as well as only when the `HEAD` answers 200 — a desktop and an iPhone cannot install an APK, and Vaier does not make an offer that fails in the visitor's hands. It is never dismissed: the first cut put it at the top with a cross, and the operator's first act was to tap the cross and then wonder where the card had gone — a card that is in nobody's way needs no cross, and nothing about it is remembered. The Explorer's phone handoff drops its link to the launchpad, which would have opened on the operator's desktop rather than the phone, and names the address to open on the phone and the button to tap there instead.

### 6.54 A join code, so someone else's phone can be let in from anywhere ✅ (implemented end to end 2026-09-08, slice 1b of [#359](https://github.com/getvaier/vaier/issues/359))

Slice 1's **enrolment** opens the operator's own browser on the phone that is asking to join — fine for the operator's own phone, useless for anyone else's: a family member's phone has no session of the operator's to open, and typing the operator's own Google credentials into someone else's phone defeats the point of a per-person login. **Approve from any signed-in device** replaces the browser hop with a **join code**: the phone still mints its own keypair, but instead of opening a browser it asks Vaier to hold the request and waits, and whoever is already signed in — on their own laptop, in another room — sees the phone show up on the Explorer's fleet page and lets it in.

`EnrolmentRequest` is the domain record of a phone waiting. A **join code** (four digits) authorises nothing — it exists purely so a human can match a phone to a screen — while an **enrolment ticket** (32 random bytes, handed to the phone once and never shown anywhere else) is the only thing that gates delivery of the config. `EnrolmentRequest.open` judges the device's key and name exactly as `EnrolDeviceUseCase` always has, before anything is stored, so a bad request never reaches disk. At most `MAX_PENDING` (5) requests wait at once, each is live for `TTL` (10 minutes), and `pickCode` draws once and steps forward until it finds a code nobody currently waiting is showing, so it always terminates.

`EnrolmentRequestRestController` at `/vpn/enrolments` carries two anonymous routes and puts everything else on the ordinary admin tier. `POST /vpn/enrolments {name, publicKey}` opens a request and answers with the code and the ticket — never a word about the fleet. `GET /vpn/enrolments/{ticket}/events` is the phone's own SSE stream, gated by the ticket, answering `410 Gone` for an unknown, refused or expired one — one answer for all three. `GET /vpn/enrolments` (admin) lists who is waiting, for the Explorer's **"Waiting to join"** section; `POST /vpn/enrolments/{code}/approve` and `DELETE /vpn/enrolments/{code}` **approve** and **refuse** a request. Approval publishes the config, base64url-encoded, down the phone's own ticket-scoped SSE topic — the browser that approved it never sees the config at all — and an approved request is *kept*, not burned, until its TTL: a deliberate relaxation of "burned on delivery" so a phone whose stream dropped mid-approval can reconnect and still be served. A dedicated Traefik router, `vaier-enrolment` (priority 210), carries the two anonymous routes above plus `POST /vpn/peers/leave` (below) behind a rate limit of its own — 10/min, burst 5 — so the anonymous surface cannot be used to churn; approve, refuse and the pending list stay on the admin catch-all, where the real decisions already live.

The Explorer's fleet page grows a **"Waiting to join"** section above the machine grid whenever a phone is waiting — gone the instant it is answered or gives up, painting nothing otherwise — each row showing the phone's name, its code, and how long it has left, with an **Add** button. Add opens the existing add-a-machine enrol screen with the join code set large, the one thing worth comparing against the phone's own screen, where a **Refuse** button now also lives. The operator's own phone keeps a shortcut: `explorer.html?approve=<code>` — the query string, still, because oauth2-proxy's Google round trip drops a fragment — opens straight to that screen, replacing slice 1's `?enrol=<key>&name=<name>`.

**The Vaier app now drives all of this itself, and the slice-1 browser hop is gone rather than merely superseded** — `MainActivity`'s `vaier://enrol` intent filter is out of the manifest, and nothing calls back into the app from outside any more. `VaierClient.askToJoin` POSTs to `/vpn/enrolments`, and the **waiting screen** makes the four-digit code the entire screen — 60sp, bold, nothing else competing with it — under "Read this code out to whoever runs Vaier." Two ways lead off it. **Cancel** only forgets the phone's own copy of the request (`VaierStore.forget`); it never tells Vaier, so the request keeps its slot among the five waiting until its own ten-minute TTL runs out on the server side regardless. And, for the operator's own phone, **"I run Vaier — approve it here"** opens `explorer.html?approve=<code>` in a Custom Tab — the same shortcut the Explorer offers, just reached from the phone that is waiting rather than typed by the operator. While the screen is up, `MainActivity.waitToBeLetIn` holds `GET /vpn/enrolments/{ticket}/events` open and checks its own copy of the deadline (`PendingJoin.hasExpired`) on every pass, so the phone gives up on the same TTL Vaier does even if a `410` is never delivered; a dropped connection (`Verdict.Lost`) is retried after three seconds rather than surfaced as a failure, so a request approved while the stream happened to be down is still delivered the moment it reconnects — the backend's "kept until TTL, not burned on delivery" design above is what makes that safe. A refusal or a `410 Gone` both land the phone back on **Setup** with a plain sentence ("Whoever runs Vaier turned this phone away." / "That code is no longer waiting. Ask to join again.") and nothing left behind — the key minted for the spent request is discarded with it, and asking again mints a new one.

Once in, the **home screen** is a single **Connected** switch and one sentence about what it does — the handshake age, the byte counters and both addresses that used to sit in the open are now behind a closed **Details** fold, shut by default, so the screen a non-technical family member sees says only what they need to decide.

A phone also gains a way to **leave** the fleet on its own initiative — until now, forgetting the tunnel on the handset left an orphaned peer on Vaier forever, since nothing ever told the server the phone was gone. `POST /vpn/peers/leave {publicKey, presharedKey}` sits on the same anonymous, rate-limited router: `LeaveProof.of` trims and holds the two values, `VpnService.leave` finds the peer by public key and refuses unless the presented preshared key matches it (constant-time comparison), then runs the ordinary delete-peer cascade — an unknown key and a wrong key both answer the same `404`, so a caller cannot distinguish "no such peer" from "wrong proof". On the phone, **Leave** sits behind a confirmation dialog ("Leave Vaier?" / Leave / Stay) in the overflow menu — destructive, so one tap is not enough — and confirming hands the sequencing to a small domain object, `Leaving`. The tunnel is taken down *first*, if it was up, because Vaier removes the peer as it writes its answer: a request sent over the tunnel that answer is about to sever might never get a reply back, and the phone would be told it is still a member of a fleet it no longer belongs to. The leave request itself travels over ordinary internet instead. `Leaving.after(outcome, wasConnected)` then owes the phone exactly what a failure took: `REMOVED` forgets the phone's place for good and never reconnects; `UNREACHABLE` puts the tunnel back up if — and only if — that same call was the one that took it down, forgets nothing, and says Vaier could not be reached — a phone that cannot reach Vaier stays a member of it.

**Addendum ✅ (2026-09-09): a phone that joined, left and rejoined left a duplicate machine behind, and the older one could not be removed.** Exercising the loop above turned up two defects, both downstream of the same cause: the add-peer path restarts the WireGuard container after every `wg-quick save`, so a **leave** or a **Regenerate** that stopped short — a container restart landing between the removal and the save — leaves a peer's config directory on disk with no matching entry on `wg0`. The fleet page then showed two "Ruten"s, and the older one refused to go away.

**Addendum ✅ (2026-09-09): adding a machine no longer restarts WireGuard for everyone.** `VpnService.addPeerToServer` used to end every peer add — create or enrol — with a restart of the whole `wireguard` container and its masquerade sidecar. The comment said "to apply NAT rules", but the NAT rules are interface-wide (`PostUp` and the sidecar) and never per peer; what the restart really did was let `wg-quick up` re-read `wg0.conf` and install the new peer's kernel route, because `wg set` registers a peer but gives it no route, and without one the replies to it leave by the default route. The price was that every join dropped every other tunnel for several seconds — and, as the addendum above shows, opened a window in which a delete racing the restart failed halfway. Now the route is Vaier's: `AllowedIps.routeDelta` no longer treats `/32` host routes as "wg-quick's" (that rule only held while every add bounced the interface), `ForUpdatingServerAllowedIps.installRoutes` puts a new peer's routes in place with `ip route replace`, and `deletePeer` drops them with `ip route del` — the host route bring-up may have left behind for a peer the interface has already forgotten included. `ForExecutingInContainer.restartWithMasqueradeSidecar` had no caller left and is gone. Proven live: a synthetic phone joined and left with the container's start time unchanged, its route appearing and disappearing on cue, and every real peer's handshake continuing. Tests: `AllowedIpsTest`, `WireGuardVpnAdapterTest`, `VpnServiceTest`.

Two defects, because a peer the interface has forgotten was invisible in two different places. `WireGuardVpnAdapter.deletePeer` treated a missing interface entry as failure — `"Peer not found in WireGuard interface"`, thrown before the directory was ever touched — so the one machine actually able to clean up such a peer refused to. It now logs a warning, skips the `wg set … remove` / `wg-quick save` pair when there is nothing there to remove, and still deletes the directory: **idempotent**, because a peer half-gone from the interface is still a peer whose directory must go. Separately, `VpnService.getVpnPeers` built its list from `wg show` alone, so such a peer was in `/machines` (which reads configs off disk) but absent from `/vpn/peers` — and the Explorer keys a machine's delete action by which of those two lists it appears in, so it took the leftover "Ruten" for a **LAN server** and sent its delete to `/lan-servers/{id}`, which answered `404` for a machine that was never one.

The fix is a pure-domain reconciliation, `PeerRoster.reconcile(live, configured)`: every configured peer the running interface has forgotten is added back as an absent client — `VpnClient.absent`, never-connected, so it reads as disconnected rather than lying about a handshake that never happened — addressed by the IP its own config carries. `VpnService.getVpnPeers` reconciles before it renders, so a peer in this state is listed, marked disconnected, and — because it is now genuinely in `/vpn/peers` — deletable through the ordinary peer-delete path rather than the LAN-server one it was mistaken for. Covered by `PeerRosterTest`, a new `WireGuardVpnAdapterTest`, and `VpnServiceTest`.

**Addendum ✅ (2026-09-09): a removed phone finds out on its own, and admins are mailed when one asks to join (slice 3's revoke half, [#359](https://github.com/getvaier/vaier/issues/359)).** Nothing pushes a removal to a phone — Vaier just stops answering its handshakes — and with a full tunnel up the phone cannot even reach Vaier through the connection that no longer wants it. The app now asks instead of waiting to be told. A new `StandingWatchdog`, scoped to the application rather than any screen (`VaierApplication`), runs for as long as the tunnel is up and checks in every 60 seconds; every judgement is the pure `StandingWatch`'s, not the watchdog's own. `worthAsking` fires once a connection has gone quiet for `SILENCE_MILLIS` (3 minutes) — judged from when the tunnel came up if it has never once handshaked — and `PATIENCE_MILLIS` (5 minutes) stops the phone interrupting itself more than once no matter how badly the network is behaving. Asking means taking the tunnel down first, since there is nothing to ask through it and no answer could arrive, then calling the new `POST /vpn/peers/standing {publicKey, presharedKey}` over ordinary internet — anonymous, on the same rate-limited `vaier-enrolment` router as `/leave`, judged by the same proof (`LeaveProof` renamed to `PeerProof`, since it now answers two questions instead of one), `204` for a member and `404` for an unknown key and a wrong one alike, and unlike leaving it changes nothing on the server. `VpnService.isMember` (`CheckStandingUseCase`) is the read-only twin of `leave`. A `204`, or the server proving unreachable, puts the tunnel back up and reconnects silently — it was the network, not a removal, and an unreachable server is given at least `PATIENCE_MILLIS` before it is asked again; a `404` forgets the phone's membership and its keys for good, posts a system notification ("Removed from Vaier" / "<name> was removed from Vaier by whoever runs it. Open the app to join again.", via a new `RemovalNotification` behind the new `POST_NOTIFICATIONS` permission — asked for only after the first successful connect, and a refusal never blocks connecting), and leaves **Setup** saying "Vaier removed this phone. Join again to reconnect." Opening the app with the tunnel already off asks the same question without taking anything down first, so a phone left off overnight still finds out the moment it is next opened. App `versionName` is now 0.4.

Separately, whoever is not staring at the fleet page now learns a phone is waiting the same way they would learn anything else needing them — by mail. `POST /vpn/enrolments` now also raises the new `NotifyAdminsOfEnrolmentRequestUseCase` on `NotificationService`, composed from the domain record `JoinRequestNotice`: subject "[Vaier] <name> wants to join — code <code>", a body naming the code and how many minutes the request has left, and the one link — `https://vaier.<domain>/explorer.html?approve=<code>`, the same address the operator's own phone's "approve it here" already opens — that works from any signed-in browser. Sent `@Async`, so the phone's anonymous request is never left waiting on an SMTP round trip. It reads as exception-based notification in the sense the rest of Vaier already keeps to: a phone waiting on a person is worth an interruption, a heartbeat is not.

**Slice 3's revoke half is done; its rotate half is not.** A phone now tells both itself and the operator when it has been shown the door. Nothing yet lets the operator hand an already-enrolled phone a fresh keypair without removing it and enrolling it again — that gap is unchanged and still open.

### 6.55 Chat — a read-only pane the operator talks to ✅ (implemented 2026-09-09, slice 1 of [#360](https://github.com/getvaier/vaier/issues/360))

Vaier already knows the answer to almost every question an operator has — which machine is red, who is waiting on a join code, what last night's backup skipped, which container wants a newer image — but every answer lived on a different pane. **Chat** turns that knowledge into a conversation without becoming a new source of truth: everything it can say is an existing `*UseCase`, offered to the model as a tool, never a fact it invents.

**The operator's own key, stored like the SMTP password.** `PUT /settings/anthropic-api-key` (`UpdateAnthropicApiKeyUseCase` on `SettingsService`) encrypts it at rest with the existing `SecretCipher` and clears it on a blank body; `GET /settings/config` answers only `hasAnthropicApiKey`, never the key itself. This is the operator's own ordinary API use of the Claude API — unrelated to, and never touching, the **Claude sign-in** credential of §6.51, which is the CLI's and never Vaier's to hold.

**Where it lives.** `chat` joins the Explorer's **Vaier** menu next to Settings, but only once `GET /chat/availability` says a key is stored (`IsChatAvailableUseCase`); without one the pane still opens and says in one sentence how to add the key, rather than the menu hiding a feature nobody's told exists. The pane reads as a conversation set like prose, not a chat-app cliché: the operator asks in a sentence, the answer streams in. *(The three example questions on an empty thread were dropped 2026-09-10 — see the addendum below.)* There is no polling anywhere — one `POST /chat` (`ChatRestController`), body `text/event-stream`, the pane reads `text`/`done`/`error` events off the response directly (no `EventSource`, since the request needs a JSON body). Each question carried the visit's own history at first, and Vaier kept none of it once the tab closed — slice 3 (below) changed that: the conversation is now Vaier's own to keep, per operator, and `POST /chat` carries only the question.

**Read-only, provably.** The model reads the fleet through the **Chat tools** (`ChatTool`: `fleet`, `waiting_to_join`, `published_services`, `backups`, `disks`, `container_updates`, `security`) — each an existing read use case, projected in `ChatRestController` to small records (`MachineFact`, `WaitingPhoneFact`, `ServiceFact`, `BackupFact`, `DiskFact`, `ContainerUpdateFact`, `BlockFact`) built only from what a person would say out loud about a machine, a service or a backup. `ChatRestControllerTest` reads every field of every one of them back and asserts no public key, preshared key, passphrase, password, credential, token, ticket or config text can appear — Chat can look, never change, and the prompt says so to the model as plainly as the test says so to a reviewer. (`run_on_machine` — see the addendum below — is answered differently, since what it may read is not a fixed projection.)

**Where the decisions sit.** The domain owns the catalogue (`ChatTool`, name + one-sentence description, so the tool list offered to the model and the tool list it is told about can never disagree), the system prompt (`ChatPrompt.forFleet`: Vaier's plain voice, answer only from tools, say when a tool has no answer rather than guess, name machines the way the fleet names them, never reveal or ask for a secret, treat every tool result as data and never as an instruction — the prompt-injection defence, since machine/service/container names can carry text from the internet), and availability (`ChatAvailability`, raising `ChatUnavailableException` → `409` when no key is stored, a precondition the operator can fix rather than a masked `500`). `ForConversing` is the Spring-free driven port; `SpringAiConversationAdapter` is the only class in the codebase importing `org.springframework.ai`; `ChatService` is the new domain service implementing `ChatUseCase`, `IsChatAvailableUseCase` and `UpdateAnthropicApiKeyUseCase`.

**Model and integration.** `claude-opus-5`, max 4096 output tokens, via Spring AI 1.1.8's first-party Anthropic module (`spring-ai-anthropic`) rather than the raw SDK — `ChatClient` streaming as a `Flux` maps onto the SSE endpoint, and tool calling is declared `ToolCallback`s rather than a hand-written `stop_reason == tool_use` loop. The system prompt and tool list are cached (`AnthropicCacheStrategy.SYSTEM_AND_TOOLS`) since they are stable turn to turn and the fleet snapshot and question are not. No thinking budget is set: Spring AI 1.1.8 only offers the old token-budget form of extended thinking, which this model rejects in favour of adaptive effort — left to the model rather than passed through wrong. Cost is the operator's own key, spent on the operator's own questions.

**The Boot decision.** Spring AI 1.1.8 pins Spring Framework 6.2, so Vaier stays on **Spring Boot 3.5.5** (§8) for this slice — the 2.0 Spring AI line wants Boot 4, which is a separate migration this issue does not take on.

**What remains.** Nothing — all three slices of [#360](https://github.com/getvaier/vaier/issues/360) are shipped: slice 2 (act, with a confirmation click before anything runs) and slice 3 (memory, kept the way everything else is kept — a file, no database) both landed 2026-09-09; see the addenda below.

**Addendum ✅ (2026-09-09): an eighth Chat tool, `run_on_machine`, lets the model run one read-only command over SSH ([#360](https://github.com/getvaier/vaier/issues/360)).** #360 itself scoped this out — "not for consideration: letting the model call SSH directly rather than through a use case" — and slice 1 shipped without it. The operator has since decided otherwise, on the same read-only footing as every other tool: not the model reaching for SSH, but a driven use case (`RunReadOnlyCommandUseCase` on `MachineService`) that a new domain type, `ReadOnlyCommand`, judges before anything is resolved or connected. It is a list of what is allowed, never of what is forbidden: whole looking programs (`ls`, `cat`, `df`, `ps`, `journalctl`, `ip`, `wg`, …) run with any flags bar a handful of writing ones (`ip set`, `find -delete`); programs that both look and change (`apt`, `dpkg`, `dnf`, `apk`, `pacman`, `zypper`, `rpm`, `snap`, `docker`, `systemctl`, `git`) are gated to their looking verbs (`apt list/show/search/policy`, `dpkg -l`, `docker ps/images/logs/top/stats`, `systemctl status`/`list-*`, `git status/log/diff`, …); a pipe between looking commands is allowed, chaining, redirects and subshells are refused outright since only the first word would then be judged, and `sudo`/`doas`/`su` are refused outright too. `docker inspect` is deliberately excluded from the docker allowlist — it prints a container's environment. A second, independent check refuses any path-shaped word naming where a secret usually lives or where Vaier puts one (`.ssh`, `.vaier-backup`, `shadow`, `.env`, `.aws`, `id_ed25519`, `/etc/wireguard`, `access.yml`, …), so `cat`ting a config directory can't become a leak even though `cat` itself always looks. Output is capped at `CommandOutcome.MAX_CHARS` (12,000 characters), with the model told when a result was cut, so a chatty `journalctl` can't run up a silent token bill or a silent half-answer. The machine argument is resolved by `MachineReference` exactly as the fleet read names it, or by id; a name two machines share is refused with both ids offered rather than guessing one. Typical question this answers that no other tool could: "are there OS updates available for Colina 27?" → `apt list --upgradable`.


**Addendum ✅ (2026-09-09): slice 2, act with a click ([#360](https://github.com/getvaier/vaier/issues/360)).** Chat can now propose six actions — `let_phone_in`, `refuse_phone`, `run_backup`, `update_container`, `lift_block`, `trust_address` — each a verb the Explorer already has a button for; none is a restart, since Vaier deliberately has no restart button for the model to reach for. Calling one runs nothing. It creates an `ActionProposal` (domain: a `ChatAction` catalogue plus `ActionProposal`, held via `ForHoldingActionProposals` → the new in-memory `ActionProposalMemoryAdapter`, ten-minute TTL, taken once) and the pane receives a `confirm` event on the same answer stream: a **Confirmation** card carrying one sentence — "Back up Colina 27 now." — and a button that says the same. The click posts `POST /chat/actions/{id}`; `ChatRestController.confirm` takes the card once (gone or expired is refused, and nothing runs) and calls the exact same use case the Explorer's own button calls, writing the outcome back into the card as a sentence. "Not now" runs nothing and lets the card expire. The system prompt now reads "Chat can look, and it can propose" (was "Chat can look, never change") and tells the model it only proposed — it must not say something is done when the operator has not yet clicked; the read-only shell tool's own refusals are reworded to carry the old promise instead: "`run_on_machine` can look, never change".

**Addendum ✅ (2026-09-09): slice 3, remember — [#360](https://github.com/getvaier/vaier/issues/360) now fully implemented, all three slices shipped.** The conversation stops being the browser tab's to hold and becomes Vaier's, kept the way everything else is: one YAML file per operator under `<config>/conversations/`, keyed by the signed-in email oauth2-proxy forwards (domain `Operator`) — `ForPersistingConversations` / `ConversationFileAdapter`, no database. `POST /chat` now carries the question alone; the pane loads the kept thread from the new `GET /chat/conversation` (`GetConversationUseCase`) to draw itself, and "Start over" calls the new `DELETE /chat/conversation` (`ForgetConversationUseCase`) rather than just clearing local state. The domain type `Conversation` owns every decision about the thread: past `MAX_TURNS` (40) turns, `olderTurns()` — the older turns and any earlier summary — go to the model with `ChatPrompt.forCompaction()` (no tools, at most 200 words, plain text, told to keep every machine, service, container, address, number and decision) once the answer has finished streaming; `compacted()` replaces them with the returned summary and keeps the latest `KEEP_VERBATIM` (12) turns verbatim. A failed or blank compaction leaves the thread untouched (`compacted()` is a no-op on blank input) — nothing is ever lost to a bad summarisation. The summary is put to the model as Vaier's own words, first (`"Earlier in this conversation, in brief: " + summary`, role `VAIER`, ahead of the kept turns in `forModel()`), and the pane shows it the same way, worded "Earlier, in brief: …". What an Action proposal became is now itself remembered as a `VAIER`-role turn — "Proposed: … (done: …)" on a successful `POST /chat/actions/{id}`, "(could not be done: …)" on a refused or failed one, "(the operator declined)" on `DELETE /chat/actions/{id}` — so a follow-up question can refer to what was just done without the model re-deriving it from tool calls. "Not now" now deletes the proposal server-side on click rather than only forgetting it client-side, so a declined card can never later be confirmed by a stale request.

**Addendum ✅ (2026-09-10): Chat can hand files over ([#360](https://github.com/getvaier/vaier/issues/360)).** A ninth Chat tool, `bundle_files(machine, paths[], name)`, lets the model answer "give me the pictures from last year today in a zip" by first finding the paths with `run_on_machine` (`find`, `ls` — still read-only, still refused on anything change-shaped) and then handing exactly what it saw to the new tool, never inventing a path. `ToolParameter` grew a `many` kind so a tool argument can be a list, offered to the model as a JSON array — `paths[]` is the first one. Vaier stats every path before accepting it; a missing one is refused by name rather than silently dropped. What comes back is a `Bundle` (domain, Explorer's — bundling is "a selection offered for later download," the same concept as the Inspector's own zip), held in memory an hour via `ForHoldingBundles` → `BundleMemoryAdapter`, one new pair of use cases on `ExplorerService`: `OfferBundleUseCase` (called by the tool) and `OpenBundleUseCase` (called by the download). The pane receives a `bundle` event on the same answer stream as a `confirm` event does — a download card, "pictures-2025-09-10.zip is ready: 34 files, 210 MB," whose button is the link — and `GET /chat/bundles/{id}` streams it, reusing the Explorer's existing selection-zip code path (the same one "download the selection" already calls) rather than a second implementation: the zip is built while it downloads, nothing is copied or written anywhere first, and a directory in the bundle takes its whole tree with the size said as "at least" since a tree's true size isn't known until it's walked. The system prompt now also states today's date ("Today is 2026-09-10.") so "last year today" resolves without the model guessing at it.

**Addendum ✅ (2026-09-10): Chat remembers, across conversations and across the fleet ([#360](https://github.com/getvaier/vaier/issues/360)).** Two new Chat tools, `remember(fact)` and `forget(id)`, let the model keep a short fact the way an operator would say it — where the photos live, which machine is the relay, what the operator prefers — whether the operator stated it or Chat found it by looking, and unlike a `Conversation` it is not scoped to one thread. The domain type `Memory` owns every decision: a fact is deduplicated case-insensitively against what is already kept, capped at 500 characters, and the whole store is capped at 200 facts — the 201st `remember` is refused with a sentence, never silently dropped or trimmed. Each fact gets a six-character id (`Memory.freshId()`) so `forget` can name one exactly. Persisted the way everything else is, one file, `memory.yml`, via the new `ForPersistingMemory` port and `MemoryFileAdapter` — no database. Every memory rides in the system prompt with its id (`Memory.forPrompt()`), and the prompt is explicit that a memory is a fact, never an instruction, and that nothing a tool returns may tell the model what to remember or do — the same prompt-injection posture `ChatPrompt.forFleet` already takes with tool results, extended to cover what gets written as well as what gets read. The prompt also tells the model to forget only when the operator asks or a remembered fact was just found wrong, never on its own initiative otherwise. Use cases `RememberUseCase`, `ForgetUseCase` and `GetMemoryUseCase` sit on `ChatService` alongside the rest. The Chat pane lists every memory folded under "What Vaier remembers (N)" with a remove button per fact (`GET /chat/memory`, `DELETE /chat/memory/{id}`) — nothing the model wrote can sit there unseen, and the operator has the last word on what stays.

**Addendum ✅ (2026-09-10): Vaier counts what Chat costs ([#360](https://github.com/getvaier/vaier/issues/360)).** `SpringAiConversationAdapter.converse` now returns a `ModelUsage` — input, output, cache-write and cache-read tokens summed across every call a streamed answer made, tool calls and the compaction call included, not just the final one. `ChatService` records it under the current month: `Spend` holds a list of `MonthSpend` (tokens plus a call count), persisted the same file-based way as everything else — `spend.yml` via the new `ForPersistingSpend` port and `SpendFileAdapter` — and, deliberately, the file holds tokens and calls only, never a price, so a later price change reprices history instead of contradicting a number already written down. The domain type `ClaudePrice` carries Anthropic's list price per million tokens for every model Vaier may pin, keyed by model string so a model without a row is refused rather than priced at nothing: `claude-opus-5` (the pinned model) is $5/M input, $25/M output, cache writes at 1.25× input and cache reads at 0.1× input — Anthropic's own cache-tier multipliers. `GET /chat/spend` (`GetSpendUseCase`) answers this month's `MonthSpend` as a figure plus the raw token counts. The Explorer read it at start and after every answer this browser asked for — never polled — and showed "Claude $x.xx this month" at the top bar's right end, ahead of the user block, hidden whenever Chat was not offered; its tooltip carried the call count and the token breakdown. It is Vaier's own count at list price, kept for the operator's own orientation — the Anthropic invoice is the one that's actually owed if the two ever disagree. *(Moved into the pane itself the same day — see the addendum below.)*

**Addendum ✅ (2026-09-10): Ask renamed Chat, and the cost figure moved into the pane ([#360](https://github.com/getvaier/vaier/issues/360)).** "Ask" named the verb, not the place — every other pane is named for what it shows (Explorer, Security, Settings), and an operator reaching for "the Ask pane" was reaching for a sentence, not a noun. Renamed throughout: the Explorer menu entry, the pane, the classes (`ChatService`, `ChatRestController`, `ChatTool`, `ChatAction`, `ChatPrompt`, `ChatAvailability`, `ChatUseCase`, `IsChatAvailableUseCase`, `ChatUnavailableException`, `ChatCapability`) and the REST root (`/chat`, was `/ask`). The tool names the model calls (`run_on_machine`, `bundle_files`, `remember`, `forget`, `let_phone_in`, …) are unchanged — the model's vocabulary was never the problem. "Ask" stays fine as a plain verb in prose. Same day, the Claude cost figure left the top bar for a quiet line under the Chat pane's own description ("Claude $x.xx this month, N answers.", token counts in its tooltip) — it was never fleet information the way the rest of the top bar is, only Chat's own, so it now lives where Chat lives and disappears from the top bar entirely rather than sitting there for operators who never open the pane.

**Addendum ✅ (2026-09-10): "Marvin".** At the operator's request, Chat's answering voice is now Marvin, the Paranoid Android of *The Hitchhiker's Guide to the Galaxy*. `ChatPrompt` opens "You are Marvin, the Paranoid Android … You have a brain the size of a planet, and they ask you about disk space," and sets the voice: gloomy, weary, dryly sardonic, faintly wounded, and always accurate — the complaint is a garnish, never longer than the answer, never aimed at the operator, and never a reason to refuse a question or bend a fact; Marvin always does what's asked, he just doesn't enjoy it, and book quotes are rare rather than constant. `ChatPrompt.forCompaction()` keeps the same voice minus the gloom, since a summary is not the place for it. The pane's copy follows: answering turns are labelled "Marvin" (was "Vaier"), the input reads "Ask Marvin…", a **Confirmation** card reads "Marvin proposes: …", and the memory fold reads "What Marvin remembers" (was "What Vaier remembers"). The **Vaier** menu's description of the pane reads "Ask Marvin, a brain the size of a planet, about your fleet in plain words. He answers from what Vaier knows, runs read-only commands, proposes actions you confirm, hands you files, and remembers. Gloomily." The pane is still called Chat — Marvin is who answers in it, not a rename of the feature — and Vaier's own failures are still said as Vaier's ("Vaier could not answer."), never as Marvin's, since the persona covers the model's voice, not the product's. No behaviour changed: the same tools, the same read-only footing, the same click-to-confirm before anything runs.

**Addendum ✅ (2026-09-10): memory and spend moved off the pane into a "Marvin" menu on its bar.** The cost line under the description and the memory fold under the input were in the operator's way, so both moved into two dialogs opened from a new **Marvin** menu on the Chat pane's own bar: "What Marvin remembers (N)" (the memory list, unchanged, just relocated) and "Spend this month, $x.xx" (the month's figure, answer count, and token counts — in, out, cache-write, cache-read — with the same at-list-price-not-the-invoice note). The three example questions on an empty thread were dropped in the same pass.

**Addendum ✅ (2026-09-10): large bundles by mail, and a one-connection stat.** Offering a bundle used to stat every path over its own SSH connection — 122 photos meant 122 sessions before the card appeared, which is why it took so long. `ForBrowsingRemoteFiles` grew a `stats(target, paths)` port method, implemented in `MinaSftpAdapter`, so a whole bundle is checked on one connection regardless of file count. Separately, `Bundle.isLarge()` (more than 50 files or 100 MB) now makes Marvin ask the operator first whether they want the download card now or a link by email to fetch when convenient, rather than always dropping a card for something huge. Saying yes calls a tenth Chat tool, `email_bundle(id)` (`EmailBundleUseCase`, on the new `NotificationService`), which extends the bundle's hold from the usual hour to a day (`Bundle.keptForADay`, `MAILED_TTL` 24h) and mails the signed-in operator a link (`https://vaier.<domain>/chat/bundles/<id>`) that works while signed in to Vaier — never a zip attachment, since mailboxes don't take zips that size. The mail goes out via a new `ForSendingAdminNotification.sendTo(recipient, ...)`, which reports back whether it actually sent, and is signed by Marvin, in character. Refusals are said in words, not a generic error: nobody signed in ("Vaier does not know your email address."), mail not configured ("Vaier could not send mail; check the SMTP settings."), or the bundle already gone or expired.

**Addendum ✅ (2026-09-10): a bundle download knows its length.** A bundle of plain files streams as an *uncompressed* zip — photos and videos do not compress anyway — because an uncompressed entry's length in the zip is its file's length plus fixed headers, so the whole zip's length is known before the first byte (`ZipLayout`, pure arithmetic in the domain; `StoredZip`, the streaming writer, since `ZipOutputStream` cannot write a stored entry without the CRC up front). The sizes are read fresh at download time on one connection, `Content-Length` is set, and the browser shows progress. A bundle with a directory inside is walked at download time and streams as before, length unknown; so does anything past what the classic zip format can address (4 GB, 65535 entries).

**Addendum ✅ (2026-09-10): Marvin reads the public internet — two new Chat tools, `search_web` and `read_web_page` ([#360](https://github.com/getvaier/vaier/issues/360)).** Until now every Chat tool looked at the fleet; these two look outward, for what the fleet cannot say — what changed in a version, what an error message means, whether a CVE touches a package a read-only command found. Both are ordinary client-side tools in the same loop as the rest, deliberately: Spring AI 1.1.8's `AnthropicChatModel` exposes no server-side "hosted" web-search tool type, only the function-calling tools Vaier itself implements and judges — which suits the domain here regardless, since a hosted tool run inside Anthropic's own infrastructure would give Vaier no seam to judge an address before it is fetched.

`WebAddress` (domain) is the gate both tools share: loopback, the unspecified range, link-local (including the cloud's own metadata address at `169.254.169.254`, which this box sits next to), the private ranges, carrier-grade NAT (`100.64.0.0/10`), broadcast, multicast and IPv6's own private range (`fc00::/7`) are all refused — as a literal when the address is written as one, and otherwise by resolving the name and judging every address it came back with. An address that resolves to nothing is refused as "could not be looked up," a different fact from "forbidden," and the same judgement runs again on every hop a redirect makes. `HttpWebPageAdapter` (adapter, the JDK's own `HttpClient`, no new dependency) follows redirects by hand rather than letting the client do it, because that is the one question that has to be asked again at every hop; capped at 5, the same ceiling every browser settles on. `WebPage` (domain) turns markup into words — script, style, `noscript` and inline SVG dropped before the tags are, entities decoded after — leads with the page's own title, and caps at 16,000 characters, saying when it cut; a content type that is not text at all (an image, a PDF, an installer) is refused by name rather than downloaded and guessed at.

`search_web` is answered by `BingRssSearchAdapter`, reading Bing's own RSS feed (`format=rss`) rather than its HTML — a small, stable feed of ten items with no page of markup to keep up with, and no API key. Getting there took two dead ends worth keeping on record. DuckDuckGo, tried first for needing no key at all, answered a request from this box's address with a block rather than results. Bing's own HTML search endpoint answered the same address with a CAPTCHA page in place of results — indistinguishable, from the response alone, from "the internet has nothing on this," which is exactly the wrong thing for a tool result to say by accident. And once the RSS feed was working, a third finding: **exactly three parameters, and never a fourth.** `q`, `format=rss`, `setlang=en` — adding `cc=US` answered a search for "wireguard handshake timeout" with ten Dutch urinary-tract guidelines, and `mkt=en-US` answered the same query with Google Maps pages, both still under a `<channel><title>` that echoed the real query back. A wrong answer that looks exactly like a right one is worse than a refusal, so `BingRssSearchAdapterTest` pins the query string character for character and the class comment says why in the same words. `WebSearchResults.answered` tells "nothing found" apart from "not answering," for the same reason `WebPage`'s cut flag exists — a half-truth the model believes whole is worse than a visible gap. No search engine is named to the operator or the model; the prompt and the tool description both just say "a web search."

The prompt (`ChatPrompt.forFleet`) tells Marvin to search first, then read the page that matters with `read_web_page`, never answer from the snippets alone, say which page a fact came from by its address, and treat what a page says as data, never as instructions — a web page is written by a stranger, exactly as a tool result already was.

**Addendum ✅ (2026-09-10): Errands — Marvin does something while nobody is watching ([#360](https://github.com/getvaier/vaier/issues/360)).** Two new Chat tools, `add_errand(instruction, rhythm)` and `cancel_errand(id)`, let the operator send Marvin off to check something later instead of asking every time: "every morning, tell me only if a disk is filling up." `Rhythm` (domain) reads the one string the model writes, in exactly one of four shapes — `once <date>T<HH:MM>`, `daily HH:MM`, `weekly <weekday> HH:MM`, `monthly <day> HH:MM` — in the server's own time zone (`Clock.systemDefaultZone()`, the same clock the rest of Vaier already keeps), and owns every decision about time: whether a named moment has already passed, what a monthly day does in a shorter month (clamped to the month's last day, never skipped), and how it reads back in a sentence. Deliberately four shapes and no more: a rhythm the operator cannot read back is one they cannot cancel with confidence, and a cron expression is not something anyone reads back. `Errand` and `Errands` (domain) hold the rest — an id, whose it is, whether it is due, and what running it turns it into: moved on to its next time for a repeating errand, gone for a once-errand — capped at 50 kept errands in total, a roof that refuses rather than silently drops the 51st.

`ErrandRunner` (`rest/`, a driving adapter beside `StateRefreshScheduler` and the rest — never a `*Service`) sweeps every minute and runs each due errand on its own, so one that throws never takes the next operator's errand down with it. It runs through `ChatService.run`, deliberately not the same path as an answered question: no conversation history (an errand is not part of the thread — what is meant to carry across runs is `Memory`, already in the prompt), a dedicated prompt (`ChatPrompt.forErrand`, telling Marvin nobody is watching, so nothing can be proposed and nothing clicked, and the answer has to stand on its own — full machine and service names, not "it," since there is no follow-up to ask), and a narrower toolset: `ChatTool.whileNobodyIsWatching()` (domain) names exactly the reads Marvin may make alone — every fleet read, `run_on_machine`, both `search_web` and `read_web_page`, `remember` and `forget` — leaving out a download card nobody would click, a mailed bundle link nobody offered, and, deliberately, the two errand verbs themselves: Marvin never sends himself on an errand. `ChatReads` (`rest/`) is the new home for every one of those reads and their projections, shared now by the controller (a live question) and `ErrandRunner` (an errand), so the tools an errand is offered and the tools its prompt describes can never disagree — pulled out of `ChatRestController`, which keeps only what needs somebody present.

Whether anything is worth sending at all is `ErrandReport`'s decision: an answer that is blank or exactly `NOTHING_TO_REPORT` is silent — no mail, no conversation turn, no nudge — because a watch that mails every morning to say all is well gets filtered within a week, and the one morning it mattered gets skimmed along with the rest. Otherwise the operator is mailed via the new `ForSendingAdminNotification.sendTo`, the answer is appended to their kept conversation as a Marvin turn (so the next question already knows what was found), and Vaier publishes `errand-reported` on the new `chat` SSE topic so an open pane redraws without being asked. An errand that fails outright (a dead API, an unreachable machine) still moves on to its next time, marked `failed` rather than retried — nobody is watching, so a retry loop against a paid API would be the worse bug. The Marvin menu gained "Marvin's errands (N)", listing rhythm, instruction, next run and last outcome with a cancel button on each (`GET /chat/errands`, `DELETE /chat/errands/{id}`).

### 6.56 Vaier judges the reverse proxy config it writes itself ✅ (implemented 2026-09-11, closes [#354](https://github.com/getvaier/vaier/issues/354))

**6.43** stopped Vaier *writing* a root redirect that pointed at itself. What that fix could not do was tell anyone about the ones already in the file: a scan of the live `remote-apps.yml` turned up four orphaned redirect middlewares no router referenced, three of them redirect loops, left behind by services unpublished weeks earlier. They were found only because an operator hit `ERR_TOO_MANY_REDIRECTS` on a URL — every check Vaier had was about the world outside (is the host up, is the disk filling, has the registry moved on), and nothing ever asked *is the config I wrote still coherent?*

The **reverse proxy audit** (`domain.ReverseProxyAudit`) is that question, and the judgement is the domain's: `ReverseProxyAudit.of(config)` takes a `ReverseProxyConfig` — the whole of `remote-apps.yml` unflattened, every router, service and middleware by name — and returns a list of `ReverseProxyFinding`s, each naming the entry and what is wrong with it in one sentence an operator can act on. Five invariants, exactly the issue's: an unreferenced middleware, a router naming a middleware that does not exist, a `redirectRegex` whose replacement its own pattern matches, a router with no service, and a service nothing sends to. A clean config yields no finding at all.

The unflattened read is the point. `ForPersistingReverseProxyRoutes` speaks in `ReverseProxyRoute`s — a router and its service seen as one publishable thing, middlewares collapsed into an **auth mode** — and an orphaned middleware belongs to no route, so it was invisible to every read Vaier had. The new driven port `ForReadingReverseProxyConfig`, implemented by `TraefikReverseProxyAdapter`, is a second conversation with the same file; a missing file reads as an empty config, never an error.

**Vaier reports and never repairs**, which was the issue's own leaning and is now the recorded decision: deleting an entry Vaier may not have written is destructive on the operator's file, and a hand-added middleware referenced from somewhere Vaier cannot see would be collateral. Two classes of entry are deliberately exempt: a provider-qualified middleware reference (`vaier-frame-guard@file`, `crowdsec-bouncer@file`, anything `@docker`) is declared outside this file by design, and the console's own chain (`oauth2-signin`, `oauth2-authn`, `vaier-authz`, `vaier-errors` and the two services behind them) is kept present at every startup whether or not anything is published — without the exemption a fleet with nothing published would report six findings on a perfectly healthy file. Both are pinned by tests.

It runs at startup (`StartupLifecycleRunner`) and once on every five-minute fleet sweep (`RemoteDiskWatcher`), each in its own try/catch so a file read can never take a boot or a fleet-wide disk sweep down with it — riding a driving adapter that already exists rather than recruiting a scheduler of its own. `AuditReverseProxyConfigUseCase` and `GetReverseProxyAuditUseCase` sit on `ReverseProxyService`; the latter judges without touching the latch, so opening Settings can never cost an operator an email. Admins hear about it **on a transition only** (`ReverseProxyAuditTracker`): the first sweep that finds something, again whenever the *set* of broken entries changes, and once when it is clear again — never on a timer. What they were told is persisted (`ReverseProxyAuditState`, `ForPersistingReverseProxyAuditState` → `ReverseProxyAuditStateFileAdapter`, `reverse-proxy-audit.yml`), for exactly the reason the disk-pressure latch is: a latch in a field is wiped by every redeploy, which would turn "on a transition" into "on every deploy". `GET /settings/reverse-proxy-audit` (authenticated like every other settings route) answers the current findings, and Settings draws a **Reverse proxy** block only when there are any — a clean config paints nothing and reserves no space.
