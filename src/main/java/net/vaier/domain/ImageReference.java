package net.vaier.domain;

import java.util.Locale;
import java.util.Optional;

/**
 * A container image name, parsed into the three things a registry needs to answer "what digest do you serve
 * for this tag today?": the <b>registry host</b>, the <b>repository</b>, and the <b>tag</b>.
 *
 * <p>Parsing is a domain decision, not string handling, because the rules decide <em>which registry Vaier
 * asks</em> — and asking the wrong one is indistinguishable from an image being up to date. Docker's naming
 * grammar is famously implicit: {@code redis} means {@code registry-1.docker.io/library/redis:latest}, while
 * {@code lscr.io/linuxserver/wireguard} means a different host entirely, and the only thing separating the
 * two is whether the first segment <em>looks like a host</em> (contains a dot or a colon, or is
 * {@code localhost}). {@code linuxserver/wireguard} is a Docker Hub namespace; {@code quay.io/prometheus} is
 * not.
 *
 * <p>An image that cannot drift has no reference: one pinned by {@code @sha256:…} is already exactly what was
 * asked for, and a bare image id is not a name at all. Both parse to {@link Optional#empty()} so the caller
 * renders them <b>unknown</b> rather than guessing.
 *
 * @param registry   the registry host to ask, e.g. {@code registry-1.docker.io} or {@code lscr.io}
 * @param repository the repository path within that registry, e.g. {@code library/redis}
 * @param tag        the tag whose digest is to be resolved, e.g. {@code latest}
 */
public record ImageReference(String registry, String repository, String tag) {

    /** The registry Docker Hub images are actually pulled from — {@code docker.io} is only ever an alias. */
    public static final String DOCKER_HUB_REGISTRY = "registry-1.docker.io";

    /** The namespace Docker Hub gives single-segment official images: {@code redis} → {@code library/redis}. */
    private static final String DOCKER_HUB_OFFICIAL_NAMESPACE = "library/";

    /** The tag Docker assumes when a name carries none. Floating, and the reason digests are compared at all. */
    private static final String DEFAULT_TAG = "latest";

    /**
     * Parse an image as Docker reports it, or empty when it names nothing a registry could be asked about —
     * a null/blank string, a digest-pinned image, or a bare image id.
     */
    public static Optional<ImageReference> parse(String image) {
        if (image == null || image.isBlank()) {
            return Optional.empty();
        }
        String name = image.strip();
        // Pinned by digest, or a bare image id: immutable, and so never "out of date".
        if (name.contains("@") || name.toLowerCase(Locale.ROOT).startsWith("sha256:")) {
            return Optional.empty();
        }

        String registry = DOCKER_HUB_REGISTRY;
        String remainder = name;
        int firstSlash = name.indexOf('/');
        if (firstSlash > 0 && looksLikeRegistryHost(name.substring(0, firstSlash))) {
            registry = name.substring(0, firstSlash);
            remainder = name.substring(firstSlash + 1);
        }

        String repository = remainder;
        String tag = DEFAULT_TAG;
        // Only a colon *after* the last slash is a tag separator — a registry port's colon comes before one.
        int tagColon = remainder.lastIndexOf(':');
        if (tagColon > remainder.lastIndexOf('/')) {
            repository = remainder.substring(0, tagColon);
            tag = remainder.substring(tagColon + 1);
        }

        if (repository.isBlank() || tag.isBlank()) {
            return Optional.empty();
        }
        if (DOCKER_HUB_REGISTRY.equals(registry) && !repository.contains("/")) {
            repository = DOCKER_HUB_OFFICIAL_NAMESPACE + repository;
        }
        return Optional.of(new ImageReference(registry, repository, tag));
    }

    /**
     * Whether a name's first segment is a registry host rather than a Docker Hub namespace. Docker's own rule:
     * it must contain a dot or a colon, or be exactly {@code localhost}. This is what keeps
     * {@code linuxserver/wireguard} pointed at Docker Hub and {@code lscr.io/linuxserver/wireguard} pointed at
     * lscr.io.
     */
    private static boolean looksLikeRegistryHost(String segment) {
        return segment.contains(".") || segment.contains(":") || "localhost".equals(segment);
    }

    /** The fully-qualified name, with every implicit default made explicit. The cache key for a lookup. */
    public String canonical() {
        return registry + "/" + repository + ":" + tag;
    }
}
