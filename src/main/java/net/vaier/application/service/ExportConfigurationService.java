package net.vaier.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.ExportConfigurationUseCase;
import net.vaier.domain.PeerType;
import net.vaier.domain.port.ForGettingPeerConfigurations;
import net.vaier.domain.port.ForPersistingDnsRecords;
import net.vaier.domain.port.ForPersistingReverseProxyRoutes;
import net.vaier.config.ConfigResolver;
import net.vaier.domain.port.ForPersistingUsers;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
@Slf4j
public class ExportConfigurationService implements ExportConfigurationUseCase {

    private final ForGettingPeerConfigurations forGettingPeerConfigurations;
    private final ForPersistingReverseProxyRoutes forPersistingReverseProxyRoutes;
    private final ForPersistingDnsRecords forPersistingDnsRecords;
    private final ForPersistingUsers forPersistingUsers;
    private final ObjectMapper objectMapper;
    private final ConfigResolver configResolver;

    public ExportConfigurationService(
            ForGettingPeerConfigurations forGettingPeerConfigurations,
            ForPersistingReverseProxyRoutes forPersistingReverseProxyRoutes,
            ForPersistingDnsRecords forPersistingDnsRecords,
            ForPersistingUsers forPersistingUsers,
            ConfigResolver configResolver) {
        this.forGettingPeerConfigurations = forGettingPeerConfigurations;
        this.forPersistingReverseProxyRoutes = forPersistingReverseProxyRoutes;
        this.forPersistingDnsRecords = forPersistingDnsRecords;
        this.forPersistingUsers = forPersistingUsers;
        this.objectMapper = new ObjectMapper();
        this.configResolver = configResolver;
    }

    @Override
    public String exportConfiguration() {
        try {
            BackupDto backup = new BackupDto(
                    "1",
                    Instant.now().toString(),
                    new SettingsDto(configResolver.getDomain()),
                    exportPeers(),
                    exportServices(),
                    exportDnsZones(),
                    exportUsers()
            );
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(backup);
        } catch (Exception e) {
            log.error("Failed to export configuration", e);
            throw new RuntimeException("Export failed: " + e.getMessage(), e);
        }
    }

    private List<PeerDto> exportPeers() {
        return forGettingPeerConfigurations.getAllPeerConfigs().stream()
                .map(p -> new PeerDto(p.name(), p.ipAddress(), p.peerType(), p.lanCidr(), p.configContent()))
                .toList();
    }

    private List<ServiceDto> exportServices() {
        return forPersistingReverseProxyRoutes.getReverseProxyRoutes().stream()
                .map(r -> new ServiceDto(
                        r.getName(),
                        r.getDomainName(),
                        r.getAddress(),
                        r.getPort(),
                        r.getAuthInfo() != null,
                        r.getRootRedirectPath()))
                .toList();
    }

    private List<DnsZoneDto> exportDnsZones() {
        return forPersistingDnsRecords.getDnsZones().stream()
                .map(zone -> {
                    List<DnsRecordDto> records = forPersistingDnsRecords.getDnsRecords(zone).stream()
                            .map(r -> new DnsRecordDto(r.name(), r.type().name(), r.ttl(), r.values()))
                            .toList();
                    return new DnsZoneDto(zone.name(), records);
                })
                .toList();
    }

    private List<UserDto> exportUsers() {
        return forPersistingUsers.getUsers().stream()
                .map(u -> new UserDto(u.getName()))
                .toList();
    }

    // DTO records for JSON serialization

    public record BackupDto(
            String version,
            String exportedAt,
            SettingsDto settings,
            List<PeerDto> peers,
            List<ServiceDto> services,
            List<DnsZoneDto> dnsZones,
            List<UserDto> users) {}

    public record SettingsDto(String domain) {}

    public record PeerDto(
            String name,
            String ipAddress,
            PeerType peerType,
            String lanCidr,
            String configContent) {}

    public record ServiceDto(
            String name,
            String domainName,
            String address,
            int port,
            boolean requiresAuth,
            String rootRedirectPath) {}

    public record DnsZoneDto(String name, List<DnsRecordDto> records) {}

    public record DnsRecordDto(String name, String type, Long ttl, List<String> values) {}

    public record UserDto(String username) {}
}
