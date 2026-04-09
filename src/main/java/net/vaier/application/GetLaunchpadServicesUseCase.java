package net.vaier.application;

import net.vaier.domain.Server.State;
import java.util.List;

public interface GetLaunchpadServicesUseCase {

    List<LaunchpadServiceUco> getLaunchpadServices();

    record LaunchpadServiceUco(String dnsAddress, String hostAddress, State state) {}
}
