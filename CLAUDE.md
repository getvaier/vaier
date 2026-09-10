# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project goals

I am basically tired of maintaining a VPN server with reverse proxy pointing to all my docker hosts with containers on different ports. This project will make it very easy to
- **Maintain a VPN server** with WireGuard and Traefik
- **Create and maintain VPN clients** by providing docker compose files and other client config that can be used to connect to the VPN server
- **Create a reverse proxy** with Let's Encrypt and Traefik
- **Stay out of DNS entirely** — one `*.<domain>` A record, made once by the operator, covers every service Vaier will ever publish
- **Manage containers remotely** with Docker
- **Manage users** with Google social login (oauth2-proxy) and Vaier's own access store
- **Web interface for managing everything** with Vaier
- **Self-generated dashboard for linking to all my services** with Vaier

## Architecture

**Hexagonal architecture** (Ports & Adapters) with four layers:

- **Domain** (`domain/`) — Business logic, entities, and port interfaces. No Spring dependencies.
- **Application** (`application/`) — Use case interfaces and service implementations that orchestrate domain logic.
- **Infrastructure** (`adapter/driven/`) — Adapter implementations for external systems (Docker API, WireGuard, Traefik, oauth2-proxy).
- **Web** (`rest/`) — REST controllers. DTOs are defined as inner Java `record` classes within controllers.

### Naming Conventions

| Pattern | Example |
|---------|---------|
| Port interfaces | `For*` (e.g., `ForGettingVpnClients`, `ForPersistingReverseProxyRoutes`) |
| Use case interfaces | `*UseCase` — one per use case, narrow (e.g., `CreatePeerUseCase`, `DeletePeerUseCase`) |
| Service implementations | `*Service` — **one per domain concept**, implements many `*UseCase` interfaces (e.g., `VpnService`, `UserService`, `PublishingService`) |
| Adapters | `*Adapter` (e.g., `TraefikReverseProxyAdapter`, `WireGuardVpnAdapter`) |

### One service per domain, not per use case

Keep `*UseCase` interfaces narrow and one-per-use-case — **so that a driving adapter's constructor is an honest, readable list of exactly what it is allowed to do.** `WriteSurvivalKitUseCase` in a controller's parameter list states a capability; a fat `BackupUseCases` would hide it behind a bean name, and a controller could quietly grow reach nobody reviewed. That is the reason, and it is the only one — measure any proposed change to this rule against it.

Do **not** justify the rule by "narrow interfaces keep controller tests small." That was the stated rationale for a long time and it is false here: 32 of 36 REST test files double their collaborators with Mockito `mock(X.class)`, and only 5 files in the whole test tree hand-write a fake. Under Mockito a twelve-method interface costs precisely what twelve one-method interfaces cost — one `mock()` call, stubbing only what the test exercises. A rule defended by a premise that doesn't hold gets discarded the first time somebody checks, so it is worth stating the load-bearing reason instead.

Be honest about the price: **117 of 131 `*UseCase` interfaces carry a single method, and 87 have exactly one consumer** (85 of those a controller or scheduler). That is the rule working as designed, not drift — none of them are dead. But it means every new operation costs a file, so the discipline that actually matters is upstream: **do not reach for new machinery a feature has not earned.** Before adding a use case, a port, an adapter, or a cache, check whether the information already flows through a path that exists. Growth here comes from features each recruiting a little more architecture than they need, not from the rules themselves.

Group **implementations** by domain concept: `VpnService`, `UserService`, `ReverseProxyService`, `ContainerService`, `SettingsService`, `PublishingService`. One `@Service` class implements every use case in its domain.

When adding a new use case, do NOT create a new `*Service` class unless the use case belongs to a genuinely new domain. Add the method to the existing domain service.

Cross-domain orchestration (e.g., `VpnService.deletePeer` cascading into `PublishingService.deleteService` when a peer with published services is removed) must go via the `*UseCase` interface, never a direct class-to-class dependency. This preserves the hex boundary and avoids circular dependencies.

