---
name: write-tests
description: How to write tests for Vaier without adding duplicate or unnecessary ones — where a behaviour's test belongs, when a new fact is a row in an existing table rather than a new method, what never gets a test, and how to check the result before running it. Use this skill every time a Java change needs a test: the red step of TDD, "add a test for", "cover this", "write tests", a new use case/port/adapter/controller, a bug fix, or a review of tests for redundancy — even when the ask is just "make the tests pass" or "implement X" (TDD means tests come first here). Only Java gets tests in this project.
---

# Write tests that earn their place

Vaier runs about six thousand tests, and the operator reads the count. Every method that restates a fact a
sibling already proves costs a line in the report and buys no new failure signal. On 2026-09-22 a sweep found
170 such methods across the suite — four tests for one validator, ten methods each checking one substring of
one generated script, an adapter test re-pinning line by line what the domain test already pinned. This skill
is how a test gets written so that sweep never has to happen again.

The rule underneath everything: **a test exists to make one distinct behaviour fail visibly.** If you cannot
name the behaviour that would break *only* this test, the test is not needed.

## 1. Only Java gets tests

Tests are JUnit tests of Java classes. Nothing else in the repository gets a test framework:

- No JavaScript, CSS or HTML tests, ever — the frontend is verified by deploying and looking (the deploy skill
  and the operator's own check). The Java source-guard tests that read `static/*.js` (`ExplorerShellTest`)
  exist to pin a security or architecture boundary — the fetch allowlist, "the browser never polls" — not to
  describe UI behaviour. Do not grow that pattern for ordinary interface work.
- No shell-script, YAML or compose tests except through the Java classes that generate or parse them
  (`PeerSetupScriptTest`, `DockerComposeStructureTest` are Java tests of Java-owned text).
- No tests for generated code: Lombok accessors, records, `equals`/`hashCode`/`toString`, enum `values()`,
  constants' values, Spring's own wiring.

## 2. Find the owner before writing anything

Every production class has at most one test class, named `<Class>Test`, in the mirrored package. Before you
write a method:

1. Open that class and read it whole. Most "new" tests are already there under another name.
2. Search for the behaviour, not the method: `grep -rn "<the literal you are about to assert>" src/test/java`.
   A literal already asserted anywhere means the fact has an owner — go there.
3. Ask which **layer owns the decision**. That layer's test pins the fact; every other layer proves only that
   it hands the fact through.

| Layer | Its test proves | Its test never re-asserts |
|---|---|---|
| Domain (`domain/`) | the rule, the generated text, the parse, the refusal — with real inputs | — |
| Service (`application/service/`) | which ports were called, in what order, what was passed, that a refusal propagates, that a cascade fires | the *content* of what the domain produced (script lines, command flags, config keys) |
| Adapter (`adapter/driven/`) | the I/O contract: what was written to disk / sent / parsed back | the domain's text, when the adapter merely emits a domain factory's output — assert it starts with / equals `Domain.thing()` |
| Controller (`rest/`) | delegation to the use case, status mapping, DTO shape | the domain rule behind the use case |
| Integration (`integration/`) | HTTP wiring the unit test cannot see: JSON field names, status codes, error mapping over the wire | a delegation the unit test already verifies with the same `verify(...)` |

If a service test wants to say `contains("snap.docker.dockerd")`, the domain test already does or should; the
service test says `verify(port).run(eq(target), eq(PeerSetupScript.generate(...)))` or asserts the result *is*
the domain's output. That is the whole hand-through proof.

## 3. One method per behaviour; inputs are rows

Inputs that differ only by literal are **rows of one test**, never sibling methods. Write a plain loop over a
small table and label each row with `.as(...)` so a failure still names the case:

```java
@Test
void validate_rejectsHostnames_andAnythingThatIsNotExactlyFourOctets() {
    for (String bad : new String[] { "nas.home", "192.168.3", "192.168.3.256", "192.168.03.1", "::1" }) {
        assertThatThrownBy(() -> LanAddress.validate(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
    }
}
```

Use a local `record Row(String in, String expected) {}` when a row carries more than one value. **Do not use
`@ParameterizedTest`** here: each row still counts as a test in the suite total, which defeats the point, and
the count is what the operator reads.

Rows share one fixture, so anything stateful must be reset per row: a service that caches its result (call
its `invalidate…()` first), a mock stubbed with `when(...)` for the previous row (`reset(mock)` or stub inside
the loop), a temp file the last row wrote. A second row passing only because the first row ran is a test that
lies — the folding sweep hit exactly this in `PublishingServiceTest`.

A regression test keeps its "why" — as a comment on its row when it joins a table, as a short comment on the
method when it stands alone. Two rules with the same shape but different meaning (`omitsDnsLine` vs
`omitsDescriptionKey`) may share a table too; the comment carries the meaning, the method count does not.

## 4. What is not a test

- **Subset tests.** If method A's assertions all hold whenever method B's do, under the same arrangement, A
  adds nothing. A strict-equals mock stub (`when(port.f(new Config("a","b"))).thenReturn(x)`) already proves
  the argument was assembled correctly; a captor test of the same fields is a subset.
- **"Does not throw" beside a sibling** that exercises the same path with real assertions.
- **Fragmented facts.** Ten methods each checking one substring of the one script the same fixture produced
  are one test with ten assertions. The arrangement is the unit; the assertions are its facts.
- **The same fact in two layers** (see the table). Pick the owner, delete the echo.
- **A second regression for the same bug** with a different fixture (`unknownMachine_404` on an empty fleet
  and `anIdNoMachineHas_is404` on a fleet of one prove one fact; keep the stronger).

## 5. Red first, and only what changed

TDD still holds: the test fails before the implementation exists, for the right reason (a missing symbol or a
wrong value — not a typo). Then:

```bash
mvn -q test -Dtest=ChangedClassTest,OtherChangedClassTest
```

Never run two Maven builds on this tree at once (shared `target/` yields hundreds of bogus
`NoClassDefFoundError`s), and run the full suite once, before deploying, not after every edit.

## 6. Check the shape before you finish

Run the bundled scanner on the class you touched:

```bash
python3 .claude/skills/write-tests/scripts/testscan.py src/test/java/net/vaier/domain/FooTest.java
```

It lists `@Test` bodies that are identical once literals are normalised, and bodies with no assertion. A group
of two or more in a class you just edited is a table you did not write. Then ask, for each new method:

- Which single behaviour fails only here? (No answer → delete or merge.)
- Is any literal I assert already asserted in another test file? (`grep -rn` it.)
- Does a sibling differ from this method only by its inputs? (Then it is a row.)
- Am I asserting content that another layer's test owns? (Then assert hand-through instead.)

Two or three new methods for a new class is normal. Ten is a sign the facts were split, not the behaviours.

## Examples from this codebase

**Fragmented → one arrangement, many facts.** `VpnServiceTest.generateSetupScript_*` was ten methods, each
mocking the same peer and checking one substring (peer name, IP, server URL, port, docker enable, snap docker…).
The behaviour is "the service renders this peer's setup script": one method, one arrangement, the assertions
back to back — and each content line belongs in `PeerSetupScriptTest` anyway, leaving the service test to prove
the domain's output is handed through.

**Cross-layer echo → hand-through.** `DockerComposeGeneratorAdapterTest` re-pinned the image, `cap_add`,
sysctls, volumes and restart policy that `WireguardClientComposeTest` already pins. The adapter emits
`WireguardClientCompose.standalone()` plus instructions, so its test asserts exactly that and stops.

**Literal-only siblings → rows.** `DbIpGeolocationAdapterTest.locate_returnsEmptyFor{Null,Blank,Unparseable,
LoopbackV4,LoopbackV6,Rfc1918,LinkLocal,Cgnat,Ipv6UniqueLocal,…}` — eleven methods, one behaviour ("addresses
that cannot be placed read as empty"), one loop.

**Unit + IT saying the same thing → keep the one that sees more.** An IT asserting `status().isOk()` plus the
same `verify(useCase).f(...)` as the unit test proves nothing the unit test cannot; keep the IT only where it
checks a JSON field name or a status mapping.
