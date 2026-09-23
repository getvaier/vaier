# Authentication and access

Back to [README](../README.md).

How sign-in is set up, how roles and per-service access rules work, and how the Users page manages identities.

---

## Setting up sign-in

Vaier delegates authentication to Google and/or GitHub — or, until you have registered either, to a **first-run password** of its own — and owns authorization itself. oauth2-proxy (mandatory infrastructure — it always starts with the stack) is the forward-auth gatekeeper; behind it, the **Dex** identity broker federates whichever provider(s) you configure:

```
Traefik → oauth2-proxy → Dex ─┬→ Google
                              ├→ GitHub
                              └→ local (the first-run password, while no provider exists)
```

### The first-run password, before you have registered anything

Registering an OAuth app is the slowest thing between a fresh server and a working console, and it is a poor first task: you don't yet know whether the rest of the stack came up. So a stack with **no provider configured at all** doesn't refuse to start — it opens one door, for one account.

`dex-init` mints a **first-run password** and hands it to Dex's own local connector, bcrypt-hashed, as the single account it knows. The account's email is `VAIER_ADMIN_EMAIL` if you set one, otherwise your `ACME_EMAIL` — your own address, which matters for the handover below — and only as a last resort `admin@yourdomain.com`. Vaier reads the password back and prints it at the bottom of its own boot log:

```bash
docker compose logs vaier
```

The bordered block there gives the console URL, the email and the password. Open the console, press **Sign in with the first-run password** — the only button on the sign-in page while no provider exists — and sign in.

That sign-in is the **first-run claim**: while the access store holds no admin, an identity arriving through the local connector *becomes* the admin, so you land straight in the console with nothing pre-seeded. Once an admin exists the claim is spent; a local sign-in for some other email lands as **pending** like anyone else.

The password is minted once and kept across restarts in `./vaier/config/first-run-password` (owner `1000`, mode `0600`), so `docker compose up -d` doesn't change it under you. Reading it means having a shell on the server, which is the boundary the door leans on — anyone who can read that log could already read every secret in the stack.

**The door closes on its own.** Add a provider from **Settings → Sign-in** (below) and the first-run password keeps working beside it — the sign-in page shows both — until an **admin signs in through the new provider**. That sign-in proves the provider works, and Vaier then closes the door in the background: `dex-init` writes no password database, deletes the file, and the button leaves the sign-in page. Nothing locks you out between the save and that sign-in. Add a provider through `.env` instead and the door closes the moment the stack is brought up with it, as it always has.

**Handing over to a provider.** Sign in with Google or GitHub under the *same* email the first-run account used, and you are the admin you already were — which is why the account defaults to `ACME_EMAIL`. If you sign in under a different address, set `VAIER_ADMIN_EMAIL` to it before bringing the stack up: once a provider exists and every admin is a first-run account that can no longer sign in, Vaier restores that email to admin on startup. With neither, Vaier's log says so as an error, naming the fix, rather than leave you to find a console nobody can open.

### Registering Google or GitHub

Each provider is independently optional — configure Google, GitHub, both, or (until you want to invite anyone) neither. `dex-init` only adds a connector for a provider once both its client id and its client secret are set. With only one provider configured, Dex skips its connector-selection screen and sign-in goes straight there.

Configure the providers you want. The sign-in page offers a button per configured provider, so an install with only Google credentials never shows a GitHub button. Both providers hand the user back to **Dex** (not oauth2-proxy), so register their redirect URIs at Dex:

