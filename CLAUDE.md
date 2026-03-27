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
| Use cases | `*UseCase` interface + `*Service` implementation |
| Adapters | `*Adapter` (e.g., `Route53DnsAdapter`, `WireGuardVpnAdapter`) |

### Key Integrations

- **WireGuard**: File-based config management at `WIREGUARD_CONFIG_PATH`, process execution for peer management
- **Traefik**: YAML dynamic config generation at `TRAEFIK_CONFIG_PATH`
- **AWS Route53**: DNS zone/record CRUD via AWS SDK v2
- **Docker**: Container discovery via Docker socket (`/var/run/docker.sock`)
- **Authelia**: Authentication middleware, YAML-based user config

### No Database

All state is file-based (WireGuard/Traefik/Authelia YAML configs), cloud-based (Route53), or ephemeral (Redis for Authelia sessions). No SQL database or ORM.

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
docker build -t getvaier/vaier:latest .                  # Build image (matches docker-compose.yml image tag)
docker compose up -d --force-recreate vaier              # Deploy
```

The `docker-compose.yml` uses `image: getvaier/vaier:latest`. Building as just `vaier:latest` will not be picked up.

## Server development

When developing on a server, you can use the `docker-compose.yml` file to run the full stack locally. This is useful for testing and debugging changes before deploying to production.
