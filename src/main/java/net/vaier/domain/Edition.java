package net.vaier.domain;

/**
 * The product edition a running Vaier instance operates as. {@code COMMUNITY} is the free,
 * always-available edition; {@code ENTERPRISE} unlocks paid features and is granted only by a
 * valid {@link License}. The edition is resolved at runtime from the installed licence — there is
 * a single binary, never a separate Enterprise build.
 */
public enum Edition {
    COMMUNITY,
    ENTERPRISE
}
