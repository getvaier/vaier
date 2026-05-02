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
- Not a multi-cloud DNS manager (Route53 only for now)
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
- Ubuntu server peers can optionally specify a **LAN CIDR** (e.g. `192.168.1.0/24`). When set, the CIDR is appended to the **server-side** `wg0.conf` `[Peer]` entry's `AllowedIPs` so the VPN server routes LAN-bound traffic into the relay peer's tunnel. The CIDR is **not** added to the relay's own client-side `AllowedIPs` — doing so would make `wg-quick` install a route for the LAN via `wg0` on the relay host itself, hijacking the relay's local LAN connectivity. The LAN CIDR is recorded in the VAIER metadata comment so it survives across re-installs. The generated peer install script additionally enables `net.ipv4.ip_forward` and installs idempotent `iptables` MASQUERADE + FORWARD rules (vpn → lan, lan → vpn for established/related), so the relay actually forwards decrypted VPN packets out its own LAN NIC. On the server side, hot-edits to `AllowedIPs` (via `wg set`) also reconcile the wireguard container's kernel routes (`ip route replace`/`ip route del`) to match — `wg set` itself is silent on routing and would otherwise leave VPN→LAN traffic falling through to the docker bridge until the next container restart (closes [#192](https://github.com/getvaier/vaier/issues/192)).
- Ubuntu/Windows server peers can additionally specify a **LAN address** (e.g. `192.168.1.50`) — the server's reachable host/IP on that LAN. Used by the launchpad to return direct, proxy-bypassing URLs when the caller is on the same LAN. Editable inline on the expanded server card via `PATCH /vpn/peers/{name}/lan-address`, so existing peers can be annotated without recreation.

---

### 6.2 Service Publishing ✅ (exists, core workflow)

The primary workflow: expose a Docker container as a public HTTPS subdomain.

**Current capabilities:**
- Discover containers with exposed ports on local host and VPN peers
- Publish a service: creates DNS CNAME record (pointing to the VPN server) + Traefik route + optional Authelia middleware
- Toggle authentication on/off per service
- Check publish status (DNS propagated, Traefik active)
- Delete published service (removes DNS + Traefik route)
- Edit root path redirect on published services
- Auto-delete published services when a VPN peer is deleted

**Publish flow (confirmed UX):**

