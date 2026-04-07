# Contributing to Vaier

Thanks for your interest. This guide covers everything you need to get your first PR merged.

---

## What to work on

- **Open issues** — [github.com/getvaier/vaier/issues](https://github.com/getvaier/vaier/issues) is the single backlog. Issues labelled `good first issue` are self-contained and well-scoped. Feature specs for larger items are in the relevant section of [`PRD.md`](PRD.md).
- **Bugs you find** — open an issue first to confirm it's a bug and that no one else is already working on it.

Open an issue before starting anything non-trivial. It avoids duplicate work and lets us discuss scope before you invest time.

---

## Architecture

Vaier uses **hexagonal architecture** (ports & adapters), strictly enforced by package structure:

```
domain/          Business logic, entities, port interfaces. No Spring, no I/O.
application/     Use case interfaces (*UseCase) + service implementations (*Service).
adapter/driven/  Infrastructure adapters (*Adapter): WireGuard, Traefik, Route53, Docker, Authelia.
rest/            REST controllers. DTOs are inner Java record classes — no separate DTO files.
```

### Naming conventions

| Concept | Pattern | Example |
|---------|---------|---------|
| Port interface | `For*` | `ForGettingVpnClients` |
| Use case interface | `*UseCase` | `PublishServiceUseCase` |
| Service (use case impl) | `*Service` | `PublishServiceService` |
| Adapter | `*Adapter` | `Route53DnsAdapter` |

### Layer isolation rule

Application services must **never** import from an unrelated use case interface just to share a constant or utility. If two services happen to need the same string, keep them as separate literals in their own contexts. Forced sharing via an unrelated interface creates spaghetti dependencies. Only introduce shared constants when the concepts are genuinely the same and the coupling is intentional.

---

## Test-driven development (mandatory)

This project follows **strict TDD**. Every PR must follow this order:

1. **Write a failing test** that captures the expected behaviour.
2. **Write the minimum implementation** to make the test pass.
3. **Refactor** if needed, keeping all tests green.

PRs that add features without a prior failing test will not be merged. There are no exceptions.

---

## Getting started locally

### Prerequisites

- Java 21
- Maven 3.9+
- Docker + Docker Compose (for integration testing)

### Run the backend

```bash
mvn clean package -DskipTests   # build
mvn spring-boot:run              # starts on :8080
```

Swagger UI: `http://localhost:8080/swagger-ui.html`

### Run the full Docker stack

```bash
# Build the image first — must use this exact tag
mvn clean package -DskipTests
docker build -t getvaier/vaier:latest .

# Start the stack
docker compose up -d
```

> The compose file uses `getvaier/vaier:latest`. Any other tag will silently deploy the old Docker Hub image.

### Run the tests

```bash
mvn test
```

---

## Pull request checklist

- [ ] A failing test was written before the implementation
- [ ] All existing tests pass (`mvn test`)
- [ ] The change is scoped to what was discussed in the issue — no bonus features
- [ ] No new Spring imports in `domain/`
- [ ] No cross-use-case constant sharing (see layer isolation rule above)
- [ ] `README.md` updated if the feature set changed
- [ ] `PRD.md` updated if a backlog item was completed or a new one was added

---

## Code style

- Java 21, Spring Boot 3.5.5
- Lombok — use `@Data`, `@Builder`, `@RequiredArgsConstructor`, etc. No manual getters/setters.
- No comments unless the logic is non-obvious. Self-documenting names are preferred.
- No speculative abstractions. Three similar lines is better than a premature helper.

---

## License

By contributing you agree that your changes will be licensed under the [Apache License 2.0](LICENSE).
