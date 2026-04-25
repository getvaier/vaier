package net.vaier.integration.base;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.vaier.application.*;
import net.vaier.config.ConfigResolver;
import net.vaier.config.SetupStateHolder;
import net.vaier.adapter.driven.SseEventPublisher;
import net.vaier.domain.port.ForUpdatingPeerConfigurations;
import net.vaier.rest.FaviconFetcher;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;

/**
 * Base class for all controller integration tests.
 *
 * Uses @WebMvcTest to load the full web layer (all controllers + filters) with all
 * service/adapter dependencies replaced by @MockBean stubs. Subclasses add @Test
 * methods targeting specific endpoints.
 *
 * The SetupRedirectFilter is active in every test. The @BeforeEach here configures
 * setupStateHolder.isConfigured() = true so non-setup endpoints are not redirected.
 * Tests that need to verify redirect behaviour should override this with isConfigured() = false.
 */
@WebMvcTest
@TestPropertySource(locations = "classpath:application-integration.yml")
public abstract class VaierWebMvcIntegrationBase {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    // --- Filter dependencies ---
    @MockBean
    protected SetupStateHolder setupStateHolder;

    // --- Non-use-case component dependencies ---
    @MockBean
    protected ConfigResolver configResolver;

    @MockBean
    protected SseEventPublisher sseEventPublisher;

    @MockBean
    protected FaviconFetcher faviconFetcher;

    // --- Setup use cases ---
    @MockBean
    protected CheckSetupStatusUseCase checkSetupStatusUseCase;

    @MockBean
    protected ValidateAwsCredentialsUseCase validateAwsCredentialsUseCase;

    @MockBean
    protected CompleteSetupUseCase completeSetupUseCase;

    // --- User use cases ---
    @MockBean
    protected GetUsersUseCase getUsersUseCase;

    @MockBean
    protected AddUserUseCase addUserUseCase;

    @MockBean
    protected DeleteUserUseCase deleteUserUseCase;

    @MockBean
    protected ChangePasswordUseCase changePasswordUseCase;

    @MockBean
    protected UpdateUserEmailUseCase updateUserEmailUseCase;

    @MockBean
    protected UpdateUserDisplayNameUseCase updateUserDisplayNameUseCase;

    @MockBean
    protected GetGroupsUseCase getGroupsUseCase;

    @MockBean
    protected UpdateUserGroupsUseCase updateUserGroupsUseCase;

    @MockBean
    protected DeleteGroupUseCase deleteGroupUseCase;

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
    protected ForUpdatingPeerConfigurations forUpdatingPeerConfigurations;

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
    protected ToggleServiceAuthUseCase toggleServiceAuthUseCase;

    @MockBean
    protected EditServiceRedirectUseCase editServiceRedirectUseCase;

    @MockBean
    protected ToggleServiceDirectUrlDisabledUseCase toggleServiceDirectUrlDisabledUseCase;

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

    // --- Docker/server use cases ---
    @MockBean
    protected GetServerInfoUseCase getServerInfoUseCase;

    @MockBean
    protected DiscoverPeerContainersUseCase discoverPeerContainersUseCase;

    @MockBean
    protected DiscoverLocalContainersUseCase discoverLocalContainersUseCase;

    // --- Launchpad use cases ---
    @MockBean
    protected GetLaunchpadServicesUseCase getLaunchpadServicesUseCase;

    @BeforeEach
    void configureSetupState() {
        when(setupStateHolder.isConfigured()).thenReturn(true);
    }
}