**A driving adapter is not a service, and calling a use case is what it is for.** Schedulers, watchers and startup runners (`StateRefreshScheduler`, `RemoteDiskWatcher`, `BreachAttemptWatcher`, `StartupLifecycleRunner`) are driven by an external actor — a clock, an application-ready event — exactly as a controller is driven by an HTTP request. They live in `rest/` and they invoke `*UseCase`s freely. The rule they must not break is the naming one: **never call such a class `*Service`, and never file it under `application/service/`.** `StartupLifecycleRunner` spent a long time as `LifecycleService` in `application/service/`, which made an ordinary driving adapter read as a "service calls a use case" violation and hid it from every audit. The name is what tells a reader which side of the hexagon a class sits on.

**Cross-domain *reads* are different from cross-domain *writes*, and the old advice here was wrong.** For years this file said "for a cross-domain query, add a driven port" — and that produced a pervasive bug: the domain that *owns* the data implemented the `For*` port itself (`MachineService implements ForGettingMachines`, `ContainerService implements ForDiscoveringPeerContainers`, …). **A `*Service` must never implement a driven `For*` port.** Driven ports are outbound boundaries — only an `*Adapter` in `adapter/driven/` sits on the implementing side. A service implementing one is a service-to-service dependency in a port costume: the coupling the "services never call use cases" rule forbids, just relabeled, and it makes Spring **bean cycles** possible (the cycle is the tell). Provide a cross-domain read one of two ways instead:
- **Driving-edge composition** (preferred when the consumer is a controller): the controller injects the several `*UseCase`s it needs and hands their results to a **pure-domain assembler** that owns the decision.
- **A real adapter** (when the consumer is another service or the domain): move the port's implementation into an `*Adapter` in `adapter/driven/` that composes the lower-level ports; the owning service and every other consumer then *inject* the port. The adapter may call domain factories to build the read model — the decisions stay in the domain.

This is stricter than the write-orchestration exception above (which is about *injecting* a `*UseCase` for a genuine cascade, and does not license a service to *implement* a driven port). See `hexagonal-architecture` skill rule 3 and hex-checker rule 9.

### No Database

