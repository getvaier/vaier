package net.vaier.domain;

import java.util.List;
import java.util.Optional;

/**
 * One machine's containers as a single scrape found them (#356) — and, crucially, <b>whether that scrape
 * got an answer at all</b>.
 *
 * <p>That second half is the whole reason this type exists rather than a bare list. A machine that did not
 * answer reports no containers, which is indistinguishable, as data, from a machine that answered and has
 * none. Reading the first as the second would mail the operator about every container on a machine that is
 * merely asleep. The scrape already knows which it was — the peer and LAN-server scrapes carry a status —
 * so the distinction is preserved here instead of being thrown away and then guessed at.
 *
 * @param machineId  whose containers these are; a standing is filed against identity, never a name
 * @param answered   whether the scrape actually reached the machine's Docker daemon
 * @param containers what it found, empty when it found nothing (or was never asked)
 */
public record ContainerObservation(MachineId machineId, boolean answered, List<DockerService> containers) {

    /** What the peer and LAN-server scrapes report when the daemon answered. */
    private static final String ANSWERED = "OK";

    public ContainerObservation {
        containers = containers == null ? List.of() : List.copyOf(containers);
    }

    /**
     * A scrape of one machine, as the peer and LAN-server scrapes report it.
     *
     * <p>Empty when {@code machineId} is null — a live WireGuard peer with no stored config is in no
     * machine registry, so there is no identity to file a standing against, and inventing one would join
     * its containers to nothing. Read, never minted.
     */
    public static Optional<ContainerObservation> of(String machineId, String status,
                                                    List<DockerService> containers) {
        if (machineId == null || machineId.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new ContainerObservation(MachineId.of(machineId), ANSWERED.equals(status),
            containers));
    }

    /**
     * The Vaier server's own stack, where the scrape reports no status to read.
     *
     * <p>An empty reading is <b>no answer</b>, not an empty host: Vaier itself runs in a container on this
     * machine, so a scrape that came back with nothing can only be a scrape that failed. Without that rule
     * a failed local scrape would first look like every container on the host vanishing at once, and then —
     * on the next good scrape — like all of them coming back.
     */
    public static ContainerObservation ofVaierServer(MachineId machineId, List<DockerService> containers) {
        return new ContainerObservation(machineId, containers != null && !containers.isEmpty(), containers);
    }
}