- **Google** — create an OAuth 2.0 Web application client in the [Google Cloud console](https://console.cloud.google.com/apis/credentials) and set its authorized redirect URI to `https://dex.yourdomain.com/callback`.
- **GitHub** — register an OAuth App in [GitHub developer settings](https://github.com/settings/developers) and set its authorization callback URL to `https://dex.yourdomain.com/callback`. Any GitHub account may sign in — Vaier's pending → admin-approval gate decides who's actually let in.

Then hand Vaier the client id and secret, one of two ways.

**From Settings → Sign-in.** The section shows the exact redirect URI with a copy button, a link to each provider's console, and a client id and client secret field per provider. **Save** applies it there and then and waits for the outcome — a few seconds:

1. Vaier writes the pair to `./vaier/config/sign-in-providers.env` (owner `1000`, mode `0600`), together with whether the first-run door is held open.
2. It re-runs `dex-init`, waits for it to exit, and only on success restarts Dex; then the same for `oauth2-proxy-init` and oauth2-proxy. Both renderers read the file line by line and strip every value to letters, digits, `.`, `_` and `-` — they never `source` it, because they run as root — and Vaier refuses any id or secret outside that charset before writing anything.
3. If a renderer fails, its service is **not** restarted: the running one keeps the config that works. Vaier puts the previous file back, re-renders from it, and shows the renderer's own error under the Save button.

The secret is write-only: no response ever carries it, and the field is empty every time you open Settings.

Vaier reaches `dex-init` and `oauth2-proxy-init` through `docker-proxy`, whose template denies every container start **except those two, by exact name**. Creating containers stays denied, so a start can only re-run the fixed renderer each was created with — which is why this is the one start Vaier is allowed.

**From `.env`.** Set `VAIER_OIDC_GOOGLE_CLIENT_ID` / `VAIER_OIDC_GOOGLE_CLIENT_SECRET` and/or `VAIER_OIDC_GITHUB_CLIENT_ID` / `VAIER_OIDC_GITHUB_CLIENT_SECRET` and run `docker compose up -d`. **`.env` wins**: a provider whose id *and* secret are both set there ignores its lines in the Settings file, and Settings shows it read-only as *set in .env*. An install that never uses Settings behaves exactly as before.

Set `VAIER_ADMIN_EMAIL` to the email that should become the first admin — optional, but it is also what names the first-run account, and it is the identity Vaier restores to admin whenever the store has none. Three secrets are **generated for you by `install.sh`** into `.env` — you don't author any of them: the oauth2-proxy session cookie secret (`VAIER_OAUTH2_COOKIE_SECRET`), the oauth2-proxy↔Dex shared secret (`VAIER_DEX_CLIENT_SECRET`), and the CrowdSec bouncer API key (`VAIER_CROWDSEC_BOUNCER_KEY`).

If you hand-write `.env`, generate all three — a missing one now stops `docker compose` at config-parse time with a message naming the variable, rather than starting the stack in a broken state. That guard matters most for the bouncer key: its forward-auth sits ahead of every other middleware and fails closed, so an empty value takes down *every* route, console included — not just the service it belongs to.

```bash
printf 'VAIER_DEX_CLIENT_SECRET=%s\nVAIER_OAUTH2_COOKIE_SECRET=%s\nVAIER_CROWDSEC_BOUNCER_KEY=%s\n' \
  "$(openssl rand -hex 32)" "$(openssl rand -base64 32)" "$(openssl rand -hex 32)" >> .env
```

Re-running `install.sh` in place does the same thing and is the simpler answer — it tops up whatever your `.env` is missing and never overwrites a value you set.

Once `docker compose ps` shows every service as `Up`, open `https://vaier.yourdomain.com` and sign in with the account you set as `VAIER_ADMIN_EMAIL`. Vaier seeds that identity as the first admin, so you land straight in the console. (If you came in through the first-run password, you are already the admin and nothing is seeded.)

Anyone else who signs in for the first time is recorded as a **pending** access request — authenticated but blocked until you approve them on the **Users** page. Promote them to **user** (or **admin**) there.

The oauth2-proxy sign-in and error pages — and the Dex broker's own screens — all share Vaier's dark theme, so the sign-in hand-off (Google or GitHub) feels seamless end to end.

---

## Access management

Manage who can sign in from the **Users** page: each signed-in identity — Google, GitHub, or the first-run account — is an access entry with a **role** (pending → user → admin) and free-form per-service **access groups**. Approve or deny newcomers, promote admins, and gate individual services by group. Each person's card shows their provider photo (GitHub picture, else Gravatar, else a coloured monogram) with a small corner glyph for the connector they last signed in with — Google, GitHub, or the first-run password.

When someone signs in for the first time, Vaier records them as a **pending** access request (authenticated but blocked) and denies access until an admin approves them. The moment that pending entry is created, Vaier emails every admin so the request doesn't sit unseen — the mail names the email and links straight to the **Users** page to approve or deny. It reuses the same SMTP configuration as the other alerts, so with SMTP unconfigured (or no admins to notify) it stays silent, and the send is fire-and-forget so it never slows the sign-in check.

Admin-vs-user is decided **only by the role** (pending → user → admin) — promote an entry with the role control. **Access groups** are a separate, per-service concept: free-form tags (e.g. `devs`, `family`) that gate individual services. Each Social service can carry an **access rule** — a set of *allowed groups* — and a user reaches the service if their entry holds **at least one** of them (any-of). Admins reach everything; pending identities reach nothing. The names `admins` and `users` are never access groups; the group picker won't suggest or accept them.

The console is admin-only, so Vaier keeps a **last-admin protection** invariant: the access store always holds at least one admin. Revoking or demoting the sole remaining admin is refused (the Access page disables those controls with an inline note, and the API answers `409 Conflict`), and on startup the configured administrator (`VAIER_ADMIN_EMAIL`, when one is set) is restored to admin whenever no admin exists — promoting an existing entry in place or creating one — so the console can never be locked out for everyone.

Vaier also captures each identity's Google **display name** (the provider's `name` claim, forwarded by oauth2-proxy) and shows it on the **Users** page with the email beneath it — so an admin recognises who's asking by name, not just by address. A pre-approved entry stays nameless until its first sign-in fills the name in; later sign-ins keep it current, and it's never wiped if a sign-in arrives without one. The same captured name follows the identity into the Vaier console — which always runs on Social login — greeting them in the topbar with their provider photo when one is available (the same GitHub-picture-else-Gravatar chain as the Users cards), falling back to their name text (or email until a name is known) when no photo loads.

The **Users** page is this single list of social identities. Vaier no longer manages local password accounts and has no self-service profile page — each identity's name and email are owned by Google and shown read-only; only the role and access groups are edited here.

Social login is the sole runtime auth gateway: **Authelia has been fully removed** — both the running service and the last of its Java code — and every gated service authenticates via Google or GitHub. There is no `authelia` auth mode; the two modes are Public and Social.

---

## Per-service auth mode

Each published service card carries an **auth mode** picker — **Public** (no sign-in) or **Social** (Google or GitHub sign-in via oauth2-proxy, with Vaier deciding who's approved). Change it any time; the change rewrites only that route's Traefik middleware chain.

## Per-service access rules

For a **Social** published service you can restrict *which* signed-in users get through. Open the published service's entry in the **Explorer** and use the **Allowed groups** chip picker to name the groups allowed to reach it. Suggestions come from the groups already assigned to your access entries, and you can free-type a new group name. Leave it empty and any signed-in, approved user can reach the service; add one or more groups and only users holding at least one of them (plus every admin) get in. A service with a non-empty rule shows a **restricted** badge so you can see at a glance it isn't open to every approved user. Rules apply only in Social auth mode — switch a service to Public and the control disappears.

Rules are keyed by the service's host, so path-scoped services that share one subdomain currently share a single rule (a known limitation for now).

## Service credentials

Some services keep a login of their own behind social login — openHAB's API, say. Rather than tell everyone its password, give the service a **service credential**: a username and password Vaier hands it for every person it lets in. In the **Explorer**, open the published service and fill in **Service credential** under Allowed groups. It is offered only in Social auth mode, because only there does Vaier's own check run on each request.

- The **shared** credential is used for everyone who has no credential of their own.
- A **personal** credential is for one access entry — Turid gets her own openHAB user, and the service can tell her apart. Pick the person, then their username and password.
- With neither, Vaier hands nothing on, and whatever the browser or app sent reaches the service as before.

**How it works.** When `/authz/verify` allows a request, its answer carries `Authorization: Basic …` for that person on that host (their own credential first, else the shared one), and the `vaier-authz` middleware lists `Authorization` among the headers Traefik copies onto the request to the backend. A refused request never carries one — Traefik shows that answer to the browser. Traefik strips every listed header from the forwarded request even when the check returns none, so when no credential applies Vaier hands back the `Authorization` the client itself sent; a service people sign in to by hand keeps working.

**One account per credential.** The service sees exactly the login Vaier handed it: everyone on the shared credential is the same user to it, with that user's rights. Give someone a personal credential when the service must tell them apart or grant them less.

**openHAB** accepts basic auth only once it is allowed: **Settings → API Security → Allow Basic Authentication**. A credential the service rejects answers 401, which the social chain turns into the sign-in page — if signing in loops, check the username and password.

**Storage.** Credentials live in `./vaier/config/service-credentials.yml` (mode `0600`), passwords sealed by the same cipher as the host credentials. Vaier reads the file once and answers every request from memory. Passwords are write-only: the console shows only the username and a Clear or Remove. Unpublishing a service's last route forgets its credentials, and revoking a person forgets theirs. Like access rules, credentials key on the service's host, so path-scoped services that share a host share them.

## What a service asks for by itself

Vaier looks at each published service's own sign-in, so the service pane can tell you what to do about it. It asks the **backend itself** — the address and port the route points at, over the tunnel, exactly where the version probe goes — never the public name, which would only show Vaier's own sign-in. One plain GET of where a visitor lands (the path prefix, else the root redirect, else `/`), three-second timeout, and at most one redirect followed, and only back to the same backend. Vaier reads the answer as one of:

- **Basic auth** — a `401` with a `WWW-Authenticate: Basic` challenge. A service credential can answer it; with none set, the pane says so and points at the field.
- **Another challenge** — a `401` with `Bearer`, `Digest` or anything else. Vaier names it and says it cannot sign in for people.
- **Its own sign-in page** — a password field, a page that offers a sign-in, or a redirect to a sign-in path. People sign in to it after Vaier's; the pane says so in one quiet line.
- **None** — a real page with nothing sign-in-like on it.
- **Unknown** — no answer, an error, a redirect elsewhere, a page that is only a shell a script fills in, or anything that is not a web page. Unknown is never read as none.

**OpenSprinkler** is recognised by its root page: `ipas=0` means it still enforces its own password, and behind Vaier's sign-in the pane suggests turning on **Ignore password** in the controller's options.

The look rides the state-refresh round that already runs every 30 seconds: a service is looked at when it is first seen, again after an edit, and otherwise every ten minutes. When what a service asks for changes, the pane updates by itself. Nothing is stored — a restart simply looks again.
