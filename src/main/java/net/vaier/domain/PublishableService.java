package net.vaier.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A discovered service eligible for publishing. Its {@code source} indicates where it was
 * discovered (Vaier server, server peer, or Docker-enabled LAN server). Owns the publishing
 * identity rules ({@link #ignoreKey()}, {@link #suggestedSubdomain()}) so the frontend and the
 * ignored-services persistence layer never recompute them and drift apart.
 */
public record PublishableService(
    PublishableSource source,
    String peerName,
    String address,
    String containerName,
    int port,
    String rootRedirectPath,
    boolean ignored
) {
    public enum PublishableSource { VAIER_SERVER, PEER, LAN_SERVER }

    // Stable identity used by the ignored-services persistence layer. Lives on the entity so the
    // frontend doesn't recompute it from the source enum (and so the two can never drift apart).
    @JsonProperty("ignoreKey")
    public String ignoreKey() {
        return switch (source) {
            case PEER         -> peerName + "/" + containerName + ":" + port;
            case LAN_SERVER   -> address  + "/" + containerName + ":" + port;
            case VAIER_SERVER -> containerName + ":" + port;
        };
    }

    /**
     * The subdomain the publish modal pre-fills. Vaier-server services get the container name
     * alone; peer- and LAN-hosted services get {@code containerName.peerName} so multiple peers
     * publishing the same container don't collide on one DNS label.
     */
    @JsonProperty("suggestedSubdomain")
    public String suggestedSubdomain() {
        return source == PublishableSource.VAIER_SERVER
            ? containerName
            : containerName + "." + peerName;
    }
}
