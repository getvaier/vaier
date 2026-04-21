<div align="center">
  <img src="docs/logo.svg" width="80" alt="Vaier logo"/>
</div>

# Vaier

[![Build](https://github.com/getvaier/vaier/actions/workflows/build-deploy.yml/badge.svg)](https://github.com/getvaier/vaier/actions/workflows/build-deploy.yml)
[![Docker Pulls](https://img.shields.io/docker/pulls/getvaier/vaier)](https://hub.docker.com/r/getvaier/vaier)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21-orange)](https://openjdk.org/projects/jdk/21/)

**Self-hosted infrastructure management for homelab developers.**

Vaier wires together WireGuard, Traefik, Authelia, and AWS Route53 into a single web UI. Add a Docker container on any VPN peer, pick a subdomain, and Vaier handles DNS, reverse proxy, and HTTPS — automatically.

---

<!-- Add a demo GIF here once available. Suggested flow: create a peer → publish a service → watch the live processing steps → service goes live. -->
<!-- ![Vaier demo](docs/demo.gif) -->

## What it does

| Feature | Description |
|---------|-------------|
| **VPN peer management** | Create and delete WireGuard peers. Download peer config as a `.conf` file, QR code (mobile), docker-compose, or a one-shot bash setup script. Server peers can record a LAN address (editable inline) so the launchpad can link directly to services on the local network. |
| **Service publishing** | Discover Docker containers on the VPN server and on connected peers. Publish any container as a public HTTPS subdomain in one action. Automatic cleanup of published services when a peer is deleted. |
| **Smart launchpad** | Public `/launchpad.html` tiles link to `https://service.domain` normally, but switch to direct `http://lanAddress:port` when the caller is on the same LAN as the hosting server — bypassing the proxy and auth. Per-service opt-out for apps whose public origin differs from `http://lan:port` (e.g. Vaultwarden). |
| **Reverse proxy** | Automatically generates Traefik dynamic config. Per-service Authelia authentication toggle, editable root path redirect, and per-service direct LAN URL opt-out. |
| **DNS management** | Full CRUD for AWS Route53 zones and records. |
| **User management** | Manage Authelia users from the UI (create, delete, change password). |
| **Backup / restore** | Export full configuration (peers, services, DNS records, users) as a JSON snapshot. Import restores everything with a real-time progress log. |
| **First-run setup wizard** | Web-based wizard at `/setup.html` guides you through domain, AWS credentials, ACME email, and admin account creation — no `.env` file editing required. |

---

## Stack

Vaier runs as part of a five-container Docker Compose stack:

| Service | Role |
|---------|------|
| **WireGuard** (`linuxserver/wireguard`) | VPN server, UDP 51820 |
| **Traefik** | Reverse proxy + Let's Encrypt TLS |
| **Authelia** | Authentication middleware |
| **Redis** | Authelia session store |
| **Vaier** | This application (port 8888 externally) |

---

## Prerequisites

- A Linux server with a public IP (EC2 t3.small or similar)
- Docker and Docker Compose installed
- A domain name you control
- AWS credentials with Route53 access

### Server ports to open

| Port | Protocol | Purpose |
|------|----------|---------|
| 22 | TCP | SSH |
| 80 | TCP | HTTP (Let's Encrypt challenge) |
| 443 | TCP | HTTPS |
| 51820 | UDP | WireGuard VPN |

---

## Quick start

### 1. Create a folder and download the compose file

```bash
mkdir vaier && cd vaier
curl -fsSL https://raw.githubusercontent.com/getvaier/vaier/main/docker-compose.yml -o docker-compose.yml
```

### 2. Create a one-line `.env` file

```bash
echo "VAIER_DOMAIN=yourdomain.com" > .env
```

Then create an A record for `vaier.yourdomain.com` pointing to your server's public IP.

### 3. Start the stack

```bash
docker compose up -d
```

### 4. Open the setup page

Navigate to `https://vaier.yourdomain.com` in your browser. The setup wizard opens automatically and walks you through:

1. **AWS Credentials** — access key and secret with Route53 permissions (validated live)
2. **ACME Email** — for Let's Encrypt certificate notifications
3. **Admin Account** — first Authelia user (username + password)

After completing the wizard, Vaier initializes DNS records, configures Authelia, and starts all services.

> **Existing deployments**: If you already have a `.env` file with `VAIER_AWS_KEY`, `VAIER_AWS_SECRET`, `VAIER_DOMAIN`, and `ACME_EMAIL`, Vaier continues to work with those environment variables — the setup wizard is skipped automatically.

---

## Environment variables

| Variable | Required | Description |
|----------|----------|-------------|
| `VAIER_AWS_KEY` | Yes | AWS access key for Route53 |
| `VAIER_AWS_SECRET` | Yes | AWS secret key for Route53 |
| `VAIER_DOMAIN` | Yes | Base domain (e.g. `yourdomain.com`) |
| `ACME_EMAIL` | Yes | Email for Let's Encrypt notifications |
| `WIREGUARD_CONFIG_PATH` | No | WireGuard config dir (default: `/wireguard/config`) |
| `WIREGUARD_CONTAINER_NAME` | No | WireGuard container name (default: `wireguard`) |
| `TRAEFIK_CONFIG_PATH` | No | Traefik dynamic config dir (default: `/traefik/config`) |
| `TRAEFIK_API_URL` | No | Traefik API URL (default: `http://traefik:8080`) |
| `AUTHELIA_CONFIG_PATH` | No | Authelia config dir (default: `/authelia/config`) |

---

## Adding a VPN peer

Peers are created from the Vaier UI. When creating a peer, select its type — the type determines the WireGuard config defaults and which download options are shown:

| Peer type | Typical use | Default routing | Downloads |
|-----------|-------------|-----------------|-----------|
| Mobile client | Phone/tablet internet access via VPN | All traffic | QR code, `.conf` |
| Windows client | Laptop internet access via VPN | All traffic | `.conf` |
| Ubuntu server with Docker | Self-hosted services on a Linux host | VPN subnet only | docker-compose, setup script |
| Windows server with Docker | Self-hosted services on a Windows Docker host | VPN subnet only | docker-compose |

After creating a peer, download its config and connect. Vaier shows the peer's handshake status.

---

## Publishing a service

1. Start a Docker container on any connected peer
2. In Vaier → Services → Publishable, the container appears automatically
3. Select the container, enter a subdomain, optionally enable Authelia authentication
4. Vaier creates a DNS CNAME record pointing to the VPN server, a Traefik route, and (optionally) Authelia middleware

The service is live at `https://subdomain.yourdomain.com`.

---

## Roadmap

The backlog is tracked in [GitHub Issues](https://github.com/getvaier/vaier/issues). Feature specs for planned items are in [`PRD.md`](PRD.md). See [`CONTRIBUTING.md`](CONTRIBUTING.md) to get started.

---

## Development

### Build and run locally

```bash
mvn clean package -DskipTests   # build
mvn spring-boot:run              # run on :8080
```

Swagger UI: `http://localhost:8080/swagger-ui.html`

### Build the Docker image

```bash
mvn clean package -DskipTests
docker build -t getvaier/vaier:latest .
docker compose up -d --force-recreate vaier
```

> The compose file uses `getvaier/vaier:latest`. Building as any other tag will not be picked up.

### Architecture

Hexagonal (ports & adapters) with four layers:

- **Domain** — business logic, entities, port interfaces. No Spring dependencies.
- **Application** — use case interfaces and service implementations.
- **Infrastructure** (`adapter/driven/`) — adapters for WireGuard, Traefik, Route53, Docker, Authelia.
- **Web** (`rest/`) — REST controllers; DTOs are inner `record` classes.

---

## Contributing

Contributions are welcome. The [roadmap](#roadmap) above lists what's planned — pick any item, check the full spec in [`PRD.md`](PRD.md), and open an issue before starting to avoid duplicate work.

For bugs, browse [open issues](https://github.com/getvaier/vaier/issues) or open a new one. See [`CONTRIBUTING.md`](CONTRIBUTING.md) for the development guide (architecture, TDD rules, PR expectations).

---

## Disclaimer

Vaier is a personal homelab tool provided as-is. Use it at your own risk. The authors accept no responsibility for security incidents, data loss, service outages, misconfigured firewalls, exposed services, or any other damage arising from its use. Running this software means exposing infrastructure to the internet — you are responsible for understanding what you are deploying.

The Apache License 2.0 (below) contains the full warranty disclaimer and limitation of liability in sections 7 and 8.

## License

Apache License 2.0 — see [LICENSE](LICENSE).

---

*Built for the self-hosted community.*
