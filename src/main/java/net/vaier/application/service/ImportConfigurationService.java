package net.vaier.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.AddDnsRecordUseCase;
import net.vaier.application.AddReverseProxyRouteUseCase;
import net.vaier.application.AddUserUseCase;
import net.vaier.application.ImportConfigurationUseCase;
import net.vaier.domain.DnsZone;
import net.vaier.domain.PeerType;
import net.vaier.domain.port.ForGettingPeerConfigurations;
import net.vaier.domain.port.ForPersistingDnsRecords;
import net.vaier.domain.port.ForPublishingEvents;
import net.vaier.domain.port.ForRestoringVpnPeers;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@Slf4j
public class ImportConfigurationService implements ImportConfigurationUseCase {

    public static final String IMPORT_TOPIC = "settings-import";

    static final Set<String> SUPPORTED_IMPORT_VERSIONS = Set.of("1");

    private final ForPersistingDnsRecords forPersistingDnsRecords;
    private final AddDnsRecordUseCase addDnsRecordUseCase;
    private final AddReverseProxyRouteUseCase addReverseProxyRouteUseCase;
    private final AddUserUseCase addUserUseCase;
    private final ForRestoringVpnPeers forRestoringVpnPeers;
    private final ForPublishingEvents forPublishingEvents;
    private final ObjectMapper objectMapper;

    public ImportConfigurationService(
            ForPersistingDnsRecords forPersistingDnsRecords,
            AddDnsRecordUseCase addDnsRecordUseCase,
            AddReverseProxyRouteUseCase addReverseProxyRouteUseCase,
            AddUserUseCase addUserUseCase,
            ForRestoringVpnPeers forRestoringVpnPeers,
            ForPublishingEvents forPublishingEvents) {
        this.forPersistingDnsRecords = forPersistingDnsRecords;
        this.addDnsRecordUseCase = addDnsRecordUseCase;
        this.addReverseProxyRouteUseCase = addReverseProxyRouteUseCase;
        this.addUserUseCase = addUserUseCase;
        this.forRestoringVpnPeers = forRestoringVpnPeers;
        this.forPublishingEvents = forPublishingEvents;
        this.objectMapper = new ObjectMapper();
    }

    private void emit(String message) {
        forPublishingEvents.publish(IMPORT_TOPIC, "log", message);
    }

    @Override
    public ImportResult importConfiguration(String jsonContent) {
        ExportConfigurationService.BackupDto backup;
        try {
            backup = objectMapper.readValue(jsonContent, ExportConfigurationService.BackupDto.class);
        } catch (Exception e) {
            log.error("Invalid backup JSON: {}", e.getMessage());
            return new ImportResult(false, "Invalid backup file: " + e.getMessage(), List.of());
        }

        String version = backup.version();
        if (version == null || !SUPPORTED_IMPORT_VERSIONS.contains(version)) {
            String msg = "Unsupported backup version: " + version
                    + " (supported: " + SUPPORTED_IMPORT_VERSIONS + ")";
            log.warn(msg);
            return new ImportResult(false, msg, List.of());
        }

        List<String> warnings = new ArrayList<>();

        emit("Starting import (backup version: " + backup.version() + ")");

        importPeers(backup.peers(), warnings);
        importServices(backup.services(), warnings);
        importDnsZones(backup.dnsZones(), warnings);
        importUsers(backup.users(), warnings);

        emit("Import complete");
        return new ImportResult(true, "Import completed", warnings);
    }

    private void importPeers(List<ExportConfigurationService.PeerDto> peers, List<String> warnings) {
        if (peers == null || peers.isEmpty()) return;
        emit("[peers] " + peers.size() + " peer(s)");
        for (var peer : peers) {
            try {
                PeerType peerType = peer.peerType() != null ? peer.peerType() : PeerType.UBUNTU_SERVER;
                forRestoringVpnPeers.restorePeer(
                        new ForGettingPeerConfigurations.PeerConfiguration(
                                peer.name(), peer.ipAddress(), peer.configContent(),
                                peerType, peer.lanCidr()));
                emit("[peers] " + peer.name() + " -> ok");
            } catch (Exception e) {
                log.warn("Failed to restore peer {}: {}", peer.name(), e.getMessage());
                emit("[peers] " + peer.name() + " -> failed: " + e.getMessage());
                warnings.add("Peer '" + peer.name() + "' could not be restored: " + e.getMessage());
            }
        }
    }

