# Advanced topics

Back to [README](../README.md).

This document covers configuration and workflows beyond the basic Quick Start. If you're just getting started, the README is enough.

---

## Environment variables

| Variable | Required | Description |
|----------|----------|-------------|
| `VAIER_DOMAIN` | Yes | Base domain (e.g. `yourdomain.com`) |
| `ACME_EMAIL` | Yes | Email for Let's Encrypt notifications |
| `VAIER_OIDC_GOOGLE_CLIENT_ID` | At least one provider required | Google OAuth 2.0 client id — a **Dex** connector uses it for sign-in. Register its redirect URI at Dex (`https://dex.<domain>/callback`). Only rendered as a connector — and only offered as a sign-in button — when both `VAIER_OIDC_GOOGLE_CLIENT_ID` and `VAIER_OIDC_GOOGLE_CLIENT_SECRET` are set |
| `VAIER_OIDC_GOOGLE_CLIENT_SECRET` | At least one provider required | Google OAuth 2.0 client secret. Written by `dex-init` to a mode-0600 secret file, never inlined |
| `VAIER_OIDC_GITHUB_CLIENT_ID` | At least one provider required | GitHub OAuth App client id — a **Dex** connector uses it for sign-in. Register its callback URL at Dex (`https://dex.<domain>/callback`). Any GitHub account may sign in; the pending → admin-approval gate decides access. Only rendered as a connector — and only offered as a sign-in button — when both `VAIER_OIDC_GITHUB_CLIENT_ID` and `VAIER_OIDC_GITHUB_CLIENT_SECRET` are set |
| `VAIER_OIDC_GITHUB_CLIENT_SECRET` | At least one provider required | GitHub OAuth App client secret. Written by `dex-init` to a mode-0600 secret file, never inlined |
| `VAIER_ADMIN_EMAIL` | No | The email seeded as the first **admin** access entry, and restored to admin on startup whenever no admin remains, so the console can't lock everyone out |
| `VAIER_OAUTH2_COOKIE_SECRET` | Auto | oauth2-proxy session cookie secret — generated automatically into `.env`, not operator-authored |
| `VAIER_DEX_CLIENT_SECRET` | Auto | oauth2-proxy↔Dex shared client secret — generated automatically into `.env`, not operator-authored |
| `VAIER_CROWDSEC_BOUNCER_KEY` | Auto | API key the CrowdSec bouncer authenticates to the Security Engine with — generated automatically into `.env`, not operator-authored |
| `VAIER_PUBLIC_HOST` | No | Public hostname of this server, when not on EC2 (where it is read from instance metadata). Used to tell you where this server is |
| `VAIER_PUBLIC_IP` | No | Public IPv4 of this server, when not on EC2. This is the address the boot-time wildcard check expects `*.<domain>` to resolve to |
| `VAIER_SERVER_LAN_CIDR` | No | CIDR Vaier treats as "the LAN this server sits on", so machines in it can be registered as LAN servers with no relay peer (see below). On EC2 the server's own **subnet** CIDR (a default-VPC subnet is a `/20`, one per AZ) is auto-detected from instance metadata; this env var **overrides** that, on EC2 too — most usefully to widen it to the whole VPC (e.g. `172.31.0.0/16`) so machines in any subnet/AZ qualify. Off EC2 it's the only way to set the value. Passed through in `docker-compose.yml` ([#204](https://github.com/getvaier/vaier/issues/204)). |
| `WIREGUARD_CONFIG_PATH` | No | WireGuard config dir (default: `/wireguard/config`) |
| `WIREGUARD_CONTAINER_NAME` | No | WireGuard container name (default: `wireguard`) |
| `TRAEFIK_CONFIG_PATH` | No | Traefik dynamic config dir (default: `/traefik/config`) |
| `TRAEFIK_API_URL` | No | Traefik API URL (default: `http://traefik:8080`) |

Each identity provider is independently optional — configure Google, GitHub, or both; `dex-init` only renders a connector when its client id and its client secret are both set. At least one provider must be fully configured, or `dex-init` fails fast (naming exactly which variables are missing) rather than starting Dex with no way to sign in. With only one provider configured, Dex's connector-selection screen is skipped and sign-in goes straight to that provider.

On EC2, the public address is detected from instance metadata. On other hosts, set `VAIER_PUBLIC_HOST` or `VAIER_PUBLIC_IP` in `.env` — without one, Vaier can still check that `*.<domain>` resolves, but not that it resolves *here*.

---

## Secrets on disk

Vaier writes new secret files at mode `600` (`rw-------`). For an updated deployment you should also tighten the existing files and the surrounding directories on the host:

```bash
chmod 600 .env production.env 2>/dev/null
chmod -R go-rwx vaier/ oauth2/ wireguard/ traefik/
```

Files Vaier creates and protects:

| File | Contents |
|------|----------|
| `vaier/config/vaier-config.yml` | Domain, SMTP settings **and the SMTP password** — owner-only |
| `vaier/config/access.yml` | Access store — the known identities, their roles and access groups |
| `oauth2/config/client-secret` | Google OAuth client secret (written mode-0600 by `oauth2-proxy-init`) |

The `.env` file you create yourself — keep it at mode `600`.

---

## Publishing a service from a LAN server (Docker optional)

A *LAN server* is any machine that isn't itself a VPN peer — a NAS, a printer, IPMI, an extra Docker host — sitting either on a relay peer's LAN or **in the Vaier server's own subnet** (an AWS VPC, say). Register it once on the Machines page, then publish its services.

1. Make sure the LAN server's address is covered by something Vaier routes to:
   - **Behind a relay peer:** Vaier reads the network the peer sits on over the SSH connection it already holds and offers it on that machine's page — "Colina 27 sits on 192.168.1.0/24" — so the usual answer is one click, not a CIDR. Set `lanCidr` (e.g. `192.168.3.0/24`) by hand only for a network Vaier cannot see, such as a second subnet the same machine fronts: the machine's edit form, under **Advanced → Network behind it**.
   - **In the Vaier server's own subnet:** on EC2 the server's own subnet CIDR is auto-detected from instance metadata — nothing to configure if the machine is in the *same* subnet/AZ. To cover the whole VPC (machines in other AZs/subnets), or off EC2, set `VAIER_SERVER_LAN_CIDR` (see above; note the [#204](https://github.com/getvaier/vaier/issues/204) caveat). Either way, make sure the target machine's security group / firewall allows inbound from the Vaier server.
2. (Docker only) On the LAN server, expose its Docker socket on TCP 2375 reachable from whatever routes to it — the relay peer, or (for a server-anchored machine) the Vaier server itself (no TLS in V1; firewall it to the LAN/VPC range). The Add Machine modal shows a one-liner `curl https://vaier.<domain>/lan-servers/docker-setup.sh | sudo bash -s -- --port 2375` — an idempotent script that handles both native (systemd) and Snap Docker installs.
3. In Vaier → Machines, click the **+** FAB, pick **LAN server**, enter a name and the LAN address (must fall inside a relay's `lanCidr` or the Vaier server's subnet — that's all the modal asks for), and toggle Docker on/off (Docker port defaults to 2375).
4. With Docker on, discovered containers appear in Services → discovered list and route through the relay (or directly via the Vaier server) when published. To publish a native (non-container) service on any LAN server — Docker on or off — go to Services → **+ Publish LAN service**, pick the machine from the dropdown, and enter port/subdomain/protocol — Vaier writes a Traefik route whose backend is `http(s)://<lanAddress>:<port>`. No DNS step: the name already resolves under your one `*.<domain>` record.

> **V1 limitations** (tracked in [#204](https://github.com/getvaier/vaier/issues/204)): the "Vaier server's own subnet" path routes only the Vaier-side containers (the Vaier app, Traefik) to that subnet — enough to register, scrape, and publish those machines. It does not yet let your *split-tunnel server peers* reach the subnet (full-tunnel mobile/Windows clients already can), and `VAIER_SERVER_LAN_CIDR` isn't yet passed through in `docker-compose.yml`, so on the stock Compose stack you're limited to the IMDS-auto-detected subnet CIDR.
