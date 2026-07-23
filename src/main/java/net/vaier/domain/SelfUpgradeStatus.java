package net.vaier.domain;

/**
 * The account {@link SelfUpgradeScript} left on the host, read back after the process that started the
 * upgrade is gone.
 *
 * <p>Parsing it is a domain rule for the usual reason: the same line decides what the operator is told and
 * whether the admins are mailed, and two readers would eventually disagree about what "it worked" means.
 *
 * @param runId   the upgrade this line belongs to, so a stale result from an earlier one is never read as this
 * @param outcome what happened
 * @param at      when the script wrote the line, as it stamped it (UTC, ISO-8601)
 * @param detail  the image it came from, or the step that fell over
 */
public record SelfUpgradeStatus(String runId, SelfUpgradeStatus.Outcome outcome, String at, String detail) {

    public enum Outcome {
        /** No upgrade has ever run on this host. */
        NONE,
        /** The new image came up and answered. */
        UPGRADED,
        /** It did not answer, so the previously running image was put back. Vaier is up — on the old build. */
        ROLLED_BACK,
        /** The upgrade could not be carried out at all: no compose project, a failed pull, a failed recreate. */
        FAILED,
        /** There is a line, and it is not one of ours. */
        UNKNOWN,
    }

    /** Nothing has ever been upgraded here. */
    public static final SelfUpgradeStatus NONE = new SelfUpgradeStatus(null, Outcome.NONE, null, null);

    /**
     * Read one result line: {@code <runId> <OUTCOME> <timestamp> [detail]}. Total — a missing file, an empty
     * read or a line in some other shape all resolve to a value rather than an exception, because this is
     * read on a schedule and a surprise here must never take a sweep down.
     *
     * <p>Absence is {@link Outcome#NONE}, not a failure: a host that has never upgraded has no file, and
     * reading that as a failed upgrade would put a permanent red mark on a healthy install.
     */
    public static SelfUpgradeStatus parse(String line) {
        if (line == null || line.isBlank()) {
            return NONE;
        }
        String[] parts = line.strip().split("\\s+", 4);
        if (parts.length < 3) {
            return new SelfUpgradeStatus(null, Outcome.UNKNOWN, null, line.strip());
        }
        Outcome outcome;
        try {
            outcome = Outcome.valueOf(parts[1]);
        } catch (IllegalArgumentException e) {
            return new SelfUpgradeStatus(parts[0], Outcome.UNKNOWN, parts[2], line.strip());
        }
        return new SelfUpgradeStatus(parts[0], outcome, parts[2], parts.length > 3 ? parts[3] : null);
    }

    /**
     * Whether this outcome is something the operator needs to hear about. A rollback counts even though Vaier
     * is running again — <em>because</em> it is running again: nothing looks broken, so silence would mean an
     * upgrade quietly reverting every time and nobody ever finding out.
     */
    public boolean trouble() {
        return outcome == Outcome.ROLLED_BACK || outcome == Outcome.FAILED;
    }
}
