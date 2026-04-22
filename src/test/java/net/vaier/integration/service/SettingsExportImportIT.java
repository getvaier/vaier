package net.vaier.integration.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.vaier.adapter.driven.AutheliaUserAdapter;
import net.vaier.adapter.driven.TraefikReverseProxyAdapter;
import net.vaier.adapter.driven.WireguardConfigFileAdapter;
import net.vaier.application.AddDnsRecordUseCase;
import net.vaier.application.AddReverseProxyRouteUseCase;
import net.vaier.application.AddUserUseCase;
import net.vaier.application.service.DnsService;
import net.vaier.application.service.ExportConfigurationService;
import net.vaier.application.service.ImportConfigurationService;
import net.vaier.application.service.ReverseProxyService;
import net.vaier.application.service.UserService;
import net.vaier.config.ConfigResolver;
import net.vaier.domain.DnsRecord;
import net.vaier.domain.DnsRecord.DnsRecordType;
import net.vaier.domain.DnsZone;
import net.vaier.domain.PeerType;
import net.vaier.domain.port.ForGettingPeerConfigurations;
import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;
import net.vaier.domain.port.ForPersistingDnsRecords;
import net.vaier.domain.port.ForPublishingEvents;
import net.vaier.domain.port.ForRestoringVpnPeers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Service+file integration tests for export and import configuration.
 *
 * Wires ExportConfigurationService and ImportConfigurationService with real file adapters
 * backed by @TempDir. DNS and WireGuard VPN peers are mocked since they require
 * real AWS credentials / Docker/WireGuard containers.
 */
class SettingsExportImportIT {

    @TempDir
    Path tempDir;

    // File adapters (real)
    TraefikReverseProxyAdapter traefikAdapter;
    AutheliaUserAdapter userAdapter;
    WireguardConfigFileAdapter wireguardAdapter;

    // Mocked cloud/external adapters
    ForPersistingDnsRecords mockDnsRecords = mock(ForPersistingDnsRecords.class);
    ForRestoringVpnPeers mockVpnRestore = mock(ForRestoringVpnPeers.class);
    ForPublishingEvents mockEvents = mock(ForPublishingEvents.class);
    ConfigResolver mockConfigResolver = mock(ConfigResolver.class);

    // Services under test
    ExportConfigurationService exportService;
    ImportConfigurationService importService;

    // Wired use-case implementations backed by real adapters
    AddReverseProxyRouteUseCase addRouteUseCase;
    AddUserUseCase addUserUseCase;

    @BeforeEach
    void setUp() {
        String configFilePath = tempDir.resolve("remote-apps.yml").toString();
        traefikAdapter = new TraefikReverseProxyAdapter(configFilePath, "http://localhost:19999", "example.com");
        userAdapter = new AutheliaUserAdapter(tempDir.resolve("users_database.yml").toString());
        wireguardAdapter = new WireguardConfigFileAdapter();
        ReflectionTestUtils.setField(wireguardAdapter, "wireguardConfigPath", tempDir.resolve("wg").toString());

        addRouteUseCase = new ReverseProxyService(traefikAdapter);
        addUserUseCase = new UserService(userAdapter);

        when(mockConfigResolver.getDomain()).thenReturn("example.com");
        when(mockDnsRecords.getDnsZones()).thenReturn(List.of());

        exportService = new ExportConfigurationService(
                wireguardAdapter, traefikAdapter, mockDnsRecords, userAdapter, mockConfigResolver);

        importService = new ImportConfigurationService(
                mockDnsRecords, mock(AddDnsRecordUseCase.class), addRouteUseCase,
                addUserUseCase, mockVpnRestore, mockEvents);
    }

    @Test
    void export_includesSeedRouteInJson() {
        traefikAdapter.addReverseProxyRoute("app.example.com", "10.13.13.2", 8080, false, null);

        String json = exportService.exportConfiguration();

        assertThat(json).contains("app.example.com");
        assertThat(json).contains("10.13.13.2");
        assertThat(json).contains("\"version\"");
    }

    @Test
    void export_includesSeedUserInJson() {
        userAdapter.addUser("alice", "pass", "alice@example.com", "Alice");

        String json = exportService.exportConfiguration();

        assertThat(json).contains("alice");
    }

