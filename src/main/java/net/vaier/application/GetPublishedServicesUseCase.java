package net.vaier.application;

import net.vaier.domain.DnsState;
import net.vaier.domain.Server.State;
import java.util.List;

public interface GetPublishedServicesUseCase {

    List<PublishedServiceUco> getPublishedServices();

    record PublishedServiceUco(
        String name,
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
        String versionProperty
    ){
        public PublishedServiceUco(String name, String dnsAddress, DnsState dnsState, String hostAddress,
                                   int hostPort, State state, boolean authenticated,
                                   String rootRedirectPath, boolean directUrlDisabled) {
            this(name, dnsAddress, dnsState, hostAddress, hostPort, state, authenticated,
                rootRedirectPath, directUrlDisabled, false, null, false, null);
        }
        public PublishedServiceUco(String name, String dnsAddress, DnsState dnsState, String hostAddress,
                                   int hostPort, State state, boolean authenticated,
                                   String rootRedirectPath, boolean directUrlDisabled, boolean isLanService) {
            this(name, dnsAddress, dnsState, hostAddress, hostPort, state, authenticated,
                rootRedirectPath, directUrlDisabled, isLanService, null, false, null);
        }
        public PublishedServiceUco(String name, String dnsAddress, DnsState dnsState, String hostAddress,
                                   int hostPort, State state, boolean authenticated,
                                   String rootRedirectPath, boolean directUrlDisabled, boolean isLanService,
                                   String pathPrefix) {
            this(name, dnsAddress, dnsState, hostAddress, hostPort, state, authenticated,
                rootRedirectPath, directUrlDisabled, isLanService, pathPrefix, false, null);
        }
        public PublishedServiceUco(String name, String dnsAddress, DnsState dnsState, String hostAddress,
                                   int hostPort, State state, boolean authenticated,
                                   String rootRedirectPath, boolean directUrlDisabled, boolean isLanService,
                                   String pathPrefix, boolean hiddenFromLaunchpad) {
            this(name, dnsAddress, dnsState, hostAddress, hostPort, state, authenticated,
                rootRedirectPath, directUrlDisabled, isLanService, pathPrefix, hiddenFromLaunchpad, null);
        }
        public PublishedServiceUco(String name, String dnsAddress, DnsState dnsState, String hostAddress,
                                   int hostPort, State state, boolean authenticated,
                                   String rootRedirectPath, boolean directUrlDisabled, boolean isLanService,
                                   String pathPrefix, boolean hiddenFromLaunchpad, String launchpadAlias) {
            this(name, dnsAddress, dnsState, hostAddress, hostPort, state, authenticated,
                rootRedirectPath, directUrlDisabled, isLanService, pathPrefix, hiddenFromLaunchpad,
                launchpadAlias, null, null);
        }
    }
}
