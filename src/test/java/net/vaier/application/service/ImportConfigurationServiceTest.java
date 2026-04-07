package net.vaier.application.service;

import net.vaier.application.AddDnsRecordUseCase;
import net.vaier.application.AddDnsZoneUseCase;
import net.vaier.application.AddReverseProxyRouteUseCase;
import net.vaier.application.AddUserUseCase;
import net.vaier.application.ForPublishingEvents;
import net.vaier.application.ImportConfigurationUseCase.ImportResult;
import net.vaier.domain.PeerType;
import net.vaier.domain.port.ForGettingPeerConfigurations;
import net.vaier.domain.port.ForRestoringVpnPeers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ImportConfigurationServiceTest {

    @Mock AddDnsZoneUseCase addDnsZoneUseCase;
    @Mock AddDnsRecordUseCase addDnsRecordUseCase;
    @Mock AddReverseProxyRouteUseCase addReverseProxyRouteUseCase;
    @Mock AddUserUseCase addUserUseCase;
    @Mock ForRestoringVpnPeers forRestoringVpnPeers;
    @Mock ForPublishingEvents forPublishingEvents;

    @InjectMocks
    ImportConfigurationService service;

    @Test
    void importConfiguration_returnsBadRequestOnInvalidJson() {
        ImportResult result = service.importConfiguration("not json at all");

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("Invalid");
    }

    @Test
    void importConfiguration_importsDnsZonesAndRecords() {
        String json = """
                {
                  "version": "1",
                  "exportedAt": "2026-04-07T12:00:00Z",
                  "settings": {},
                  "peers": [],
                  "services": [],
                  "dnsZones": [
                    {
                      "name": "example.com",
                      "records": [
                        { "name": "grafana", "type": "CNAME", "ttl": 300, "values": ["vaier.example.com"] }
                      ]
                    }
                  ],
                  "users": []
                }
                """;

        ImportResult result = service.importConfiguration(json);

        assertThat(result.success()).isTrue();
        verify(addDnsZoneUseCase).addDnsZone(new AddDnsZoneUseCase.DnsZoneUco("example.com"));
        verify(addDnsRecordUseCase).addDnsRecord(
                new AddDnsRecordUseCase.DnsRecordUco("grafana", "CNAME", 300L, java.util.List.of("vaier.example.com")),
                "example.com");
    }

    @Test
    void importConfiguration_importsServices() {
        String json = """
                {
                  "version": "1",
                  "exportedAt": "2026-04-07T12:00:00Z",
                  "settings": {},
                  "peers": [],
                  "services": [
                    { "name": "grafana", "domainName": "grafana.example.com", "address": "10.13.13.2", "port": 3000, "requiresAuth": true }
                  ],
                  "dnsZones": [],
                  "users": []
                }
                """;

        ImportResult result = service.importConfiguration(json);

        assertThat(result.success()).isTrue();
        verify(addReverseProxyRouteUseCase).addReverseProxyRoute(
                new AddReverseProxyRouteUseCase.ReverseProxyRouteUco("grafana.example.com", "10.13.13.2", 3000, true, null));
    }

    @Test
    void importConfiguration_importsUsers_andAddsWarningAboutPasswordReset() {
        String json = """
                {
                  "version": "1",
                  "exportedAt": "2026-04-07T12:00:00Z",
                  "settings": {},
                  "peers": [],
                  "services": [],
                  "dnsZones": [],
                  "users": [ { "username": "alice" } ]
                }
                """;

        ImportResult result = service.importConfiguration(json);

        assertThat(result.success()).isTrue();
        verify(addUserUseCase).addUser(eq("alice"), anyString(), any(), any());
        assertThat(result.warnings()).anyMatch(w -> w.contains("alice") && w.toLowerCase().contains("password"));
    }

    @Test
    void importConfiguration_importsPeers() {
        String json = """
                {
                  "version": "1",
                  "exportedAt": "2026-04-07T12:00:00Z",
                  "settings": {},
                  "peers": [
                    { "name": "myserver", "ipAddress": "10.13.13.2", "peerType": "UBUNTU_SERVER", "lanCidr": null, "configContent": "# config" }
                  ],
                  "services": [],
                  "dnsZones": [],
                  "users": []
                }
                """;

        ImportResult result = service.importConfiguration(json);

        assertThat(result.success()).isTrue();
        verify(forRestoringVpnPeers).restorePeer(
                eq("wg0"),
                eq(new ForGettingPeerConfigurations.PeerConfiguration(
                        "myserver", "10.13.13.2", "# config", PeerType.UBUNTU_SERVER, null)));
    }

    @Test
    void importConfiguration_continuesAfterPartialFailure() {
        doThrow(new RuntimeException("Zone already exists"))
                .when(addDnsZoneUseCase).addDnsZone(any());

        String json = """
                {
                  "version": "1",
                  "exportedAt": "2026-04-07T12:00:00Z",
                  "settings": {},
                  "peers": [],
                  "services": [],
                  "dnsZones": [
                    { "name": "example.com", "records": [] }
                  ],
                  "users": []
                }
                """;

        ImportResult result = service.importConfiguration(json);

        assertThat(result.success()).isTrue();
        assertThat(result.warnings()).anyMatch(w -> w.contains("example.com"));
    }

    @Test
    void importConfiguration_handlesEmptyBackup() {
        String json = """
                {
                  "version": "1",
                  "exportedAt": "2026-04-07T12:00:00Z",
                  "settings": {},
                  "peers": [],
                  "services": [],
                  "dnsZones": [],
                  "users": []
                }
                """;

        ImportResult result = service.importConfiguration(json);

        assertThat(result.success()).isTrue();
        verify(addDnsZoneUseCase, never()).addDnsZone(any());
        verify(addReverseProxyRouteUseCase, never()).addReverseProxyRoute(any());
        verify(addUserUseCase, never()).addUser(any(), any(), any(), any());
        verify(forRestoringVpnPeers, never()).restorePeer(any(), any());
    }
}