1. User sees two lists on the published services page:
   - **Discovered** — containers with exposed TCP ports not yet published, found on local host and reachable VPN peers
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
- **LAN service publishing** ✅ (closes [#175](https://github.com/getvaier/vaier/issues/175)) — expose a LAN host (NAS, IPMI, printer, IoT) reachable through a relay peer's `lanCidr`, no Docker container required. The publish flow validates that the target IP falls inside some relay peer's `lanCidr`, writes a DNS CNAME and a Traefik route whose backend is `http(s)://<lan-ip>:<port>`. Cryptokey routing on `wg0` and the relay's #170 forwarding deliver packets to the LAN host. Surfaces with a small "LAN" badge in the published-services list and uses the target host as the launchpad direct-URL shortcut for on-LAN callers.
- **LAN server registration (Docker optional)** ✅ (closes [#177](https://github.com/getvaier/vaier/issues/177), [#184](https://github.com/getvaier/vaier/issues/184), [#181](https://github.com/getvaier/vaier/issues/181)) — register any machine on a relay peer's LAN as a `LAN_SERVER` machine, with optional Docker. With Docker on, Vaier scrapes its remote Docker socket through the relay (same `tcp://<host>:<port>` pattern as VPN peers) and surfaces discovered containers in the publishable list with a `LAN_SERVER` source. With Docker off, the LAN server still appears on the Machines page and is publishable through the manual LAN-service flow. Registration validates that `lanAddress` falls inside some relay peer's `lanCidr`. Persisted as YAML at `${VAIER_CONFIG_PATH}/lan-servers.yml` (legacy `lan-docker-hosts.yml` is auto-migrated on startup). V1 scope: insecure tcp 2375 only; no TLS/SSH yet. Backed by the unified `MachineType` taxonomy: `MOBILE_CLIENT`, `WINDOWS_CLIENT`, `UBUNTU_SERVER`, `WINDOWS_SERVER`, `LAN_SERVER`. A unified `GET /machines` endpoint returns all five in one list. The Add Machine modal exposes a one-liner `curl … | sudo bash -s -- --port <port>` that downloads `GET /lan-servers/docker-setup.sh` — an idempotent script that opens the Docker engine API on the host, covering both native (systemd drop-in to clear `-H fd://` + `/etc/docker/daemon.json`) and snap (`/var/snap/docker/current/config/daemon.json`) installs.
- **LAN server reachability check** ✅ (closes [#186](https://github.com/getvaier/vaier/issues/186)) — every registered LAN server is probed every 30s with a TCP connect to a small set of common ports (80, 443, 22). Any TCP response (handshake or RST) marks the host pingable; a clean timeout on every port marks it down. The Machines page combines that signal with the Docker socket scrape to render a four-state dot: grey (not yet probed), green (host pingable; if Docker-enabled, scrape also OK), yellow (Docker host pingable but scrape failed), red (host not pingable). Cache changes publish a `lan-servers-updated` SSE event on the existing `vpn-peers` topic so the page updates without a manual refresh.
- **Last seen for LAN servers** ✅ (closes [#194](https://github.com/getvaier/vaier/issues/194)) — every successful reachability probe stamps an in-memory `lastSeen` epoch second on the LAN server, reusing the same recency-bars affordance that VPN peers get from their WireGuard handshake. The bars (and the "Last Seen" detail row inside the card) render greyed out until the first successful probe, then darken in tiers as recency improves. A later DOWN probe never erases `lastSeen` — the whole point is to remember when the host last responded. `lastSeen` is exposed as a Long epoch second on `GET /lan-servers`.
- **Unified machine UI** ✅ (closes [#185](https://github.com/getvaier/vaier/issues/185), [#182](https://github.com/getvaier/vaier/issues/182)) — single **Add Machine** modal on the Machines page covers all five machine types with conditional fields driven by the type dropdown (LAN address required for `LAN_SERVER`, Docker checkbox + port shown only for LAN servers). The Servers section now combines local + VPN servers + LAN servers sorted by name; the dedicated LAN-Docker-hosts section is removed. The manual **Publish LAN service** dialog picks a machine from a dropdown of registered LAN servers — including Docker-enabled ones, since a Docker host can still expose native (non-container) services that auto-discovery doesn't cover. The map tab places `LAN_SERVER` markers anchored at the relay's geo location with a "Behind &lt;relay&gt;" label.

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
- **Per-service direct LAN URL opt-out** — the reverse-proxy route carries a `directUrlDisabled` flag (persisted in the Traefik YAML as `x-vaier-direct-url-disabled`). When set, the launchpad always serves the public HTTPS hostname for that service, skipping the direct LAN URL shortcut. This is required for services whose public origin differs from `http://lan:port` — Vaultwarden is the canonical case: its `DOMAIN` env is `https://vaultwarden.<domain>`, so opening the LAN URL yields a near-blank page because the Vue app won't initialise against a mismatched origin. Available as a checkbox both in the Publish service modal (so it can be set on creation) and on the expanded service details row. Also togglable via `PATCH /published-services/{dnsName}/direct-url-disabled`; accepted on `POST /published-services/publish` as a `directUrlDisabled` body field.

---

### 6.4 DNS Management ✅ (exists)

Direct CRUD for Route53 DNS zones and records.

**Current capabilities:**
- List zones and records
- Create/delete zones
- Create/delete records (all standard types)

No planned changes — this is a power-user escape hatch for records Vaier doesn't manage automatically.

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
- For each container running on any VPN peer (and local host), check whether the current image digest has a newer version available on Docker Hub
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
- **Server peer connect/disconnect alerts** ([#173](https://github.com/getvaier/vaier/issues/173)): a 30s scheduler tracks WireGuard handshake age for `UBUNTU_SERVER`/`WINDOWS_SERVER` peers (mobile/Windows clients excluded — their disconnects are routine). On a state change it emails every user in the `admins` group with subject `[Vaier] <peer> is now <connected|disconnected>` and a body containing peer name, type, last handshake, LAN address, and a link back to `vaier.<domain>/vpn-peers.html`. Per-peer state is in-memory; the first observation after Vaier startup is treated as a baseline so a restart never produces a notification storm. No quiet-hours setting — alerts fire 24/7.
- **Last-seen timestamp on peer cards** ([#173](https://github.com/getvaier/vaier/issues/173)): every peer header shows a relative "5m ago / 2h ago / never" badge derived from the latest handshake, updated live by the existing `peers-stats` SSE stream so operators can see at a glance how recently a machine was alive without expanding the card.

**Known gotcha:** Gmail requires an **App Password** (not the account password) when 2FA is on. The pre-save verification catches this cleanly — save is rejected with the Gmail `534 5.7.9 Application-specific password required` message.

---

### 6.10 First-Run Setup Wizard ⚠️ (deprecated as a Getting Started path, tracked in [#48](https://github.com/getvaier/vaier/issues/48), [#161](https://github.com/getvaier/vaier/issues/161))

The in-app wizard at `/setup.html` exists in the code but is no longer part of the Getting Started flow. A tester walking through the README on 2026-04-23 found that the wizard is unreachable when the four required env vars are already populated, and not documented anywhere for the case when they are not. README step 5 was rewritten to "first login" (cat the bootstrap password file, change the admin password, delete the file). The wizard plumbing (`/setup.html`, `SetupRedirectFilter`, `/api/setup/*`) remains in place for now but is scheduled for removal in a follow-up unless a clear use case emerges.

### 6.11 Zero-touch first-run DNS + Authelia boot ✅ (implemented 2026-04-23, closes [#163](https://github.com/getvaier/vaier/issues/163), [#164](https://github.com/getvaier/vaier/issues/164))

- **Auto-creates `vaier.<domain>` on first boot.** Vaier resolves the server's public address in order: `VAIER_PUBLIC_HOST` (CNAME target) → `VAIER_PUBLIC_IP` (A target) → EC2 IMDSv2 `public-hostname` (CNAME). If none resolve and the record is already missing, Vaier logs a clear instruction and exits the lifecycle step without crash-looping — the rest of the stack stays up so the operator can fix .env and restart.
- **`authelia-init` one-shot container** mirrors the `redis-init` pattern: writes a minimum-viable `configuration.yml` + `placeholder_users.yml` into `./authelia/config` before Authelia starts, so the Authelia container no longer crash-loops against its own default template on the first `docker compose up -d`. Vaier overwrites the placeholder config on its own first start and restarts Authelia.

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

