package net.vaier.domain.port;

import net.vaier.domain.ImageReference;

import java.util.Optional;

/**
 * Driven port for asking a container registry the one question the update sweep needs: <b>what digest do you
 * serve for this tag, right now?</b>
 *
 * <p>The conversation is deliberately this narrow. Vaier is read-only about containers — it never pulls, never
 * restarts, never logs in — so the port exposes no way to. Implementations answer for any Registry v2 host
 * (Docker Hub, ghcr.io, lscr.io) and are free to cache, since manifest requests are rate-limited.
 */
public interface ForResolvingRegistryDigest {

    /**
     * The digest {@code reference}'s registry currently serves for its tag, or empty when it cannot be
     * resolved — registry unreachable, rate-limited, unauthorized, or the tag is simply not there.
     *
     * <p><b>Empty means "cannot tell", never "up to date"</b>, and the domain treats it as such. Implementations
     * may throw; the sweep is total and reads a throw the same way as an empty.
     */
    Optional<String> resolveDigest(ImageReference reference);
}