    private void importServices(List<ExportConfigurationService.ServiceDto> services, List<String> warnings) {
        if (services == null || services.isEmpty()) return;
        emit("[services] " + services.size() + " service(s)");
        for (var service : services) {
            try {
                addReverseProxyRouteUseCase.addReverseProxyRoute(
                        new AddReverseProxyRouteUseCase.ReverseProxyRouteUco(
                                service.domainName(), service.address(), service.port(),
                                service.requiresAuth(), service.rootRedirectPath()));
                emit("[services] " + service.name() + " -> ok");
            } catch (Exception e) {
                log.warn("Failed to restore service {}: {}", service.name(), e.getMessage());
                emit("[services] " + service.name() + " -> failed: " + e.getMessage());
                warnings.add("Service '" + service.name() + "' could not be restored: " + e.getMessage());
            }
        }
    }

    private void importDnsZones(List<ExportConfigurationService.DnsZoneDto> dnsZones, List<String> warnings) {
        if (dnsZones == null || dnsZones.isEmpty()) return;
        int totalRecords = dnsZones.stream().mapToInt(z -> z.records() == null ? 0 : z.records().size()).sum();
        emit("[dns] " + dnsZones.size() + " zone(s), " + totalRecords + " record(s)");

        var existingZoneNames = forPersistingDnsRecords.getDnsZones().stream()
                .map(DnsZone::name)
                .collect(java.util.stream.Collectors.toSet());

        for (var zone : dnsZones) {
            if (existingZoneNames.contains(zone.name())) {
                emit("[dns] zone " + zone.name() + " -> already exists, skipping");
            } else {
                try {
                    forPersistingDnsRecords.addDnsZone(new DnsZone(zone.name()));
                    emit("[dns] zone " + zone.name() + " -> ok");
                } catch (Exception e) {
                    log.warn("Failed to restore DNS zone {}: {}", zone.name(), e.getMessage());
                    emit("[dns] zone " + zone.name() + " -> failed: " + e.getMessage());
                    warnings.add("DNS zone '" + zone.name() + "' could not be restored: " + e.getMessage());
                }
            }

            if (zone.records() == null) continue;
            for (var record : zone.records()) {
                try {
                    addDnsRecordUseCase.addDnsRecord(
                            new AddDnsRecordUseCase.DnsRecordUco(
                                    record.name(), record.type(), record.ttl(), record.values()),
                            zone.name());
                    emit("[dns] " + zone.name() + " / " + record.name() + " (" + record.type() + ") -> ok");
                } catch (Exception e) {
                    log.warn("Failed to restore DNS record {}/{}: {}", zone.name(), record.name(), e.getMessage());
                    emit("[dns] " + zone.name() + " / " + record.name() + " -> failed: " + e.getMessage());
                    warnings.add("DNS record '" + record.name() + "' in zone '" + zone.name()
                            + "' could not be restored: " + e.getMessage());
                }
            }
        }
    }

    private void importUsers(List<ExportConfigurationService.UserDto> users, List<String> warnings) {
        if (users == null || users.isEmpty()) return;
        emit("[users] " + users.size() + " user(s)");
        for (var user : users) {
            try {
                String tempPassword = UUID.randomUUID().toString() + "A1";
                String email = (user.email() == null || user.email().isBlank())
                        ? user.username() + "@placeholder.invalid"
                        : user.email();
                String displayname = (user.displayname() == null || user.displayname().isBlank())
                        ? user.username()
                        : user.displayname();
                addUserUseCase.addUser(user.username(), tempPassword, email, displayname);
                emit("[users] " + user.username() + " -> created (reset password required)");
                warnings.add("User '" + user.username()
                        + "' was created with a temporary password — please reset it");
            } catch (Exception e) {
                log.warn("Failed to restore user {}: {}", user.username(), e.getMessage());
                emit("[users] " + user.username() + " -> failed: " + e.getMessage());
                warnings.add("User '" + user.username() + "' could not be restored: " + e.getMessage());
            }
        }
    }
}
