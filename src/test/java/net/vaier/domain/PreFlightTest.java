package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Is it working?" (#265): the judgement of each fact is the domain's — what counts as issued, up, resolving here,
 * full — and a healthy server yields nothing, because a check that cries wolf teaches the operator to skim past
 * the one day it says otherwise.
 */
class PreFlightTest {

    private static final Instant NOW = Instant.parse("2026-09-22T12:00:00Z");
    private static final MachineId SERVER = MachineId.generate();

    private static ConsoleCertificate letsEncrypt(Duration validFor) {
        return new ConsoleCertificate("R11", NOW.plus(validFor), false);
    }

    private static PreFlightFacts healthy() {
        return new PreFlightFacts("vaier.example.com", "OK", "Wildcard DNS is working.",
            Optional.of(letsEncrypt(Duration.ofDays(60))), true, Optional.empty(), NOW);
    }

    @Test
    void aHealthyServer_hasNoFindings_andNothingToSay() {
        PreFlight preFlight = PreFlight.of(healthy());

        assertThat(preFlight.findings()).isEmpty();
        assertThat(preFlight.isClean()).isTrue();
        assertThat(preFlight.summary()).isEmpty();
    }

    @Test
    void eachThingThatIsWrong_isNamedOnce_withWhatToDo() {
        record Case(String name, PreFlightFacts facts, PreFlightFinding.Check check, String messageFragment) {}
        PreFlightFacts h = healthy();
        List<Case> cases = List.of(
            new Case("wildcard not resolving here",
                new PreFlightFacts(h.consoleHost(), "ERROR", "Wildcard DNS is not set up. Create one record.",
                    h.certificate(), true, h.serverDisk(), NOW),
                PreFlightFinding.Check.WILDCARD_DNS, "Wildcard DNS is not set up"),
            new Case("wildcard warning counts too",
                new PreFlightFacts(h.consoleHost(), "WARNING", "Wildcard DNS points somewhere else.",
                    h.certificate(), true, h.serverDisk(), NOW),
                PreFlightFinding.Check.WILDCARD_DNS, "somewhere else"),
            new Case("front door unreachable",
                new PreFlightFacts(h.consoleHost(), "OK", "", Optional.empty(), true, h.serverDisk(), NOW),
                PreFlightFinding.Check.CERTIFICATE, "could not reach its own front door"),
            new Case("only Traefik's default certificate",
                new PreFlightFacts(h.consoleHost(), "OK", "",
                    Optional.of(new ConsoleCertificate("TRAEFIK DEFAULT CERT", NOW.plus(Duration.ofDays(300)), true)),
                    true, h.serverDisk(), NOW),
                PreFlightFinding.Check.CERTIFICATE, "No certificate has been issued for vaier.example.com"),
            new Case("certificate about to expire",
                new PreFlightFacts(h.consoleHost(), "OK", "", Optional.of(letsEncrypt(Duration.ofDays(9))),
                    true, h.serverDisk(), NOW),
                PreFlightFinding.Check.CERTIFICATE, "expires in 9 days"),
            new Case("WireGuard not answering",
                new PreFlightFacts(h.consoleHost(), "OK", "", h.certificate(), false, h.serverDisk(), NOW),
                PreFlightFinding.Check.WIREGUARD, "WireGuard is not answering"),
            new Case("this server's own disk past its threshold",
                new PreFlightFacts(h.consoleHost(), "OK", "", h.certificate(), true,
                    Optional.of(new MachineDiskStanding(SERVER, "/", 91, 85, 1, 2)), NOW),
                PreFlightFinding.Check.DISK, "91% full on /"));

        for (Case c : cases) {
            List<PreFlightFinding> findings = PreFlight.of(c.facts()).findings();
            assertThat(findings).as(c.name()).hasSize(1);
            assertThat(findings.get(0).check()).as(c.name()).isEqualTo(c.check());
            assertThat(findings.get(0).message()).as(c.name()).contains(c.messageFragment());
            assertThat(findings.get(0).remedy()).as(c.name() + " says what to do").isNotBlank();
        }

        // Not wrong: a certificate with weeks left, a disk under its threshold, a wildcard verdict not yet taken.
        assertThat(PreFlight.of(new PreFlightFacts(h.consoleHost(), null, null,
            Optional.of(letsEncrypt(Duration.ofDays(29))), true,
            Optional.of(new MachineDiskStanding(SERVER, "/", 40, 85, 0, 2)), NOW)).findings()).isEmpty();
        // No domain configured yet: there is no front door to judge, so no certificate finding either.
        assertThat(PreFlight.of(new PreFlightFacts(null, null, null, Optional.empty(), true, Optional.empty(), NOW))
            .findings()).isEmpty();
    }

    @Test
    void summary_countsWhatNeedsAttention_inTheOperatorsWords() {
        PreFlightFacts h = healthy();
        PreFlight one = PreFlight.of(new PreFlightFacts(h.consoleHost(), "OK", "", h.certificate(), false,
            h.serverDisk(), NOW));
        PreFlight two = PreFlight.of(new PreFlightFacts(h.consoleHost(), "ERROR", "Wildcard DNS is not set up.",
            h.certificate(), false, h.serverDisk(), NOW));

        assertThat(one.summary()).isEqualTo("1 thing needs attention before Vaier works as expected.");
        assertThat(two.summary()).isEqualTo("2 things need attention before Vaier works as expected.");
    }
}
