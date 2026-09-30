---
name: deploy-vaier
description: Build the Vaier Docker image with the correct tag and deploy it to the local docker-compose stack. Use after any change to Vaier app code (Java or static resources) that needs to run in the real stack, or when asked to deploy/redeploy Vaier locally.
---

# Deploy Vaier to the local stack

Vaier app changes (including `src/main/resources/static/**`) only take effect once the image is rebuilt and the container recreated. `docker-compose.yml` uses `image: getvaier/vaier:latest` — **the tag matters**.

## Steps

0. **Only if `android/` changed** — rebuild the Vaier app first, because the image copies `apk/` and
   serves whatever package is sitting there. Skip this whenever the change is Java or static assets only.
   ```bash
   set -a; source ~/.vaier-android/release.env; set +a
   cd android && timeout 600 ./gradlew --no-daemon -q \
     -Dorg.gradle.jvmargs="-Xmx900m -XX:MaxMetaspaceSize=384m" \
     -Pkotlin.compiler.execution.strategy=in-process assembleRelease
   cp android/app/build/outputs/apk/release/app-release.apk apk/vaier.apk
   ```
   `release.env` holds the signing secrets (`VAIER_ANDROID_KEYSTORE`, `..._KEYSTORE_PASSWORD`,
   `..._KEY_ALIAS`); without it Gradle signs with the debug key and the phone refuses the upgrade.

   **Only if `windows/` changed:** `windows/build.sh` — self-contained (no `VAIER_SELF_CONTAINED`
   override); it produces `windows/dist/VaierSetup.exe`, which the image copies when it is there and 404s without it.
1. Build with the version baked in and the **exact** tag `getvaier/vaier:latest`:
   ```bash
   docker build --build-arg VAIER_VERSION=$(mvn -q help:evaluate -Dexpression=project.version -DforceStdout) \
     -t getvaier/vaier:latest .
   ```
2. Recreate just the vaier service:
   ```bash
   docker compose up -d --force-recreate vaier
   ```
3. **Recreate the masquerade sidecar — never skip this.** `vaier` has `depends_on: wireguard`, so step 2
   recreates the *tunnel* container too. That gives wireguard a new network namespace, which kills
   `wireguard-masquerade` (it runs inside wireguard's namespace via `network_mode: "service:wireguard"`)
   and leaves it `Exited (1)`:
   ```bash
   docker compose up -d wireguard-masquerade
   ```
   It must be **recreated, not restarted** — `network_mode: "service:"` resolves to the dependency's
   container ID at create time, so `docker restart` would rejoin a namespace that no longer exists.
4. Confirm the wg0 masquerade rule is actually back, and that Vaier can reach the fleet's LANs again:
   ```bash
   docker exec wireguard iptables -t nat -S POSTROUTING | grep -- '-o wg0 -j MASQUERADE'
   docker exec vaier ping -c2 -W2 192.168.3.3   # the NAS, across the Apalveien gateway peer
   ```
5. Wait for readiness, then confirm:
   ```bash
   docker compose ps vaier
   docker logs vaier 2>&1 | grep -iE "Started VaierApplication|APPLICATION FAILED" | tail -1
   ```

## Gotchas (do not skip)

- **A deploy silently cuts Vaier off from every LAN, and the symptom is a false alarm.** Without step 3
  the `-s 172.20.0.0/16 -o wg0 -j MASQUERADE` rule is gone, so packets from `vaier` enter the tunnel wearing
  a Docker source address no LAN host has a route back to. The WireGuard handshake stays healthy and `wg
  show` looks perfect, which is exactly what makes this hard to read: the tunnel is fine and the
  conversation is one-way. `BackupServerWatcher` then probes the NAS, gets nothing, and emails **"Backup
  server down"** about a machine that is up. Backups, the disk sweep and terminals to LAN hosts are all
  broken meanwhile. If that mail arrives right after a deploy, check the sidecar before believing it.
- **Wrong tag = silent stale deploy.** Building as plain `vaier:latest` (without the `getvaier/` prefix) is NOT picked up by compose — it silently keeps running the old image pulled from Docker Hub. Always use `getvaier/vaier:latest`.
- **Don't curl `localhost:8888` to verify** — that port isn't publicly reachable and Vaier is fronted by Traefik/Authelia. For API checks, exec inside the container: `docker exec vaier curl -s localhost:8080/<path>`. For browser checks, use `vaier.${VAIER_DOMAIN}`.
- **Updating staging** (Docker Hub `getvaier/vaier:latest`) needs `docker compose pull` first — `--force-recreate` alone reuses the cached `:latest`.
- This deploys **only** the vaier service. Bumping pinned sub-images (wireguard/traefik/authelia/redis) is a different, ask-first workflow — see the `bump-subimage` skill.
- **Never run Gradle and Maven at the same time on this box.** Both want most of the free RAM and both
  hold it for minutes; earlyoom kills whichever loses, and the loser dies with an exit 143 and a wall of
  nonsense errors that look like a code problem. Finish the APK build, *then* run `mvn test`.
- Run `mvn test` green before deploying.
