---
name: hexagonal-architecture
description: Authoritative reference for Vaier's hexagonal (ports & adapters) architecture — the pattern as Cockburn defined it, plus the rules Vaier enforces. Read BEFORE writing or reviewing any backend (Java) code, or before spawning a backend agent, so decisions land in the domain and infrastructure is reached only through driven ports.
---

# Hexagonal Architecture (Ports & Adapters)

The pattern Alistair Cockburn coined (originally "hexagonal architecture", renamed **Ports & Adapters** in 2005). Vaier's whole backend is built on it. Getting it wrong means rebuilding features — so this is enforced, not just documented (see the bottom).

## The pattern, at its source

**Intent (Cockburn's own sentence):** *"Allow an application to equally be driven by users, programs, automated test or batch scripts, and to be developed and tested in isolation from its eventual run-time devices and databases."*

- **The application core** (domain + the use cases that orchestrate it) holds the business logic and knows nothing about the outside world.
- **A port** is *"a purposeful conversation"* — an interface the core owns. One port can have many adapters (real DB, in-memory fake, etc.).
- **An adapter** is technology-specific glue that translates between the outside and the core's language. Adapters are interchangeable and hold **no business logic** — only mapping/formatting/infra.
- **Core rule:** *"Code pertaining to the inside part should not leak into the outside part."* The core is independent of any external technology and fully testable in isolation via fake adapters.

### Two sides — the distinction is "who triggers the conversation"

- **Driving / primary (left):** an actor *controls* the app. A driving adapter (a REST controller, a scheduler, a test) calls a **use case** (a driving port). Control flows adapter → core; the adapter depends on the core's interface.
- **Driven / secondary (right):** the app *controls* an external thing. The core calls a **driven port** it defines, and an adapter *implements* that port (a DB, an SSH client, an SMTP sender). This is **dependency inversion**: the core depends on the abstraction; the concrete adapter depends on the port — the reverse of the natural dependency.

A use case is something a **driving actor invokes**. A **side-effect** the core produces (e.g. "provision a host because these paths are now protected") is **NOT a use case** — the domain triggers it through a **driven port**.

## How Vaier applies it (the enforced rules)

Layers: `domain/` (entities, value objects, port interfaces — no Spring) · `application/` (`*UseCase` interfaces + `*Service` implementations) · `adapter/driven/` (driven-port implementations) · `rest/` (driving adapters / controllers).

1. **Decisions live in the DOMAIN.** Predicates and business choices ("is this a duplicate?", "does this path count as covered?", "does this machine need provisioning?") belong on entities/value objects — never in a service, a controller, or the frontend.
2. **The domain owns port calls.** A domain operation that needs infrastructure is a **domain method that receives a driven port and calls it**; the `*Service` only passes the port in and orchestrates.
3. **Services never call use cases.** A `*Service` implements use cases and calls **driven ports**. It must not inject or call another service's `*UseCase`. A cross-domain query → add a **driven port**, not a use-case dependency.
4. **A side-effect is not a use case.** Don't invent a `*UseCase` for a consequence of a domain operation — trigger it from the domain through a **driven port**.
5. **Controllers are thin driving adapters.** Request → use case → response. No business decisions, no orchestration-of-consequences.
6. **Strict layer isolation.** Never import an unrelated use case just to share a constant/utility.
7. **Naming:** ports `For*`; use cases `*UseCase` (narrow, one per operation); one `*Service` per domain concept (implements many `*UseCase`s); adapters `*Adapter`; DTOs as inner `record`s in controllers.

## Mistakes that have actually cost rebuilds here

- Modelling a **side-effect as a driving use case** and orchestrating it in the **controller** (the "auto-provision on first backup" first attempt). Correct: the domain decides and calls a driven port.
- Putting a **containment/coverage predicate in the frontend JS** instead of the domain, then shipping the flag as data.
- A `*Service` injecting another `*UseCase` to reach across domains, instead of adding a driven port.

## Enforcement

- **Put rules 1–7 verbatim into every backend delegation prompt** (vaier-dev / tdd-runner). A wrong prompt is a wrong feature.
- **Run the `hex-architecture-checker` agent on the working diff before committing any backend change.** A confirmed violation blocks the commit until fixed — the semantic review catches what greps and memory miss.
- A `git commit` hook surfaces this checklist when backend Java is staged.

## Sources
- [Alistair Cockburn — Hexagonal Architecture](https://alistair.cockburn.us/hexagonal-architecture) (the source)
- [Hexagonal architecture — Wikipedia](https://en.wikipedia.org/wiki/Hexagonal_architecture_(software))
- [Arho Huttunen — Hexagonal Architecture Explained](https://www.arhohuttunen.com/hexagonal-architecture/) (driving vs driven)
