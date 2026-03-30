# Product Requirements Document — Vaier

**Last updated:** 2026-03-30
**Status:** Living document

---

## 1. Overview

Vaier is a self-hosted infrastructure management tool for developers running a homelab. It eliminates the manual work of maintaining a WireGuard VPN server, reverse proxy, and DNS records by providing a single interface that wires everything together automatically.

The core value proposition: add a new Docker service anywhere on your VPN, select a subdomain, and Vaier handles DNS, reverse proxy, and HTTPS — end to end.

Vaier is a personal tool that will be open-sourced. It is not intended to compete with general-purpose infrastructure platforms (Portainer, Coolify, Rancher, etc.). It is opinionated about its stack: WireGuard + Traefik + Authelia + AWS Route53.

---

## 1.1 Design Philosophy: Automation First, Zero Config

The defining characteristic of Vaier is that **things should just work**. The user should never need to set an environment variable or edit a config file to enable a feature that Vaier can detect or infer automatically.

Principles:
- **Detect, don't configure.** If a capability (Pi-hole, Netdata, Docker socket location) can be discovered at runtime, it must be. Env vars are a last resort, not a first instinct.
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
- Not a monitoring platform (Netdata integration is read-only)
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

**Config distribution options (all currently implemented):**
- **WireGuard config file** — download as `.conf` for native clients
- **QR code** — server-rendered PNG via ZXing; scannable by WireGuard mobile apps
- **docker-compose template** — ready-to-run compose file for peers running Docker
- **Bash setup script** — automated peer setup for Linux hosts

**Planned:**

#### Peer types

When creating a peer, the user selects a **peer type**. The type drives two things: the WireGuard config defaults, and which download options are presented after creation. Showing irrelevant downloads (e.g. a QR code to a server, or a bash script to a phone) adds confusion and is eliminated by the type selection.

| Type | Use case | Route all traffic default | Downloads shown |
|------|----------|--------------------------|-----------------|
| **Mobile client** | Phone or tablet accessing the internet via VPN | Yes | QR code, `.conf` file |
| **Windows client** | Laptop or desktop accessing the internet via VPN | Yes | `.conf` file |
| **Ubuntu server with Docker** | Self-hosted services exposed via reverse proxy | No | docker-compose, bash setup script |
| **Windows server with Docker** | Self-hosted services on a Windows Docker host | No | docker-compose |

**Config implications by type:**
- Client types (mobile, Windows client): `AllowedIPs = 0.0.0.0/0` by default (route all traffic). The user can uncheck this to get a split-tunnel config instead.
- Server types (Ubuntu/Windows with Docker): `AllowedIPs = <VPN subnet>` by default — only VPN traffic is routed through the tunnel. Route-all-traffic option is hidden; it makes no sense for a server peer.
- Server types expose their containers in the Vaier peer view; client types do not (the Docker API is not expected to be reachable on client devices).

The type is stored in the peer name or as a metadata label in the WireGuard config comment block so Vaier can re-derive it on the list view without a database.

#### Pi-hole DNS per peer

When creating a peer, optionally route the peer's DNS through the Pi-hole running on the VPN server. Vaier detects Pi-hole automatically by scanning local Docker containers for the well-known Pi-hole image (`pihole/pihole`). If detected, a "Use Pi-hole DNS" toggle appears in the create-peer form (for all peer types). When enabled, Vaier injects the Pi-hole container's VPN IP into the peer's `DNS =` field. No env var required — if Pi-hole is not running, the option is simply not shown.

---

### 6.2 Service Publishing ✅ (exists, core workflow)

The primary workflow: expose a Docker container as a public HTTPS subdomain.

**Current capabilities:**
- Discover containers with exposed ports on local host and VPN peers
- Publish a service: creates DNS A record + Traefik route + optional Authelia middleware
- Toggle authentication on/off per service
- Check publish status (DNS propagated, Traefik active)
- Delete hosted service (removes DNS + Traefik route)

**Planned:**
- **Root redirect path UI** — the `rootRedirectPath` field already exists in the publish API request body; it needs a corresponding optional input in the publish form (e.g. a collapsible "Advanced" section)
- **Publish status polish** — clearer loading state while DNS propagates; current status endpoint is available but not auto-polled after publish

---

### 6.3 Service Dashboard ✅ (partial — `hosted-services.html`)

A launchpad page listing all published services with their current status.

**Current state:** The hosted services page shows service cards with DNS/host state indicators, auth status, and management actions. It is primarily a management UI.

**Planned — read-only launchpad view:**
- Separate page (`/launchpad.html` or a toggle within the existing page) with a clean grid of service tiles
- Each tile: service name, full URL (clickable), favicon if fetchable, status dot (reachable / unreachable), auth badge
- No management controls in the launchpad view — those remain in the management UI
- Suitable for use as a browser home page or new-tab page
- Protected by Authelia under the same policy as the rest of Vaier — not a public page *(OQ1 resolved: protected)*

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

