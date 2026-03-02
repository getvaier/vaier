package net.vaier.application;

import net.vaier.domain.DnsState;
import net.vaier.domain.Server.State;
import java.util.List;

public interface GetHostedServicesUseCase {

    List<HostedServiceUco> getHostedServices();

    record HostedServiceUco(
        String name,
        String dnsAddress,
        DnsState dnsState,
        String hostAddress,
        int hostPort,
        State state,
        boolean authenticated
    ){

    }
}
