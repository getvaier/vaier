package net.vaier.application;

import net.vaier.domain.LaunchpadVisibility;
import java.util.List;

public interface GetLaunchpadServicesUseCase {

    List<LaunchpadServiceUco> getLaunchpadServices(String callerIp);

    /**
     * The launchpad's slice of a published service. {@code visibility} is a domain-derived
     * tri-state — the launchpad client only needs to render it, not interpret individual reasons
     * (operator hidden, DNS not propagated, backend unreachable, …). NOT_VISIBLE entries are
     * filtered out by the use case and never appear in the returned list.
     */
    record LaunchpadServiceUco(String dnsAddress, String pathPrefix, String hostAddress,
                               LaunchpadVisibility visibility, String url) {}
}
