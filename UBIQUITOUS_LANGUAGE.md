# Ubiquitous Language

This is the shared vocabulary for Vaier. Use these terms exactly — in code, commit messages, issues, PRD entries, UI copy, and conversation. When two words could mean the same thing, only the term listed here is correct.

Terms are drawn from the domain model (`src/main/java/net/vaier/domain/`), the use-case ports, REST APIs, the PRD, the README, and active GitHub issues. When the codebase and the docs disagree, the codebase wins and this document gets updated.

---

## 1. Product

| Term | Definition |
|------|------------|
| **Vaier** | The product. Also the name of the Spring Boot application container in the Docker Compose stack. |
| **Vaier server** | The single Linux host that runs the full Compose stack (WireGuard, Traefik, Authelia, Redis, Vaier). Has one public IP and is the WireGuard gateway. There is exactly one. |
| **Operator** | The human running Vaier — typically a homelab developer. Not "user" (which means an Authelia account). |
| **Stack** | The five containers in `docker-compose.yml`: `wireguard`, `traefik`, `authelia`, `redis`, `vaier`. Stack components are upstream and pinned by tag — never floating `:latest`. |
| **Bootstrap admin** | The one-time `admin` user Vaier creates on first boot, with a password written to `authelia/config/.bootstrap-admin-password`. The operator reads it, logs in, changes the password, and deletes the file. |

---

## 2. Machines and peers

The unified term is **machine**. A machine is anything Vaier knows about that can host services or connect to the VPN.

| Term | Definition |
|------|------------|
| **Machine** | Read projection (`domain.Machine`) covering every host Vaier manages. Has a `MachineType`. Returned by `GET /machines`. |
| **MachineType** | Enum with five values: `MOBILE_CLIENT`, `WINDOWS_CLIENT`, `UBUNTU_SERVER`, `WINDOWS_SERVER`, `LAN_SERVER`. The first four are **VPN peers**; the fifth is not. |
| **VPN peer** / **peer** | A machine connected to Vaier over WireGuard. Equivalent to "MachineType where `isVpnPeer()` is true". Use "peer" in UI and casual reference; "VPN peer" when disambiguating from a LAN server. |
| **Client peer** | Mobile or Windows client; default `AllowedIPs = 0.0.0.0/0` (full tunnel). Cannot host containers in the Vaier model. |
| **Server peer** | `UBUNTU_SERVER` or `WINDOWS_SERVER`; default `AllowedIPs` is the VPN subnet only (split tunnel). Can host Docker containers. `MachineType.isServerType()` also returns true for `LAN_SERVER`. |
| **LAN server** | A non-WireGuard machine on a relay peer's LAN that Vaier knows about — NAS, IPMI host, printer, extra Docker host. Persisted in `lan-servers.yml`. Docker is optional; when on, Vaier scrapes its remote socket *through the relay*. |
| **Relay peer** | A server peer with a `lanCidr` set. Vaier routes traffic for that CIDR into the relay's tunnel, and the relay's install script enables `ip_forward` and idempotent iptables NAT/FORWARD rules so packets reach LAN hosts behind it. |
| **Gateway peer** | (Planned, #174) A single peer designated to carry all internet traffic for opted-in clients. Distinct from a relay peer. |
| **LAN address** | The reachable IP (or hostname) of a server peer or LAN server on its local LAN — e.g. `192.168.1.50`. Used by the launchpad to hand out direct URLs. Editable inline via `PATCH /vpn/peers/{name}/lan-address`. Not the WireGuard tunnel IP. |
| **LAN CIDR** | The IPv4 CIDR of the LAN sitting behind a relay peer — e.g. `192.168.1.0/24`. Recorded in the `# VAIER:` JSON header of the peer's client config and appended to the **server-side** `[Peer] AllowedIPs` only. Never appended to the client-side `AllowedIPs` (would hijack the relay's own LAN). |
| **VPN subnet** | The WireGuard /24 used for tunnel addresses (default `10.13.13.0/24`). |
| **Tunnel IP** / **WG address** | A peer's address inside the VPN subnet (e.g. `10.13.13.7`). Distinct from LAN address and endpoint IP. |
| **Endpoint IP** | The peer's public IP as seen by the WireGuard server (`wg show dump`). The carrier IP for mobile/client peers. Used for geolocation. |
| **Latest handshake** | Unix epoch seconds of the most recent successful WireGuard handshake. Source of truth for connection state. |
| **Connected** | A peer is connected when `latestHandshake > 0` and `(now - latestHandshake) < 180s`. See `VpnClient.isConnected()`. Anything older is **disconnected** (which on the UI may render as "stale"). |
| **AllowedIPs** | WireGuard config field. Has both a **server-side** form (in `wg0.conf`'s `[Peer]` block on the Vaier server) and a **client-side** form (in the peer's own config). They are not interchangeable — see the LAN CIDR rules above. |
| **VAIER metadata** | The `# VAIER: {...}` JSON comment at the top of every client WireGuard config. Carries `peerType`, `lanCidr`, `lanAddress`. Legacy peers without it default to `UBUNTU_SERVER`. |

