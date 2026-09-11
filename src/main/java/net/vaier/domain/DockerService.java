package net.vaier.domain;

import lombok.Builder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One container as Vaier sees it on a host, plus what Vaier has decided about its image.
 *
 * @param imageDigest     the registry digest of the image this container actually runs, read from the image's
 *                        {@code RepoDigests}. Null when there is none to read — a locally-built image, or an
 *                        image whose inspect failed. Not the config sha ({@code ImageId}), which is a
 *                        different number entirely and never matches what a registry serves.
 * @param updateAvailable Vaier's verdict on this container's image. {@link UpdateAvailability#UNKNOWN} on a
 *                        freshly-scraped container: a scrape reads the host, and the verdict needs the
 *                        registry, which only the update sweep asks.
 * @param composeCoordinates how Docker Compose started this container, read from its own labels. Null when
 *                        it carries no compose labels Vaier will act on — a plain {@code docker run}.
 * @param updateEligibility whether Vaier may offer to update this container's image, and why not when it
 *                        may not. Null on a container nobody has judged yet: the verdict needs to know which
 *                        machine the container runs on, which only the scrape of that machine knows. Null is
 *                        read as "no action offered" — never as permission.
 * @param movingTag       whether this container's image tag is a <b>moving tag</b> — a nightly or edge
 *                        channel that the last two sweeps each found on a different digest. It still earns
 *                        the <b>update available</b> mark; it raises no <b>update-available alert</b>. False
 *                        on a container no sweep has judged, which is the honest reading: nothing has shown
 *                        a rhythm yet.
 */
@Builder(toBuilder = true)
public record DockerService(
        String containerId,
        String containerName,
        String image,
        String version,
        List<PortMapping> ports,
        List<String> networks,
        String state,
        String imageDigest,
        UpdateAvailability updateAvailable,
        ComposeCoordinates composeCoordinates,
        ContainerUpdateEligibility updateEligibility,
        boolean movingTag
) {

    /**
     * A container as a host scrape knows it: no registry digest, and no verdict yet. The update sweep is the
     * only thing that can supply either, so an un-swept container reads as {@link UpdateAvailability#UNKNOWN}
     * rather than quietly as up to date.
     */
    public DockerService(String containerId, String containerName, String image, String version,
                         List<PortMapping> ports, List<String> networks, String state) {
        this(containerId, containerName, image, version, ports, networks, state, null,
            UpdateAvailability.UNKNOWN, null, null, false);
    }

    /** A container known down to its update verdict, but not yet to how it was started or judged. */
    public DockerService(String containerId, String containerName, String image, String version,
                         List<PortMapping> ports, List<String> networks, String state,
                         String imageDigest, UpdateAvailability updateAvailable) {
        this(containerId, containerName, image, version, ports, networks, state, imageDigest,
            updateAvailable, null, null, false);
    }

    /** Null-safe: a record built with no verdict still reads as {@link UpdateAvailability#UNKNOWN}. */
    public DockerService {
        updateAvailable = updateAvailable == null ? UpdateAvailability.UNKNOWN : updateAvailable;
    }

    /** This container carrying {@code verdict}. The scrape stays as the host reported it. */
    public DockerService withUpdateAvailability(UpdateAvailability verdict) {
        return toBuilder().updateAvailable(verdict).build();
    }

    /**
     * This container carrying Vaier's verdict on whether its image may be updated. Everything the host
     * reported, and every other verdict, stays as it was.
     */
    public DockerService withUpdateEligibility(ContainerUpdateEligibility eligibility) {
        return toBuilder().updateEligibility(eligibility).build();
    }

    /** This container carrying whether its image tag is a <b>moving tag</b>. Everything else stays. */
    public DockerService withMovingTag(boolean movingTag) {
        return toBuilder().movingTag(movingTag).build();
    }

    /**
     * The registry digest for {@code image} out of an image's {@code RepoDigests} — the {@code sha256:…} half
     * of an entry like {@code vaultwarden/server@sha256:bbb}.
     *
     * <p>An image tagged into several repositories carries several repo digests, and only the one for the
     * repository this container runs is the right one to compare — so the entries are matched on repository
     * rather than taken blind. Where nothing matches (Docker reports the repository differently than the
     * container's image string spells it) a lone digest is taken as this image's; anything more ambiguous is
     * null, and a null digest renders unknown.
     */
    public static String digestFromRepoDigests(List<String> repoDigests, String image) {
        if (repoDigests == null || repoDigests.isEmpty()) {
            return null;
        }
        String repository = repositoryOf(image);
        for (String entry : repoDigests) {
            int at = entry == null ? -1 : entry.indexOf('@');
            if (at > 0 && repositoryOf(entry.substring(0, at)).equals(repository)) {
                return entry.substring(at + 1);
            }
        }
        if (repoDigests.size() == 1) {
            String only = repoDigests.get(0);
            int at = only == null ? -1 : only.indexOf('@');
            return at > 0 ? only.substring(at + 1) : null;
        }
        return null;
    }

    /** An image string reduced to its repository, so {@code RepoDigests} entries can be matched to it. */
    private static String repositoryOf(String image) {
        return ImageReference.parse(image).map(ImageReference::repository).orElse(image);
    }

    public boolean isRunning() {
        return isRunningState(state);
    }

    /**
     * Whether a raw Docker state string means the container is running. The same rule {@link #isRunning()}
     * answers by, reachable before a {@code DockerService} exists — a scrape has to know which containers
     * are running to decide whether it may trust the port mappings the daemon handed it, and that question
     * is the domain's to answer rather than each adapter's to re-spell.
     */
    public static boolean isRunningState(String state) {
        return "running".equalsIgnoreCase(state);
    }

    public boolean listensOnPort(int port) {
        return ports.stream()
            .anyMatch(mapping -> mapping.containsPort(port) ||
                      (mapping.publicPort() != null && mapping.publicPort() == port));
    }

    /**
     * Whether this container is attached to {@code networkName} — or to no network at all, in
     * which case it is assumed reachable by container name (some daemons report no network info).
     *
     * <p>Docker Compose names a network {@code <project>_<name>}, so the network Vaier's own stack
     * declares as {@code vaier-network} reaches the daemon — and this scrape — as
     * {@code vaier_vaier-network}. Matching the bare name alone made every container in the stack read
     * as off-network, and an off-network container is only reachable if it publishes a host port: that
     * is why Traefik's dashboard, which publishes nothing, could never be offered for publishing
     * however plainly the catalogue offered it. The project prefix is matched as a whole segment, so
     * {@code not-vaier-network} is still a different network.
     */
    public boolean isOnNetwork(String networkName) {
        return networks.isEmpty()
            || networks.stream().anyMatch(n -> n.equals(networkName) || n.endsWith("_" + networkName));
    }

    /**
     * How {@code port} of this container is reached from the Vaier server: on the Vaier network,
     * directly by container name + private port; otherwise via the Docker gateway IP + the
     * published port. Empty when the container is off-network and the port is not published.
     */
    public Optional<ServiceEndpoint> reachableEndpoint(PortMapping port, String vaierNetworkName,
                                                       String dockerGatewayIp) {
        if (isOnNetwork(vaierNetworkName)) {
            return Optional.of(new ServiceEndpoint(containerName, port.privatePort()));
        }
        if (port.publicPort() != null) {
            return Optional.of(new ServiceEndpoint(dockerGatewayIp, port.publicPort()));
        }
        return Optional.empty();
    }

    /**
     * Every address+port at which {@code port} of this container can be reached, preferred spelling first
     * — so {@code get(0)} is what {@link #reachableEndpoint} would pick and what a new route is written
     * with. A container that is both on the Vaier network and publishing a host port has two spellings of
     * the same service, and only asking about the preferred one makes an already-published service look
     * unpublished: routes written before Vaier could see the container on its own network hold the gateway
     * spelling, and would go on being offered as candidates forever.
     */
    public List<ServiceEndpoint> everyReachableEndpoint(PortMapping port, String vaierNetworkName,
                                                        String dockerGatewayIp) {
        List<ServiceEndpoint> endpoints = new ArrayList<>();
        if (isOnNetwork(vaierNetworkName)) {
            endpoints.add(new ServiceEndpoint(containerName, port.privatePort()));
        }
        if (port.publicPort() != null) {
            endpoints.add(new ServiceEndpoint(dockerGatewayIp, port.publicPort()));
        }
        return List.copyOf(endpoints);
    }

    /** An address+port at which a container's service is reachable. */
    public record ServiceEndpoint(String address, int port) {}

    public static String versionFromLabels(Map<String, String> labels, String image) {
        if (labels != null) {
            String oci = labels.get("org.opencontainers.image.version");
            if (oci != null && !oci.isBlank()) return oci;

            String labelSchema = labels.get("org.label-schema.version");
            if (labelSchema != null && !labelSchema.isBlank()) return labelSchema;

            String buildVersion = labels.get("build_version");
            if (buildVersion != null) {
                // LinuxServer.io format: "Version:- 1.0.20210914 Build-date:- ..."
                int idx = buildVersion.indexOf("Version:- ");
                if (idx >= 0) {
                    String rest = buildVersion.substring(idx + "Version:- ".length()).trim();
                    int space = rest.indexOf(' ');
                    return space > 0 ? rest.substring(0, space) : rest;
                }
            }
        }
        // Fallback: extract tag from image string
        int slashIdx = image.lastIndexOf('/');
        String nameAndTag = slashIdx >= 0 ? image.substring(slashIdx + 1) : image;
        int colonIdx = nameAndTag.indexOf(':');
        return colonIdx >= 0 ? nameAndTag.substring(colonIdx + 1) : "latest";
    }

    public record PortMapping(
            int privatePort,
            Integer lastPrivatePort,
            Integer publicPort,
            String type,
            String ip
    ) {
        public PortMapping(int privatePort, Integer publicPort, String type, String ip) {
            this(privatePort, null, publicPort, type, ip);
        }

        public boolean isRange() {
            return lastPrivatePort != null && lastPrivatePort > privatePort;
        }

        public boolean containsPort(int port) {
            int last = lastPrivatePort != null ? lastPrivatePort : privatePort;
            return port >= privatePort && port <= last;
        }

        public String toString() {
            if (isRange()) {
                return privatePort + "-" + lastPrivatePort + "/" + type;
            }
            if (publicPort != null) {
                String ipPart = (ip != null && !ip.isEmpty()) ? ip + ":" : "";
                return ipPart + publicPort + "->" + privatePort + "/" + type;
            }
            return privatePort + "/" + type;
        }

        /**
         * Group runs of consecutive {@code (port, type, ip)} tuples into a single range mapping.
         * Roon-style images expose huge contiguous ranges (e.g. {@code 9100-9339/tcp}); without
         * this collapse, one container floods the publishable-services list with hundreds of rows.
         *
         * <p>The rule belongs to the domain because it shapes {@link PortMapping} values: the
         * resulting mappings carry {@code lastPrivatePort} so downstream code can render them via
         * {@link #isRange()}. Mappings are grouped by {@code (type, ip)}, sorted by
         * {@code privatePort}, and any chain where each entry is exactly one above the previous
         * is merged. Only meaningful on data where {@code publicPort == privatePort} — i.e.
         * host-network ExposedPorts; bridge-network mappings already arrive collapsed by Docker.
         */
        public static java.util.List<PortMapping> collapseContiguous(java.util.List<PortMapping> input) {
            if (input == null || input.isEmpty()) return java.util.List.of();

            java.util.Map<String, java.util.List<PortMapping>> grouped = new java.util.LinkedHashMap<>();
            for (PortMapping pm : input) {
                String key = pm.type() + "|" + (pm.ip() == null ? "" : pm.ip());
                grouped.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(pm);
            }

            java.util.List<PortMapping> result = new java.util.ArrayList<>();
            for (java.util.List<PortMapping> group : grouped.values()) {
                group.sort(java.util.Comparator.comparingInt(PortMapping::privatePort));
                int runStart = group.get(0).privatePort();
                int runEnd = runStart;
                String type = group.get(0).type();
                String ip = group.get(0).ip();

                for (int i = 1; i < group.size(); i++) {
                    int p = group.get(i).privatePort();
                    if (p == runEnd + 1) {
                        runEnd = p;
                    } else {
                        result.add(buildRange(runStart, runEnd, type, ip));
                        runStart = p;
                        runEnd = p;
                    }
                }
                result.add(buildRange(runStart, runEnd, type, ip));
            }
            return result;
        }

        private static PortMapping buildRange(int first, int last, String type, String ip) {
            return first == last
                ? new PortMapping(first, first, type, ip)
                : new PortMapping(first, last, first, type, ip);
        }
    }
}
