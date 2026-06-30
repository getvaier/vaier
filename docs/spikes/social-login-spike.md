# Spike: Google social login to replace Authelia

**Status:** spike / exploratory — not implemented. Captures architecture, a runnable
config scaffold, and the authorization model decided with the maintainer.

**Goal:** let a user sign in with Google (and later GitHub/etc.), then have an admin
grant them a **role** before they get any access. Replace Authelia's file/LDAP-only
first factor, which cannot do social login at all.

**Decided requirements:**
- Role model: `pending → user → admin`. A fresh Google identity lands as **pending**
  (authenticated but blocked, "awaiting approval"). An admin promotes to **user**
  (reaches approved services) or **admin** (administers Vaier).
- Authorization scope: the role/group gates **both** the Vaier console **and**
  per-service access (`group family → [plex, photos]`, `group admins → *`).

---

## The forcing finding

Authelia today plays two roles (see the integration map): it is the **forward-auth
gateway** Traefik calls (`http://authelia:9091/api/verify`) for both Vaier's own
write endpoints and every published service with `requiresAuth=true`. Its identity
is asserted to Vaier via `Remote-User` / `Remote-Groups` headers. Vaier itself has
**no in-process auth** — it is purely the control plane that *generates* Authelia +
Traefik config.

Stack the four requirements together and the topology is essentially forced:

