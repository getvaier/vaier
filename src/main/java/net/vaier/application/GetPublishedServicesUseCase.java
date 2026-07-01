package net.vaier.application;

import net.vaier.domain.DnsState;
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
     * @param healthy          true when both DNS and Traefik backend health are OK; computed in
     *                         the domain so the browser doesn't recombine {@code dnsState} and
     *                         {@code state}.
     * @param image            the Docker image reference of the container backing this route
     *                         (e.g. {@code grafana/grafana:11.3.0}), or null when no container
     *                         backs the route — e.g. a service published as a bare LAN host:port.
     *                         Resolved the same way the launchpad does (issue #245).
     * @param version          the running version of the backing container, or the probed value
     *                         from a configured {@code versionEndpoint} when set (the endpoint
     *                         takes precedence over the container's image tag). Null when nothing
     *                         backs the route and no endpoint is configured.
     */
    record PublishedServiceUco(
        String name,
        String shortName,
        String hostName,
        String lanServerName,
        ServiceLocation serviceLocation,
        boolean healthy,
        String dnsAddress,
        DnsState dnsState,
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
        String authMode
    ){
        private static String legacyAuthMode(boolean authenticated) {
            return (authenticated ? net.vaier.domain.AuthMode.SOCIAL : net.vaier.domain.AuthMode.NONE).wireValue();
        }
        public PublishedServiceUco(String name, String dnsAddress, DnsState dnsState, String hostAddress,
                                   int hostPort, State state, boolean authenticated,
                                   String rootRedirectPath, boolean directUrlDisabled) {
            this(name, name, "", null, ServiceLocation.VAIER_SERVER, dnsState == DnsState.OK && state == State.OK,
                dnsAddress, dnsState, hostAddress, hostPort, state, authenticated,
                rootRedirectPath, directUrlDisabled, false, null, false, null, null, null, null, null,
                legacyAuthMode(authenticated));
        }
        public PublishedServiceUco(String name, String dnsAddress, DnsState dnsState, String hostAddress,
                                   int hostPort, State state, boolean authenticated,
                                   String rootRedirectPath, boolean directUrlDisabled, boolean isLanService) {
            this(name, name, "", null,
                isLanService ? ServiceLocation.LAN_SERVICE : ServiceLocation.VAIER_SERVER,
                dnsState == DnsState.OK && state == State.OK,
                dnsAddress, dnsState, hostAddress, hostPort, state, authenticated,
                rootRedirectPath, directUrlDisabled, isLanService, null, false, null, null, null, null, null,
                legacyAuthMode(authenticated));
        }
        public PublishedServiceUco(String name, String dnsAddress, DnsState dnsState, String hostAddress,
                                   int hostPort, State state, boolean authenticated,
                                   String rootRedirectPath, boolean directUrlDisabled, boolean isLanService,
                                   String pathPrefix) {
            this(name, name, "", null,
                isLanService ? ServiceLocation.LAN_SERVICE : ServiceLocation.VAIER_SERVER,
                dnsState == DnsState.OK && state == State.OK,
                dnsAddress, dnsState, hostAddress, hostPort, state, authenticated,
                rootRedirectPath, directUrlDisabled, isLanService, pathPrefix, false, null, null, null, null, null,
                legacyAuthMode(authenticated));
        }
        public PublishedServiceUco(String name, String dnsAddress, DnsState dnsState, String hostAddress,
                                   int hostPort, State state, boolean authenticated,
                                   String rootRedirectPath, boolean directUrlDisabled, boolean isLanService,
                                   String pathPrefix, boolean hiddenFromLaunchpad) {
            this(name, name, "", null,
                isLanService ? ServiceLocation.LAN_SERVICE : ServiceLocation.VAIER_SERVER,
                dnsState == DnsState.OK && state == State.OK,
                dnsAddress, dnsState, hostAddress, hostPort, state, authenticated,
                rootRedirectPath, directUrlDisabled, isLanService, pathPrefix, hiddenFromLaunchpad,
                null, null, null, null, null, legacyAuthMode(authenticated));
        }
        public PublishedServiceUco(String name, String dnsAddress, DnsState dnsState, String hostAddress,
                                   int hostPort, State state, boolean authenticated,
                                   String rootRedirectPath, boolean directUrlDisabled, boolean isLanService,
                                   String pathPrefix, boolean hiddenFromLaunchpad, String launchpadAlias) {
            this(name, name, "", null,
                isLanService ? ServiceLocation.LAN_SERVICE : ServiceLocation.VAIER_SERVER,
                dnsState == DnsState.OK && state == State.OK,
                dnsAddress, dnsState, hostAddress, hostPort, state, authenticated,
                rootRedirectPath, directUrlDisabled, isLanService, pathPrefix, hiddenFromLaunchpad,
                launchpadAlias, null, null, null, null, legacyAuthMode(authenticated));
        }
    }
}
