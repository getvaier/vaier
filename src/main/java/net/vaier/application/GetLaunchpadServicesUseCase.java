package net.vaier.application;

import net.vaier.domain.LaunchpadVisibility;
import java.util.List;

public interface GetLaunchpadServicesUseCase {

    List<LaunchpadServiceUco> getLaunchpadServices(String callerIp);

    /**
     * The launchpad's slice of a published service. The domain decides everything the client
     * needs to render:
     * <ul>
     *   <li>{@code displayName} — the label for the tile (operator alias, or last path segment,
     *       or first DNS label). The launchpad never re-derives this from {@code dnsAddress} /
     *       {@code pathPrefix}.</li>
     *   <li>{@code visibility} — tri-state outcome (NOT_VISIBLE entries are filtered out by the
     *       use case and never appear in this list).</li>
     * </ul>
     * {@code dnsAddress} and {@code pathPrefix} are still here because the client uses them to
     * derive the peer / subdomain sub-line and the browser-tab target.
     */
    record LaunchpadServiceUco(String dnsAddress, String pathPrefix, String hostAddress,
                               LaunchpadVisibility visibility, String url, String displayName) {}
}