| Requirement | Consequence |
|---|---|
| Social login (Google) | Authelia is out as the authenticator (file/LDAP only) — need oauth2-proxy or a full IdP |
| Arbitrary per-service **group** rules | *Something in the request data-path must know Vaier's `email → groups` map* |
| No database | Rules out heavy IdPs (Authentik/Keycloak need Postgres) without a big departure |
| Gateway independent of Vaier (today's property) | In tension with the row above |

The middle two collide: arbitrary per-service group authorization needs a runtime
component that knows each user's Vaier-assigned groups. Google doesn't supply them;
oauth2-proxy can't invent them. So either **Vaier injects the decision at request
time** (Vaier enters the data-path) or you adopt a **heavier IdP that loads
Vaier-generated group rules** (Authelia could — but it can't do social).

There is no option that keeps *all four*. The spike picks the one that best fits
Vaier's identity and is honest about the cost.

---

## Options considered

### Option A — oauth2-proxy replaces Authelia; tiers only
oauth2-proxy does Google authn + Redis session + domain-wide cookie. Authorization
is its global `authenticated-emails-file` (+ a second admin-only instance for the
console). **Pros:** no DB, Vaier stays control-plane, gateway stays independent of
Vaier. **Cons:** authorization is coarse — one allow-list per proxy instance, so
arbitrary `group → services` needs one proxy per group. Fine for `pending/user/admin`
(≈2 access tiers), painful for arbitrary groups. **Fails the per-service-group requirement.**

### Option B — full IdP (Authentik / Keycloak) replaces Authelia
Social + groups + per-app authz + Traefik forward-auth, all in one, maintained.
**Cons:** needs Postgres (+Redis+worker) → breaks Vaier's "no database" tenet;
heaviest infra; Vaier would drive it via API instead of generating YAML.

### Option C — oauth2-proxy (authn) + Vaier authz endpoint (authz) ✅ recommended
- **oauth2-proxy** does *only* authentication: the Google dance, the Redis-backed
  domain-wide SSO session, and injects `X-Auth-Request-Email` downstream.
- **Vaier** owns authorization (it's literally Vaier's domain): a file-based
  `email → role + groups` store, the pending/approve flow, and a small forward-auth
  endpoint `GET /authz/verify` that answers "may *this email* reach *this host*?".
- Traefik chains the two middlewares per protected route: `oauth2-proxy → vaier-authz → service`.

**Why C:** it's the smallest conceptual leap from today (Vaier already reads
forward-auth headers; now it also answers one), keeps the file-based / no-DB model
(the access store is a sibling of today's `users_database.yml`), and supports
arbitrary per-service groups because the decision lives where the data lives.

**The cost (stated plainly):** Vaier's authz endpoint is now in the request path for
protected services, so **Vaier being down means protected services fail authz** — a
regression from Authelia's independence. Mitigations: the decision is a trivial
in-memory map lookup (Vaier "up" is the only dependency, no I/O per request);
oauth2-proxy still independently handles authn so sessions/login survive Vaier being
down (only the final yes/no fails); and the existing `vaier-offline` fallback already
acknowledges Vaier can blip. If that coupling is unacceptable, fall back to Option B.

---

## Scaffold (Option C)

### 1. Google Cloud setup (operator, one-time)
1. Google Cloud Console → **APIs & Services → Credentials → Create OAuth client ID → Web application**.
2. **Authorized redirect URI:** `https://login.<VAIER_DOMAIN>/oauth2/callback`
3. Copy the **Client ID** and **Client secret** into `.env`:
   ```ini
   VAIER_OIDC_GOOGLE_CLIENT_ID=...
   VAIER_OIDC_GOOGLE_CLIENT_SECRET=...
   ```
4. A cookie secret is auto-generated by an init container (no operator env var —
   consistent with the redis-password pattern).

### 2. docker-compose additions

```yaml
  # Generates oauth2-proxy's cookie secret once (like redis-init), no operator input.
  oauth2-proxy-init:
    image: busybox:1.37.0
    container_name: oauth2-proxy-init
    command:
      - sh
      - -c
      - |
        test -s /secrets/oauth2-cookie-secret && exit 0
        umask 077
        od -An -tx1 -N 32 /dev/urandom | tr -d ' \n' > /secrets/oauth2-cookie-secret
    volumes:
      - ./oauth2/config:/secrets
    restart: "no"

  oauth2-proxy:
    image: quay.io/oauth2-proxy/oauth2-proxy:v7.6.0
    container_name: oauth2-proxy
    depends_on:
      oauth2-proxy-init:
        condition: service_completed_successfully
      redis:
        condition: service_started
    command:
      - --provider=google
      - --client-id=${VAIER_OIDC_GOOGLE_CLIENT_ID}
      - --client-secret=${VAIER_OIDC_GOOGLE_CLIENT_SECRET}
      - --cookie-secret-file=/secrets/oauth2-cookie-secret
      - --cookie-domain=.${VAIER_DOMAIN}          # domain-wide SSO cookie
      - --whitelist-domain=.${VAIER_DOMAIN}
      - --email-domain=*                          # authN is open; authZ is Vaier's job
      - --upstream=static://202                    # forward-auth only, no proxying
      - --reverse-proxy=true
      - --set-xauthrequest=true                    # emit X-Auth-Request-Email/User
      - --session-store-type=redis
      - --redis-connection-url=redis://redis:6379
      # NOTE: point redis password in via --redis-password-file in real impl
      - --http-address=0.0.0.0:4180
    volumes:
      - ./oauth2/config:/secrets:ro
    restart: unless-stopped
    labels:
      - traefik.enable=true
      # The login/callback portal lives on login.<domain> (replaces Authelia's host)
      - traefik.http.routers.oauth2-proxy.rule=Host(`login.${VAIER_DOMAIN}`)
      - traefik.http.routers.oauth2-proxy.entrypoints=websecure
      - traefik.http.routers.oauth2-proxy.tls.certresolver=letsencrypt
      - traefik.http.services.oauth2-proxy.loadbalancer.server.port=4180
    networks:
      - vaier-network
```

### 3. Traefik forward-auth chain (generated by Vaier, replaces `vaier-auth`)

```yaml
http:
  middlewares:
    # Stage 1: authentication (Google). 401 -> redirect to the login portal.
    oauth2-authn:
      forwardAuth:
        address: http://oauth2-proxy:4180/oauth2/auth
        trustForwardHeader: true
        authResponseHeaders:
          - X-Auth-Request-Email
          - X-Auth-Request-User
    # Stage 2: authorization (Vaier). Reads X-Auth-Request-Email + X-Forwarded-Host,
    # returns 200 (allow) / 403 (pending or not in the service's group).
    vaier-authz:
      forwardAuth:
        address: http://vaier:8080/authz/verify
        trustForwardHeader: true
        authResponseHeaders:
          - Remote-User
          - Remote-Email
          - Remote-Groups
```

A protected route then carries `middlewares: [oauth2-authn, vaier-authz, vaier-errors]`.
`TraefikReverseProxyAdapter.ensureAuthMiddlewareExists()` and the per-route
attachment in `addReverseProxyRoute()` change to emit this two-link chain instead of
the single `auth-middleware`. oauth2-proxy's 401-on-deny needs Traefik's
`forwardAuth` to surface the `Location` redirect — handle the unauthenticated case by
routing browser requests through oauth2-proxy's own sign-in path
(`/oauth2/start?rd=...`), same pattern as Authelia's `?rd=`.

---

## Vaier-side authorization model (the genuinely new, testable code)

File-based, a sibling of `users_database.yml`. **Access store** at
`${VAIER_CONFIG_PATH}/access.yml`:

```yaml
# An "access entry" per known identity.
entries:
  you@gmail.com:
    role: admin            # admin | user | pending
    groups: [admins]
  friend@gmail.com:
    role: user
    groups: [family]
  newcomer@gmail.com:
    role: pending          # authenticated by Google, no access yet
    groups: []
# Per-service required group (which group may reach a published service).
# Mirrors / feeds the existing per-service requiresAuth toggle (#86/#88 territory).
serviceGroups:
  plex.<domain>: family
  vaier.<domain>: admins   # console requires admin
```

**Flow when a new Google identity appears:** `GET /authz/verify` sees an email with
no entry → auto-creates a `pending` entry (so it shows up for the admin to action) →
returns 403 with an "awaiting approval" page. Admin opens *Settings → Access*,
promotes to `user`/`admin` and assigns groups. Next request passes.

**Hexagonal surface (implement TDD-first per project rules):**

| Layer | Element |
|---|---|
| Domain | `AccessEntry` (email, `Role` enum `PENDING/USER/ADMIN`, groups); decision predicate `mayAccess(host)` lives **on the entity**, not the service |
| Port (driven) | `ForPersistingAccessEntries` (load/list/upsert/delete), `ForResolvingServiceGroup` |
| Use cases | `VerifyAccessUseCase`, `ListAccessEntriesUseCase`, `GrantRoleUseCase`, `AssignGroupsUseCase` |
| Service | extend `UserService` (same domain concept — identities) — **do not** add a new `*Service` |
| Adapter | `AccessFileAdapter` (SnakeYAML, mirrors `AutheliaUserAdapter`) |
| Web | `AuthzRestController` → `GET /authz/verify` (forward-auth) + `GET/PATCH /access` (admin) |
| UI | *Settings → Access* table: pending rows highlighted with **Approve → user/admin** + group chips |

`GET /authz/verify` is the only data-path endpoint — keep it allocation-light and
backed by an in-memory snapshot refreshed on file change.

---

## What this spike does NOT settle (open questions)
- **Availability coupling** of the Vaier-in-path authz stage — accept, or take Option B?
- **2FA/TOTP**: Authelia gave it free; Google login covers MFA for Google accounts,
  but local/non-Google identities would have none. Probably fine if *all* logins are social.
- **Bootstrap admin**: with social login there's no password file — instead seed the
  first admin email from `.env` (`VAIER_ADMIN_EMAIL`) so the owner isn't locked out as `pending`.
- **Multiple providers** (GitHub, Microsoft, …): oauth2-proxy is single-provider per
  instance, so adding providers is an authentication-layer change only — **the authz core
  does not change**, because it keys on the user's email, which is provider-agnostic.
  Recommended path: put **Dex** (an OIDC broker) between oauth2-proxy and the upstream
  providers. Dex federates many connectors (Google, GitHub, Microsoft, GitLab, generic
  OIDC/SAML) behind one OIDC endpoint plus a "choose how to sign in" screen, is
  config-file driven, and needs **no database** — so Vaier would generate its connector
  config the same way it generates Traefik/Authelia config today. Alternatives: one
  oauth2-proxy instance per provider behind a chooser (clunky session coordination), or a
  full IdP (Authentik/Keycloak, Postgres-backed — only if Option B is taken anyway).
  Two rules when implemented: (1) identity = email, so the same person on two providers
  with different emails is two access entries; (2) accept only providers that return a
  **verified** email claim, so nobody can self-assert another person's address.
- **Migration** off Authelia for existing deployments (users_database.yml → access.yml).
- Ubiquitous language: add *social login, access entry, role (admin/user/pending),
  access group, identity provider* to `UBIQUITOUS_LANGUAGE.md` on implementation.

## Target end-state: one users list, with state
The Access overview lives on the **Users** page as its own section *for now* only because
two identity stores currently coexist: Authelia local accounts (the existing users list)
and Google social identities (access entries). They are deliberately kept side-by-side
while both exist.

The intended convergence: once oauth2-proxy replaces Authelia, social identities *become*
the users, and the two lists collapse into **a single users list where every row carries
its access state** — role (pending/user/admin) and groups shown inline per user, no
separate panel. The current separate-section layout is a stepping stone, not the
destination. Implement the merge as part of (or immediately after) the oauth2-proxy wiring,
not before — merging while the stores are distinct would conflate a Google email with an
Authelia account.

## Recommendation
Build **Option C**. Next concrete step: implement the Vaier authorization core
(`AccessEntry` domain + `AccessFileAdapter` + `VerifyAccessUseCase` + the admin
endpoints) **TDD-first** — it's provider-agnostic and fully testable without Google
credentials, and it's the part that carries the real product logic. Wire oauth2-proxy
once the core is green and the operator has Google credentials.
