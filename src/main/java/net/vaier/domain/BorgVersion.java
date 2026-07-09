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
 *
 * <p>{@link #isCompatibleWith(BorgVersion)} is the other business predicate: borg <b>2</b> changed the
 * on-disk repository format and the client/server protocol, so a borg-1.x client cannot talk to a
 * borg-2.x server (and a repo created by one is unreadable to the other). Compatibility is therefore
 * <b>major equality</b> — <em>not</em> a {@code >=} ordering: a 1.2 client is compatible with a 1.4
 * server but not with a 2.0 server, even though 2.0 is "newer".
 */
public record BorgVersion(int major, int minor, int patch) {

    // The numeric <major>.<minor>[.<patch>] prefix; a pre-release suffix (e.g. the "b20" of "borg 2.0.0b20")
    // is intentionally not captured — compatibility keys off the major, so the beta parses as major 2 rather
    // than failing to parse (which would silently look like borg is absent).
    private static final Pattern VERSION = Pattern.compile("borg\\s+(\\d+)\\.(\\d+)(?:\\.(\\d+))?");

    /**
     * Parse {@code borg --version} output into a {@link BorgVersion}, or {@link Optional#empty()} when the
     * output is null, blank, or does not carry a recognisable {@code borg <major>.<minor>[.<patch>]}
     * version (e.g. a "command not found"). Never throws.
     *
     * <p>A pre-release/beta suffix is tolerated by keying off the numeric prefix: {@code "borg 2.0.0b20"}
     * parses to {@code 2.0.0} (major 2). This is deliberate — the borg-2 line currently only exists as a
     * beta, and it must be recognised as major 2 by the version guard, never dropped as unparseable.
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

    /**
     * Whether a client at this version can talk to a borg {@code server}: {@code true} only when their
     * <b>majors match</b>. Borg 2 introduced a new repository format and client/server protocol, so a
     * borg-1.x client cannot address a borg-2.x server at all — and a repo one creates is unreadable to the
     * other. Compatibility is therefore major-equality, deliberately not a {@code >=} "newer is fine" rule
     * (a 1.2 client is <em>incompatible</em> with a 2.0 server despite 2.0 being newer).
     */
    public boolean isCompatibleWith(BorgVersion server) {
        return server != null && this.major == server.major;
    }

    /**
     * The honest client/server compatibility verdict when either version may be unknown: {@code true} only
     * when <b>both</b> are present and their majors match (see {@link #isCompatibleWith(BorgVersion)}). An
     * unknown version on either side is never optimistically compatible — a check that cannot read a version
     * must fail closed, not green.
     */
    public static boolean compatible(Optional<BorgVersion> client, Optional<BorgVersion> server) {
        return client.isPresent() && server.isPresent() && client.get().isCompatibleWith(server.get());
    }
}
