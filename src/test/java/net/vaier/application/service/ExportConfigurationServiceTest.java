package net.vaier.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.vaier.domain.DnsRecord;
import net.vaier.domain.DnsRecord.DnsRecordType;
import net.vaier.domain.DnsZone;
import net.vaier.domain.PeerType;
import net.vaier.domain.ReverseProxyRoute;
import net.vaier.domain.User;
import net.vaier.domain.port.ForGettingPeerConfigurations;
import net.vaier.domain.port.ForPersistingDnsRecords;
import net.vaier.domain.port.ForPersistingReverseProxyRoutes;
import net.vaier.domain.port.ForPersistingUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExportConfigurationServiceTest {

    @Mock ForGettingPeerConfigurations forGettingPeerConfigurations;
    @Mock ForPersistingReverseProxyRoutes forPersistingReverseProxyRoutes;
    @Mock ForPersistingDnsRecords forPersistingDnsRecords;
    @Mock ForPersistingUsers forPersistingUsers;

    ExportConfigurationService service;
    ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new ExportConfigurationService(
                forGettingPeerConfigurations,
                forPersistingReverseProxyRoutes,
                forPersistingDnsRecords,
                forPersistingUsers);
        ReflectionTestUtils.setField(service, "domain", "example.com");
    }

    @Test
    void exportConfiguration_includesVersionAndExportedAt() throws Exception {
        stubEmptyData();

        String json = service.exportConfiguration();
        JsonNode root = objectMapper.readTree(json);

        assertThat(root.get("version").asText()).isEqualTo("1");
        assertThat(root.has("exportedAt")).isTrue();
    }

    @Test
    void exportConfiguration_includesSettings() throws Exception {
        stubEmptyData();

        String json = service.exportConfiguration();
        JsonNode root = objectMapper.readTree(json);

        assertThat(root.get("settings").get("domain").asText()).isEqualTo("example.com");
    }

    @Test
    void exportConfiguration_includesPeers() throws Exception {
        when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of(
                new ForGettingPeerConfigurations.PeerConfiguration(
                        "myserver", "10.13.13.2", "# config content", PeerType.UBUNTU_SERVER, "192.168.1.0/24")
        ));
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of());
        when(forPersistingDnsRecords.getDnsZones()).thenReturn(List.of());
        when(forPersistingUsers.getUsers()).thenReturn(List.of());

        String json = service.exportConfiguration();
        JsonNode root = objectMapper.readTree(json);

        JsonNode peers = root.get("peers");
        assertThat(peers).hasSize(1);
        assertThat(peers.get(0).get("name").asText()).isEqualTo("myserver");
        assertThat(peers.get(0).get("ipAddress").asText()).isEqualTo("10.13.13.2");
        assertThat(peers.get(0).get("peerType").asText()).isEqualTo("UBUNTU_SERVER");
        assertThat(peers.get(0).get("lanCidr").asText()).isEqualTo("192.168.1.0/24");
        assertThat(peers.get(0).get("configContent").asText()).isEqualTo("# config content");
    }

    @Test
    void exportConfiguration_includesServices() throws Exception {
        when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of());
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(
                new ReverseProxyRoute("grafana", "grafana.example.com", "10.13.13.2", 3000,
                        "grafana-service", new ReverseProxyRoute.AuthInfo("forward-auth", null, null))
        ));
        when(forPersistingDnsRecords.getDnsZones()).thenReturn(List.of());
        when(forPersistingUsers.getUsers()).thenReturn(List.of());

        String json = service.exportConfiguration();
        JsonNode root = objectMapper.readTree(json);

        JsonNode services = root.get("services");
        assertThat(services).hasSize(1);
        assertThat(services.get(0).get("name").asText()).isEqualTo("grafana");
        assertThat(services.get(0).get("domainName").asText()).isEqualTo("grafana.example.com");
        assertThat(services.get(0).get("address").asText()).isEqualTo("10.13.13.2");
        assertThat(services.get(0).get("port").asInt()).isEqualTo(3000);
        assertThat(services.get(0).get("requiresAuth").asBoolean()).isTrue();
    }

    @Test
    void exportConfiguration_includesDnsZonesAndRecords() throws Exception {
        DnsZone zone = new DnsZone("example.com");
        when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of());
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of());
        when(forPersistingDnsRecords.getDnsZones()).thenReturn(List.of(zone));
        when(forPersistingDnsRecords.getDnsRecords(zone)).thenReturn(List.of(
                new DnsRecord("grafana", DnsRecordType.CNAME, 300L, List.of("vaier.example.com"))
        ));
        when(forPersistingUsers.getUsers()).thenReturn(List.of());

        String json = service.exportConfiguration();
        JsonNode root = objectMapper.readTree(json);

        JsonNode dnsZones = root.get("dnsZones");
        assertThat(dnsZones).hasSize(1);
        assertThat(dnsZones.get(0).get("name").asText()).isEqualTo("example.com");
        JsonNode records = dnsZones.get(0).get("records");
        assertThat(records).hasSize(1);
        assertThat(records.get(0).get("name").asText()).isEqualTo("grafana");
        assertThat(records.get(0).get("type").asText()).isEqualTo("CNAME");
    }

    @Test
    void exportConfiguration_includesUsers() throws Exception {
        when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of());
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of());
        when(forPersistingDnsRecords.getDnsZones()).thenReturn(List.of());
        when(forPersistingUsers.getUsers()).thenReturn(List.of(new User("alice")));

        String json = service.exportConfiguration();
        JsonNode root = objectMapper.readTree(json);

        JsonNode users = root.get("users");
        assertThat(users).hasSize(1);
        assertThat(users.get(0).get("username").asText()).isEqualTo("alice");
    }

    private void stubEmptyData() {
        when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of());
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of());
        when(forPersistingDnsRecords.getDnsZones()).thenReturn(List.of());
        when(forPersistingUsers.getUsers()).thenReturn(List.of());
    }
}