    @Test
    void export_emptyState_producesValidJsonWithEmptyArrays() throws Exception {
        String json = exportService.exportConfiguration();

        ObjectMapper mapper = new ObjectMapper();
        ExportConfigurationService.BackupDto backup =
                mapper.readValue(json, ExportConfigurationService.BackupDto.class);

        assertThat(backup.version()).isEqualTo("1");
        assertThat(backup.services()).isEmpty();
        assertThat(backup.users()).isEmpty();
        assertThat(backup.peers()).isEmpty();
    }

    @Test
    void import_fixtureJson_createsTraefikRouteAndUser() throws IOException {
        String fixtureJson = Files.readString(
                Path.of("src/test/resources/fixtures/backup-v1.json"));

        net.vaier.application.ImportConfigurationUseCase.ImportResult result =
                importService.importConfiguration(fixtureJson);

        assertThat(result.success()).isTrue();

        // Route from fixture should be in Traefik config file
        List<net.vaier.domain.ReverseProxyRoute> routes = traefikAdapter.getReverseProxyRoutes();
        assertThat(routes).extracting(net.vaier.domain.ReverseProxyRoute::getDomainName)
                         .contains("app.example.com");

        // User from fixture should be in Authelia file
        assertThat(userAdapter.getUsers())
                .extracting(net.vaier.domain.User::getName)
                .contains("alice");
    }

    @Test
    void import_malformedJson_returnsFailureResult() {
        net.vaier.application.ImportConfigurationUseCase.ImportResult result =
                importService.importConfiguration("this is not valid json {{{");

        assertThat(result.success()).isFalse();
        assertThat(result.message()).containsIgnoringCase("invalid");
    }

    @Test
    void import_emptyJson_rejectsDueToMissingVersion() {
        net.vaier.application.ImportConfigurationUseCase.ImportResult result =
                importService.importConfiguration("{}");

        assertThat(result.success()).isFalse();
        assertThat(result.message().toLowerCase()).contains("version");
    }

    @Test
    void import_backupWithUnsupportedVersion_isRejected() {
        String json = """
                {"version":"999","peers":[],"services":[],"dnsZones":[],"users":[]}
                """;

        net.vaier.application.ImportConfigurationUseCase.ImportResult result =
                importService.importConfiguration(json);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("999");
    }

    @Test
    void roundTrip_allEntityTypes_importedFaithfully(@TempDir Path targetDir) {
        // --- Seed source state: services, users, DNS zones with records, peers ---
        traefikAdapter.addReverseProxyRoute("app.example.com", "10.13.13.2", 8080, false, null);
        userAdapter.addUser("alice", "pass", "alice@example.com", "Alice");

        InMemoryDns sourceDns = new InMemoryDns();
        sourceDns.addDnsZone(new DnsZone("example.com"));
        sourceDns.addDnsRecord(new DnsRecord("grafana.example.com", DnsRecordType.CNAME, 300L,
                List.of("vaier.example.com")), new DnsZone("example.com"));
        sourceDns.addDnsRecord(new DnsRecord("mail.example.com", DnsRecordType.A, 300L,
                List.of("1.2.3.4")), new DnsZone("example.com"));

        InMemoryPeers sourcePeers = new InMemoryPeers();
        sourcePeers.add(new PeerConfiguration("alice-phone", "10.13.13.5",
                "# VAIER: {\"peerType\":\"MOBILE_CLIENT\"}\n[Interface]\nAddress = 10.13.13.5/32\n",
                PeerType.MOBILE_CLIENT, null));

        // Rebuild export service wired to the source-side in-memory state
        ExportConfigurationService freshExport = new ExportConfigurationService(
                sourcePeers, traefikAdapter, sourceDns, userAdapter, mockConfigResolver);

        // --- Export ---
        String exportedJson = freshExport.exportConfiguration();

        // --- Fresh state on import side ---
        TraefikReverseProxyAdapter freshTraefik = new TraefikReverseProxyAdapter(
                targetDir.resolve("remote-apps.yml").toString(), "http://localhost:19999", "example.com");
        AutheliaUserAdapter freshUsers = new AutheliaUserAdapter(
                targetDir.resolve("users_database.yml").toString());
        InMemoryDns targetDns = new InMemoryDns();
        InMemoryPeers targetPeers = new InMemoryPeers();

        ImportConfigurationService freshImport = new ImportConfigurationService(
                targetDns,
                new DnsService(targetDns),
                new ReverseProxyService(freshTraefik),
                new UserService(freshUsers),
                targetPeers,
                mockEvents);

        // --- Import ---
        net.vaier.application.ImportConfigurationUseCase.ImportResult result =
                freshImport.importConfiguration(exportedJson);

        // --- Verify every exported entity is restored ---
        assertThat(result.success()).isTrue();
        assertThat(freshTraefik.getReverseProxyRoutes())
                .extracting(net.vaier.domain.ReverseProxyRoute::getDomainName)
                .containsExactly("app.example.com");
        assertThat(freshUsers.getUsers())
                .extracting(net.vaier.domain.User::getName)
                .containsExactly("alice");
        assertThat(targetDns.getDnsZones())
                .extracting(DnsZone::name)
                .containsExactly("example.com");
        assertThat(targetDns.getDnsRecords(new DnsZone("example.com")))
                .extracting(DnsRecord::name)
                .containsExactlyInAnyOrder("grafana.example.com", "mail.example.com");
        assertThat(targetPeers.restored)
                .extracting(PeerConfiguration::name)
                .containsExactly("alice-phone");
    }

