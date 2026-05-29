---
name: bump-subimage
description: Bump a pinned upstream sub-image version in docker-compose.yml (WireGuard, Traefik, Authelia, Redis, docker-socket-proxy, etc.) following Vaier's version-pin policy. Use when asked to update/upgrade a sub-image, or when checking whether sub-images are out of date.
---

# Bump a pinned sub-image version

All upstream images in `docker-compose.yml` are pinned to specific versions — **no floating `:latest`**. This skill changes one of those pins safely. (This is NOT for the Vaier app image — that's the `deploy-vaier` skill.)

## Policy (must follow)

1. **Check upstream for the latest stable release** for the image in question — its GitHub releases / Docker Hub tags. **Skip pre-release / rc / beta tags.**
2. **Ask the dev before bumping — never bump unilaterally.** Explain which images have newer releases and what changed (changelog highlights, breaking notes).
3. Only bump if the dev confirms, and **bump one image at a time** unless asked otherwise.
4. **WireGuard drift rule:** the generated WireGuard client compose (`DockerComposeGeneratorAdapter`) must pin the **same** wireguard version as the server. A drift-check test enforces this — if you bump the server's wireguard image, update the generated client pin too, or the test fails (by design).
5. After bumping: run `mvn test`, then deploy the full stack (`docker compose up -d`) and confirm nothing broke before committing.
6. **Bump the Maven `project.version`** in the same change (per issue #167: Vaier release cuts only happen when sub-image deps change).
7. Update `README.md` / `PRD.md` if the change is user-visible. Commit locally (include any relevant `Closes #<n>`); **do not push** — the dev pushes when ready.

## Where the pins live
`docker-compose.yml` `services.*.image` (e.g. `lscr.io/linuxserver/wireguard:…`, `traefik:…`, `authelia/authelia:…`, `redis:…`, the docker-socket-proxy/haproxy image). The matching WireGuard client pin lives in `DockerComposeGeneratorAdapter`.

## Checklist
- [ ] Found latest STABLE tag (no rc/beta) for the one image being bumped.
- [ ] Asked the dev with what-changed summary; got confirmation.
- [ ] Bumped the pin (and the WireGuard client pin too, if bumping wireguard).
- [ ] Bumped Maven `project.version`.
- [ ] `mvn test` green; `docker compose up -d` healthy.
- [ ] Docs updated if user-visible; committed locally, not pushed.
