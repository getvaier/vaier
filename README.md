<div align="center">
  <img src="docs/logo.svg" width="80" alt="Vaier logo"/>
</div>

# Vaier

[![Build](https://github.com/getvaier/vaier/actions/workflows/build-deploy.yml/badge.svg)](https://github.com/getvaier/vaier/actions/workflows/build-deploy.yml)
[![Docker Pulls](https://img.shields.io/docker/pulls/getvaier/vaier)](https://hub.docker.com/r/getvaier/vaier)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21-orange)](https://openjdk.org/projects/jdk/21/)

**Self-hosted infrastructure management for homelab developers.**

Vaier wires together WireGuard, Traefik, Google or GitHub sign-in (via oauth2-proxy), and AWS Route53 into a single web UI. Add a Docker container on any VPN peer, pick a subdomain, and Vaier handles DNS, reverse proxy, and HTTPS — automatically.

---

## What it does

| Feature | Description |
|---------|-------------|
| **VPN peer management** | Create, delete, and monitor WireGuard peers with downloadable configs (QR code, `.conf`, docker-compose, or setup script). |
| **Service publishing** | Publish any container on a peer — or a bare host:port on a LAN server — as a public HTTPS subdomain in one click, managed from the machine's card on the Infrastructure page. Share one subdomain across several services via path prefixes (`host/auth/*`, `host/api/*`, …), ignore discovered containers you don't want to publish, and watch each publish progress live. Automatic rollback if the flow fails. |
| **Smart launchpad** | A public, **viewer-adaptive** dashboard that links to your published services, switching to direct LAN URLs when you're on the same network. A logged-out visitor sees only your public services; sign in and it additionally shows every social-login service that identity is allowed to reach (admins see all) — so internal URLs never leak to strangers, while admin pages stay admin-only. Tiles show the path segment (for path-based routes) or the subdomain, with an optional operator-supplied display name. Hover a tile to see the Docker image and version behind the service — or point a service at a version endpoint so one running natively on a LAN machine reports its version too. Hide internal-only services per route, and read each tile's status dot at a glance — green when the hosting machine is confirmed reachable, grey while reachability is still being probed (e.g. just after startup), and a red "host offline" dot when the machine is confirmed unreachable (VPN handshake age or LAN reachability probe). Vaier's own infrastructure hosts (the console, oauth2-proxy, and the Dex broker) are never listed as tiles. |
| **Reverse proxy** | Traefik dynamic config generated automatically, with a per-service **auth mode** (public or **Social login** — Google or GitHub via oauth2-proxy, with Vaier deciding who's approved) and root-path redirect. When a service's backend is down, visitors get Vaier's branded **offline page** (naming the service, with retry and back-to-launchpad links) instead of Traefik's bare gateway error. A standalone page server stands in even when **Vaier itself** is down, so the control panel host shows the branded page rather than "Bad gateway". |
| **DNS management** | Full CRUD for AWS Route53 zones and records. |
| **Access management** | Manage who can sign in from the **Users** page: each Google or GitHub identity is an access entry with a **role** (pending → user → admin) and free-form per-service **access groups**. Approve or deny newcomers, promote admins, and gate individual services by group. Each person's card shows their provider photo (GitHub picture, else Gravatar, else a coloured monogram) with a small corner glyph for the identity provider (Google or GitHub) they last signed in with. |
| **Email notifications** | SMTP-powered password resets and admin alerts when any server-type machine (VPN server peers and LAN servers) goes up or down, when any SSH-reachable machine behind the VPN — including the Vaier host itself — fills past a configurable threshold, or when someone signs in for the first time and lands as a pending access request awaiting approval. |
| **Disk monitoring** | Vaier watches free space on every SSH-capable machine it holds a credential for, including the Vaier host itself via SSH-to-self (via `df` over SSH), emailing all admins when usage crosses a configurable threshold (default 85%), with a recovery email once it drops back below. |
| **Disk-fill forecast** | From the same `df` readings, Vaier projects each machine's **runway** — the time until the disk hits 100% at the recent fill rate — and emails an early warning when a still-below-threshold disk is projected to fill within 24 hours, so you hear about a runaway log before it's already full. |
| **Device category** | Each machine carries a **device category** (phone, laptop, desktop, server, NAS, printer, router, gateway, IoT, camera, media, or generic) that decides which icon it shows — independent of its VPN role. Vaier auto-detects it from the machine's name, scan hints, and type; you can pin an explicit category to override the guess, and clear it to fall back to auto-detection. Icon-only: it never changes routing. |
| **Inline field help** | Advanced fields (LAN CIDR, path prefix, root redirect, the auth toggle, direct LAN URL, hide-from-launchpad, version endpoint) carry a small "?" you can hover for a one-line plain-language explanation — no need to read the docs to know what a field does. |
| **Concepts page** | An in-app **Concepts** glossary in the admin shell explaining, in plain language, every term you meet in the UI — grouped by area, each with a short definition and a one-line "why it matters". Each entry is deep-linkable via its anchor (e.g. `concepts.html#lan-cidr`). |
| **Consistent branding** | The oauth2-proxy sign-in and error pages — and the Dex broker's own screens — all share Vaier's dark theme, so the sign-in hand-off (Google or GitHub) feels seamless end to end. |
| **LAN scanner** _(Enterprise)_ | When adding a **LAN server**, scan its relay's LAN right from the Add Machine dialog to discover hosts and pick one to fill in the address. Already-registered machines are filtered out, so only new hosts appear. Requires an Enterprise licence. |
| **Web terminal** | Open a real SSH shell to any SSH-capable machine — VPN peers, LAN servers, and the **Vaier server** host itself — with the **Terminal** button on its Infrastructure card. Shells open as tabs in a dedicated **Terminal** section, its own top-nav tab in the admin console that appears while shells are open and retires when you close the last one (dropping you back to Infrastructure), so they keep running while you move between Infrastructure, Users, and Settings (a badge on the tab counts the live ones). Keep several open at once — even two to the same host — and switch by tab. On desktop you can tile them into a **2-D split grid** like Termius: drag a tab onto a pane's left/right edge to add a column or its top/bottom edge to add a row (a drop preview shows where it'll land), and drag the dividers to resize. On a phone it's a single full-screen shell at a smaller font, with touch-scroll that stays inside the terminal. The remote PTY reflows to fit whichever layout you choose, and a dropped connection **reconnects automatically** (with backoff) so a tunnel blip or a host reboot heals itself. Vaier authenticates server-side from the **credential vault** (the browser never sees the secret) and pins each host's key on first use, refusing a later mismatch. Distinct, legible failures when there's no credential, the host is unreachable, auth fails, or the host key changed. Powered by a local xterm.js over a WebSocket and Apache MINA sshd. |
| **Host credentials** | Store the one SSH login Vaier holds for each machine — a username plus a password or private key (with optional passphrase) — from the machine's card on the Infrastructure page. Every machine card — including the **Vaier server** host's own card — has an **SSH access** toggle that decides whether Vaier offers SSH for that machine; it defaults sensibly from the device type (servers and NAS on, printers/phones/appliances off) and the credential + terminal controls only appear when SSH access is on. Secrets are encrypted at rest in a **credential vault**; the UI only ever reports whether a credential exists, never the secret itself. |
| **Version visibility** | The running Vaier version and edition (Community/Enterprise) are shown under *Settings → About*, so you always know which build is deployed. |

> **Editions** — Vaier ships as a single binary. **Community** (the default) is free and fully featured for everything above the line. **Enterprise** unlocks paid add-ons (starting with the **LAN scanner**) when you install a licence — see [Enterprise licence](#enterprise-licence).

![docs/launchpad.png](docs/launchpad.png)

---

## How it fits together

```mermaid
flowchart LR
    browser([User browser])
    route53[AWS Route53]
    server[Vaier server]
    p1[Peer 1 container]
    p2[Peer 2 container]

    browser -->|DNS| route53
    browser -->|HTTPS| server
    server <-->|WG tunnel| p1
    server <-->|WG tunnel| p2
```

Every published service resolves via Route53 to the single Vaier server, terminates TLS at Traefik, optionally passes social-login authorization (Google or GitHub via oauth2-proxy, then Vaier's own access check), and is proxied over WireGuard to the container running on a peer.

---

## Prerequisites

- A Linux server with a public IP (EC2 t3.small or similar)
- Docker and Docker Compose v2.23+ (the compose file embeds an inline `configs:` entry, which requires Compose v2.23 or newer — December 2023). The `curl get.docker.com | sh` step below installs current.
- A domain name you control
- AWS credentials with Route53 access — *or* skip them entirely and Vaier will run in manual DNS mode (you maintain records yourself)

### Server ports to open

| Port | Protocol | Purpose |
|------|----------|---------|
| 22 | TCP | SSH |
| 80 | TCP | HTTP (Let's Encrypt challenge) |
| 443 | TCP | HTTPS |
| 51820 | UDP | WireGuard VPN |

---

## Quick start

### 1. Install Docker

Run as your regular SSH user (e.g. `ubuntu` on EC2 Ubuntu AMIs, `ec2-user` on Amazon Linux) — **don't `sudo su -` to root first**. The rest of the quick start assumes an unprivileged user that's a member of the `docker` group; running as root skips that path and leaves bind-mounted config dirs root-owned, which complicates later edits.

```bash
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker $USER   # then log out and back in
```

Confirm with `docker ps` (no `sudo`) before continuing. If it errors with permission denied, the new group membership hasn't taken effect — fully close the SSH session and reconnect.

### 2. Download the compose file

```bash
mkdir -p vaier && cd vaier
curl -fsSL https://raw.githubusercontent.com/getvaier/vaier/main/docker-compose.yml -o docker-compose.yml
```

### 3. Pick a DNS mode

Vaier supports two modes — choose one based on where your domain lives. The mode is **inferred at boot from the presence of AWS credentials**: include them, you get Route53 automation; omit them, you get manual DNS. There is no separate switch.

#### Option A: Route53 (automated)

If your domain is on AWS Route53 and you want Vaier to manage DNS for you, include the AWS credentials in `.env`:

```bash
cat > .env <<'EOF'
VAIER_DOMAIN=yourdomain.com
ACME_EMAIL=you@yourdomain.com
VAIER_AWS_KEY=AKIA...
VAIER_AWS_SECRET=...
# Social sign-in — how you and your users authenticate (see step 3b)
VAIER_OIDC_GOOGLE_CLIENT_ID=...apps.googleusercontent.com
VAIER_OIDC_GOOGLE_CLIENT_SECRET=...
VAIER_OIDC_GITHUB_CLIENT_ID=...
VAIER_OIDC_GITHUB_CLIENT_SECRET=...
VAIER_ADMIN_EMAIL=you@gmail.com
EOF
chmod 600 .env
```

The AWS credentials need Route53 permissions on the hosted zone for `yourdomain.com`. Vaier auto-creates `vaier.yourdomain.com` on first boot, and a CNAME per published service after that.

#### Option B: Manual DNS (no AWS)

If your domain isn't on Route53, or you'd rather Vaier never touched AWS, simply leave the AWS variables out:

```bash
cat > .env <<'EOF'
VAIER_DOMAIN=yourdomain.com
ACME_EMAIL=you@yourdomain.com
# Social sign-in — how you and your users authenticate (see step 3b)
VAIER_OIDC_GOOGLE_CLIENT_ID=...apps.googleusercontent.com
VAIER_OIDC_GOOGLE_CLIENT_SECRET=...
VAIER_OIDC_GITHUB_CLIENT_ID=...
VAIER_OIDC_GITHUB_CLIENT_SECRET=...
VAIER_ADMIN_EMAIL=you@gmail.com
EOF
chmod 600 .env
```

You then maintain DNS records yourself in whatever provider you use. **Before first boot**, create these two records:

| Record | Type | Value |
|--------|------|-------|
| `vaier.yourdomain.com` | A or CNAME | the public IP/hostname of this server |
| `oauth2.yourdomain.com` | CNAME | `vaier.yourdomain.com` |
| `dex.yourdomain.com` | CNAME | `vaier.yourdomain.com` |

(`oauth2.yourdomain.com` is where oauth2-proxy serves the sign-in flow; `dex.yourdomain.com` is the Dex identity broker behind it that federates Google and GitHub.)

**Each time you publish a service**, also create a `<subdomain>.yourdomain.com` CNAME pointing at `vaier.yourdomain.com`. Vaier waits up to two minutes for the record to propagate, then activates the Traefik route automatically. If the record never appears the publish is rolled back.

### 3b. Set up sign-in (Google and GitHub)

Vaier delegates authentication to Google or GitHub and owns authorization itself. oauth2-proxy (mandatory infrastructure — it always starts with the stack) is the forward-auth gatekeeper; behind it, the **Dex** identity broker federates the two providers:

```
Traefik → oauth2-proxy → Dex ─┬→ Google
                              └→ GitHub
```

Both providers hand the user back to **Dex** (not oauth2-proxy), so register their redirect URIs at Dex:

- **Google** — create an OAuth 2.0 Web application client in the [Google Cloud console](https://console.cloud.google.com/apis/credentials), set its authorized redirect URI to `https://dex.yourdomain.com/callback`, and put the client id and secret in `.env` as `VAIER_OIDC_GOOGLE_CLIENT_ID` / `VAIER_OIDC_GOOGLE_CLIENT_SECRET`.
- **GitHub** — register an OAuth App in [GitHub developer settings](https://github.com/settings/developers), set its authorization callback URL to `https://dex.yourdomain.com/callback`, and put the client id and secret in `.env` as `VAIER_OIDC_GITHUB_CLIENT_ID` / `VAIER_OIDC_GITHUB_CLIENT_SECRET`. Any GitHub account may sign in — Vaier's pending → admin-approval gate decides who's actually let in.

Set `VAIER_ADMIN_EMAIL` to the email that should become the first admin. The oauth2-proxy session cookie secret (`VAIER_OAUTH2_COOKIE_SECRET`) and the oauth2-proxy↔Dex shared secret (`VAIER_DEX_CLIENT_SECRET`) are generated automatically — you don't author them.

### 4. Start the stack

```bash
docker compose up -d
```

### 5. First login

Once `docker compose ps` shows every service as `Up`, open `https://vaier.yourdomain.com` and sign in with the Google account you set as `VAIER_ADMIN_EMAIL`. Vaier seeds that identity as the first admin, so you land straight in the console.

Anyone else who signs in with Google for the first time is recorded as a **pending** access request — authenticated but blocked until you approve them on the **Users** page. Promote them to **user** (or **admin**) there.

For optional environment variables, secret-file hardening, the Google OAuth details, and other advanced topics, see [`docs/ADVANCED.md`](docs/ADVANCED.md).

---

## Adding a VPN peer

Create peers from the Vaier UI. The peer type determines WireGuard defaults and which download options are shown:

| Peer type | Typical use | Default routing | Downloads |
|-----------|-------------|-----------------|-----------|
| Mobile client | Phone/tablet internet access via VPN | All traffic | QR code, `.conf` |
| Windows client | Laptop internet access via VPN | All traffic | `.conf` |
| Ubuntu server with Docker | Self-hosted services on a Linux host | VPN subnet only | docker-compose, setup script |
| Windows server with Docker | Self-hosted services on a Windows Docker host | VPN subnet only | docker-compose |

Each machine — VPN peer or LAN server — can carry an optional **description**, a free-text note (e.g. "Home media server (NUC, Ubuntu 22.04)") set on the Add Machine form and editable inline on the expanded card. It shows as a muted subtitle under the machine name so its purpose is obvious at a glance.

Peers and LAN servers can be **renamed** in place — expand the card and edit the **Name** field. A peer's **name** is just a display label: editing it leaves the peer's underlying id (its config directory, REST paths, and routing) untouched, so the live tunnel and any published services keep working. The id is the slug Vaier derives from the name you first typed; the name is then yours to change freely. Names must be **unique across every machine** — Vaier won't let you add or rename a peer or LAN server onto a name another machine already uses (matched case-insensitively, surrounding spaces ignored), so you never end up with two cards wearing the same label. Clearing a peer's name reverts it to the humanised id — allowed as long as that fallback label isn't already used by another machine.

**LAN servers** (a NAS, printer, IPMI host, or an extra Docker host on a peer's LAN or in the Vaier server's own subnet) are added from **Add Machine** — Vaier only needs the host's LAN address. After adding, the machine's card offers a single **Setup script** to run on that host. The script adapts to what the host needs: it opens the Docker engine API (if you marked it as running Docker — native and snap installs covered) and installs persistent routes to the Vaier server's subnet (and other sites' LANs) via the host's relay peer, so a machine behind one relay can reach the rest of your Vaier network. It's idempotent and safe to re-run.

Each machine also has a **device category** — phone, laptop, desktop, server, NAS, printer, router, gateway, IoT, camera, media, or generic — that decides which icon it shows. Vaier auto-detects it from the machine's name (e.g. "synology-nas" → NAS), any LAN-scan hint, and its peer type, falling back to a generic icon. You can pin an explicit category to override the guess; clearing the override reverts to auto-detection, and renaming a machine re-detects when no override is set. The device category is presentation only — it never affects how Vaier routes or keys a machine.

Every machine card carries a **status colour** on its type icon — green (reachable / connected), amber (reachable but the Docker scrape failed), red (unreachable), or grey (not yet probed). Hovering a machine's icon shows the state in plain language with the evidence behind it (e.g. "Green — connected, last handshake 12s ago").

The Infrastructure page offers two views via a tab switcher: a **List** of machine cards and a **Map** plotting each machine at its geographic location on a world map.

On the **List** tab, expanding a machine card reveals a **Services** section that fuses what's published with what could be: the reverse-proxy routes hosted on that machine appear first — each expandable to edit its authentication, display name, and advanced options inline, or to delete it — followed by the host's discovered-but-unpublished containers as **+ Publish** rows that open the publish flow pre-filled. Each candidate row also offers an **Ignore** button to hide it from the list; a machine with ignored candidates shows a collapsible "N hidden" line to reveal and **Unignore** them. LAN-server cards that sit behind a relay add a **+ Publish LAN port** button for publishing a bare host:port (port + protocol + subdomain). Every publish runs as a floating, non-blocking **progress card** that advances through DNS creation, DNS propagation, and reverse-proxy routing — turning green on success or red on rollback / DNS timeout — and is rebuilt from the server on reload so a refresh never loses an in-flight publish. Deleting a service asks for confirmation in an in-app modal and shows a busy overlay while Traefik and DNS teardown runs. This makes each machine card the single place to see and manage everything running on a host, without leaving the page.

After creating a peer, download its config and connect. Vaier shows the peer's handshake status.

### Show-once peer config

The WireGuard config for a peer is delivered **exactly once**, at create time: the create-success modal shows the config text, an inline QR code, and download buttons for `.conf` / `docker-compose.yml` / setup script as appropriate. Save what you need before closing the modal — the five secret-bearing endpoints (`/config`, `/config-file`, `/qr-code`, `/docker-compose`, `/setup-script`) return `410 Gone` once the budget is burned.

To get a fresh config for an existing peer, the peer's row offers two actions:

- **Reissue config** — re-renders the config from the *current* generation logic while **keeping the peer's keypair**, then re-opens the one-shot delivery. Use this to **recover a lost config** without disrupting the tunnel — the keys are preserved, though the re-rendered contents may differ from the original (e.g. updated `AllowedIPs`) — or to refresh one that's gone **out of date** because what Vaier would generate now no longer matches the installed config (the peer's row shows a ⚠ **out-of-date config** badge). The live tunnel keeps working; reinstall the reissued config on the peer machine to apply it.
- **Regenerate** — deletes and recreates the peer with the same name, **rotating the keypair** as a side effect. Use this if the key may be compromised; the old config stops working immediately.

Why show-once: WireGuard has no session concept, no server-side revocation, and the same config works on any number of devices. A leaked screenshot or `.conf` would otherwise be a permanent backdoor.

---

## Publishing a service

1. Start a Docker container on any connected peer.
2. In Vaier → Infrastructure → List, expand the peer's card; the container appears as a **+ Publish** row in its **Services** section.
3. Click it, enter a subdomain, optionally require Social login (Google sign-in).
4. Vaier creates the DNS CNAME, the Traefik route, and (optionally) the social-login middleware chain.

The service is live at `https://subdomain.yourdomain.com`.

### Per-service auth mode

Each published service card carries an **auth mode** picker — **Public** (no sign-in) or **Social** (Google
sign-in via oauth2-proxy, with Vaier deciding who's approved). Change it any time; the change rewrites only
that route's Traefik middleware chain.

Social login is the sole runtime auth gateway: **Authelia has been fully removed** — both the running
service and the last of its Java code — and every gated service authenticates via Google. There is no
`authelia` auth mode; the two modes are Public and Social.

When someone signs in with Google for the first time, Vaier records them as a **pending** access request (authenticated but blocked) and denies access until an admin approves them. The moment that pending entry is created, Vaier emails every admin so the request doesn't sit unseen — the mail names the email and links straight to the **Users** page to approve or deny. It reuses the same SMTP configuration as the other alerts, so with SMTP unconfigured (or no admins to notify) it stays silent, and the send is fire-and-forget so it never slows the sign-in check.

Admin-vs-user is decided **only by the role** (pending → user → admin) — promote an entry with the role control. **Access groups** are a separate, per-service concept: free-form tags (e.g. `devs`, `family`) that gate individual services. Each Social service can carry an **access rule** — a set of *allowed groups* — and a user reaches the service if their entry holds **at least one** of them (any-of). Admins reach everything; pending identities reach nothing. The names `admins` and `users` are never access groups; the group picker won't suggest or accept them.

### Per-service access rules

For a **Social** published service you can restrict *which* signed-in users get through. Expand the service's row on the machine card and use the **Allowed groups** chip picker to name the groups allowed to reach it. Suggestions come from the groups already assigned to your access entries, and you can free-type a new group name. Leave it empty and any signed-in, approved user can reach the service; add one or more groups and only users holding at least one of them (plus every admin) get in. A service with a non-empty rule shows a **restricted** badge so you can see at a glance it isn't open to every approved user. Rules apply only in Social auth mode — switch a service to Public and the control disappears.

Rules are keyed by the service's host, so path-scoped services that share one subdomain currently share a single rule (a known limitation for now).

The console is admin-only, so Vaier keeps a **last-admin protection** invariant: the access store always holds at least one admin. Revoking or demoting the sole remaining admin is refused (the Access page disables those controls with an inline note, and the API answers `409 Conflict`), and on startup the configured administrator (`VAIER_ADMIN_EMAIL`) is restored to admin whenever no admin exists — promoting an existing entry in place or creating one — so the console can never be locked out for everyone.

Vaier also captures each identity's Google **display name** (the provider's `name` claim, forwarded by oauth2-proxy) and shows it on the **Users** page with the email beneath it — so an admin recognises who's asking by name, not just by address. A pre-approved entry stays nameless until its first sign-in fills the name in; later sign-ins keep it current, and it's never wiped if a sign-in arrives without one. The same captured name follows the identity into the Vaier console — which always runs on Social login — greeting them in the topbar with their provider photo when one is available (the same GitHub-picture-else-Gravatar chain as the Users cards), falling back to their name text (or email until a name is known) when no photo loads.

The **Users** page is this single list of social identities. Vaier no longer manages local password accounts and has no self-service profile page — each identity's name and email are owned by Google and shown read-only; only the role and access groups are edited here.

### Multiple services on one subdomain

Set an optional **Path prefix** at publish time (e.g. `/auth`) to put more than one service behind a single subdomain. Traefik routes by `Host(...) && PathPrefix(...)`, picks the more-specific rule first, and forwards the full path unchanged to the backend:

```
bmp.yourdomain.com         →  http://rig.yourdomain.com:8080
bmp.yourdomain.com/auth/*  →  http://rig.yourdomain.com:8090/auth/*
```

(`/auth` reaches the backend intact — Vaier doesn't strip the prefix.)

The first publish on a host creates the DNS CNAME; later routes — host-only or path-prefixed — reuse it. Deleting any sibling leaves the CNAME alive; only when the last route on a host is removed does the CNAME go.

For publishing services from non-peer LAN machines (NAS, printers, extra Docker hosts), see [`docs/ADVANCED.md`](docs/ADVANCED.md).

---

## Host disk monitoring

Vaier watches disk usage on every SSH-capable machine it holds a credential for — every machine behind the VPN, and the Vaier host itself, watched over SSH-to-self exactly like any other machine — running `df` over SSH on a periodic cadence and emailing every admin user when a machine's usage fills past a threshold. A **recovery** email follows once usage drops back below the threshold, and Vaier only emails on a boundary crossing (not on every poll), so a disk hovering just over the line won't spam you.

This reuses the same SMTP configuration as the up/down machine alerts (Settings → *Email notifications*), so it needs no extra mail setup. With SMTP unconfigured, monitoring is silent.

**Threshold** — the alert fires when usage rises above the configured percentage (default **85%**). Adjust it in Settings; valid range is 1–99.

**Requirements** — a machine is watched only once it has a stored **host credential** (the same vault the web terminal uses) and SSH access enabled. A host that's unreachable or whose `df` fails is quietly skipped — never mistaken for a full disk. Machines without a stored credential or with SSH access turned off are left alone, so there's no failed-auth noise. To have the Vaier host itself watched, store a host credential for it just like any other machine.

**Early warning (disk-fill forecast)** — beyond the level threshold above, Vaier fits a line through each machine's recent `df` readings and projects its **runway**: the time until the disk reaches 100% at the current fill rate. When the runway drops under a fixed **24-hour horizon** *while the disk is still below the pressure threshold*, admins get a one-time early-warning email naming the machine, its current usage, the fill rate (%/h) and the projected runway. It's a trend alarm ("this disk *will be* full"), distinct from the level alarm above ("this disk *is* full"): a filling disk pages once as a forecast and then, when it crosses the threshold, the disk-pressure alert takes over — never both at once. An all-clear follows on a *genuine recovery* — the disk drains, or its fill slows so the projected runway rises back over the horizon, all while still below the threshold. The hand-off case (the disk simply climbing past the threshold) raises **no** all-clear: the disk-pressure alert already speaks for it, so you're never sent a contradictory "cleared" and "is full" at the same poll. A flat or draining disk has no forecast, and a failed `df` records no sample, so a transient blip can't fabricate a warning.

---

## Enterprise licence

Vaier is open-core: one binary, two editions. **Community** is the default and needs no licence. **Enterprise** unlocks paid features (currently the **LAN scanner**) once you install a licence token.

A licence is an offline, cryptographically signed token — Vaier verifies it locally against a key built into the binary, so there's **no phone-home and no licence server**. To install one, set it in your `.env`:

```ini
VAIER_LICENSE=eyJ...token...
```

Restart Vaier and the Enterprise features appear; `GET /license` reports the active edition, who it's issued to, and when it expires. Without a valid licence, Enterprise endpoints return `402 Payment Required` and their UI stays hidden — Community is otherwise unaffected. An expired licence simply reverts the instance to Community.

> Licence tokens are minted by the Vaier maintainers with a private key that never ships. To obtain one, [open an issue](https://github.com/getvaier/vaier/issues) or contact the maintainers.

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

IP geolocation on the Infrastructure page is provided by [DB-IP](https://db-ip.com), licensed under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). The `geoip-init` container downloads the latest DB-IP City Lite database to a local volume on first boot and refreshes it monthly.

---

*Built for the self-hosted community.*
