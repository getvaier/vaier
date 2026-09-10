package net.vaier.integration.base;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.vaier.application.*;
import net.vaier.config.ConfigResolver;
import net.vaier.domain.port.ForGeolocatingIps;
import net.vaier.domain.port.ForHoldingClaudeSignInStandings;
import net.vaier.domain.port.ForPublishingEvents;
import net.vaier.domain.port.ForSubscribingToEvents;
import net.vaier.domain.port.ForTrackingPeerConfigRetrieval;
import net.vaier.domain.port.ForUpdatingPeerConfigurations;
import net.vaier.domain.port.ForVendingSetupTokens;
import net.vaier.rest.ImageUpdateAlerter;
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
    protected ForHoldingClaudeSignInStandings forHoldingClaudeSignInStandings;

    @MockBean
    protected ForSubscribingToEvents forSubscribingToEvents;

    @MockBean
    protected GetIconUseCase getIconUseCase;

    /** #359: AndroidAppRestController is a controller, so @WebMvcTest loads it and needs its use case. */
    @MockBean
    protected GetAndroidAppUseCase getAndroidAppUseCase;

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
    protected ResolveVpnPeerIdUseCase resolveVpnPeerNameUseCase;

    @MockBean
    protected GetPeerConfigUseCase getPeerConfigUseCase;

    @MockBean
    protected CreatePeerUseCase createPeerUseCase;

    @MockBean
    protected EnrolDeviceUseCase enrolDeviceUseCase;

    // #359 slice 1b: the join-code flow and a phone removing itself.
    @MockBean
    protected LeaveFleetUseCase leaveFleetUseCase;

    @MockBean
    protected CheckStandingUseCase checkStandingUseCase;

    @MockBean
    protected NotifyAdminsOfEnrolmentRequestUseCase notifyAdminsOfEnrolmentRequestUseCase;

    @MockBean
    protected RequestEnrolmentUseCase requestEnrolmentUseCase;

    @MockBean
    protected ListEnrolmentRequestsUseCase listEnrolmentRequestsUseCase;

    @MockBean
    protected ApproveEnrolmentUseCase approveEnrolmentUseCase;

    @MockBean
    protected RefuseEnrolmentUseCase refuseEnrolmentUseCase;

    @MockBean
    protected LookUpEnrolmentTicketUseCase lookUpEnrolmentTicketUseCase;

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
    protected ForVendingSetupTokens forVendingSetupTokens;

    @MockBean
    protected ForGeolocatingIps forGeolocatingIps;

    @MockBean
    protected ReportMyPositionUseCase reportMyPositionUseCase;

    @MockBean
    protected ForgetMyPositionUseCase forgetMyPositionUseCase;

    @MockBean
    protected ClaimDeviceUseCase claimDeviceUseCase;

    @MockBean
    protected GetMyDeviceUseCase getMyDeviceUseCase;

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
    protected UpdateSmtpSettingsUseCase updateSmtpSettingsUseCase;

    @MockBean
    protected TestSmtpCredentialsUseCase testSmtpCredentialsUseCase;

    @MockBean
    protected UpdateDiskMonitorSettingsUseCase updateDiskMonitorSettingsUseCase;

    @MockBean
    protected UpdateBackupSettingsUseCase updateBackupSettingsUseCase;

    @MockBean
    protected SetSurvivalKitPassphraseUseCase setSurvivalKitPassphraseUseCase;

    @MockBean
    protected UpdateAnthropicApiKeyUseCase updateAnthropicApiKeyUseCase;

    // --- Chat (#360) ---
    @MockBean
    protected ChatUseCase chatUseCase;

    @MockBean
    protected IsChatAvailableUseCase isChatAvailableUseCase;

    @MockBean
    protected RunReadOnlyCommandUseCase runReadOnlyCommandUseCase;

    @MockBean
    protected ProposeActionUseCase proposeActionUseCase;

    @MockBean
    protected TakeActionProposalUseCase takeActionProposalUseCase;

    @MockBean
    protected GetConversationUseCase getConversationUseCase;

    @MockBean
    protected ForgetConversationUseCase forgetConversationUseCase;

    @MockBean
    protected RememberActionOutcomeUseCase rememberActionOutcomeUseCase;

    @MockBean
    protected OfferBundleUseCase offerBundleUseCase;

    @MockBean
    protected OpenBundleUseCase openBundleUseCase;

    @MockBean
    protected RememberUseCase rememberUseCase;

    @MockBean
    protected ForgetUseCase forgetUseCase;

    @MockBean
    protected GetMemoryUseCase getMemoryUseCase;

    @MockBean
    protected GetSpendUseCase getSpendUseCase;

    @MockBean
    protected EmailBundleUseCase emailBundleUseCase;

    // Implemented by rest/SurvivalKitWriter, which composes machines, the backup stores and SSH — mocked
    // here like the other rest-layer orchestrators (@WebMvcTest loads controllers only).
    @MockBean
    protected WriteSurvivalKitUseCase writeSurvivalKitUseCase;

    // Self-update: the runner that implements these lives in rest/ (it drives a detached process over SSH,
    // like BackupRunner), so a controller-slice context has no instance of it to wire.
    @MockBean
    protected net.vaier.application.GetSelfUpdateStatusUseCase getSelfUpdateStatusUseCase;

    @MockBean
    protected net.vaier.application.UpdateVaierUseCase updateVaierUseCase;

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

    @MockBean
    protected CheckForImageUpdatesUseCase checkForImageUpdatesUseCase;

    @MockBean
    protected ImageUpdateAlerter imageUpdateAlerter;

    @MockBean
    protected UpdateContainerImageUseCase updateContainerImageUseCase;

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
    protected net.vaier.application.ProbeLanHostUseCase probeLanHostUseCase;

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

    @MockBean
    protected GetMachineDiskUsageUseCase getMachineDiskUsageUseCase;

    @MockBean
    protected GetMachineDiskStandingsUseCase getMachineDiskStandingsUseCase;

    @MockBean
    protected GetClaudeSignInStandingsUseCase getClaudeSignInStandingsUseCase;

    @MockBean
    protected SetDiskWatchUseCase setDiskWatchUseCase;

    @MockBean
    protected GetDiskWatchesUseCase getDiskWatchesUseCase;

    @MockBean
    protected GetSshServerPresenceUseCase getSshServerPresenceUseCase;

    @MockBean
    protected GetMachineNetworksUseCase getMachineNetworksUseCase;

    // --- Host credential (web terminal, credential vault) use cases ---
    @MockBean
    protected SaveHostCredentialUseCase saveHostCredentialUseCase;

    @MockBean
    protected GetHostCredentialUseCase getHostCredentialUseCase;

    @MockBean
    protected DeleteHostCredentialUseCase deleteHostCredentialUseCase;

    @MockBean
    protected GenerateManagedKeypairUseCase generateManagedKeypairUseCase;

    @MockBean
    protected GetHostPublicKeyUseCase getHostPublicKeyUseCase;

    // --- Fleet credential (the vault's fleet-wide half) use cases ---
    @MockBean
    protected SaveFleetCredentialUseCase saveFleetCredentialUseCase;

    @MockBean
    protected GetFleetCredentialsUseCase getFleetCredentialsUseCase;

    @MockBean
    protected DeleteFleetCredentialUseCase deleteFleetCredentialUseCase;

    @MockBean
    protected DistributeFleetCredentialUseCase distributeFleetCredentialUseCase;

    @MockBean
    protected WithdrawFleetCredentialUseCase withdrawFleetCredentialUseCase;

    @MockBean
    protected GetFleetCredentialStandingsUseCase getFleetCredentialStandingsUseCase;

    // --- Claude sign-in use cases ---

    @MockBean
    protected GetClaudeSignInStatusUseCase getClaudeSignInStatusUseCase;

    @MockBean
    protected StartClaudeSignInUseCase startClaudeSignInUseCase;

    @MockBean
    protected SubmitClaudeSignInCodeUseCase submitClaudeSignInCodeUseCase;

    @MockBean
    protected CancelClaudeSignInUseCase cancelClaudeSignInUseCase;

    @MockBean
    protected SignOutOfClaudeUseCase signOutOfClaudeUseCase;

    // --- Explorer use cases ---
    @MockBean
    protected BrowseFilesUseCase browseFilesUseCase;

    @MockBean
    protected ListMachineArchivesUseCase listMachineArchivesUseCase;

    @MockBean
    protected DownloadFileUseCase downloadFileUseCase;

    @MockBean
    protected DeleteFileUseCase deleteFileUseCase;

    @MockBean
    protected ViewFileUseCase viewFileUseCase;

    @MockBean
    protected UploadFileUseCase uploadFileUseCase;

    // --- Explorer Transfer (Clipboard) use cases ---
    @MockBean
    protected StartTransferUseCase startTransferUseCase;

    @MockBean
    protected GetTransfersUseCase getTransfersUseCase;

    // --- Fleet threat detection (#329) use cases ---
    @MockBean
    protected GetBlockDecisionsUseCase getBlockDecisionsUseCase;

    @MockBean
    protected LiftBlockUseCase liftBlockUseCase;

    @MockBean
    protected TrustAddressUseCase trustAddressUseCase;

    @MockBean
    protected GetTrustedAddressesUseCase getTrustedAddressesUseCase;

    @MockBean
    protected UntrustAddressUseCase untrustAddressUseCase;

    // --- Access sources: where allowed accesses came from ---
    @MockBean
    protected RecordAllowedAccessUseCase recordAllowedAccessUseCase;

    @MockBean
    protected GetAccessSourcesUseCase getAccessSourcesUseCase;

    @MockBean
    protected FlushAccessSourcesUseCase flushAccessSourcesUseCase;

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

    // --- LAN scanner use cases ---
    @MockBean
    protected ScanLanUseCase scanLanUseCase;

    @MockBean
    protected net.vaier.application.ScanLanAnchorUseCase scanLanAnchorUseCase;

    @MockBean
    protected net.vaier.application.ListScannableLansUseCase listScannableLansUseCase;

    @MockBean
    protected GetDiscoveredLanMachinesUseCase getDiscoveredLanMachinesUseCase;

    @MockBean
    protected IgnoreLanMachineUseCase ignoreLanMachineUseCase;

    @MockBean
    protected UnignoreLanMachineUseCase unignoreLanMachineUseCase;

    @MockBean
    protected AdoptDiscoveredMachineUseCase adoptDiscoveredMachineUseCase;

    @MockBean
    protected net.vaier.application.VerifySshCredentialUseCase verifySshCredentialUseCase;

    // --- Fleet backup CRUD use cases ---
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
    protected EnableBackupAsRootUseCase enableBackupAsRootUseCase;

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

    @MockBean
    protected net.vaier.application.ProtectMachinePathsUseCase protectMachinePathsUseCase;

    // --- Offline page ---
    @MockBean
    protected GetOfflinePageUseCase getOfflinePageUseCase;

    // --- Concepts (operator glossary) ---
    @MockBean
    protected GetConceptsUseCase getConceptsUseCase;
}
