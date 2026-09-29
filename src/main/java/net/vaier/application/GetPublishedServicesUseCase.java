package net.vaier.application;

import lombok.Builder;
import net.vaier.domain.AuthMode;
import net.vaier.domain.ReverseProxyRoute.ServiceLocation;
import net.vaier.domain.Server.State;
import java.util.List;

public interface GetPublishedServicesUseCase {

    List<PublishedServiceUco> getPublishedServices();

    /**
     * @param name             the composite display label, kept for backwards compatibility. New
     *                         consumers should read {@link #shortName} and {@link #hostName}
     *                         separately rather than splitting this on {@code " @ "}.
     * @param shortName        the operator-facing service label without the host suffix.
     * @param hostName         the display name of the machine hosting this route — for a LAN
     *                         service this is the relay peer, not the LAN server (the section
     *                         heading on the Services page groups by this).
     * @param lanServerName    the display name of the LAN server targeted by the route, or null
     *                         when the route isn't a LAN service / no registered LAN server
     *                         matches the address. Surfaced in the card sub-line so the operator
     *                         sees the actual host even though the section names the relay.
     * @param serviceLocation  where the backing service runs — drives icon choice and grouping.
     * @param healthy          true when the backing host is reachable. Every published name resolves
     *                         from the moment its route is written (#331), so host health is the
     *                         whole question; computed here so the browser never recombines signals.
     * @param image            the Docker image reference of the container backing this route
     *                         (e.g. {@code grafana/grafana:11.3.0}), or null when no container
     *                         backs the route — e.g. a service published as a bare LAN host:port.
     *                         Resolved the same way the launchpad does (issue #245).
     * @param version          the running version of the backing container, or the probed value
     *                         from a configured {@code versionEndpoint} when set (the endpoint
     *                         takes precedence over the container's image tag). Null when nothing
     *                         backs the route and no endpoint is configured.
     */
    @Builder(toBuilder = true)
    record PublishedServiceUco(
        String name,
        String shortName,
        /** The identity of the machine the backend runs on; null when nothing bears its address. */
        String machineId,
        String hostName,
        String lanServerName,
        ServiceLocation serviceLocation,
        boolean healthy,
        String dnsAddress,
        String hostAddress,
        int hostPort,
        State state,
        boolean authenticated,
        String rootRedirectPath,
        boolean directUrlDisabled,
        boolean isLanService,
        String pathPrefix,
        boolean hiddenFromLaunchpad,
        String launchpadAlias,
        String versionEndpoint,
        String versionProperty,
        String image,
        String version,
        /**
         * The route's auth mode wire value ({@code none}/{@code social}). Read off the route's
         * middleware chain so the UI auth-mode picker reflects the live gateway. {@code authenticated}
         * stays for callers that only need "is it gated at all".
         */
        String authMode,
        /**
         * True when this is a stream — a TCP service published by SNI on the HTTPS port. It has no
         * URL to open, so the UI shows {@link #connectAddress} instead of a link.
         */
        boolean stream,
        /** Where a client dials a stream ({@code <fqdn>:443}); null for an HTTP(S) service. */
        String connectAddress,
        /** <b>Ask before reading</b>: every GET Marvin makes here waits for a yes. */
        boolean askBeforeReading
    ){
        private static String legacyAuthMode(boolean authenticated) {
            return (authenticated ? AuthMode.SOCIAL : AuthMode.NONE).wireValue();
        }
        public PublishedServiceUco(String name, String dnsAddress, String hostAddress,
                                   int hostPort, State state, boolean authenticated,
                                   String rootRedirectPath, boolean directUrlDisabled) {
            this(name, name, null, "", null, ServiceLocation.VAIER_SERVER, state == State.OK,
                dnsAddress, hostAddress, hostPort, state, authenticated,
                rootRedirectPath, directUrlDisabled, false, null, false, null, null, null, null, null,
                legacyAuthMode(authenticated), false, null, false);
        }
        public PublishedServiceUco(String name, String dnsAddress, String hostAddress,
                                   int hostPort, State state, boolean authenticated,
                                   String rootRedirectPath, boolean directUrlDisabled, boolean isLanService) {
            this(name, name, null, "", null,
                isLanService ? ServiceLocation.LAN_SERVICE : ServiceLocation.VAIER_SERVER,
                state == State.OK,
                dnsAddress, hostAddress, hostPort, state, authenticated,
                rootRedirectPath, directUrlDisabled, isLanService, null, false, null, null, null, null, null,
                legacyAuthMode(authenticated), false, null, false);
        }
        public PublishedServiceUco(String name, String dnsAddress, String hostAddress,
                                   int hostPort, State state, boolean authenticated,
                                   String rootRedirectPath, boolean directUrlDisabled, boolean isLanService,
                                   String pathPrefix) {
            this(name, name, null, "", null,
                isLanService ? ServiceLocation.LAN_SERVICE : ServiceLocation.VAIER_SERVER,
                state == State.OK,
                dnsAddress, hostAddress, hostPort, state, authenticated,
                rootRedirectPath, directUrlDisabled, isLanService, pathPrefix, false, null, null, null, null, null,
                legacyAuthMode(authenticated), false, null, false);
        }
        public PublishedServiceUco(String name, String dnsAddress, String hostAddress,
                                   int hostPort, State state, boolean authenticated,
                                   String rootRedirectPath, boolean directUrlDisabled, boolean isLanService,
                                   String pathPrefix, boolean hiddenFromLaunchpad) {
            this(name, name, null, "", null,
                isLanService ? ServiceLocation.LAN_SERVICE : ServiceLocation.VAIER_SERVER,
                state == State.OK,
                dnsAddress, hostAddress, hostPort, state, authenticated,
                rootRedirectPath, directUrlDisabled, isLanService, pathPrefix, hiddenFromLaunchpad,
                null, null, null, null, null, legacyAuthMode(authenticated), false, null, false);
        }
        public PublishedServiceUco(String name, String dnsAddress, String hostAddress,
                                   int hostPort, State state, boolean authenticated,
                                   String rootRedirectPath, boolean directUrlDisabled, boolean isLanService,
                                   String pathPrefix, boolean hiddenFromLaunchpad, String launchpadAlias) {
            this(name, name, null, "", null,
                isLanService ? ServiceLocation.LAN_SERVICE : ServiceLocation.VAIER_SERVER,
                state == State.OK,
                dnsAddress, hostAddress, hostPort, state, authenticated,
                rootRedirectPath, directUrlDisabled, isLanService, pathPrefix, hiddenFromLaunchpad,
                launchpadAlias, null, null, null, null, legacyAuthMode(authenticated), false, null, false);
        }
    }
}
