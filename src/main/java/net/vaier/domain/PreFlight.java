package net.vaier.domain;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * "Is it working?" (#265) — Vaier's judgement of its own basics: the wildcard record, the certificate on its
 * front door, the tunnel, and the disk under it. Every "what counts as wrong" is decided here, in the operator's
 * words, and a healthy server yields nothing — the same rule as the {@link ReverseProxyAudit} beside it.
 */
public record PreFlight(List<PreFlightFinding> findings) {

    /** Traefik renews at 30 days out; a certificate this close to expiry means renewal is not happening. */
    static final Duration EXPIRY_WARNING = Duration.ofDays(14);

    public PreFlight {
        findings = findings == null ? List.of() : List.copyOf(findings);
    }

    public static PreFlight of(PreFlightFacts f) {
        List<PreFlightFinding> findings = new ArrayList<>();
        wildcard(f).ifPresent(findings::add);
        certificate(f).ifPresent(findings::add);
        if (!f.wireguardAnswering()) {
            findings.add(new PreFlightFinding(PreFlightFinding.Check.WIREGUARD,
                "WireGuard is not answering, so no peer can connect and no LAN behind one can be reached.",
                "Check the wireguard container: docker compose ps wireguard, then docker logs wireguard."));
        }
        f.serverDisk().filter(d -> d.breachingFilesystems() > 0).ifPresent(d -> findings.add(new PreFlightFinding(
            PreFlightFinding.Check.DISK,
            "This server's own disk is " + d.worstUsedPercent() + "% full on " + d.worstMountPoint()
                + ", past the " + d.worstThresholdPercent() + "% threshold; Vaier itself may stop working.",
            "Free space here first — Docker's build cache is the usual culprit.")));
        return new PreFlight(findings);
    }

    private static Optional<PreFlightFinding> wildcard(PreFlightFacts f) {
        String severity = f.wildcardSeverity();
        if (severity == null || "OK".equals(severity) || f.wildcardMessage() == null || f.wildcardMessage().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new PreFlightFinding(PreFlightFinding.Check.WILDCARD_DNS, f.wildcardMessage(),
            "Everything Vaier publishes rides on that one record; fix it at your DNS provider."));
    }

    private static Optional<PreFlightFinding> certificate(PreFlightFacts f) {
        if (f.consoleHost() == null || f.consoleHost().isBlank()) {
            return Optional.empty();   // no domain yet, so no front door to judge; the settings say so already
        }
        if (f.certificate().isEmpty()) {
            return Optional.of(new PreFlightFinding(PreFlightFinding.Check.CERTIFICATE,
                "Vaier could not reach its own front door over HTTPS to see which certificate it shows.",
                "Check that the traefik container is running: docker compose ps traefik."));
        }
        ConsoleCertificate cert = f.certificate().get();
        if (cert.isPlaceholder()) {
            return Optional.of(new PreFlightFinding(PreFlightFinding.Check.CERTIFICATE,
                "No certificate has been issued for " + f.consoleHost() + " yet; browsers will warn before every page.",
                "Let's Encrypt needs port 80 reachable from the internet and the wildcard record pointing here."));
        }
        long daysLeft = Duration.between(f.now(), cert.notAfter()).toDays();
        if (daysLeft < EXPIRY_WARNING.toDays()) {
            return Optional.of(new PreFlightFinding(PreFlightFinding.Check.CERTIFICATE,
                "The certificate for " + f.consoleHost() + " expires in " + daysLeft + " days.",
                "Traefik renews a month out on its own; one this close means port 80 or the wildcard record is blocked."));
        }
        return Optional.empty();
    }

    public boolean isClean() {
        return findings.isEmpty();
    }

    /** The lead sentence, in the operator's words; empty when there is nothing to say. */
    public String summary() {
        if (findings.isEmpty()) return "";
        int n = findings.size();
        return n == 1 ? "1 thing needs attention before Vaier works as expected."
            : n + " things need attention before Vaier works as expected.";
    }
}
