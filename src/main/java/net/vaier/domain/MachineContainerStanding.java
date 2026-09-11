package net.vaier.domain;

import lombok.Builder;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;

/**
 * What Vaier remembers about <b>one container on one machine</b> (#356, widened in #317), and the words it
 * says about it.
 *
 * <p>A standing exists only for a container Vaier has actually seen running well. That is what separates
 * "this was up when I last looked and is in trouble now" — the honest trigger the issue asks for — from
 * "a container is stopped", which is true of a great many containers on purpose and forever.
 *
 * <p>It owns the sentences too, so the email and the machine card can never drift apart in how they
 * describe the same container, and so no service or controller writes operator-facing words. Each trouble
 * gets its own words: a container that is <b>not running</b>, one that is <b>unhealthy</b>, and one that is
 * <b>restart-looping</b> are three different things to go and look at, and a single sentence covering all
 * three would tell the operator none of them.
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
 * @param standing        what Vaier currently <b>says</b> about this container: the verdict the mail was
 *                        sent on and the card is drawn from
 * @param reading         what the last scrapes have been <b>seeing</b>, which becomes the standing once
 *                        it has been seen twice running. The two differ only in the window between a
 *                        trouble starting and it being worth saying.
 * @param lastSeenRunning when Vaier last saw this container running <em>and well</em> — passing its health
 *                        check, or having none. That is "last seen healthy" on an unhealthy container,
 *                        which is the fact the mail actually needs.
 * @param troubledSince   the first scrape that read the current trouble, or null while all is well. It is
 *                        reset when the trouble changes, so the words date what is happening now rather
 *                        than something that stopped happening an hour ago.
 * @param machineBootedAt when the machine last booted, as far as the five-minute sweep has learned. Null
 *                        when it has never managed to read one — and then the reboot sentence is simply
 *                        absent rather than guessed at.
 * @param misses          how many consecutive answered scrapes have read the same trouble
 */
