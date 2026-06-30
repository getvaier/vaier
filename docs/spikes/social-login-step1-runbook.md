# #305 Step 1 runbook — prove Google login on ONE test service

Staged rollout, step 1: stand up oauth2-proxy **alongside** Authelia and gate a single
throwaway service with the two-stage chain (`oauth2-authn → vaier-authz`). Authelia keeps
protecting everything else, so there is always a working path back in. Nothing here is
applied until your Google credentials exist in `.env`.

## Prerequisites (operator)
1. Google OAuth **Web application** client, with BOTH redirect URIs registered:
   - `https://oauth2.<domain>/oauth2/callback` (staged phase — used now)
   - `https://login.<domain>/oauth2/callback` (cutover — used in step 4)
2. `.env`:
   ```ini
   VAIER_OIDC_GOOGLE_CLIENT_ID=...
   VAIER_OIDC_GOOGLE_CLIENT_SECRET=...
   VAIER_ADMIN_EMAIL=you@gmail.com
   ```
3. DNS (Vaier/Route53): `oauth2.<domain>` and `whoami.<domain>` → the Vaier server
   (CNAME to `vaier.<domain>`). Created as part of execution if Route53 mode.

## Compose additions (NOT yet applied)
```yaml
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
      - --cookie-domain=.${VAIER_DOMAIN}
      - --whitelist-domain=.${VAIER_DOMAIN}
      - --email-domain=*                 # authN open; authZ is Vaier's /authz/verify
      - --upstream=static://202          # forward-auth only
      - --reverse-proxy=true
      - --set-xauthrequest=true
      - --session-store-type=redis
      - --redis-connection-url=redis://redis:6379
      - --redis-password-file=/redis-secret/redis-password
      - --http-address=0.0.0.0:4180
      - --whitelist-domain=oauth2.${VAIER_DOMAIN}
    volumes:
      - ./oauth2/config:/secrets:ro
      - ./authelia/config:/redis-secret:ro   # reuse the existing redis-password file
    restart: unless-stopped
    labels:
      - traefik.enable=true
      - traefik.http.routers.oauth2-proxy.rule=Host(`oauth2.${VAIER_DOMAIN}`)
      - traefik.http.routers.oauth2-proxy.entrypoints=websecure
      - traefik.http.routers.oauth2-proxy.tls.certresolver=letsencrypt
      - traefik.http.services.oauth2-proxy.loadbalancer.server.port=4180
    networks:
      - vaier-network

  # Throwaway target for step 1 — echoes request headers so we can SEE the injected identity.
  whoami:
    image: traefik/whoami:v1.10.2
    container_name: whoami
    restart: unless-stopped
    networks:
      - vaier-network
```

## Traefik test route (file provider) — `traefik/config/oauth2-test.yml`
```yaml
http:
  routers:
    whoami:
      rule: Host(`whoami.${DOMAIN}`)
      entryPoints: [websecure]
      service: whoami
      tls:
        certResolver: letsencrypt
      middlewares: [oauth2-authn, vaier-authz]
  services:
    whoami:
      loadBalancer:
        servers:
          - url: http://whoami:80
  middlewares:
    # Stage 1 — authentication (Google). On 401, Traefik redirects to the sign-in page.
    oauth2-authn:
      forwardAuth:
        address: http://oauth2-proxy:4180/oauth2/auth
        trustForwardHeader: true
        authResponseHeaders: [X-Auth-Request-Email, X-Auth-Request-User]
    # Stage 2 — authorization (Vaier). Reads X-Auth-Request-Email + X-Forwarded-Host.
    vaier-authz:
      forwardAuth:
        address: http://vaier:8080/authz/verify
        trustForwardHeader: true
        authResponseHeaders: [Remote-User, Remote-Email, Remote-Groups]
```
> The unauthenticated redirect: oauth2-proxy's `/oauth2/auth` returns 401 on no session;
> wire Traefik to send the browser to `https://oauth2.<domain>/oauth2/start?rd=<original>`
> (oauth2-proxy `--reverse-proxy` sign-in path). Confirm the exact 401→redirect handling
> during execution; if Traefik doesn't auto-redirect, add an errors/redirect middleware
> for 401 pointing at the start URL.

## Verification (the whole point of step 1)
1. `docker compose up -d oauth2-proxy whoami` (after creds in `.env`); confirm oauth2-proxy
   is Up (not crash-looping) and got a cert for `oauth2.<domain>`.
2. Visit `https://whoami.<domain>` in a fresh browser → should bounce to Google.
3. Sign in with a **non-admin** Google account → expect **403 "awaiting approval"**
   (and a new PENDING row appears under Users → Access).
4. Approve that account to **user** in the UI → reload `whoami` → now reaches it; the
   whoami output shows the injected `Remote-User`/`Remote-Email`/`Remote-Groups`.
5. Sign in with `VAIER_ADMIN_EMAIL` → reaches it immediately (seeded admin).
6. Confirm everything ELSE (the real Authelia-protected services + console) is untouched.

## Rollback
`docker compose stop oauth2-proxy whoami && rm traefik/config/oauth2-test.yml` — the test
route and proxy vanish; Authelia and all real services are unaffected.

## After step 1 passes
- Step 3 (code): teach `TraefikReverseProxyAdapter` to emit the two-stage chain, add a
  per-service auth-mode so services migrate off the Authelia middleware deliberately.
- Then the console, then decommission Authelia + plan the `users_database.yml` migration
  (step 4), and fold the Access list into the users list (the convergence end-state).
