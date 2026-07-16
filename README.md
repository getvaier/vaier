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
| **Email notifications** | SMTP-powered password resets and admin alerts when any server-type machine (VPN server peers and LAN servers) goes up or down, when a filesystem on any SSH-reachable machine behind the VPN — including the Vaier host itself — fills past its threshold, or when someone signs in for the first time and lands as a pending access request awaiting approval. |
| **Disk monitoring** | Vaier watches free space on **every filesystem** of every SSH-capable machine it holds a credential for, including the Vaier host itself via SSH-to-self (via `df` over SSH) — not just the root one, so the 12 TB volume holding your backups is watched, not only the small system partition it sits beside. Alerts name the mount and its size ("NAS /volume1 is at 91% full (10.8 TiB, 1.0 TiB free)"), and every admin gets one when a filesystem crosses its threshold, with a recovery email once it drops back below. Each filesystem can be **muted** or given a **threshold of its own** — a fixed-size system partition that's 88% full by design shouldn't page you forever — while everything Vaier hasn't been told about is watched at the fleet-wide threshold (default 85%). You don't have to wait for the email to see the numbers: a machine's **disk** entry in the **Explorer** takes the same reading on demand, lists every filesystem with its size and free space, and is where you mute one or set its threshold. |
| **Disk-fill forecast** | From the same `df` readings, Vaier projects each filesystem's **runway** — the time until it hits 100% at the recent fill rate — and emails an early warning when a still-below-threshold filesystem is projected to fill within 24 hours, so you hear about a runaway log before it's already full. |
| **Device category** | Each machine carries a **device category** (phone, laptop, desktop, server, NAS, printer, router, gateway, IoT, camera, media, or generic) that decides which icon it shows — independent of its VPN role. Vaier auto-detects it from the machine's name, scan hints, and type; you can pin an explicit category to override the guess, and clear it to fall back to auto-detection. Icon-only: it never changes routing. |
| **Inline field help** | Advanced fields (LAN CIDR, path prefix, root redirect, the auth toggle, direct LAN URL, hide-from-launchpad, version endpoint) carry a small "?" you can hover for a one-line plain-language explanation — no need to read the docs to know what a field does. |
| **Concepts page** | An in-app **Concepts** glossary in the admin shell explaining, in plain language, every term you meet in the UI — grouped by area, each with a short definition and a one-line "why it matters". Each entry is deep-linkable via its anchor (e.g. `concepts.html#lan-cidr`). |
| **Consistent branding** | The oauth2-proxy sign-in and error pages — and the Dex broker's own screens — all share Vaier's dark theme, so the sign-in hand-off (Google or GitHub) feels seamless end to end. |
| **LAN scanner** _(Enterprise)_ | When adding a **LAN server**, scan its relay's LAN right from the Add Machine dialog to discover hosts and pick one to fill in the address. Already-registered machines are filtered out, so only new hosts appear. Requires an Enterprise licence. |
| **Web terminal** | Open a real SSH shell to any SSH-capable machine — VPN peers, LAN servers, and the **Vaier server** host itself — with the **Terminal** button on its Infrastructure card. Shells open as tabs in a dedicated **Terminal** section, its own top-nav tab in the admin console that appears while shells are open and retires when you close the last one (dropping you back to Infrastructure), so they keep running while you move between Infrastructure, Users, and Settings (a badge on the tab counts the live ones). Keep several open at once — even two to the same host — and every shell is always a **pane**: opening one tiles it straight into a **2-D split grid** like Termius, beside the pane you had focused, starting a new row underneath once a row already holds two columns; closing a shell removes its pane without re-tiling the rest. On desktop you can rearrange the grid yourself: drag a tab onto another pane's left/right edge to add a column or its top/bottom edge to add a row (a drop preview shows where it'll land; dropping a tab on its own pane is a no-op), and drag the dividers to resize. Any shell can **maximize** to fill the whole panel — the grid stays intact underneath, so **Restore** brings back exactly the arrangement you had — from a third entry in its ⋯ menu or by double-clicking its tab; it's unavailable when only one shell is open, since a lone shell already fills the panel. On a phone it's a single full-screen shell at a smaller font, with touch-scroll that stays inside the terminal, and — for as long as any shell is open — Vaier **holds the screen awake** (via the browser's Screen Wake Lock) so a command you're watching doesn't vanish behind a dimmed display, releasing it the moment you close the last shell. An on-screen **key bar** appears above it carrying the keys a soft keyboard can't reach — **Esc**, **Tab**, the four **arrow** keys (which navigate correctly in vim/less), and **Ctrl**/**Alt** as *sticky* modifiers you tap to arm (it glows) so the very next key — tapped on the bar or typed on the keyboard — is modified, e.g. arm Ctrl then type `c` for Ctrl-C. The remote PTY reflows to fit whichever layout you choose, and a dropped connection **reconnects automatically** (with backoff) so a tunnel blip or a host reboot heals itself. Each shell runs inside a tmux session on the machine (a **persistent shell**), so it keeps running even when Vaier itself is redeployed — a reconnect **reattaches** to the same shell with your cwd, history, and scrollback intact, and the banner tells you the truth ("reattached — session resumed", or a new shell if the old one was genuinely lost). On a machine without tmux installed it opens a plain shell instead, so a terminal never fails to open. Because a persistent shell is built to survive a lost connection, only *you* can end one: closing a shell (its ✕, or the shell exiting on its own) **ends** it — the tmux session on the machine stops, and so does whatever was still running inside it — while everything else merely **disconnects** it (a tunnel blip, a closed laptop, a Vaier redeploy) and leaves the shell running on the machine to reattach to. A page reload counts as a disconnect: Vaier remembers your open panes in the browser, so reloading reattaches you to the shells you already had instead of abandoning them on the host and opening new ones beside them. Vaier authenticates server-side from the **credential vault** (the browser never sees the secret) and pins each host's key on first use, refusing a later mismatch. Each pane wears its own **tab** as its header — one line flush with the pane's left edge, carrying that shell's status dot, the machine name, a small **⋯** actions menu, and an ✕ that closes the shell outright — so a machine's name is written exactly once, right above the terminal it names, and there's no panel-wide tab strip. The one pane that fills the whole panel — maximized, or the lone pane on a phone — is the exception: it adopts every open shell's tab into one horizontally-scrolling header, so a shell it's covering still has a handle, and clicking a covered shell's tab brings it to the front (on a phone this is how you switch shells). The focus ring only appears on the focused pane once more than one is on screen. Clicking **⋯** drops a menu holding the shell's **Send password** action. The action is never taken away when it can't be used — it stays in the menu, greyed with a tooltip that explains why, and enables the moment the server says it's usable. Keeping the action behind a menu rather than in a permanent button row means a rare action (a password prompt) never costs every shell a line of terminal output forever, and — because the menu hangs off one named tab — a click is never ambiguous about which pane it meant. While a shell's remote is at a live password prompt, its ⋯ button takes the accent colour, the visible cue that an action just became usable. When a shell prompts you for a password or key passphrase again — a `sudo`, an `ssh` to a further hop — **Send password** enables (Vaier watches the shell output server-side, so it's usable only while the remote is actually at a prompt) and writes the machine's stored password straight from Vaier into the remote shell, again without the browser ever seeing it; Vaier re-checks the remote is at a prompt before sending, so it can't echo into the screen or your shell history. When the shell ends, its pane closes on its own rather than leaving a dead window to tidy. Distinct, legible failures when there's no credential, the host is unreachable, auth fails, or the host key changed. Powered by a local xterm.js over a WebSocket and Apache MINA sshd. |
| **Fleet backup** _(Enterprise)_ | Back up every machine in your fleet to a [borg](https://www.borgbackup.org/) repository on a **backup server** — and get there from nothing. Designate the fleet's one backup server on a machine in the **Explorer** (any machine can become it, but only while none is designated yet), then provision a pinned borg server on it (Vaier runs the setup where it can drive docker over SSH, or stages an idempotent `setup.sh` on the host and hands you the one `sudo bash <path>` command where it can't, as on a Synology NAS), **authorize** each client host's key on it (a **restricted** key — no shell, confined to just that host's repositories), then add a **backup repository** by name (with an auto-generated, shell-safe passphrase). Give each machine a **backup job** (source paths, excludes, retention, compression), and run it on demand with **Run now** or on the nightly schedule. borg runs as the machine's SSH user, so files that user can't read are silently skipped — turn on a job's **Back up as root** toggle and its borg runs under `sudo` instead, picking up the root-owned container volumes it would otherwise leave out of the archive. Guided provisioning checks host readiness — borg installed and a supported version, server reachable, client key **actually trusted**, client/server borg majors **compatible**, and (for a job that backs up as root) that borg **can actually run as root** there — and can **prepare a client** (installs the `borgbackup` package for the host's package manager and grants passwordless sudo for the borg binary alone, itself where it has passwordless sudo, or by handing you one `sudo bash <path>` command where it doesn't); browse the archives it writes. A run **initialises its own repository** if it doesn't exist yet, so there is no manual init step. A run whose host has no borg is refused up front with a clear "borg is not installed — run Prepare client" rather than dying mid-flight with `borg: not found`. A failed run, or a backup server that goes quiet, emails all admins. The Backups page is laid out as the chain it is — server, then repository, then job — with the backup server shown **read-only** (you designate it in the Explorer, not here), and each later step whose prerequisite is missing shown blocked, refusing its "New…" button so it can't be worked out of order. Restore from the UI isn't available yet. Requires an Enterprise licence. |
| **Explorer** | One tree spanning the whole fleet, at `/explorer.html` — and the shape Vaier's UI is becoming. Every machine is an **entry** you can open, and it grows only the entries Vaier can actually reach on it: **files** and a **shell** when Vaier has SSH to it, **containers** when it runs Docker, **services** when something is published from it, its **disk** when Vaier has SSH, and a **backup** entry on the one machine that is the fleet's backup server — so a machine with no SSH doesn't sprout a shell that can't open, and a machine running no Docker doesn't sprout an empty container list. The **path** you're standing on is the address bar, the **Inspector** on the right renders whatever you select (a machine shows its details, a directory shows its listing, a container shows what Vaier knows about it, a published service shows its route, a shell opens a terminal in the dock at the bottom — and the shell keeps running as you move around the tree, because nothing here is loaded in a frame), and **⌘K** searches every entry in the fleet by path — including containers and published services, since they're entries in the same namespace now. **Directories expand in the rail**, read one at a time over SFTP as you open them — never eagerly, because the fleet is behind a VPN and a tree that walks it all at once is a tree that hangs. The **rail** carries the structure, so only directories become rows in it; the Inspector lists what's actually inside (files and directories both). A directory is read once and remembered, so folding it away and opening it again costs nothing, and one that can't be read says so on the spot, in the server's own words ("Not allowed to read /root as geir.") — it never pretends to be empty. The **status dot** beside a machine reports what Vaier actually knows: green when a peer's tunnel is up or a LAN machine answers its probe, amber when a machine is on the network but its Docker scrape is failing, red when it's unreachable, and grey only when nothing has probed it yet — grey means "no answer yet", never "fine". Vaier reads a machine's filesystem over SFTP, authenticating server-side from the **credential vault** and trusting the host key by the same trust-on-first-use pin as the web terminal, so browsing a machine needs no new credentials and no new trust. Every file is shown at the **machine's own path** — the one you'd see in a shell, in `df`, or in a backup job's source paths — even when the machine disagrees with itself: a NAS that chroots its SFTP service into `/volume1` while its terminal sees the real root has one directory under two names, so Vaier learns where each machine's **SFTP root** is — by asking both channels where the SSH user's home is, and taking the difference — and maps between them, showing you `/volume1/homes/geir` and never the jail's `/homes/geir`. (A machine that won't say where its SFTP service is rooted gets *found* instead: Vaier asks its SFTP side which of the home's possible names it can actually see. The machine's own name for the home is always tried first, so an ordinary machine matches straight away and is never given a jail it doesn't have.) A machine's tree therefore begins at its SFTP root, not at `/`, and a path above it (`/volume2` on that NAS) says exactly that — it is never shown as an empty folder. A machine Vaier can't ask is simply left alone: unknown is safe, guessing isn't. Directories list before files, and a path that isn't absolute — or that tries to climb above the root — is refused before Vaier opens any connection. A machine's **containers** are the ones Vaier's Docker scrape returns for it (every container with at least one exposed port, running or stopped — the same set the Infrastructure page discovers from), and opening one shows its image, version, state, ports, networks and container id — **read-only, deliberately**: Vaier has no way to start, stop or restart a container or fetch its logs, so it offers no button that would only pretend to (use the machine's shell for that). A machine's **published services** show that a service is one thing with three homes — a container on the machine, a route through Traefik, and a DNS record: opening one shows its DNS record and DNS state, its route state, the backend it points at, its path prefix, the image and version behind it, its **auth mode** and its **allowed groups**, and offers one action, **Unpublish** (after a confirmation), which takes down the route and the DNS record while leaving the container running. Publishing a *new* service still happens on **Infrastructure**, where the subdomain / auth-mode / backend form lives. A machine's **disk** is read when you look at it — `df` over the same SSH connection everything else uses — and lists **every** filesystem on it, not just the root one: each with its mount point, device, size and free space, a usage meter, and the threshold tick it's judged against, with the same verdict the alert email is sent from. Kernel and in-memory mounts, and the bind-mount aliases a Docker storage driver leaves behind, are left out — they're not disks, and one volume wearing eight masks would raise eight alerts. Each filesystem carries its own **watch**: mute the ones that are full by design (a NAS system partition sits at 88% forever) or give one a threshold of its own, right there in the pane — a muted filesystem keeps its meter but loses its tick, because nothing is being judged. Anything you haven't muted is watched, so a new volume nags rather than hides. A disk Vaier can't read (an asleep machine, an SSH user that can't run `df`) says exactly that, and is never painted as an empty disk. You **designate the fleet's one backup server** from a machine's own view — a **Make this the fleet's backup server** action shown while none is designated yet, its form prefilled from the machine's address — and the machine that plays the role then grows a **backup** entry: its Inspector shows that server's coordinates (name, where Vaier reaches its `borg serve`, borg user, base repo path, data path, and whether Vaier stood it up or adopted it) and the repositories that live on it (read-only), with **Edit coordinates** and **Remove designation** actions and a link to the **Backups** page for provisioning, authorizing clients, and managing repositories and jobs. Service and container liveness arrives over the existing published-services event stream, so the page never polls. You can **download** any file to your browser — a directory downloads as a zip of its whole tree — and **copy a file or folder from one machine to another** — a **Transfer**. Because Vaier sits at the VPN hub and is the only node with SSH to every machine, a cross-machine copy is a read from the source streamed straight through Vaier's own process to a write on the destination: no host ever needs SSH to another, and nothing is buffered whole, however large the file. A Transfer runs in the background and reports its progress live as the bytes move, and it settles as done or failed on the same event stream the page already listens on — it never polls. **You can only paste into the present**: a copy's destination is always the live machine, though its *source* may be a point in the past (an **archive**), which is how a restore works — pasting an archived file back onto its own live path. You can also **delete** a file or folder from a machine's live filesystem — a folder goes with everything inside it — behind a typed machine-name confirmation, because a delete is destructive and cannot be undone. Deleting is **present-only**: there's no deleting the past, since an archive is read-only by construction, and a machine's whole browsable tree (its **SFTP root**) can never be deleted at all. Renaming and creating folders land in a later slice. **Browse a machine's past** right on its file view: a machine that has backups grows a **time rail** — a row of stops, one per backup **archive**, newest nearest **Now** — and clicking a stop shows that machine's files *as they were* in that archive. Vaier mounts the archive as a read-only filesystem on the machine (via `borg mount`) and lists the very same directory inside it, so a file keeps one **path** across both the present and the past. Scrub back and the whole view crossfades to an amber "past" palette, the liveness dots go dark, and a line tells you the archive's time and how long ago it was; click **Now** to return to the live filesystem. The past is read-only by construction (the mount is `ro`, so you can only ever paste into the present), and idle mounts are swept off the fleet automatically. A machine's archives are read once when you first open its files — never on a timer — and a machine with no backup job simply grows no rail. The sections not yet part of the tree — Infrastructure, Backups, Users, Settings, Concepts — appear in it as entries that open today's page unchanged, and the **admin console** (`/admin.html`) with all of its pages stays exactly where it is until every section has moved across: **Infrastructure** in particular is still the only place to add a machine, scan a LAN, see the world map, store SSH credentials, run setup scripts, edit allowed groups, and publish a discovered container. |
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
# The zone Vaier reads local time in — the nightly backup hour means this zone, not UTC. Defaults to UTC.
VAIER_TZ=Europe/Oslo
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

Vaier watches disk usage on every SSH-capable machine it holds a credential for — every machine behind the VPN, and the Vaier host itself, watched over SSH-to-self exactly like any other machine — running `df` over SSH on a periodic cadence and emailing every admin user when a **filesystem** fills past its threshold. A **recovery** email follows once it drops back below, and Vaier only emails on a boundary crossing (not on every poll), so a filesystem hovering just over the line won't spam you.

**Every filesystem, not just the root one.** A machine's disks are read whole: `df` reports each mounted filesystem and Vaier watches all of them. This matters more than it sounds — on a Synology NAS, `/` is a fixed-size ~2 GB DSM system partition that is 88% full by design and never moves, while `/volume1` is the 12 TB volume holding every backup. Watching only `/` means watching the one filesystem that can never tell you anything, while the one that matters fills to 100% in silence. Kernel and in-memory mounts (tmpfs, proc, sysfs, cgroup, squashfs, overlay…) and the bind-mount aliases a Docker storage driver leaves behind are skipped — they aren't disks, and reporting a volume nine times would let it raise nine alerts.

**Readings carry size, not just a percentage.** Alerts read *"[Vaier] NAS /volume1 is at 91% full (10.8 TiB, 1.0 TiB free)"* — the mount, the size and the free space, in the same binary units `df -h` prints. "NAS is at 88%" was a number nobody could act on.

This reuses the same SMTP configuration as the up/down machine alerts (Settings → *Email notifications*), so it needs no extra mail setup. With SMTP unconfigured, monitoring is silent.

**Threshold** — the alert fires when usage rises above the configured percentage (default **85%**). Adjust it in Settings; valid range is 1–99. This is the **fleet-wide fallback**: it governs every filesystem that hasn't been given one of its own.

**Watching and muting a filesystem** — no single rule fits a whole fleet, so each filesystem on each machine carries its own **watch**, set from its machine's **disk** entry in the **Explorer**: leave it watched at the fleet-wide threshold, give it a threshold of its own (`/` on the NAS is fine at 95%), or **mute** it entirely (a system partition that's near-full by design). The default is **watched, at the fleet-wide threshold** — nothing is ever silently unwatched, so a new volume that appears on a machine nags rather than hides, and muting is always something you chose. Only your exceptions are stored (in `vaier/config/disk-watches.yml`); no file means every filesystem is watched at the fleet-wide threshold. The Explorer and the alert email ask the same question of the same code, so they can never disagree about whether a disk is in trouble.

**Requirements** — a machine is watched only once it has a stored **host credential** (the same vault the web terminal uses) and SSH access enabled. A host that's unreachable or whose `df` fails is quietly skipped — never mistaken for a full disk. Machines without a stored credential or with SSH access turned off are left alone, so there's no failed-auth noise. To have the Vaier host itself watched, store a host credential for it just like any other machine.

**Early warning (disk-fill forecast)** — beyond the level threshold above, Vaier fits a line through each *filesystem's* recent `df` readings and projects its **runway**: the time until it reaches 100% at the current fill rate. When the runway drops under a fixed **24-hour horizon** *while the filesystem is still below its threshold*, admins get a one-time early-warning email naming the machine, the mount point, its current usage, the fill rate (%/h) and the projected runway. It's a trend alarm ("this filesystem *will be* full"), distinct from the level alarm above ("this filesystem *is* full"): a filling filesystem pages once as a forecast and then, when it crosses the threshold, the disk-pressure alert takes over — never both at once. An all-clear follows on a *genuine recovery* — it drains, or its fill slows so the projected runway rises back over the horizon, all while still below the threshold. The hand-off case (it simply climbs past the threshold) raises **no** all-clear: the disk-pressure alert already speaks for it, so you're never sent a contradictory "cleared" and "is full" at the same poll. A flat or draining filesystem has no forecast, and a failed `df` records no sample, so a transient blip can't fabricate a warning.

---

## Fleet backup

_(Enterprise)_ Vaier can back up the machines in your fleet to a [borg](https://www.borgbackup.org/) repository on a **backup server**, and get you there from nothing. Open the **Backups** tab in the console (Community instances see the tab in a locked state, since it's an Enterprise feature).

**Backup server** — A machine running a borg server that holds your repositories. The fleet has **at most one**. You designate which machine plays the role from its Inspector in the **Explorer** — a **Make this the fleet's backup server** action, offered on any machine but only while none is designated yet, with the coordinates form prefilled from that machine's own address. Adopt an existing borg server or have Vaier provision one from scratch (often a LAN server such as your NAS), and Vaier stands up a **pinned** borg-server container there. The Backups page then shows that one server **read-only** — with a link back to the Explorer to change the designation — and keeps the operational actions (Provision, Authorize host, download the setup script), the repositories, and the jobs. Where Vaier can drive docker over SSH it runs the setup for you (**Provision**); where it can't — a Synology NAS, for instance, doesn't expose a usable docker CLI over SSH — it **stages** an idempotent **setup.sh** on the host over SSH and hands you the one command to run (`sudo bash <path>`). The setup script is served behind admin login, so it is never curled onto the host; if Vaier can't reach the host at all, download setup.sh from the UI and copy it over yourself. Either way that's guidance, not a failure. **Authorize host** trusts a client machine's SSH key on the server exactly once, so backup jobs authenticate — borg runs on the client as the SSH user, not root, and that key has to be trusted server-side. Authorizing also **pins the server's host key** on that client, so borg's non-interactive SSH can verify the server with no trust-on-first-use — Vaier obtains the key over its own authenticated channel and installs it in the client's `known_hosts`. If the backup server goes quiet, Vaier emails every admin (and again, once, when it recovers).

**Backup repository** — Add one just by naming it on a backup server; its path derives as `base/<name>` (an advanced field lets you point at an existing, oddly-named repository instead). A repository or server **name is a safe identifier** (letters, digits, `_` and `-`) because it becomes a shell/path token in every borg command — type "NUC 02" and it's slugged to `NUC-02` as you go, and spaces or shell metacharacters are refused outright. As defense in depth Vaier also **single-quotes every borg path** (the repo URL, each `--restrict-to-path`) so a hand-edited config file can never inject a command. On create, Vaier generates a strong, shell-safe **passphrase** for you, shown once with a copy button — save it, since it's stored encrypted at rest and never shown back. Guided provisioning does the rest — **Check host readiness** reports whether borg is installed on a machine (and its version, and whether it's supported), whether the machine can reach the server, whether the **client's key is actually trusted** on the server (proved by running `borg info` for the repository from the client — reaching borg proves the key authenticated, even before the repository is initialised), and whether the client and server borg **versions are compatible** (borg 1.x and 2.x use incompatible repo formats, so their majors must match). Vaier knows a **provisioned** server's borg version from its pinned image; for an **adopted** server the version is unknown, so compatibility fails closed rather than guessing. A green borg and open port no longer read as ready when the key isn't trusted — authorize the host right there and re-check. When borg isn't installed on the machine, **Prepare client** installs it: Vaier detects the host's package manager (apt/dnf/yum/apk/pacman/zypper — Arch's package is `borg`, the rest ship `borgbackup`) and runs an idempotent install, itself over SSH where the SSH user has passwordless sudo, or by staging the script and handing you the one `sudo bash <path>` command where it doesn't. Prepare client also grants the SSH user **passwordless sudo for the borg binary alone**, which is what a job that backs up **as root** needs — so it does useful work on a machine that already has borg, and re-running it on a prepared host is how you add that grant. The rule is validated before it is installed, and it names borg's paths and nothing else (never a shell or an env wrapper — either would hand out root on the host). The install runs detached on the host (it can outlast the SSH exec cap); the **backend** watches it — and, the same way, a **server provision** and an **on-demand run** — and the browser learns each one finished over a single live server-sent-events stream, never by polling. And a run that would otherwise die with `borg: not found` is now refused up front — Vaier probes for borg before launching and records a clear "borg is not installed on _machine_ — run Prepare client" instead. There is no manual initialise step: a **backup run** creates its repository with `borg init` when it is absent, so a repository is never a hidden prerequisite.

When you authorize a host, Vaier writes a **restricted** `authorized_keys` entry on the backup server, not a bare key: the key is forced to `borg serve` and confined (`--restrict-to-path`) to exactly the repositories that host backs up to on that server, with no shell, pty or forwarding. So one compromised client can never read or delete another host's repositories. Because the restriction is derived from the host's jobs, **adding a repository for a machine means re-authorizing that host** to widen its access to the new repository — until you do, backups to the new repository are refused. If you authorize before creating any job, the key is confined to the repository root as a safe placeholder, and Vaier tells you to re-authorize once a job exists.

Authorizing also **pins the backup server's SSH host key** on the client, so borg never has to trust-on-first-use. A freshly provisioned borg server generates brand-new host keys, which either clashes with a client's stale `known_hosts` pin (`REMOTE HOST IDENTIFICATION HAS CHANGED`) or leaves a new client with no pin at all — and borg's non-interactive SSH fails rather than prompting. Because Vaier is the trusted broker (it reaches the server's machine with its own vault credential and pinned host key), it reads the server's public host keys authoritatively and installs them in the client's `known_hosts` — no `ssh-keyscan`, no `accept-new`. The setup script publishes those keys when it stands the server up, so a **provisioned** server is ready to pin; an **adopted** server (registered, never provisioned by Vaier) has no published host-key file, so authorizing still trusts the client key but reports that the host key wasn't pinned — run the setup script once on the server, or pin it manually, then authorize again.

**Backup jobs** — Give each machine a job: which machine (by name), which backup repository, the source paths to back up, exclude patterns, retention (`keepDaily` / `keepWeekly` / `keepMonthly`), compression (default `zstd,6`), whether the job is enabled, and whether it backs up **as root**.

**Back up as root** — borg runs on the machine as the SSH user (e.g. `ubuntu`), not root, so **every file in the job's source paths that this user cannot read is silently skipped**: the run still writes an archive and settles to *warnings*, and the holes are easy to miss. Container volumes are the usual victims — a mosquitto database owned `1883:1883` mode `0600`, a pihole file owned `root:root`. Chmod'ing files one by one is whack-a-mole, since every new container volume is a fresh silent hole. Tick **Back up as root** on the job and its borg runs under `sudo` on the machine instead, reading everything. It is **opt-in** — a job never escalates itself, and an existing job keeps running as the SSH user until you turn it on. It needs the machine to allow passwordless sudo *for borg only*: **Prepare client** installs exactly that grant (a sudoers rule naming the borg binary and nothing else — never a shell or `env`), so run it once on a machine before turning the toggle on. Be clear-eyed about what you are granting: **a passwordless `sudo borg` is root-equivalent**, and scoping the rule to the borg binary does not change that — a borg running as root can read and write any file on the machine, and its `--rsh` flag runs a command of the caller's choosing as root. The narrow rule keeps the grant auditable and honest about its purpose; it is not a sandbox. The real control is that the toggle is per-job and opt-in: turning it on makes Vaier's stored SSH credential for that machine as powerful as root, so grant it only where that is acceptable. **Check host readiness** shows a *borg can run as root* row for any job with the toggle on — and only for those, since a job that runs as the SSH user doesn't need the grant — with a **Prepare client** button right on that row when it's missing.

**Running** — Run any job on demand with **Run now**, or let the **nightly schedule** run every enabled job once a day. The schedule hour (0–23, default 2) is set on the ungated **Settings** surface, alongside the disk-pressure threshold. Each execution is a **backup run** with a status (running, success, warnings, failed, or unknown); a failed run emails every admin, reusing the same SMTP configuration as the other alerts. A run that completes but skips some files (borg exit 1, e.g. files unreadable by the SSH user) settles to **warnings** — its archive was still written, so it is not a failure and does not page anyone. If the skipped files are root-owned data you do want in the archive, that is what the job's **Back up as root** toggle is for. A run that ended in warnings, failure, or an unknown outcome carries its **run diagnostics** — the lines borg actually reported, such as `/home/ubuntu/mqtt/data/mosquitto.db: open: [Errno 13] Permission denied` — and its status badge on the job card **opens** to show them, so *which* files were skipped and why is answered in the UI instead of by reading a YAML file on the server. borg's machine-readable statistics are stripped out, so what you read is only the part meant for a human; a clean run has no diagnostics and its badge stays a plain badge. A run starts detached on the host and its outcome is **pushed to the browser over the same SSE stream** as the prepare-client install — the UI shows *Running*, then updates that job's card the moment the backend sweep sees it settle, never by polling. Vaier keeps its small per-host working state — the borg passphrase file and each run's result/log — in `~/.vaier-backup` on the target machine (the SSH user's own home, so it works whether or not that user is root), falling back to `/tmp/vaier-backup` if the home directory can't be determined.

**Archives** — Browse the point-in-time archives a repository holds, each with its name and time. **Restore from the UI is not yet available** — recover with the borg CLI against the repository for now.

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