No planned changes.

---

### 6.7 System Metrics (Netdata) ✅ (exists)

Per-peer system health at a glance, without leaving Vaier.

**Current capabilities:**
- CPU usage %, RAM usage %, disk usage %
- Network I/O (inbound / outbound)
- Docker container count
- System uptime
- Displayed inline in the expanded peer card on `vpn-peers.html`
- Metrics fetched live from the Netdata HTTP API on the peer's VPN IP; gracefully absent if Netdata is not running on a peer

**Detection:** Vaier infers the Netdata endpoint from the peer's VPN IP (default port 19999). No configuration required. If Netdata is not reachable, the metrics section is simply not shown for that peer.

No planned changes to the data shown. Future consideration: persist last-seen values so metrics are visible even when a peer is temporarily offline.

---

### 6.8 Container Update Notifications 🔲 (planned)

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
- Frontend polls or refreshes on page load

**Out of scope for v1:** GHCR, self-hosted registries, push notifications (webhook/email).

---

## 7. End-to-End Workflows

### 7.1 New service on a peer (primary workflow)

1. Peer is already connected to VPN (created via Vaier)
2. Developer starts a Docker container on the peer
3. In Vaier → Services → Publishable, the container appears automatically
4. Developer selects container, types a subdomain, toggles auth if needed
5. Vaier creates: DNS A record → Traefik route → (optional) Authelia middleware
6. Service appears in launchpad dashboard with live status

**Success:** zero manual DNS/Traefik/Authelia steps.

### 7.2 Add a new VPN peer

1. Developer clicks "Add peer" → enters name, optionally enables Pi-hole DNS
2. Vaier generates WireGuard keys, assigns IP from subnet, writes config
3. Developer downloads QR code or docker-compose file
4. Peer is running; developer can see handshake status in Vaier

### 7.3 Check for stale containers

1. Developer opens VPN Peers view
2. Containers with available updates show an "update available" badge
3. Developer updates container manually on the peer host

### 7.4 Monitor peer health

1. Developer expands a peer card
2. CPU, RAM, disk, network, and container count are fetched live from Netdata
3. No navigation away from Vaier required

---

## 8. Technical Constraints

- **Stack is fixed:** WireGuard (linuxserver), Traefik, Authelia, Redis, AWS Route53
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
- Backup / restore of configs

---

## 10. Success Criteria

Vaier is "done enough" when:

1. A developer can add a new Docker container on any VPN peer and have it publicly accessible via HTTPS subdomain in under 2 minutes with no manual steps outside Vaier
2. All VPN peers can be managed (create, configure, delete) without editing any WireGuard config file
3. A launchpad page exists that works as a browser home page showing all services and their status
4. The operator is notified when container images have updates available
5. The full stack can be installed from scratch with `docker compose up -d` and a single `.env` file

---

## 11. Open Questions

All original open questions have been resolved:

| # | Question | Decision |
|---|----------|----------|
| OQ1 | Should the launchpad be unauthenticated or protected? | Protected by Authelia, same policy as rest of Vaier UI |
| OQ2 | Non-Docker Hub registries in v1? | No — Docker Hub only. GHCR / self-hosted are stretch goals for v2. |
| OQ3 | Pi-hole detection: automatic or env var? | Automatic — scan local containers for `pihole/pihole` image. Env var not needed. |
| OQ4 | Update notifications: push or UI only? | UI only in v1. Webhook/email is a v2 consideration. |

---

## 12. Implementation Backlog

Ordered by user value. Items at the top should be worked first.

| # | Feature | Section | Notes |
|---|---------|---------|-------|
| B1 | Peer types | 6.1 | Type selector in create form; drives config defaults and download options shown |
| B1a | Simplify Windows Docker peer service discovery | 6.1 | Getting containers listed on Windows + Docker Desktop is complex: named pipe vs TCP socket, WSL2 networking isolation, and Windows Firewall blocking inbound connections on the WireGuard interface. Explore a lightweight Vaier agent sidecar container bundled in the generated docker-compose — mounts the Docker socket and exposes a simple HTTP API on the VPN IP from within the Docker network (so traffic never crosses the Windows Firewall). Would also eliminate TLS cert management and make Windows/Ubuntu server peers functionally identical. |
| B2 | Pi-hole DNS per peer | 6.1 | Detect `pihole/pihole` container; inject VPN IP into DNS field |
| B3 | Root redirect path UI | 6.2 | Add optional input to publish modal; wire to existing API field |
| B4 | Launchpad view | 6.3 | Clean read-only grid; no management controls; Authelia-protected |
| B5 | Container update notifications | 6.8 | Docker Hub digest comparison; badge in peer cards and nav |
| B6 | Publish status auto-poll | 6.2 | Auto-poll `/status` after publish until DNS propagates |
