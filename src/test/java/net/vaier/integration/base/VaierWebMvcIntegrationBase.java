package net.vaier.integration.base;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.vaier.application.*;
import net.vaier.config.ConfigResolver;
import net.vaier.domain.port.ForGeolocatingIps;
import net.vaier.domain.port.ForPublishingEvents;
import net.vaier.domain.port.ForSubscribingToEvents;
import net.vaier.domain.port.ForTrackingPeerConfigRetrieval;
import net.vaier.domain.port.ForUpdatingPeerConfigurations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Base class for all controller integration tests.
 *
 * Uses @WebMvcTest to load the full web layer (all controllers + filters) with all
 * service/adapter dependencies replaced by @MockBean stubs. Subclasses add @Test
 * methods targeting specific endpoints.
 */
@WebMvcTest
@TestPropertySource(locations = "classpath:application-integration.yml")
public abstract class VaierWebMvcIntegrationBase {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    // --- Non-use-case component dependencies ---
    @MockBean
    protected ConfigResolver configResolver;

    @MockBean
    protected ForPublishingEvents forPublishingEvents;

    @MockBean
    protected ForSubscribingToEvents forSubscribingToEvents;

    @MockBean
    protected GetIconUseCase getIconUseCase;

    // Required by WebConfig -> EnterpriseLicenseInterceptor, which every @WebMvcTest
    // context loads; without it the whole controller IT suite fails to start.
    @MockBean
    protected GetEditionUseCase getEditionUseCase;

    // --- Social-login authorization use cases ---
    @MockBean
    protected VerifyAccessUseCase verifyAccessUseCase;

    @MockBean
    protected ListAccessEntriesUseCase listAccessEntriesUseCase;

    @MockBean
    protected GrantRoleUseCase grantRoleUseCase;

    @MockBean
    protected AssignGroupsUseCase assignGroupsUseCase;

    @MockBean
    protected RevokeAccessUseCase revokeAccessUseCase;

    @MockBean
    protected SetServiceAccessRuleUseCase setServiceAccessRuleUseCase;

    @MockBean
    protected GetServiceAccessRulesUseCase getServiceAccessRulesUseCase;

    // --- DNS use cases ---
    @MockBean
    protected GetDnsInfoUseCase getDnsInfoUseCase;

    @MockBean
    protected AddDnsRecordUseCase addDnsRecordUseCase;

    @MockBean
    protected AddDnsZoneUseCase addDnsZoneUseCase;

    @MockBean
    protected DeleteDnsRecordUseCase deleteDnsRecordUseCase;

    @MockBean
    protected DeleteDnsZoneUseCase deleteDnsZoneUseCase;

    // --- Reverse proxy use cases ---
    @MockBean
    protected AddReverseProxyRouteUseCase addReverseProxyRouteUseCase;

    @MockBean
    protected DeleteReverseProxyRouteUseCase deleteReverseProxyRouteUseCase;

    @MockBean
    protected GetReverseProxyRoutesUseCase getReverseProxyRoutesUseCase;

    // --- VPN peer use cases ---
    @MockBean
    protected GetVpnClientsUseCase getVpnClientsUseCase;

    @MockBean
    protected GetVpnPeersUseCase getVpnPeersUseCase;

    @MockBean
    protected ResolveVpnPeerNameUseCase resolveVpnPeerNameUseCase;

    @MockBean
    protected GetPeerConfigUseCase getPeerConfigUseCase;

    @MockBean
    protected CreatePeerUseCase createPeerUseCase;

    @MockBean
    protected DeletePeerUseCase deletePeerUseCase;

    @MockBean
    protected GenerateDockerComposeUseCase generateDockerComposeUseCase;

    @MockBean
    protected GeneratePeerSetupScriptUseCase generatePeerSetupScriptUseCase;

    @MockBean
    protected RenamePeerUseCase renamePeerUseCase;

    @MockBean
    protected ReissuePeerConfigUseCase reissuePeerConfigUseCase;

    @MockBean
    protected net.vaier.application.UpdatePeerDeviceCategoryUseCase updatePeerDeviceCategoryUseCase;

    @MockBean
    protected ForUpdatingPeerConfigurations forUpdatingPeerConfigurations;

    @MockBean
    protected ForTrackingPeerConfigRetrieval forTrackingPeerConfigRetrieval;

    @MockBean
    protected ForGeolocatingIps forGeolocatingIps;

    // --- Published service use cases ---
    @MockBean
    protected GetPublishedServicesUseCase getPublishedServicesUseCase;

    @MockBean
    protected PublishPeerServiceUseCase publishPeerServiceUseCase;

    @MockBean
    protected GetPublishableServicesUseCase getPublishableServicesUseCase;

    @MockBean
    protected DeletePublishedServiceUseCase deletePublishedServiceUseCase;

    @MockBean
    protected UpdatePublishedServiceUseCase updatePublishedServiceUseCase;

    @MockBean
    protected ResolveLanAnchorUseCase resolveLanAnchorUseCase;

    @MockBean
    protected GenerateLanServerSetupScriptUseCase generateLanServerSetupScriptUseCase;

    @MockBean
    protected IgnorePublishableServiceUseCase ignorePublishableServiceUseCase;

    @MockBean
    protected UnignorePublishableServiceUseCase unignorePublishableServiceUseCase;

    // --- Settings use cases ---
    @MockBean
    protected GetAppSettingsUseCase getAppSettingsUseCase;

    @MockBean
    protected UpdateAwsCredentialsUseCase updateAwsCredentialsUseCase;

    @MockBean
    protected UpdateSmtpSettingsUseCase updateSmtpSettingsUseCase;

    @MockBean
    protected TestSmtpCredentialsUseCase testSmtpCredentialsUseCase;

