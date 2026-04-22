# Product Requirements Document — Vaier

**Last updated:** 2026-04-10
**Status:** Living document
**Issues:** https://github.com/getvaier/vaier/issues — GitHub issues are part of the spec and represent confirmed requirements and bugs.

---

## 1. Overview

Vaier is a self-hosted infrastructure management tool for developers running a homelab. It eliminates the manual work of maintaining a WireGuard VPN server, reverse proxy, and DNS records by providing a single interface that wires everything together automatically.

The core value proposition: add a new Docker service anywhere on your VPN, select a subdomain, and Vaier handles DNS, reverse proxy, and HTTPS — end to end.

Vaier is a personal tool that will be open-sourced. It is not intended to compete with general-purpose infrastructure platforms (Portainer, Coolify, Rancher, etc.). It is opinionated about its stack: WireGuard + Traefik + Authelia + pluggable DNS (Route53 and Cloudflare).

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
- Not a full multi-cloud DNS manager (Route53 and Cloudflare supported; no GCP/Azure/etc.)
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
- Ubuntu server peers can optionally specify a **LAN CIDR** (e.g. `192.168.1.0/24`). When set, the CIDR is appended to `AllowedIPs` in both the client config and the server-side peer entry, so the VPN server routes LAN traffic through that peer's tunnel. The LAN CIDR is also stored in the VAIER metadata comment.
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
   - **Discoverable** — containers with exposed TCP ports not yet published, found on local host and reachable VPN peers
   - **Active** — published services with their DNS/reachability state
2. Clicking **+ Add** on a discoverable service opens a modal: subdomain input + auth toggle
3. On submit, the modal closes immediately and the service moves into a **Processing** list that sits between the discoverable and active lists
4. The processing card shows live progress steps: DNS record created → DNS propagated → Traefik route active
5. When the Traefik route is confirmed active, the processing card disappears and the service appears in the active list
6. The discoverable list hides the service as soon as it enters processing (server-side, not client-side)
7. Both active and processing lists are driven by SSE — no polling from the browser
8. Processing state survives page refresh (backed by in-memory server state, not persisted to disk)
9. Duplicate submissions are rejected: attempting to add a service already in active or processing shows an error

**Also implemented:**
- **Root redirect path UI** — collapsible "Advanced" section in the publish modal with an optional root path redirect input, wired to the `rootRedirectPath` API field. Redirect path is also editable on published services via a modal.
- **Service cleanup on peer deletion** — when a VPN peer is deleted, all published services routing to that peer's IP are automatically removed (DNS + Traefik routes)
- **Published services page cleanup** — consolidated host/status rows, hide discoverable section when empty, replaced fragile optimistic auth toggle with server-side refresh
- **Publish rollback on failure** — if DNS propagation times out, Traefik route creation throws, or Traefik never picks up the new route, Vaier removes the CNAME (and, where applicable, the Traefik route) so no orphan records remain in Route53. Emits `publish-rolled-back` on the `published-services` SSE topic.

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

Direct CRUD for DNS zones and records, spanning one or more providers simultaneously.

**Current capabilities:**
- List zones and records (unioned across all configured providers)
- Create/delete zones
- Create/delete records (all standard types)
- **Multi-provider:** Route53 and Cloudflare can be active at the same time. A composite adapter dispatches operations to whichever provider owns the target zone, so migrations from one provider to another can happen gradually.

Providers activate by presence of credentials:
- Route53 when `VAIER_AWS_KEY` / `VAIER_AWS_SECRET` are set
- Cloudflare when `VAIER_CLOUDFLARE_TOKEN` is set (scoped API token with Zone:Read and Zone.DNS:Edit)

Cloudflare zones must exist in the Cloudflare dashboard before Vaier can manage their records (API-based zone creation requires an account_id and is not supported today).

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

**Known gotcha:** Gmail requires an **App Password** (not the account password) when 2FA is on. The pre-save verification catches this cleanly — save is rejected with the Gmail `534 5.7.9 Application-specific password required` message.

---

### 6.10 First-Run Setup Wizard ✅ (implemented, tracked in [#48](https://github.com/getvaier/vaier/issues/48))

Currently Vaier requires four environment variables before it can start (`VAIER_AWS_KEY`, `VAIER_AWS_SECRET`, `VAIER_DOMAIN`, `ACME_EMAIL`). This is a barrier for new users who need to edit a `.env` file they may not know how to create, and it makes adding new required config (like SMTP credentials for 6.9) increasingly painful.

**Goal:** replace the mandatory `.env` file with a web-based first-run wizard. `docker compose up -d` with no `.env` file should produce a running — but unconfigured — Vaier instance that redirects the user to a setup page.

**Wizard flow:**

1. **Detect unconfigured state** — on startup, if required config is missing, Vaier serves a setup page instead of the normal UI. The setup page is accessible without Authelia (Authelia itself cannot be configured yet).
2. **Step 1 — Domain** — enter base domain (e.g. `yourdomain.com`). Vaier derives `vaier.yourdomain.com` for its own URL and `auth.yourdomain.com` for Authelia.
3. **Step 2 — AWS credentials** — AWS access key and secret for Route53. Vaier can test the credentials live (list zones) before proceeding.
4. **Step 3 — Let's Encrypt** — email address for ACME certificate notifications.
5. **Step 4 — Email (SMTP)** — SMTP settings for Authelia email delivery (see 6.9). Optional: can be skipped and configured later from Settings.
6. **Step 5 — Admin account** — create the first Authelia user (username + password). Vaier already auto-creates an `admin` user on first run; this step lets the user set their own credentials instead.
7. **Done** — Vaier writes config to a persisted file, restarts Authelia with the new config, and redirects to the normal UI.

**Config persistence:** settings are written to a YAML file (e.g. `vaier-config.yml`) mounted into the container. This file takes precedence over env vars, so env vars still work for automated/CI deployments. If both are present, the config file wins.

**`.env` file is not removed** — it remains valid and documented. The wizard is an alternative path, not a replacement. Users who prefer env vars (e.g. for scripted deployments) should continue to use them.

---

## 7. End-to-End Workflows

### 7.1 New service on a peer (primary workflow)

1. Peer is already connected to VPN (created via Vaier)
2. Developer starts a Docker container on the peer
3. In Vaier → Services, the container appears in the **Discoverable** list automatically
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

### 7.5 First-time setup (no .env file)

1. User runs `docker compose up -d` with no `.env` file
2. Vaier starts and detects missing required config
3. Browser opens Vaier URL — redirected to the setup wizard
4. User steps through: domain → AWS credentials (tested live) → ACME email → SMTP → admin account
5. Vaier writes config, initialises Authelia, redirects to normal UI
6. Full stack is operational — no file editing required

## 8. Technical Constraints

- **Stack is fixed:** WireGuard (linuxserver), Traefik, Authelia, Redis, AWS Route53
- **No database:** all state is file-based (WireGuard/Traefik/Authelia configs) or cloud-based (Route53)
- **Single WireGuard server:** multi-server mesh is out of scope
- **Java 21 / Spring Boot 3.5.5:** backend language and framework are fixed
- **Docker socket required:** container discovery requires access to `/var/run/docker.sock` or TCP Docker API on peers

---

## 9. Out-of-Scope Integrations

The following are explicitly out of scope to avoid feature creep and overlap with dedicated tools:

- Other DNS providers beyond Route53 and Cloudflare (GCP, Azure, etc.)
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
5. The full stack can be installed from scratch with `docker compose up -d` — either via a `.env` file or the first-run setup wizard, with no manual config file editing required

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

