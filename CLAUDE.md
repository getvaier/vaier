# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project goals

I am basically tired of maintaining a VPN server with reverse proxy pointing to all my docker hosts with containers on different ports. This project will make it very easy to
- **Maintain a VPN server** with WireGuard and Traefik
- **Create and maintain VPN clients** by providing docker compose files and other client config that can be used to connect to the VPN server
- **Create a reverse proxy** with Let's Encrypt and Traefik
- **Create DNS records** with AWS Route53
- **Manage DNS records** with AWS Route53
- **Manage containers remotely** with Docker
- **Manage users** with Authelia
- **Web interface for managing everything** with Vaier
- **Self-generated dashboard for linking to all my services** with Vaier

## Build & Run Commands

```bash
mvn clean package              # Build JAR (with tests)
mvn clean package -DskipTests  # Build JAR (skip tests)
mvn test                       # Run all tests
mvn spring-boot:run            # Run locally (port 8080)
docker compose up -d           # Run full stack (WireGuard + Traefik + Vaier)
```

Swagger UI: `http://localhost:8080/swagger-ui.html` (local) or `http://localhost:8888/swagger-ui.html` (Docker)

## Architecture

**Hexagonal architecture** (Ports & Adapters) with four layers:

- **Domain** (`domain/`) — Business logic, entities, and port interfaces. No Spring dependencies.
- **Application** (`application/`) — Use case interfaces and service implementations that orchestrate domain logic.
- **Infrastructure** (`adapter/driven/`) — Adapter implementations for external systems (AWS Route53, Docker API, WireGuard, Traefik, Authelia).
- **Web** (`rest/`) — REST controllers. DTOs are defined as inner Java `record` classes within controllers.

### Naming Conventions

| Pattern | Example |
|---------|---------|
| Port interfaces | `For*` (e.g., `ForGettingVpnClients`, `ForPersistingDnsRecords`) |
| Use case interfaces | `*UseCase` — one per use case, narrow (e.g., `CreatePeerUseCase`, `DeletePeerUseCase`) |
| Service implementations | `*Service` — **one per domain concept**, implements many `*UseCase` interfaces (e.g., `VpnService`, `UserService`, `PublishingService`) |
| Adapters | `*Adapter` (e.g., `Route53DnsAdapter`, `WireGuardVpnAdapter`) |

### One service per domain, not per use case

Keep `*UseCase` interfaces narrow and one-per-use-case — they are the ports controllers depend on, and narrow interfaces keep controller tests small. But group their **implementations** by domain concept: `VpnService`, `UserService`, `DnsService`, `ReverseProxyService`, `ContainerService`, `SettingsService`, `PublishingService`. One `@Service` class implements every use case in its domain.

When adding a new use case, do NOT create a new `*Service` class unless the use case belongs to a genuinely new domain. Add the method to the existing domain service.

Cross-domain orchestration (e.g., `VpnService.deletePeer` cascading into `PublishingService.deleteService` when a peer with published services is removed) must go via the `*UseCase` interface, never a direct class-to-class dependency. This preserves the hex boundary and avoids circular dependencies.

### Key Integrations

- **WireGuard**: File-based config management at `WIREGUARD_CONFIG_PATH`, process execution for peer management
- **Traefik**: YAML dynamic config generation at `TRAEFIK_CONFIG_PATH`
- **AWS Route53**: DNS zone/record CRUD via AWS SDK v2
- **Docker**: Container discovery via Docker socket (`/var/run/docker.sock`)
- **Authelia**: Authentication middleware, YAML-based user config

### No Database

All state is file-based (WireGuard/Traefik/Authelia YAML configs), cloud-based (Route53), or ephemeral (Redis for Authelia sessions). No SQL database or ORM.

### Strict layer isolation

Application services must never import from an unrelated use case interface just to share a constant or utility. If two unrelated services happen to need the same string value (e.g. a container name and a subdomain that are both "vaier"), keep them as separate literals in their own contexts — forced sharing via an unrelated interface creates spaghetti dependencies that violate the hexagonal architecture. Only introduce shared constants when the concepts are genuinely the same and the coupling is intentional.

## Tech Stack

- Java 21, Spring Boot 3.5.5, Maven
- Project Lombok (use `@Data`, `@Builder`, etc. — no manual getters/setters)
- Springdoc OpenAPI 2.7.0 for API documentation
- Argon2-JVM for password hashing

## CI/CD

GitHub Actions (`.github/workflows/build-deploy.yml`): build → test → Docker image → push to Docker Hub on main branch.

## Docker Stack