    @Test
    void roundTrip_exportThenImportIntoFreshDirectory_stateEquivalent(@TempDir Path targetDir) {
        // Seed source state
        traefikAdapter.addReverseProxyRoute("app.example.com", "10.13.13.2", 8080, false, null);
        userAdapter.addUser("alice", "pass", "alice@example.com", "Alice");

        // Export
        String exportedJson = exportService.exportConfiguration();

        // Fresh adapters backed by a separate temp dir
        TraefikReverseProxyAdapter freshTraefik = new TraefikReverseProxyAdapter(
                targetDir.resolve("remote-apps.yml").toString(), "http://localhost:19999", "example.com");
        AutheliaUserAdapter freshUsers = new AutheliaUserAdapter(
                targetDir.resolve("users_database.yml").toString());

        ImportConfigurationService freshImport = new ImportConfigurationService(
                mockDnsRecords, mock(AddDnsRecordUseCase.class),
                new ReverseProxyService(freshTraefik), new UserService(freshUsers),
                mockVpnRestore, mockEvents);

        // Import
        net.vaier.application.ImportConfigurationUseCase.ImportResult result =
                freshImport.importConfiguration(exportedJson);

        assertThat(result.success()).isTrue();
        assertThat(freshTraefik.getReverseProxyRoutes())
                .extracting(net.vaier.domain.ReverseProxyRoute::getDomainName)
                .contains("app.example.com");
        assertThat(freshUsers.getUsers())
                .extracting(net.vaier.domain.User::getName)
                .contains("alice");
    }

    // --- in-memory fakes for round-trip testing ---

    static class InMemoryDns implements ForPersistingDnsRecords {
        private final List<DnsZone> zones = new ArrayList<>();
        private final Map<String, List<DnsRecord>> records = new HashMap<>();

        @Override
        public void addDnsZone(DnsZone zone) {
            zones.add(zone);
            records.computeIfAbsent(zone.name(), k -> new ArrayList<>());
        }

        @Override
        public List<DnsZone> getDnsZones() { return new ArrayList<>(zones); }

        @Override
        public void addDnsRecord(DnsRecord record, DnsZone zone) {
            records.computeIfAbsent(zone.name(), k -> new ArrayList<>()).add(record);
        }

        @Override
        public List<DnsRecord> getDnsRecords(DnsZone zone) {
            return new ArrayList<>(records.getOrDefault(zone.name(), List.of()));
        }

        @Override public void updateDnsRecord(DnsRecord r, DnsZone z) {}
        @Override public void deleteDnsRecord(String name, DnsRecordType t, DnsZone z) {}
        @Override public void updateDnsZone(DnsZone z) {}
        @Override public void deleteDnsZone(DnsZone z) {}
    }

    static class InMemoryPeers implements ForGettingPeerConfigurations, ForRestoringVpnPeers {
        private final List<PeerConfiguration> peers = new ArrayList<>();
        final List<PeerConfiguration> restored = new ArrayList<>();

        void add(PeerConfiguration p) { peers.add(p); }

        @Override public java.util.Optional<PeerConfiguration> getPeerConfigByName(String name) {
            return peers.stream().filter(p -> p.name().equals(name)).findFirst();
        }

        @Override public java.util.Optional<PeerConfiguration> getPeerConfigByIp(String ip) {
            return peers.stream().filter(p -> ip.equals(p.ipAddress())).findFirst();
        }

        @Override public List<PeerConfiguration> getAllPeerConfigs() { return new ArrayList<>(peers); }

        @Override public void restorePeer(PeerConfiguration p) { restored.add(p); }
    }
}