    @MockBean
    protected UpdateDiskMonitorSettingsUseCase updateDiskMonitorSettingsUseCase;

    @MockBean
    protected net.vaier.application.UpdateBackupSettingsUseCase updateBackupSettingsUseCase;

    // --- Docker/server use cases ---
    @MockBean
    protected GetServerInfoUseCase getServerInfoUseCase;

    @MockBean
    protected DiscoverPeerContainersUseCase discoverPeerContainersUseCase;

    @MockBean
    protected DiscoverVaierServerContainersUseCase discoverVaierServerContainersUseCase;

    @MockBean
    protected DiscoverLanServerContainersUseCase discoverLanServerContainersUseCase;

    @MockBean
    protected GetLanServerScrapeUseCase getLanServerScrapeUseCase;

    // --- LAN server use cases ---
    @MockBean
    protected RegisterLanServerUseCase registerLanServerUseCase;

    @MockBean
    protected RenameLanServerUseCase renameLanServerUseCase;

    @MockBean
    protected UpdateLanServerDescriptionUseCase updateLanServerDescriptionUseCase;

    @MockBean
    protected net.vaier.application.UpdateLanServerDeviceCategoryUseCase updateLanServerDeviceCategoryUseCase;

    @MockBean
    protected DeleteLanServerUseCase deleteLanServerUseCase;

    @MockBean
    protected GetLanServersUseCase getLanServersUseCase;

    @MockBean
    protected GetLanServerReachabilityUseCase getLanServerReachabilityUseCase;

    @MockBean
    protected PublishLanServiceUseCase publishLanServiceUseCase;

    @MockBean
    protected UpdateLanCidrUseCase updateLanCidrUseCase;

    // --- Machine use cases ---
    @MockBean
    protected GetMachinesUseCase getMachinesUseCase;

    @MockBean
    protected SetMachineSshAccessUseCase setMachineSshAccessUseCase;

    @MockBean
    protected GetVaierServerUseCase getVaierServerUseCase;

    @MockBean
    protected ClearHostKeyUseCase clearHostKeyUseCase;

    // --- Host credential (web terminal, credential vault) use cases ---
    @MockBean
    protected SaveHostCredentialUseCase saveHostCredentialUseCase;

    @MockBean
    protected GetHostCredentialUseCase getHostCredentialUseCase;

    @MockBean
    protected DeleteHostCredentialUseCase deleteHostCredentialUseCase;

    // --- Explorer use cases ---
    @MockBean
    protected BrowseFilesUseCase browseFilesUseCase;

    // --- Server location use case ---
    @MockBean
    protected GetServerLocationUseCase getServerLocationUseCase;

    // --- Peer notification use case ---
    @MockBean
    protected NotifyAdminsOfPeerTransitionUseCase notifyAdminsOfPeerTransitionUseCase;

    // --- Launchpad use cases ---
    @MockBean
    protected GetLaunchpadServicesUseCase getLaunchpadServicesUseCase;

    // --- Viewer resolution (launchpad topbar + per-viewer filtering, /users/me) ---
    @MockBean
    protected ResolveViewerUseCase resolveViewerUseCase;

    // --- Viewer identity capture (write-through on /users/me) ---
    @MockBean
    protected CaptureViewerIdentityUseCase captureViewerIdentityUseCase;

    // --- Settings / version ---
    @MockBean
    protected GetAppVersionUseCase getAppVersionUseCase;

    // --- Licensing ---
    @MockBean
    protected GetLicenseStatusUseCase getLicenseStatusUseCase;

    // --- LAN scanner (Enterprise) use cases ---
    @MockBean
    protected ScanLanUseCase scanLanUseCase;

    @MockBean
    protected GetDiscoveredLanMachinesUseCase getDiscoveredLanMachinesUseCase;

    // --- Fleet backup (Enterprise) CRUD use cases ---
    @MockBean
    protected SaveBackupRepositoryUseCase saveBackupRepositoryUseCase;

    @MockBean
    protected GetBackupRepositoriesUseCase getBackupRepositoriesUseCase;

    @MockBean
    protected DeleteBackupRepositoryUseCase deleteBackupRepositoryUseCase;

    @MockBean
    protected GetBackupServersUseCase getBackupServersUseCase;

    @MockBean
    protected SaveBackupServerUseCase saveBackupServerUseCase;

    @MockBean
    protected DeleteBackupServerUseCase deleteBackupServerUseCase;

    @MockBean
    protected GenerateBackupServerSetupScriptUseCase generateBackupServerSetupScriptUseCase;

    @MockBean
    protected ProvisionBackupServerUseCase provisionBackupServerUseCase;

    @MockBean
    protected AuthorizeBackupClientUseCase authorizeBackupClientUseCase;

    @MockBean
    protected PrepareBackupClientUseCase prepareBackupClientUseCase;

    @MockBean
    protected SaveBackupJobUseCase saveBackupJobUseCase;

    @MockBean
    protected GetBackupJobsUseCase getBackupJobsUseCase;

    @MockBean
    protected DeleteBackupJobUseCase deleteBackupJobUseCase;

    @MockBean
    protected GetBackupRunsUseCase getBackupRunsUseCase;

    @MockBean
    protected RunBackupJobUseCase runBackupJobUseCase;

    @MockBean
    protected ListArchivesUseCase listArchivesUseCase;

    @MockBean
    protected CheckBackupPrerequisitesUseCase checkBackupPrerequisitesUseCase;

    @MockBean
    protected InitBackupRepositoryUseCase initBackupRepositoryUseCase;

    // --- Offline page ---
    @MockBean
    protected GetOfflinePageUseCase getOfflinePageUseCase;

    // --- Concepts (operator glossary) ---
    @MockBean
    protected GetConceptsUseCase getConceptsUseCase;
}
