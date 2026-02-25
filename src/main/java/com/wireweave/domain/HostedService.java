package com.wireweave.domain;

import com.wireweave.application.GetHostedServicesUseCase.HostedServiceUco.DnsState;
import com.wireweave.application.GetHostedServicesUseCase.HostedServiceUco.HostState;
import com.wireweave.domain.DnsRecord.DnsRecordType;
import com.wireweave.domain.port.ForGettingDockerInfo;
import com.wireweave.domain.port.ForGettingVpnClients;
import com.wireweave.domain.port.ForPersistingDnsRecords;
import java.util.Optional;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class HostedService {
    private final String name;
    private final String dnsAddress;
    private final String hostAddress;
    private final int hostPort;
    private final boolean authenticated;

    private final ForPersistingDnsRecords forPersistingDnsRecords;
    private final ForGettingDockerInfo forGettingDockerInfo;
    private final ForGettingVpnClients forGettingVpnClients;

    public DnsState dnsState() {
        Optional<DnsRecord> dnsRecord = forPersistingDnsRecords.getDnsZones().stream()
            .flatMap(zone -> forPersistingDnsRecords.getDnsRecords(zone).stream())
            .filter(record -> record.name().equals(dnsAddress + "."))
            .filter(record -> record.type() == DnsRecordType.CNAME)
            .findFirst();
        if(dnsRecord.isEmpty()) {
            return DnsState.NON_EXISTING;
        }
        return DnsState.OK;
    }

    public HostState hostState() {
        Optional<VpnClient> wireGuardPeer = forGettingVpnClients.getClients().stream()
            .peek(peer -> System.out.println("Processing peer: " + peer))
            .filter(peer -> peer.endpointIp().equals(hostAddress))
            .findFirst();
        if(wireGuardPeer.isPresent()) {
            return HostState.OK;
        }
        return HostState.UNREACHABLE;
    }
}
