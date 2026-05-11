# Advanced topics

Back to [README](../README.md).

This document covers configuration and workflows beyond the basic Quick Start. If you're just getting started, the README is enough.

---

## Environment variables

| Variable | Required | Description |
|----------|----------|-------------|
| `VAIER_AWS_KEY` | Yes | AWS access key for Route53 |
| `VAIER_AWS_SECRET` | Yes | AWS secret key for Route53 |
| `VAIER_DOMAIN` | Yes | Base domain (e.g. `yourdomain.com`) |
| `ACME_EMAIL` | Yes | Email for Let's Encrypt notifications |
| `VAIER_PUBLIC_HOST` | No | Public hostname of this server; used as the CNAME target for `vaier.<domain>` when not on EC2 |
| `VAIER_PUBLIC_IP` | No | Public IPv4 of this server; used as an A-record target for `vaier.<domain>` when not on EC2 |
| `VAIER_SERVER_LAN_CIDR` | No | CIDR of the LAN/VPC subnet this server sits on (e.g. `172.31.0.0/16`). On EC2 this is auto-detected from instance metadata; set it only when not on EC2. Lets machines in that subnet be registered as LAN servers with no relay peer — see below. |
| `WIREGUARD_CONFIG_PATH` | No | WireGuard config dir (default: `/wireguard/config`) |
| `WIREGUARD_CONTAINER_NAME` | No | WireGuard container name (default: `wireguard`) |
| `TRAEFIK_CONFIG_PATH` | No | Traefik dynamic config dir (default: `/traefik/config`) |
| `TRAEFIK_API_URL` | No | Traefik API URL (default: `http://traefik:8080`) |
| `AUTHELIA_CONFIG_PATH` | No | Authelia config dir (default: `/authelia/config`) |

On EC2, the public hostname is detected from instance metadata. On other hosts, set `VAIER_PUBLIC_HOST` (CNAME target) or `VAIER_PUBLIC_IP` (A record target) in `.env`.

---

## Secrets on disk

Vaier writes new secret files at mode `600` (`rw-------`). For an upgraded deployment you should also tighten the existing files and the surrounding directories on the host:

```bash
chmod 600 .env production.env 2>/dev/null
chmod -R go-rwx vaier/ authelia/ wireguard/ traefik/
```

Files Vaier creates and protects:

| File | Contents |
|------|----------|
| `vaier/config/vaier-config.yml` | AWS Route53 credentials, SMTP settings |
| `authelia/config/secrets.properties` | Authelia JWT/session/encryption secrets, SMTP password |
| `authelia/config/users_database.yml` | Authelia users with Argon2 password hashes |
| `authelia/config/redis-password` | Auto-generated Redis password (created by the `redis-init` container) |
| `authelia/config/.bootstrap-admin-password` | One-time bootstrap admin password (delete after first login) |

The `.env` file you create yourself — keep it at mode `600`.

---

## Publishing a service from a LAN server (Docker optional)

A *LAN server* is any machine that isn't itself a VPN peer — a NAS, a printer, IPMI, an extra Docker host — sitting either on a relay peer's LAN or **in the Vaier server's own subnet** (an AWS VPC, say). Register it once on the Machines page, then publish its services.

1. Make sure the LAN server's address is covered by something Vaier routes to:
   - **Behind a relay peer:** set `lanCidr` (e.g. `192.168.3.0/24`) on that peer so Vaier knows which LAN sits behind it.
   - **In the Vaier server's own subnet:** nothing to configure — on EC2 the subnet CIDR is auto-detected from instance metadata. Off EC2, set `VAIER_SERVER_LAN_CIDR` (see above). Make sure the target machine's firewall / security group allows inbound from the Vaier server.
2. (Docker only) On the LAN server, expose its Docker socket on TCP 2375 reachable from whatever routes to it — the relay peer, or (for a server-anchored machine) the Vaier server itself (no TLS in V1; firewall it to the LAN/VPC range). The Add Machine modal shows a one-liner `curl https://vaier.<domain>/lan-servers/docker-setup.sh | sudo bash -s -- --port 2375` — an idempotent script that handles both native (systemd) and Snap Docker installs.
3. In Vaier → Machines, click the **+** FAB, pick **LAN server**, enter a name and the LAN address (must fall inside a relay's `lanCidr` or the Vaier server's subnet — that's all the modal asks for), and toggle Docker on/off (Docker port defaults to 2375).
4. With Docker on, discovered containers appear in Services → discovered list and route through the relay (or directly via the Vaier server) when published. To publish a native (non-container) service on any LAN server — Docker on or off — go to Services → **+ Publish LAN service**, pick the machine from the dropdown, and enter port/subdomain/protocol — Vaier creates the DNS CNAME and a Traefik route whose backend is `http(s)://<lanAddress>:<port>`.

> **V1 limitation:** the "Vaier server's own subnet" path routes the Vaier-side containers (the Vaier app, Traefik) to that subnet — enough to register, scrape, and publish those machines. It does not yet let your *split-tunnel server peers* reach the subnet (full-tunnel mobile/Windows clients already can). That's a tracked follow-up.
