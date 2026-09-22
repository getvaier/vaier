package net.vaier.domain;

import java.time.Instant;
import java.util.Optional;

/**
 * Everything the pre-flight judges, gathered at the driving edge from facts Vaier already has or can learn on a
 * path that exists. {@code certificate} is empty when the front door could not be reached at all;
 * {@code serverDisk} is empty when this server's disks have no standing yet.
 *
 * @param consoleHost      the console's own hostname, {@code vaier.<domain>}; null while no domain is configured, in
 *                         which case there is no front door to look at yet
 * @param wildcardSeverity the boot probe's grade of the wildcard record (OK / WARNING / ERROR), null before it ran
 * @param wildcardMessage  that verdict as the sentence the domain already worded
 */
public record PreFlightFacts(String consoleHost, String wildcardSeverity, String wildcardMessage,
                             Optional<ConsoleCertificate> certificate, boolean wireguardAnswering,
                             Optional<MachineDiskStanding> serverDisk, Instant now) {

    public PreFlightFacts {
        certificate = certificate == null ? Optional.empty() : certificate;
        serverDisk = serverDisk == null ? Optional.empty() : serverDisk;
        now = now == null ? Instant.now() : now;
    }
}
