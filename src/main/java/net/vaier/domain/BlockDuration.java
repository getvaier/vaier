package net.vaier.domain;

/**
 * How long a {@link SourceAddress#block hand-placed block} lasts (#349). Exactly the four choices the
 * operator decided on, and nothing wider: <b>there is deliberately no permanent option</b>, so a hand block
 * self-heals the same way every other decision on the Security view already does — CrowdSec forgets it on
 * its own once the duration elapses, and it never becomes the accumulate-forever list #348 already worried
 * about in the trusted direction.
 *
 * <p>{@link #cscliDuration()} is what actually reaches {@code cscli decisions add --duration}. CrowdSec's
 * duration flag is parsed by Go's {@code time.ParseDuration}, which understands {@code h}/{@code m}/{@code
 * s} but has no unit for days at all — so "7 days" is expressed to the engine as {@code 168h}, never as a
 * literal {@code 7d} it cannot read. The wire value this type accepts from the browser stays {@code 7d}
 * because that is how the operator reads it on the Security view's duration select.
 */
public enum BlockDuration {

    ONE_HOUR("1h", "1h", "1 hour"),
    FOUR_HOURS("4h", "4h", "4 hours"),
    TWENTY_FOUR_HOURS("24h", "24h", "24 hours"),
    SEVEN_DAYS("7d", "168h", "7 days");

    private final String wireValue;
    private final String cscliDuration;
    private final String label;

    BlockDuration(String wireValue, String cscliDuration, String label) {
        this.wireValue = wireValue;
        this.cscliDuration = cscliDuration;
        this.label = label;
    }

    /**
     * Parses the browser's chosen duration. Refuses anything outside the four offered choices — including
     * a blank or missing value — the same way {@link SourceAddress#of} refuses anything that is not a plain
     * dotted quad: the operator's own duration select never sends anything else, so anything else reaching
     * here is either a bug or a caller that skipped the UI.
     */
    public static BlockDuration of(String wireValue) {
        if (wireValue != null) {
            for (BlockDuration duration : values()) {
                if (duration.wireValue.equals(wireValue.trim())) {
                    return duration;
                }
            }
        }
        throw new IllegalArgumentException(
            "Not a valid block duration — choose one of 1h, 4h, 24h or 7d");
    }

    /** The CrowdSec-readable duration string for {@code cscli decisions add --duration}. */
    public String cscliDuration() {
        return cscliDuration;
    }

    /** How this reads to an operator, e.g. {@code "4 hours"}. */
    public String label() {
        return label;
    }
}