@Builder(toBuilder = true)
public record MachineContainerStanding(MachineId machineId, String containerName,
                                       ContainerStanding standing, ContainerStanding reading,
                                       Instant lastSeenRunning, Instant troubledSince,
                                       Instant machineBootedAt, int misses) {

    /**
     * The zone is named in full rather than abbreviated: "CEST" is one more thing to work out, and it is
     * ambiguous twice a year. An explicit locale for the same reason the zone is explicit — a formatter
     * built without one binds whatever the JVM default happens to be.
     */
    private static final DateTimeFormatter WHEN =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm (VV)", Locale.ENGLISH);

    /** A memory with no standing written into it is a container that was fine, never one in trouble. */
    public MachineContainerStanding {
        standing = standing == null ? ContainerStanding.RUNNING : standing;
        reading = reading == null ? standing : reading;
    }

    /** A container just seen running and well: the standing resets completely, whatever it was before. */
    public static MachineContainerStanding seenRunning(MachineId machineId, String containerName,
                                                       Instant at, Instant machineBootedAt) {
        return MachineContainerStanding.builder()
            .machineId(machineId)
            .containerName(containerName)
            .standing(ContainerStanding.RUNNING)
            .reading(ContainerStanding.RUNNING)
            .lastSeenRunning(at)
            .machineBootedAt(machineBootedAt)
            .build();
    }

    /** Whether Vaier has told the operator something is wrong with this container. */
    public boolean isTrouble() {
        return standing.isTrouble();
    }

    /** Whether what Vaier has said is specifically that the container is not running. */
    public boolean isGone() {
        return standing == ContainerStanding.GONE;
    }

    /**
     * This standing after one more answered scrape read {@code trouble}. The scrape that first reads a
     * trouble is what dates it; later ones reading the same thing leave that alone, so the mail says when
     * it actually started rather than when the count happened to reach the threshold.
     *
     * <p>A <b>different</b> trouble starts its own count and its own clock: one unhealthy scrape followed
     * by one restarting scrape is not two misses of anything, and dating a restart loop from a health
     * check that failed earlier would be wrong about when.
     */
    public MachineContainerStanding troubled(ContainerStanding trouble, Instant at, Instant machineBootedAt) {
        boolean sameAsBefore = trouble == reading;
        return toBuilder()
            .reading(trouble)
            .misses(sameAsBefore ? misses + 1 : 1)
            .troubledSince(sameAsBefore && troubledSince != null ? troubledSince : at)
            .machineBootedAt(machineBootedAt)
            .build();
    }

    /** This standing, now told about: what the scrapes have been reading becomes what Vaier says. */
    public MachineContainerStanding announce() {
        return toBuilder().standing(reading).build();
    }

    /**
     * Whether what the scrapes are reading is <b>worse</b> than what Vaier has already said — the only
     * thing that earns a second email about the same container.
     *
     * <p>An unhealthy container that finally exits has got worse, and that is news. One that was gone and
     * comes back up unwell has got better; the operator hears nothing until it is properly well again, and
     * then hears it once. Without this, the pair of them would trade mails back and forth.
     */
    public boolean isEscalation() {
        return reading.isWorseThan(standing);
    }

    /** This standing carrying what the sweep has since learned about when the machine booted. */
    public MachineContainerStanding withMachineBootedAt(Instant bootedAt) {
        return toBuilder().machineBootedAt(bootedAt).build();
    }

    /**
     * The one sentence that makes a missing container legible: the machine rebooted <em>after</em> Vaier
     * last saw it running, so the reboot is what explains it. Empty when the sweep has never learned a boot
     * instant, or when the machine has not rebooted since — a guess dressed as context is worse than no
     * context at all.
     *
     * <p>Empty for the other troubles too. A reboot explains a container that did not come back; it
     * explains nothing about one that is up and failing its own health check, and a line that does not
     * explain anything is just another line to read.
     */
    public Optional<String> rebootExplanation(ZoneId zone) {
        if (standing != ContainerStanding.GONE || machineBootedAt == null || lastSeenRunning == null
            || !machineBootedAt.isAfter(lastSeenRunning)) {
            return Optional.empty();
        }
        return Optional.of("The machine rebooted at " + format(machineBootedAt, zone)
            + ", after Vaier last saw this container running — that is what explains it.");
    }

    /** What this container's card is called, and the half of the mail subject that says what happened. */
    public String cardTitle() {
        return containerName + " is " + switch (standing) {
            case GONE -> "not running";
            case UNHEALTHY -> "unhealthy";
            case RESTARTING -> "restart-looping";
            case RUNNING -> "running";
        };
    }

    /** Subject for the trouble alert, sent once when something that was running well stops being. */
    public String troubleSubject(String machineName) {
        return "[Vaier] " + cardTitle() + " on " + machineName;
    }

    /** Subject for the all-clear, sent once the container is running well again. */
    public String backSubject(String machineName) {
        return "[Vaier] " + containerName + " is running again on " + machineName;
    }

    /**
     * Body for the trouble alert, with every time written in {@code zone}. It says what Vaier saw and when,
     * adds the reboot sentence where there is one to add, and is plain that Vaier changed nothing and
     * cannot put it right — there is no endpoint in Vaier that starts or restarts a container, and a mail
     * implying otherwise would send the operator looking for a button that does not exist.
     */
    public String troubleBody(String machineName, ZoneId zone) {
        StringBuilder body = new StringBuilder();
        body.append("Machine: ").append(machineName).append("\n");
        body.append("Container: ").append(containerName).append("\n");
        body.append(lastWellLabel()).append(": ").append(format(lastSeenRunning, zone)).append("\n");
        body.append(sinceLabel()).append(": ").append(format(troubledSince, zone)).append("\n");
        rebootExplanation(zone).ifPresent(sentence -> body.append("\n").append(sentence).append("\n"));
        body.append("\n").append(situation()).append("\n");
        body.append("Vaier changed nothing here, and ").append(cannotVerb())
            .append(": it reads the fleet's containers and never starts or stops one.\n");
        return body.toString();
    }

    /** Body for the all-clear. The container is up and well again; there is nothing else to say. */
    public String backBody(String machineName, ZoneId zone) {
        return "Machine: " + machineName + "\n"
            + "Container: " + containerName + "\n"
            + "Running again since: " + format(lastSeenRunning, zone) + "\n";
    }

    /** The evidence the machine's card carries — the same facts as the mail, in one line. */
    public String evidence(ZoneId zone) {
        String line = "Vaier last saw " + containerName + " " + wellWord() + " at "
            + format(lastSeenRunning, zone) + ", and has found it " + troubleWord()
            + " on every scrape since " + format(troubledSince, zone) + ".";
        return rebootExplanation(zone).map(sentence -> line + " " + sentence).orElse(line);
    }

    /** What the card offers instead of a button, since Vaier has none honest to offer. */
    public String cardAction() {
        return switch (standing) {
            case GONE -> "Vaier watched this container run and now finds it stopped. It reads the fleet's "
                + "containers and cannot start it: bring it back on the machine.";
            case UNHEALTHY -> "Vaier watched this container run well and now its own health check is "
                + "failing. It reads the fleet's containers and cannot restart it: the container's logs "
                + "on the machine say why.";
            case RESTARTING -> "Vaier watched this container run and now finds Docker restarting it over "
                + "and over. It reads the fleet's containers and cannot restart it: the container's logs "
                + "on the machine say why.";
            case RUNNING -> "Nothing to do: this container is running.";
        };
    }

    /** "Last seen healthy" on a container that is up and unwell; "last seen running" on the rest. */
    private String lastWellLabel() {
        return standing == ContainerStanding.UNHEALTHY ? "Last seen healthy" : "Last seen running";
    }

    private String sinceLabel() {
        return switch (standing) {
            case GONE -> "Found not running";
            case UNHEALTHY -> "Unhealthy since";
            case RESTARTING -> "Restarting since";
            case RUNNING -> "Running since";
        };
    }

    private String situation() {
        return switch (standing) {
            case GONE -> "The machine itself is fine as far as everything else Vaier watches is concerned "
                + "— it answers, its disk has room, its backups run. This container is simply not running.";
            case UNHEALTHY -> "The container is up: Docker has it running, and it is the container's own "
                + "health check that is failing. Everything else Vaier watches on this machine is fine.";
            case RESTARTING -> "Docker keeps restarting this container: it starts, exits, and is started "
                + "again. On a restart policy it may never settle into being stopped, so this is the only "
                + "word you will get about it.";
            case RUNNING -> "This container is running.";
        };
    }

    private String cannotVerb() {
        return standing == ContainerStanding.GONE ? "cannot start it" : "cannot restart it";
    }

    private String wellWord() {
        return standing == ContainerStanding.UNHEALTHY ? "healthy" : "running";
    }

    private String troubleWord() {
        return switch (standing) {
            case GONE -> "stopped";
            case UNHEALTHY -> "unhealthy";
            case RESTARTING -> "restarting";
            case RUNNING -> "running";
        };
    }

    private static String format(Instant at, ZoneId zone) {
        return at == null ? "unknown" : WHEN.format(at.atZone(zone));
    }
}
