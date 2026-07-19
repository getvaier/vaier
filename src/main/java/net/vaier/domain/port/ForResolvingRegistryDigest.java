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
     *
     * <p>May be answered from a remembered answer, which is what keeps a fleet-wide sweep under the anonymous
     * rate limit. When that is not good enough, ask {@link #resolveDigestNow} instead.
     */
    Optional<String> resolveDigest(ImageReference reference);

    /**
     * The same question, with <b>no remembered answer allowed</b>: go and ask the registry itself.
     *
     * <p>This exists because of an inversion that is easy to build by accident (#57 slice 3). What an
     * implementation remembers is the <em>registry's</em> answer. What changes when the operator pulls is the
     * <em>local</em> digest. So an operator-driven "check now" served from memory compares their freshly
     * pulled digest against a registry answer that may be hours old — and if upstream moved in the meantime,
     * it reports <em>update available</em> on the image they have just updated. That is the exact opposite of
     * what they asked for, delivered at the precise moment they are testing whether Vaier can be trusted.
     *
     * <p>So this is not "the same but faster to be safe" — the two methods answer materially different
     * questions, and the caller picks by whether a remembered answer could be wrong in a way that matters.
     * Costly by design: every call is a real manifest request against a rate limit, so the domain rations it.
     *
     * <p>Empty means "cannot tell" here exactly as above, and a failure to refresh must not discard whatever
     * good answer the implementation already held.
     */
    Optional<String> resolveDigestNow(ImageReference reference);
}
