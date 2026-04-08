package net.vaier.application;

import java.util.List;

public interface GetLaunchpadServicesUseCase {

    List<LaunchpadServiceUco> getLaunchpadServices();

    record LaunchpadServiceUco(String dnsAddress, String hostAddress) {}
}
