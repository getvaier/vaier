<div align="center">
  <img src="docs/logo.svg" width="80" alt="Vaier logo"/>
</div>

# Vaier

[![Build](https://github.com/getvaier/vaier/actions/workflows/build-deploy.yml/badge.svg)](https://github.com/getvaier/vaier/actions/workflows/build-deploy.yml)
[![Docker Pulls](https://img.shields.io/docker/pulls/getvaier/vaier)](https://hub.docker.com/r/getvaier/vaier)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21-orange)](https://openjdk.org/projects/jdk/21/)

**Vaier** — Norwegian for *wire* (as in cable), pronounced **VY-er** — is the glue for your homelab.

One box on the internet. Your machines at home, behind WireGuard. Every service gets an HTTPS address, a login, and a dashboard tile — nothing to configure by hand: no Traefik files, no WireGuard configs, no DNS record beyond the one wildcard you make on day one.

---

## What it does

Each row is the short version. The linked page carries the mechanism, the caveats and the reasons.

| Feature | In short |
|---------|----------|
| **VPN mesh** | WireGuard peers and LAN servers (NAS, printers, extra Docker hosts) join one mesh, with cross-site routing between your networks. Vaier reads the network a machine sits on over the SSH it already has, so you are never asked for a CIDR. → [Networking](docs/NETWORKING.md) |
| **Wildcard DNS** | One `*.yourdomain.com` record, made once, covers the console, sign-in, and every service you ever publish. Vaier checks it at every boot. → [Networking](docs/NETWORKING.md#wildcard-dns) |
| **Reverse proxy & edge hardening** | Traefik terminates HTTPS with Let's Encrypt, enforces a security-header and TLS floor on every route, and shows a branded offline page when a backend is down. CrowdSec blocks malicious traffic at the edge; every block is listed in the Explorer, one click to lift it or trust the address. → [Networking](docs/NETWORKING.md#edge-hardening) |
| **Service publishing & launchpad** | Publish any container's web interface in one click. A port that isn't a website — MQTT, a database — is published as a **stream** on the same HTTPS port. The launchpad shows each visitor only what they may reach. → [Networking](docs/NETWORKING.md#publishing-a-service) |
| **Access management** | Google or GitHub sign-in via oauth2-proxy and Dex, with roles (pending → user → admin) and per-service access groups. → [Auth](docs/AUTH.md) |
| **The Vaier app** | A phone joins without the WireGuard app: it makes its own key, shows a four-digit join code, and connects the moment you let it in from any browser you're signed in on. The private key never leaves the phone. Get the app from your own Vaier's launchpad. → [Networking](docs/NETWORKING.md#enrolment-from-the-vaier-app) |
| **Explorer** | One address space for the whole fleet: files, containers, services, disks and backup archives, with selection and transfer across machines and a link for every place you stand. Each machine's card says what it can do and where it stands. An in-app Concepts glossary explains every term you meet. → [Explorer](docs/EXPLORER.md) |
| **Map** | Every machine plotted honestly: a device's own reported position beats an ISP estimate, a disconnected device with nothing reported draws no marker, and an open marker shows where that device has been over the last 30 days. → [Explorer](docs/EXPLORER.md#map) |
| **Web terminal** | A real, persistent SSH shell to any machine, in its own window, reattached across reconnects and redeploys. → [Explorer](docs/EXPLORER.md#web-terminal) |
| **Host & fleet credentials** | One encrypted vault holds the SSH login for every machine; the browser never sees a secret. A **fleet credential** is the other direction: one secret Vaier places on every machine, verifies, and puts back when it goes missing. → [Explorer](docs/EXPLORER.md#host-credentials) |
| **Claude sign-in** | Sign each machine's Claude Code CLI in to your own Anthropic account from that machine's terminal window. The credential is Anthropic's to mint and the CLI's to keep — it never passes through Vaier. → [Explorer](docs/EXPLORER.md#claude-sign-in) |
| **Fleet backup & survival kit** | Automated borg backups to one designated backup server, plus a self-updating survival kit so your backups stay readable even if Vaier itself is gone. → [Backup](docs/BACKUP.md) |
| **Monitoring & alerts** | Disk watching with a fill-rate forecast that mails you about a week before a disk fills, a word when a container that was running stops, image-update detection with a one-click **Update**, and an inbox that stays quiet unless something is wrong. → [Monitoring](docs/MONITORING.md) |
| **Chat** | Ask about your fleet in plain sentences and Marvin answers — from Vaier's own facts, a read-only command, or a public web page when neither covers it. He can act with your click, hand you files, and run errands: send him off to check something later and he mails the answer. → [Chat](docs/CHAT.md) |
| **What to do next** | Each machine's pane nudges you toward the next thing worth doing with it, with the evidence alongside. Each is one yes. → [Explorer](docs/EXPLORER.md#suggested-next-steps) |

![The Vaier launchpad](docs/vaier-launchpad.png)

---

## How it fits together

```mermaid
flowchart LR
    browser([User browser])
    server[Vaier server]
    p1[Peer 1 container]
    p2[Peer 2 container]

    browser -->|HTTPS| server
    server <-->|WG tunnel| p1
    server <-->|WG tunnel| p2
```

Every published service resolves to the single Vaier server through your one `*.yourdomain.com` record, terminates TLS at Traefik, optionally passes social-login authorization (Google or GitHub via oauth2-proxy, then Vaier's own access check), and is proxied over WireGuard to the container running on a peer. A **stream** takes the same path as far as TLS — matched by the name in the handshake — and then forwards raw bytes; it carries no login, because nothing inside it is a web request. More in [`docs/NETWORKING.md`](docs/NETWORKING.md).

---

## Prerequisites

- A Linux server with a public IP (EC2 t3.small or similar)
- Docker and Docker Compose v2.23+ (the compose file embeds an inline `configs:` entry, which requires Compose v2.23 or newer — December 2023). The `curl get.docker.com | sh` step below installs current.
- A domain name you control, hosted anywhere that can serve a wildcard `A` record

### Server ports to open

| Port | Protocol | Purpose |
|------|----------|---------|
| 22 | TCP | SSH |
| 80 | TCP | HTTP (Let's Encrypt challenge) |
| 443 | TCP | HTTPS |
| 51820 | UDP | WireGuard VPN |

---

## Quick start

### 1. Install Docker and rig the machine

```bash
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker $USER   # then log out and back in
```

Confirm with `docker ps` (no `sudo`). Then fetch the runtime files Vaier needs (the compose file, and the assets it bind-mounts) and scaffold a `.env` — **no git clone**:

```bash
mkdir -p vaier && cd vaier
curl -fsSL https://raw.githubusercontent.com/getvaier/vaier/main/install.sh | bash
```

### 2. Point your domain at it

Make one DNS record, before first boot, at whatever DNS host your domain lives on:

| Record | Type | Value |
|--------|------|-------|
| `*.yourdomain.com` | A | the public IP of this server |

That single wildcard covers the console, the sign-in hosts, and every service you publish from now on — nothing to add, ever, when you publish a service. Vaier checks it for you at every boot and reports the verdict in the boot log and in **Settings**. Caveats and the full mechanics are in [`docs/NETWORKING.md`](docs/NETWORKING.md#wildcard-dns).

### 3. Configure `.env`

Step 1 already created `.env` with three secrets generated for you. Open it and fill in your own values — **don't recreate the file**, or you'll wipe those secrets:

```ini
VAIER_DOMAIN=yourdomain.com
ACME_EMAIL=you@yourdomain.com
VAIER_OIDC_GOOGLE_CLIENT_ID=...apps.googleusercontent.com
VAIER_OIDC_GOOGLE_CLIENT_SECRET=...
VAIER_ADMIN_EMAIL=you@gmail.com
```

At least one sign-in provider — Google and/or GitHub — is required; the full registration walkthrough (OAuth client setup, redirect URIs, GitHub as an alternative or an addition) is in [`docs/AUTH.md`](docs/AUTH.md). `VAIER_ADMIN_EMAIL` becomes the first admin on first login.

### 4. Start the stack and sign in

```bash
docker compose up -d
```

Once `docker compose ps` shows every service `Up`, open `https://vaier.yourdomain.com` and sign in with your admin account. Anyone else who signs in lands as **pending** until you approve them on the **Users** page.

From here: add your machines and publish their services from the **Explorer** — see [`docs/NETWORKING.md`](docs/NETWORKING.md). Want to ask Vaier about your fleet instead of clicking through it? Paste your own Anthropic API key under **Settings** and the **Chat** pane appears in the **Vaier** menu — see [`docs/CHAT.md`](docs/CHAT.md). For optional environment variables, secret-file hardening, and other advanced topics, see [`docs/ADVANCED.md`](docs/ADVANCED.md).

---

## Updating an existing install

Re-run the same installer in your install directory, then bring the stack up:

```bash
cd vaier
curl -fsSL https://raw.githubusercontent.com/getvaier/vaier/main/install.sh | bash
docker compose up -d
```

It is safe to re-run: it refreshes the compose file and the assets the stack bind-mounts, leaves your `.env` untouched, and adds any secret a newer release generates but your `.env` predates. There is no DNS record to add and nothing to edit.

If `docker compose up -d` stops with something like

```
required variable VAIER_CROWDSEC_BOUNCER_KEY is missing a value:
not in .env — re-run install.sh to generate it
```

your `.env` was written before that secret existed. Re-run the installer as above and start again. Compose refuses at config-parse time, before it touches a single container, so a stack that is already running keeps running.

---

## Roadmap

The backlog is tracked in [GitHub Issues](https://github.com/getvaier/vaier/issues). Feature specs for planned items are in [`PRD.md`](PRD.md).

---

## Contributing

Contributions are welcome. See [`CONTRIBUTING.md`](CONTRIBUTING.md) for the development guide (architecture, TDD rules, build instructions, PR expectations).

---

## Disclaimer

Vaier is a personal homelab tool provided as-is. Use it at your own risk. The authors accept no responsibility for security incidents, data loss, service outages, misconfigured firewalls, exposed services, or any other damage arising from its use. Running this software means exposing infrastructure to the internet — you are responsible for understanding what you are deploying.

The Apache License 2.0 (below) contains the full warranty disclaimer and limitation of liability in sections 7 and 8.

## License

Apache License 2.0 — see [LICENSE](LICENSE).

## Attribution

IP geolocation on the Explorer's map is provided by [DB-IP](https://db-ip.com), licensed under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). The `geoip-init` container downloads the latest DB-IP City Lite database to a local volume on first boot and refreshes it monthly.

---

*Built for the self-hosted community.*
