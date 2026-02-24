package com.wireweave.application;

import java.util.List;

public interface GetHostedServicesUseCase {

    List<HostedServiceUco> getHostedServices();

    record HostedServiceUco(
        String name,
        String dnsAddress,
        DnsState dnsState,
        String hostAddress,
        int hostPort,
        HostState hostState,
        boolean authenticated
    ){
        public enum DnsState {
            OK, NON_EXISTING
        }

        public enum HostState {
            OK, UNREACHABLE, AUTH_FAILED
        }
    }
}
