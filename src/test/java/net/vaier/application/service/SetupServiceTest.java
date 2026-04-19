package net.vaier.application.service;

import net.vaier.config.SetupStateHolder;
import net.vaier.domain.VaierConfig;
import net.vaier.domain.port.ForPersistingAppConfiguration;
import net.vaier.domain.port.ForPersistingUsers;
import net.vaier.domain.port.ForValidatingAwsCredentials;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SetupServiceTest {

    private static final String VALID_DOMAIN = "example.com";
    private static final String VALID_KEY = "AKIAIOSFODNN7EXAMPLE";
    private static final String VALID_SECRET = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";
    private static final String VALID_EMAIL = "admin@example.com";
    private static final String VALID_USERNAME = "admin";
    private static final String VALID_PASSWORD = "password123";

    @Mock SetupStateHolder setupStateHolder;
    @Mock ForPersistingAppConfiguration configPersistence;
    @Mock ForValidatingAwsCredentials forValidatingAwsCredentials;
    @Mock ForPersistingUsers forPersistingUsers;
    @Mock LifecycleService lifecycleService;
    @InjectMocks SetupService setupService;

    @Test
    void isConfigured_delegatesToSetupStateHolder() {
        when(setupStateHolder.isConfigured()).thenReturn(true);
        assertThat(setupService.isConfigured()).isTrue();
    }

    @Test
    void validateAndListZones_delegatesToPort() {
        when(forValidatingAwsCredentials.listHostedZones(VALID_KEY, VALID_SECRET))
            .thenReturn(List.of("example.com", "other.org"));

        List<String> zones = setupService.validateAndListZones(VALID_KEY, VALID_SECRET);

        assertThat(zones).containsExactly("example.com", "other.org");
    }

    @Test
    void validateAndListZones_propagatesAwsLookupFailure() {
        when(forValidatingAwsCredentials.listHostedZones(VALID_KEY, VALID_SECRET))
            .thenThrow(new RuntimeException("AWS credentials are invalid"));

        assertThatThrownBy(() -> setupService.validateAndListZones(VALID_KEY, VALID_SECRET))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("AWS");
    }

    @Test
    void completeSetup_savesConfigAndCreatesAdmin() {
        setupService.completeSetup(VALID_DOMAIN, VALID_KEY, VALID_SECRET, VALID_EMAIL, VALID_USERNAME, VALID_PASSWORD);

        ArgumentCaptor<VaierConfig> configCaptor = ArgumentCaptor.forClass(VaierConfig.class);
        verify(configPersistence).save(configCaptor.capture());
        VaierConfig saved = configCaptor.getValue();
        assertThat(saved.getDomain()).isEqualTo(VALID_DOMAIN);
        assertThat(saved.getAwsKey()).isEqualTo(VALID_KEY);
        assertThat(saved.getAwsSecret()).isEqualTo(VALID_SECRET);
        assertThat(saved.getAcmeEmail()).isEqualTo(VALID_EMAIL);

        verify(setupStateHolder).markConfigured();
        verify(forPersistingUsers).addUser(VALID_USERNAME, VALID_PASSWORD, "", VALID_USERNAME);
    }

    @Test
    void completeSetup_triggersLifecycle() {
        setupService.completeSetup(VALID_DOMAIN, VALID_KEY, VALID_SECRET, VALID_EMAIL, VALID_USERNAME, VALID_PASSWORD);

        verify(lifecycleService).runLifecycle();
    }

    @Test
    void completeSetup_throwsWhenAlreadyConfigured() {
        when(setupStateHolder.isConfigured()).thenReturn(true);

        assertThatThrownBy(() ->
            setupService.completeSetup(VALID_DOMAIN, VALID_KEY, VALID_SECRET, VALID_EMAIL, VALID_USERNAME, VALID_PASSWORD)
        ).isInstanceOf(IllegalStateException.class);
    }

    // --- input validation ---

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t"})
    void completeSetup_rejectsBlankDomain(String domain) {
        assertThatThrownBy(() ->
            setupService.completeSetup(domain, VALID_KEY, VALID_SECRET, VALID_EMAIL, VALID_USERNAME, VALID_PASSWORD)
        ).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("domain");

        verifyNoInteractions(configPersistence, forPersistingUsers, lifecycleService);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void completeSetup_rejectsBlankAwsKey(String awsKey) {
        assertThatThrownBy(() ->
            setupService.completeSetup(VALID_DOMAIN, awsKey, VALID_SECRET, VALID_EMAIL, VALID_USERNAME, VALID_PASSWORD)
        ).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("awsKey");

        verifyNoInteractions(configPersistence, forPersistingUsers, lifecycleService);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void completeSetup_rejectsBlankAwsSecret(String awsSecret) {
        assertThatThrownBy(() ->
            setupService.completeSetup(VALID_DOMAIN, VALID_KEY, awsSecret, VALID_EMAIL, VALID_USERNAME, VALID_PASSWORD)
        ).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("awsSecret");

        verifyNoInteractions(configPersistence, forPersistingUsers, lifecycleService);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void completeSetup_rejectsBlankAcmeEmail(String email) {
        assertThatThrownBy(() ->
            setupService.completeSetup(VALID_DOMAIN, VALID_KEY, VALID_SECRET, email, VALID_USERNAME, VALID_PASSWORD)
        ).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("acmeEmail");

        verifyNoInteractions(configPersistence, forPersistingUsers, lifecycleService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-an-email", "missing-at-sign.com", "@no-local.com", "no-domain@", "no-tld@foo"})
    void completeSetup_rejectsInvalidAcmeEmailFormat(String email) {
        assertThatThrownBy(() ->
            setupService.completeSetup(VALID_DOMAIN, VALID_KEY, VALID_SECRET, email, VALID_USERNAME, VALID_PASSWORD)
        ).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("acmeEmail");

        verifyNoInteractions(configPersistence, forPersistingUsers, lifecycleService);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void completeSetup_rejectsBlankAdminUsername(String username) {
        assertThatThrownBy(() ->
            setupService.completeSetup(VALID_DOMAIN, VALID_KEY, VALID_SECRET, VALID_EMAIL, username, VALID_PASSWORD)
        ).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("adminUsername");

        verifyNoInteractions(configPersistence, forPersistingUsers, lifecycleService);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void completeSetup_rejectsBlankAdminPassword(String password) {
        assertThatThrownBy(() ->
            setupService.completeSetup(VALID_DOMAIN, VALID_KEY, VALID_SECRET, VALID_EMAIL, VALID_USERNAME, password)
        ).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("adminPassword");

        verifyNoInteractions(configPersistence, forPersistingUsers, lifecycleService);
    }

    @Test
    void completeSetup_rejectsAdminPasswordShorterThanMinimum() {
        assertThatThrownBy(() ->
            setupService.completeSetup(VALID_DOMAIN, VALID_KEY, VALID_SECRET, VALID_EMAIL, VALID_USERNAME, "short")
        ).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("adminPassword");

        verifyNoInteractions(configPersistence, forPersistingUsers, lifecycleService);
    }
}
