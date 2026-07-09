package net.vaier.domain;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The borg version installed on a client host, parsed from {@code borg --version} output ("{@code borg
 * 1.2.8}"). Guided provisioning uses it to tell an operator whether a host's borg is new enough to run a
 * fleet-backup job before they try one.
 *
 * <p>{@link #isSupported()} is a business predicate on the entity: Vaier's nightly chain ends in
 * {@code borg compact}, which only exists from borg 1.2, so anything older is unsupported.
 */
public record BorgVersion(int major, int minor, int patch) {

    private static final Pattern VERSION = Pattern.compile("borg\\s+(\\d+)\\.(\\d+)(?:\\.(\\d+))?");

    /**
     * Parse {@code borg --version} output into a {@link BorgVersion}, or {@link Optional#empty()} when the
     * output is null, blank, or does not carry a recognisable {@code borg <major>.<minor>[.<patch>]}
     * version (e.g. a "command not found"). Never throws.
     */
    public static Optional<BorgVersion> parse(String borgVersionStdout) {
        if (borgVersionStdout == null || borgVersionStdout.isBlank()) {
            return Optional.empty();
        }
        Matcher m = VERSION.matcher(borgVersionStdout);
        if (!m.find()) {
            return Optional.empty();
        }
        int major = Integer.parseInt(m.group(1));
        int minor = Integer.parseInt(m.group(2));
        int patch = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
        return Optional.of(new BorgVersion(major, minor, patch));
    }

    /**
     * Whether this borg is new enough for Vaier's fleet-backup chain. The nightly run ends in
     * {@code borg compact}, introduced in borg 1.2, so 1.2 and later are supported and anything older is
     * not.
     */
    public boolean isSupported() {
        return major > 1 || (major == 1 && minor >= 2);
    }
}
