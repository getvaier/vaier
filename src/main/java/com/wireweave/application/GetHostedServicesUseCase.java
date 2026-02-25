package com.wireweave.application;

import com.wireweave.domain.DnsState;
import com.wireweave.domain.Server.State;
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