The `docker-compose.yml` runs five services on a custom bridge network (`172.20.0.0/16`):
1. **WireGuard** — VPN server (UDP 51820)
2. **Traefik** — Reverse proxy with Let's Encrypt (ports 80, 443, 8080)
3. **Authelia** — Authentication middleware
4. **Redis** — Session store for Authelia
5. **Vaier** — This Spring Boot app (port 8888 externally, 8080 internally)

### Sub-image version pinning

All upstream images in `docker-compose.yml` are pinned to specific versions (no floating `:latest` tags). The generated WireGuard client compose (`DockerComposeGeneratorAdapter`) must pin the same wireguard version as the server — a drift-check test enforces this.

**When bumping a pinned version:**
1. Look up the latest stable release for each image (check the upstream's GitHub releases / Docker Hub tags — skip pre-release, rc, beta tags).
2. **Ask the dev before bumping** — never bump unilaterally. Explain which images have newer releases and what changed.
3. Only bump if the dev confirms, and only bump one image at a time unless asked otherwise.
4. After bumping, run `mvn test` and deploy the full stack with `docker compose up -d` to confirm nothing broke before committing.
5. Bump the Maven `project.version` in the same change (per issue #167 policy: Vaier release cuts only when sub-image deps change).

## Required Environment Variables

| Variable | Description |
|----------|-------------|
| `VAIER_AWS_KEY` | AWS access key for Route53 |
| `VAIER_AWS_SECRET` | AWS secret key for Route53 |
| `VAIER_DOMAIN` | Base domain name |
| `ACME_EMAIL` | Let's Encrypt email |

## Deploying changes

Always build the Docker image locally with the correct tag before deploying:

```bash
docker build --build-arg VAIER_VERSION=$(mvn -q help:evaluate -Dexpression=project.version -DforceStdout) \
  -t getvaier/vaier:latest .                             # Build image (matches docker-compose.yml image tag)
docker compose up -d --force-recreate vaier              # Deploy
```

The `docker-compose.yml` uses `image: getvaier/vaier:latest`. Building as just `vaier:latest` will not be picked up.

## Test-driven development

This project follows strict TDD. Always write a failing test before writing any implementation code:

1. Write a test that captures the expected behaviour — it must fail before any implementation exists
2. Write the minimum implementation to make the test pass
3. Refactor if needed, keeping tests green

Never write implementation code without a corresponding test written first. PRs that add features without prior failing tests are not acceptable.

## Keeping docs in sync

After any change to the feature set — new features, changed behaviour, removed functionality, renamed concepts — update `README.md`, `PRD.md`, and `UBIQUITOUS_LANGUAGE.md` before committing:

- **README.md** — user-facing; update feature tables, workflow descriptions, and any affected quick-start steps
- **PRD.md** — planning document; mark implemented items ✅, update planned items, and add backlog entries for anything new that was discussed
- **UBIQUITOUS_LANGUAGE.md** — vocabulary; add new terms, update definitions when behaviour changes, retire terms that no longer apply

All three documents must always reflect the actual state of the codebase. Stale docs are treated as bugs.

## Ubiquitous language is authoritative

`UBIQUITOUS_LANGUAGE.md` is the source of truth for vocabulary in this project. Apply it like a rule, not a reference:

- **Before introducing a new term** (in code, commits, issues, PRs, UI copy, conversation), check `UBIQUITOUS_LANGUAGE.md`. If a term already exists for the concept, use it exactly — don't invent a synonym.
- **Before adding a new concept**, decide its canonical name *first* and add an entry to `UBIQUITOUS_LANGUAGE.md` in the same change. The name lands in the document and the code together.
- **When the codebase and this document disagree**, the codebase wins and the document gets updated.
- Watch especially for near-synonyms (e.g. "host" vs "machine", "client" vs "peer", "subdomain" vs "service"). Pick one, retire the other.

## After changing code

After any code change, build and deploy to the local Docker Compose stack:

```bash
docker build --build-arg VAIER_VERSION=$(mvn -q help:evaluate -Dexpression=project.version -DforceStdout) \
  -t getvaier/vaier:latest .
docker compose up -d --force-recreate vaier
```

Then ask the user to verify the fix works.

If the user confirms the fix is good:
1. Commit the changes to git.
2. If the change was triggered by a GitHub issue, include `Closes #<issue-number>` in the commit message — GitHub will close the issue automatically when pushed to main.
3. **Do NOT push** — only commit locally. The user will push when ready.

## Server development

When developing on a server, you can use the `docker-compose.yml` file to run the full stack locally. This is useful for testing and debugging changes before deploying to production.
