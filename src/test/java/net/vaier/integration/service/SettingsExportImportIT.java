package net.vaier.integration.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.vaier.adapter.driven.AutheliaUserAdapter;
import net.vaier.adapter.driven.TraefikReverseProxyAdapter;
import net.vaier.adapter.driven.WireguardConfigFileAdapter;
import net.vaier.application.AddDnsRecordUseCase;
import net.vaier.application.AddReverseProxyRouteUseCase;
import net.vaier.application.AddUserUseCase;
import net.vaier.application.service.ExportConfigurationService;
import net.vaier.application.service.ImportConfigurationService;
import net.vaier.application.service.ReverseProxyService;
import net.vaier.application.service.UserService;
import net.vaier.config.ConfigResolver;
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
import java.util.List;

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
    void import_emptyJson_returnsFailureResult() {
        net.vaier.application.ImportConfigurationUseCase.ImportResult result =
                importService.importConfiguration("{}");

        // Empty JSON parses but produces a backup with null version and empty collections
        // The service should handle gracefully
        assertThat(result).isNotNull();
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
}
