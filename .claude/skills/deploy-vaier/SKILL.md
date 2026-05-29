---
name: deploy-vaier
description: Build the Vaier Docker image with the correct tag and deploy it to the local docker-compose stack. Use after any change to Vaier app code (Java or static resources) that needs to run in the real stack, or when asked to deploy/redeploy Vaier locally.
---

# Deploy Vaier to the local stack

Vaier app changes (including `src/main/resources/static/**`) only take effect once the image is rebuilt and the container recreated. `docker-compose.yml` uses `image: getvaier/vaier:latest` — **the tag matters**.

## Steps

1. Build with the version baked in and the **exact** tag `getvaier/vaier:latest`:
   ```bash
   docker build --build-arg VAIER_VERSION=$(mvn -q help:evaluate -Dexpression=project.version -DforceStdout) \
     -t getvaier/vaier:latest .
   ```
2. Recreate just the vaier service:
   ```bash
   docker compose up -d --force-recreate vaier
   ```
3. Wait for readiness, then confirm:
   ```bash
   docker compose ps vaier
   docker logs vaier 2>&1 | grep -iE "Started VaierApplication|APPLICATION FAILED" | tail -1
   ```

## Gotchas (do not skip)

- **Wrong tag = silent stale deploy.** Building as plain `vaier:latest` (without the `getvaier/` prefix) is NOT picked up by compose — it silently keeps running the old image pulled from Docker Hub. Always use `getvaier/vaier:latest`.
- **Don't curl `localhost:8888` to verify** — that port isn't publicly reachable and Vaier is fronted by Traefik/Authelia. For API checks, exec inside the container: `docker exec vaier curl -s localhost:8080/<path>`. For browser checks, use `vaier.${VAIER_DOMAIN}`.
- **Updating staging** (Docker Hub `getvaier/vaier:latest`) needs `docker compose pull` first — `--force-recreate` alone reuses the cached `:latest`.
- This deploys **only** the vaier service. Bumping pinned sub-images (wireguard/traefik/authelia/redis) is a different, ask-first workflow — see the `bump-subimage` skill.
- Run `mvn test` green before deploying.
