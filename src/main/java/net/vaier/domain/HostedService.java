package net.vaier.domain;

import net.vaier.domain.DnsRecord.DnsRecordType;
import net.vaier.domain.Server.State;
import net.vaier.domain.port.ForGettingServerInfo;
import net.vaier.domain.port.ForGettingVpnClients;
import net.vaier.domain.port.ForPersistingDnsRecords;
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
    private final ForGettingServerInfo forGettingServerInfo;
    private final ForGettingVpnClients forGettingVpnClients;

    public DnsState dnsState() {
        Optional<DnsRecord> dnsRecord = forPersistingDnsRecords.getDnsZones().stream()
            .flatMap(zone -> forPersistingDnsRecords.getDnsRecords(zone).stream())
            .filter(record -> record.name().equals(dnsAddress))
            .filter(record -> record.type() == DnsRecordType.CNAME || record.type() == DnsRecordType.A)
            .findFirst();
        if(dnsRecord.isEmpty()) {
            return DnsState.NON_EXISTING;
        }
        return DnsState.OK;
    }

    public State hostState() {
        Optional<DockerService> dockerService = forGettingServerInfo.getServicesWithExposedPorts(Server.local())
            .stream()
            .filter(service -> service.listensOnPort(hostPort))
            .findFirst();
        if(dockerService.isPresent()) {
            return State.OK;
        }
        Optional<VpnClient> wireGuardPeer = forGettingVpnClients.getClients().stream()
            .peek(peer -> System.out.println("Processing peer: " + peer))
            .filter(peer -> peer.endpointIp().equals(hostAddress))
            .findFirst();
        if(wireGuardPeer.isPresent()) {
            return State.OK;
        }
        return State.UNREACHABLE;
    }
}