Avoid: "node", "host" (ambiguous), "device", "client" by itself, "VPN node".

---

## 3. Service publishing

The primary workflow. Always use these terms — the UI is built around them.

| Term | Definition |
|------|------------|
| **Service** | (Without qualifier) a Docker container with at least one exposed port, running on the local host or a server peer or a Docker-enabled LAN server. Discovered automatically. Backed by `domain.DockerService`. |
| **Publishable service** | A discovered service eligible for publishing. Has a `source` indicating where it was discovered (local, peer, LAN server). Listed under **Services → Publishable** / "Discovered". |
| **Published service** | A service that has been wired through the publish flow: a DNS CNAME (or A) plus a Traefik route plus optional Authelia middleware. Backed by a `ReverseProxyRoute`. Listed under **Services → Active**. |
| **LAN service** | A published service whose backend is a host:port on a relay peer's LAN, not a container. `ReverseProxyRoute.isLanService == true`. Routed via the relay peer. Surfaces with a "LAN" badge. |
| **Publish flow** | The end-to-end pipeline: validate → write CNAME → wait for DNS propagation → write Traefik route → confirm Traefik picked it up. UI shows it as a **Processing** card between Discovered and Active. Survives page refresh because state lives server-side. |
| **Pending publication** | A publish in flight, tracked in-memory by `PendingPublicationsService`. Used to hide the source service from the discovered list and to reject duplicate submits. |
| **Publish rollback** | If any step fails (DNS timeout, Traefik error, Traefik never picks up the route), Vaier removes the CNAME (and the route where applicable) and emits a `publish-rolled-back` SSE event on the `published-services` topic. |
| **Cleanup on peer deletion** | When a VPN peer is deleted, every published service whose backend address routes to that peer is also removed (DNS + Traefik). |
| **Subdomain** | The label the operator picks at publish time. Combined with `VAIER_DOMAIN` to form the published service's DNS name. |
| **Mandatory subdomains** | `vaier` and `login`. Cannot be reused as published-service subdomains. Defined in `PublishingConstants.MANDATORY_SUBDOMAINS`. |
| **Root redirect path** | Optional `rootRedirectPath` on a published service — appended to the direct URL and (where applicable) used for a Traefik redirect at root. |
| **Direct URL** | The `http://lanAddress:port` URL the launchpad hands out *to callers on the same LAN* as the hosting peer, bypassing Traefik and Authelia. |
| **Direct URL disabled** | Per-route opt-out (`directUrlDisabled`, persisted as `x-vaier-direct-url-disabled`). For services whose public origin differs from `http://lan:port` — Vaultwarden is the canonical case. |
| **Auth toggle** | The per-service Authelia forward-auth on/off switch. Stored as a Traefik middleware reference. |
| **Ignored service** | A publishable service the operator has chosen to hide from the discovered list. Persisted via `ForManagingIgnoredServices`. |

