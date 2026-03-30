# Vaier

**Self-hosted infrastructure management for homelab developers.**

Vaier wires together WireGuard, Traefik, Authelia, and AWS Route53 into a single web UI. Add a Docker container on any VPN peer, pick a subdomain, and Vaier handles DNS, reverse proxy, and HTTPS — automatically.

---

## What it does

| Feature | Description |
|---------|-------------|
| **VPN peer management** | Create and delete WireGuard peers. Download peer config as a `.conf` file, QR code (mobile), docker-compose, or a one-shot bash setup script. |
| **Service publishing** | Discover Docker containers on the VPN server and on connected peers. Publish any container as a public HTTPS subdomain in one action. |
| **Reverse proxy** | Automatically generates Traefik dynamic config. Per-service Authelia authentication toggle. |
| **DNS management** | Full CRUD for AWS Route53 zones and records. |
| **User management** | Manage Authelia users from the UI (create, delete, change password). |
| **System metrics** | Per-peer CPU, RAM, disk, network, and Docker container count via Netdata — shown inline in the peer view. |

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

### 1. Provision the server

```bash
# Install Docker
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker $USER && newgrp docker

# (Optional) Add swap for small instances
sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile
sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile swap swap defaults 0 0' | sudo tee -a /etc/fstab
```

### 2. Clone and configure

```bash
git clone https://github.com/getvaier/vaier.git
cd vaier
```

Create a `.env` file:

```env
VAIER_AWS_KEY=your_aws_access_key
VAIER_AWS_SECRET=your_aws_secret_key
VAIER_DOMAIN=yourdomain.com
ACME_EMAIL=you@example.com
```

### 3. Point your base domain at your server

Create an A record for `yourdomain.com` pointing to the server's public IP. Vaier automatically creates the `vaier.yourdomain.com` and `auth.yourdomain.com` DNS records in Route53 on first startup.

### 4. Start the stack

```bash
docker compose up -d
```

Vaier will be available at `https://vaier.yourdomain.com` once certificates are issued (usually under a minute).

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

After creating a peer, download its config and connect. Vaier shows the peer's handshake status and (if Netdata is running on the peer) live system metrics.

---

## Publishing a service

1. Start a Docker container on any connected peer
2. In Vaier → Services → Publishable, the container appears automatically
3. Select the container, enter a subdomain, optionally enable Authelia authentication
4. Vaier creates a DNS CNAME record pointing to the VPN server, a Traefik route, and (optionally) Authelia middleware

The service is live at `https://subdomain.yourdomain.com`.

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
- **Infrastructure** (`adapter/driven/`) — adapters for WireGuard, Traefik, Route53, Docker, Authelia, Netdata.
- **Web** (`rest/`) — REST controllers; DTOs are inner `record` classes.

---

## Disclaimer

Vaier is a personal homelab tool provided as-is. Use it at your own risk. The authors accept no responsibility for security incidents, data loss, service outages, misconfigured firewalls, exposed services, or any other damage arising from its use. Running this software means exposing infrastructure to the internet — you are responsible for understanding what you are deploying.

The Apache License 2.0 (below) contains the full warranty disclaimer and limitation of liability in sections 7 and 8.

## License

Apache License 2.0 — see [LICENSE](LICENSE).

---

*Built for the self-hosted community.*
