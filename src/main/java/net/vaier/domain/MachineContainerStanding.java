package net.vaier.domain;

import lombok.Builder;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;

/**
 * What Vaier remembers about <b>one container on one machine</b> (#356), and the words it says about it.
 *
 * <p>A standing exists only for a container Vaier has actually seen running. That is what separates "this
 * was up when I last looked and is down now" — the honest trigger the issue asks for — from "a container
 * is stopped", which is true of a great many containers on purpose and forever.
 *
 * <p>It owns the sentences too, so the email and the machine card can never drift apart in how they
 * describe the same container, and so no service or controller writes operator-facing words.
 *
 * <p>Every time it writes is rendered in a <b>zone the caller hands in</b> — in production the Vaier
 * server's own, off the {@code Clock}. The instants themselves stay UTC, stored and compared as instants;
 * only the words move. A person reading "07:21" for something that happened at 09:21 on their own machine
 * does the arithmetic on every line of every mail, and the domain must not go and ask the environment what
 * zone that is.
 *
 * @param machineId       whose container this is. Identity, never a name: two machines in this fleet
 *                        really can run a container called {@code webtrees}, and a rename must not move
 *                        one machine's alert onto another.
 * @param containerName   the container's name on that machine — what the operator calls it
 * @param standing        {@link ContainerStanding#RUNNING} or {@link ContainerStanding#GONE}
 * @param lastSeenRunning when Vaier last saw this container actually running
 * @param notRunningSince the first scrape that found it not running, or null while it is running
 * @param machineBootedAt when the machine last booted, as far as the five-minute sweep has learned. Null
 *                        when it has never managed to read one — and then the reboot sentence is simply
 *                        absent rather than guessed at.
 * @param misses          how many consecutive answered scrapes have found it not running
 */
@Builder(toBuilder = true)
public record MachineContainerStanding(MachineId machineId, String containerName,
                                       ContainerStanding standing, Instant lastSeenRunning,
                                       Instant notRunningSince, Instant machineBootedAt, int misses) {

    /**
     * The zone is named in full rather than abbreviated: "CEST" is one more thing to work out, and it is
     * ambiguous twice a year. An explicit locale for the same reason the zone is explicit — a formatter
     * built without one binds whatever the JVM default happens to be.
     */
    private static final DateTimeFormatter WHEN =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm (VV)", Locale.ENGLISH);

    /** A container just seen running: the standing resets completely, whatever it was before. */
    public static MachineContainerStanding seenRunning(MachineId machineId, String containerName,
                                                       Instant at, Instant machineBootedAt) {
        return MachineContainerStanding.builder()
            .machineId(machineId)
            .containerName(containerName)
            .standing(ContainerStanding.RUNNING)
            .lastSeenRunning(at)
            .machineBootedAt(machineBootedAt)
            .build();
    }

    /** Whether Vaier has told the operator this container is gone. */
    public boolean isGone() {
        return standing == ContainerStanding.GONE;
    }

    /**
     * This standing after one more answered scrape found the container not running. The first miss is what
     * dates {@code notRunningSince}; later ones leave that alone, so the mail says when it actually stopped
     * rather than when the count happened to reach the threshold.
     */
    public MachineContainerStanding missed(Instant at, Instant machineBootedAt) {
        return toBuilder()
            .misses(misses + 1)
            .notRunningSince(notRunningSince == null ? at : notRunningSince)
            .machineBootedAt(machineBootedAt)
            .build();
    }

    /** This standing, now told about: {@link ContainerStanding#GONE}. */
    public MachineContainerStanding gone() {
        return toBuilder().standing(ContainerStanding.GONE).build();
    }

    /** This standing carrying what the sweep has since learned about when the machine booted. */
    public MachineContainerStanding withMachineBootedAt(Instant bootedAt) {
        return toBuilder().machineBootedAt(bootedAt).build();
    }

    /**
     * The one sentence that makes the rest legible: the machine rebooted <em>after</em> Vaier last saw this
     * container running, so the reboot is what explains it. Empty when the sweep has never learned a boot
     * instant, or when the machine has not rebooted since — a guess dressed as context is worse than no
     * context at all.
     */
    public Optional<String> rebootExplanation(ZoneId zone) {
        if (machineBootedAt == null || lastSeenRunning == null
            || !machineBootedAt.isAfter(lastSeenRunning)) {
            return Optional.empty();
        }
        return Optional.of("The machine rebooted at " + format(machineBootedAt, zone)
            + ", after Vaier last saw this container running — that is what explains it.");
    }

    /** Subject for the container-gone alert, sent once when something that was running stops being. */
    public String goneSubject(String machineName) {
        return "[Vaier] " + containerName + " is not running on " + machineName;
    }

    /** Subject for the all-clear, sent once the container is running again. */
    public String backSubject(String machineName) {
        return "[Vaier] " + containerName + " is running again on " + machineName;
    }

    /**
     * Body for the container-gone alert, with every time written in {@code zone}. It says what Vaier saw
     * and when, adds the reboot sentence where
     * there is one to add, and is plain that Vaier changed nothing and cannot start it — there is no
     * endpoint in Vaier that starts a container, and a mail implying otherwise would send the operator
     * looking for a button that does not exist.
     */
    public String goneBody(String machineName, ZoneId zone) {
        StringBuilder body = new StringBuilder();
        body.append("Machine: ").append(machineName).append("\n");
        body.append("Container: ").append(containerName).append("\n");
        body.append("Last seen running: ").append(format(lastSeenRunning, zone)).append("\n");
        body.append("Found not running: ").append(format(notRunningSince, zone)).append("\n");
        rebootExplanation(zone).ifPresent(sentence -> body.append("\n").append(sentence).append("\n"));
        body.append("\nThe machine itself is fine as far as everything else Vaier watches is concerned — "
            + "it answers, its disk has room, its backups run. This container is simply not running.\n");
        body.append("Vaier changed nothing here, and cannot start it: it reads the fleet's containers and "
            + "never starts or stops one.\n");
        return body.toString();
    }

    /** Body for the all-clear. The container is up again; there is nothing else to say. */
    public String backBody(String machineName, ZoneId zone) {
        return "Machine: " + machineName + "\n"
            + "Container: " + containerName + "\n"
            + "Running again since: " + format(lastSeenRunning, zone) + "\n";
    }

    /** The evidence the machine's card carries — the same facts as the mail, in one line. */
    public String evidence(ZoneId zone) {
        String line = "Vaier last saw " + containerName + " running at " + format(lastSeenRunning, zone)
            + ", and has found it stopped on every scrape since " + format(notRunningSince, zone) + ".";
        return rebootExplanation(zone).map(sentence -> line + " " + sentence).orElse(line);
    }

    private static String format(Instant at, ZoneId zone) {
        return at == null ? "unknown" : WHEN.format(at.atZone(zone));
    }
}