Avoid: "expose", "deploy", "route" (as a verb for the user-facing publish action), "site", "endpoint" (ambiguous with WireGuard's endpoint).

---

## 4. DNS, reverse proxy, auth

| Term | Definition |
|------|------------|
| **DNS zone** | A Route53 hosted zone (`domain.DnsZone`). Vaier owns at most one — the zone for `VAIER_DOMAIN`. |
| **DNS record** | `domain.DnsRecord` with a `DnsRecordType` (A, CNAME, MX, etc.). Stored in Route53. |
| **DNS state** | `OK` (record exists matching expectation) or `NON_EXISTING` (`domain.DnsState`). Used in published-service status. |
| **Public host** | The Vaier server's public address as a `(value, type)` pair — either A (IPv4) or CNAME (hostname). Resolved by `ForResolvingPublicHost` in order: `VAIER_PUBLIC_HOST` → `VAIER_PUBLIC_IP` → EC2 IMDSv2 `public-hostname` → DNS lookup of `vaier.<domain>`. |
| **Reverse proxy route** | `domain.ReverseProxyRoute`. The Traefik-side half of a published service. Some routes are created by service publishing; others are added directly through the reverse-proxy CRUD endpoints (the escape hatch). |
| **Entry point** | Traefik concept: the named port a route binds to (`web`, `websecure`). |
| **TLS config** | Per-route TLS settings; `certResolver` names the ACME resolver in Traefik static config. |
| **Middleware** | Traefik chain element. `authelia` is the canonical middleware Vaier toggles on/off per route. |
| **Forward-auth** | Authelia's auth pattern: Traefik forwards each request to Authelia, which returns 200 or a redirect to `login.<domain>`. Vaier's only auth integration in V1 — there is no in-process Spring Security. |
| **Group** | An Authelia group string, free-form (e.g. `admins`). A user has one or more groups. In V1 every logged-in user reaches every published service regardless of group; per-service group gating is planned. |
| **ACME** | Let's Encrypt protocol for issuing TLS certs. `ACME_EMAIL` env var feeds the Traefik resolver. |

Avoid: "vhost", "site", "auth provider".

---

## 5. Discovery, reachability, status

| Term | Definition |
|------|------------|
| **Discovery** | The process of listing Docker containers on a host. `DiscoverLocalContainersUseCase` (Vaier server), `DiscoverPeerContainersUseCase` (server peer via `tcp://<peer>:<port>`), `DiscoverLanServerContainersUseCase` (LAN server, scraped through the relay). |
| **Reachability check** | A TCP probe (`ForProbingTcp`) used for LAN servers. Every 30s, hits ports 80/443/22; *any* response (handshake or RST) means pingable. Drives the **four-state status dot**. |
| **Probe result** | `CONNECTED` (open), `REFUSED` (host alive, port closed — still pingable), `UNREACHABLE` (timeout or low-level error). |
| **Four-state status dot** | UI indicator on machine cards: **grey** (not yet probed), **green** (host pingable; if Docker-enabled, scrape also OK), **yellow** (Docker host pingable but scrape failed), **red** (host not pingable). |
| **Last seen** | Relative timestamp ("5m ago", "2h ago", "never") rendered on each machine card. Sourced from the latest WireGuard handshake for VPN peers; for LAN servers, the most recent successful TCP probe (CONNECTED or REFUSED). Preserved across later DOWN probes — once a host has been seen, the timestamp sticks until the LAN server is removed or the app restarts. |
| **Geolocation** | `GeoLocation(latitude, longitude, city, country)` resolved by `DbIpGeolocationAdapter` against a DB-IP City Lite MMDB downloaded by the `geoip-init` container. Used for the Map tab. |
| **Map tab** / **List tab** | The two views on the Machines page. The Map tab renders a self-hosted Leaflet/OpenStreetMap world map with markers and clustering. |
| **Server marker** | The single distinct marker for the Vaier server itself on the Map tab. |
| **Mobile/client dual marker** | Mobile/Windows-client peers plot twice: a dotted "approx. ISP" marker at the carrier IP plus a firm marker stacked at the Vaier server. Reflects `AllowedIPs = 0.0.0.0/0` full-tunnel routing. |

---

## 6. Launchpad

| Term | Definition |
|------|------------|
| **Launchpad** | The public read-only dashboard at `/launchpad.html` showing all published services as a tile grid. Suitable as a browser home page. Has its own API (`/launchpad/services`, `/favicon`); both are public. |
| **Tile** | A single service card on the launchpad — service name, peer name, favicon, link. |
| **Caller IP** | The public IP of the launchpad visitor, used to decide whether to hand out the direct URL. Taken from `X-Forwarded-For` only when the immediate connection is from inside `launchpad.trusted-proxy-cidr` (default `172.20.0.0/16`); otherwise from `RemoteAddr`. |
| **Trusted proxy CIDR** | The CIDR Vaier trusts to set `X-Forwarded-For`. Default is the Docker bridge subnet. |

---

## 7. Lifecycle, setup, notifications

| Term | Definition |
|------|------------|
| **Lifecycle** | `domain.Lifecycle`. The boot sequence: ensure the `vaier.<domain>` DNS record exists, ensure the `login.<domain>` CNAME exists, initialise Authelia config, write the bootstrap admin password if no users exist. |
| **First-run** | The first `docker compose up -d` on a host. Triggers the `authelia-init` and `redis-init` one-shot containers, the bootstrap admin write, and the auto-create of `vaier.<domain>` and `login.<domain>`. |
| **Setup wizard** | (Deprecated) The in-app `/setup.html` flow. No longer part of Getting Started; scheduled for removal. Don't reference it in new docs. |
| **SMTP notifier** | The Jakarta Mail-based outbound mail integration. Powers Authelia password-reset email and Vaier admin alerts. Settings in `vaier-config.yml`; password in Authelia's `secrets.properties`. |
| **Test email** | A full AUTH + roundtrip Jakarta Mail send triggered from Settings, used to verify SMTP config independently of the auth layer. |
| **Peer transition** / **connect/disconnect alert** | Email sent to every user in the `admins` group when a server peer flips connected/disconnected. The first observation after Vaier startup is treated as a baseline so restarts don't generate noise. |
| **Update available** | (Planned, #57) Indicator on a container when its image has a newer digest on Docker Hub. UI-only in V1; no auto-update. |
| **Wireguard out of date** | Badge on a peer card whose running wireguard image differs from `WireguardClientImage.EXPECTED`. Operator action: re-download the client compose and redeploy. |

---

## 8. Persistence and config

Vaier has **no database**. State lives in files, in Route53, or in memory.

| Term | Definition |
|------|------------|
| **Vaier config** | `vaier/config/vaier-config.yml`. Holds Route53 credentials (`awsKey`, `awsSecret`), `domain`, `acmeEmail`, SMTP settings. Loaded as `domain.VaierConfig`. |
| **WireGuard config** | The set of files under `WIREGUARD_CONFIG_PATH`. `wg0.conf` plus per-peer client configs. |
| **Traefik dynamic config** | YAML files under `TRAEFIK_CONFIG_PATH`, generated by `TraefikReverseProxyAdapter`. Picked up live by Traefik. |
| **Authelia config** | `authelia/config/configuration.yml`, `users_database.yml`, `secrets.properties`. Generated/regenerated by `AutheliaConfigAdapter` and `AutheliaUserAdapter`. |
| **lan-servers.yml** | Persists registered LAN servers. `lan-docker-hosts.yml` is the legacy filename and is auto-migrated on startup. |
| **Bootstrap password file** | `authelia/config/.bootstrap-admin-password`, mode `600`. Removed by the operator after first login. |
| **Secrets on disk** | New secret files are written at mode `600`; surrounding directories should be `go-rwx`. |

---

## 9. Architecture

Hexagonal architecture (ports & adapters), four layers. See `CLAUDE.md` for the rules.

| Term | Convention | Example |
|------|-----------|---------|
| **Domain** | Pure Java, no Spring. Entities, value objects, port interfaces. | `domain.Machine`, `domain.ReverseProxyRoute` |
| **Port (driving)** | `*UseCase` interface — one per use case, narrow. Controllers depend on these. | `PublishPeerServiceUseCase`, `DeletePeerUseCase` |
| **Port (driven)** | `For*` interface — one per outbound capability. | `ForPersistingDnsRecords`, `ForProbingTcp`, `ForResolvingPublicHost` |
| **Application service** | `*Service` — one per *domain concept*, not per use case. Implements every use case in its domain. | `VpnService`, `PublishingService`, `UserService`, `MachineService`, `LanServerService`, `LanServerReachabilityService`, `DnsService`, `ReverseProxyService`, `SettingsService`, `SetupService`, `ContainerService`, `LifecycleService`, `NotificationService` |
| **Adapter** | `*Adapter` — driven adapter, implements `For*` ports. Lives in `adapter/driven/`. | `Route53DnsAdapter`, `WireGuardVpnAdapter`, `TraefikReverseProxyAdapter`, `LanServerFileAdapter`, `JavaSocketTcpProbeAdapter` |
| **REST controller** | `*RestController`, in `rest/`. DTOs are inner `record` classes. | `MachineRestController`, `PublishedServiceRestController` |

**Cross-domain orchestration goes through use-case interfaces, never class-to-class.** E.g. `VpnService.deletePeer` cascading into published-service cleanup is wired via `DeletePublishedServiceUseCase`, not a direct dependency on `PublishingService`.

**Strict layer isolation.** Two unrelated services that happen to need the same string literal keep their own copies. Don't import an unrelated `*UseCase` interface just to share a constant.

---

## 10. Events (SSE)

The browser receives live updates via Server-Sent Events. Topics and event names are part of the API.

| Topic | Events | Triggered by |
|-------|--------|--------------|
| `published-services` | `services-updated`, `publish-rolled-back` | Publishing flow, rollback, peer deletion, route changes |
| `vpn-peers` | `peers-updated`, `peers-stats`, `lan-servers-updated` | `VpnService` mutations, `PeerStatsScheduler`, `LanServerReachabilityScheduler` |

Do not poll from the browser when an SSE topic exists.

---

## 11. Naming choices to avoid drift

These pairs come up often. Use the left, never the right.

| Use | Don't use |
|-----|-----------|
| machine | node, device, host (when ambiguous) |
| peer / VPN peer | client (alone), VPN node |
| relay peer | bridge peer, gateway (means #174) |
| LAN server | LAN docker host (legacy, code path migrated) |
| LAN service | host route, raw route |
| published service | site, deployed service, route (when meaning the publish flow) |
| publish | expose, deploy, route (verb) |
| publishable service | candidate, available service |
| direct URL | local URL, lan URL (when written as one word) |
| operator | admin (means a group), user (means an Authelia user) |
| bootstrap admin | initial admin, default user |
| latest handshake | last seen (UI label only — derived from this) |
| endpoint IP | public IP (peer's public IP, not the Vaier server's) |
| public host | server hostname, our IP |

---

## 12. Out-of-language

Terms that look like they belong here but don't — these are explicitly **not** Vaier vocabulary because the underlying concept is out of scope:

- Cloudflare, nginx, Caddy, Keycloak, Vault, Kubernetes, Portainer, Coolify, Pi-hole — listed only to record that they were considered and rejected.
- "Backup snapshot" / "export" — V1 has no backup feature; V2 will (see #153).
- "Multi-server", "WireGuard mesh" — single Vaier server, period.