All state is file-based (WireGuard/Traefik YAML configs, the `access.yml` social-login store) or ephemeral (oauth2-proxy's own signed cookie session). No SQL database or ORM.

### Strict layer isolation

Application services must never import from an unrelated use case interface just to share a constant or utility. If two unrelated services happen to need the same string value (e.g. a container name and a subdomain that are both "vaier"), keep them as separate literals in their own contexts — forced sharing via an unrelated interface creates spaghetti dependencies that violate the hexagonal architecture. Only introduce shared constants when the concepts are genuinely the same and the coupling is intentional.

## Use Lombok, never hand-written getters/setters

Project Lombok is on the classpath — use `@Data`, `@Builder`, etc. Never hand-write a getter, setter,
`equals`, `hashCode`, or `toString`.

## Import types, never write them fully qualified

Every type reference in Java source is an unqualified name backed by an `import`. Never write
`net.vaier.domain.MachineId machineId`, `java.util.Optional<String>`, or
`org.mockito.Mockito.mock(...)` inline — **including in tests**, and including a type used exactly once.

A fully-qualified name inline is almost always a shortcut taken by whoever was editing: it lets a
mechanical find-and-replace land without also touching the import block. The cost lands on every later
reader, and it makes the import block stop being a truthful summary of what a file depends on — which is
the first thing you read when judging whether a class sits on the right side of a port.

A handful of pre-existing inline FQNs remain in the codebase (e.g. `TerminalService`,
`MachineSshTargetAdapter`). They are debt, not precedent: convert them when you are already editing that
line, and never add new ones.

Two narrow exceptions: an unavoidable name collision between two imported types, and a fully-qualified
name inside a Javadoc `{@link}` where the type is not otherwise imported.

## Docker Stack

Authelia and its Redis session store were decommissioned (#305) in favour of Google social login via oauth2-proxy. Do not reintroduce them.

### Sub-image version pinning

All upstream images in `docker-compose.yml` are pinned to specific versions — **no floating `:latest` tags**. To change one, use the `bump-subimage` skill: bumping is an ask-the-dev-first workflow with its own version-cut and drift-check rules.

## Required Environment Variables

| Variable | Description |
|----------|-------------|
| `VAIER_DOMAIN` | Base domain name |
| `ACME_EMAIL` | Let's Encrypt email |

## Test-driven development

This project follows strict TDD. Always write a failing test before writing any implementation code:

1. Write a test that captures the expected behaviour — it must fail before any implementation exists
2. Write the minimum implementation to make the test pass
3. Refactor if needed, keeping tests green

Never write implementation code without a corresponding test written first. PRs that add features without prior failing tests are not acceptable.

## Keeping docs in sync

After any change to the feature set — new features, changed behaviour, removed functionality, renamed concepts — update `README.md`, `PRD.md`, `UBIQUITOUS_LANGUAGE.md`, and `web/index.html` before committing:

- **README.md** — user-facing, and **the short version only**: every feature-table row is one to three sentences, under about 50 words, ending with a link to the `docs/` page that owns the feature. Never append a sentence to a row to record a change; rewrite the row if it must change, and put the mechanism, caveats and reasons on the owning `docs/*.md` page (`NETWORKING`, `AUTH`, `EXPLORER`, `MONITORING`, `BACKUP`, `CHAT`, `ADVANCED`). The README was rebuilt on 2026-09-10 after 200 commits had each added a sentence to a cell, and it is not to grow back
- **PRD.md** — planning document; mark implemented items ✅, update planned items, and add backlog entries for anything new that was discussed
- **UBIQUITOUS_LANGUAGE.md** — vocabulary; add new terms, update definitions when behaviour changes, retire terms that no longer apply
- **web/index.html** — the public promo page (deployed to GitHub Pages via `.github/workflows/pages.yml` on every push to `main` that touches `web/**`); update its feature cards, architecture diagram, and quick-start steps to match. It isn't linked from anywhere else in the repo, which is exactly why it drifts silently if skipped — treat it as a fourth living doc, not a one-off page.

All four must always reflect the actual state of the codebase. Stale docs are treated as bugs.

## Ubiquitous language is authoritative

`UBIQUITOUS_LANGUAGE.md` is the source of truth for vocabulary in this project. Apply it like a rule, not a reference:

- **Before introducing a new term** (in code, commits, issues, PRs, UI copy, conversation), check `UBIQUITOUS_LANGUAGE.md`. If a term already exists for the concept, use it exactly — don't invent a synonym.
- **Before adding a new concept**, decide its canonical name *first* and add an entry to `UBIQUITOUS_LANGUAGE.md` in the same change. The name lands in the document and the code together.
- **When the codebase and this document disagree**, the codebase wins and the document gets updated.
- Watch especially for near-synonyms (e.g. "host" vs "machine", "client" vs "peer", "subdomain" vs "service"). Pick one, retire the other.

## After changing code

After any code change, build and deploy to the local Docker Compose stack using the `deploy-vaier`
skill (it carries the exact build tag and the gotchas). Then ask the user to verify the fix works.

If the user confirms the fix is good:
1. Commit the changes to git.
2. If the change was triggered by a GitHub issue, include `Closes #<issue-number>` in the commit message — GitHub closes the issue automatically once the commit reaches main.
3. **Push to main.** Pushing is normal now; it no longer needs asking for each time.

Two things that do NOT change because pushing became routine:

- **The user still verifies before the commit.** Deploy, ask, and wait. Pushing earlier means a
  mistake is public rather than local, so the confirmation step matters more than it did, not less.
- **Push only what was verified.** Don't sweep unrelated working-tree changes into the commit to get
  a clean tree — commit the change under discussion and leave the rest.

`main` carries branch protection ("Changes must be made through a pull request") that an admin push
bypasses, so a direct push succeeds and still prints that remote message. Take a plain
`<old>..<new>  main -> main` line as success; a genuine rejection is prefixed with `!`. Open a PR
instead when the change wants review — a risky migration, anything touching auth or the backup
chain, or work the user has said they want to look at first.
