package net.vaier.application.service;

import net.vaier.application.GetAppSettingsUseCase.AppSettingsResult;
import net.vaier.config.ConfigResolver;
import net.vaier.config.WildcardDnsStatusHolder;
import net.vaier.domain.AndroidApp;
import net.vaier.domain.VaierConfig;
import net.vaier.domain.WildcardDnsReport;
import net.vaier.domain.WildcardDnsStatus;
import net.vaier.domain.port.ForPersistingAppConfiguration;
import net.vaier.domain.port.ForReadingAndroidApp;
import net.vaier.domain.port.ForReadingAppVersion;
import net.vaier.domain.port.ForReadingStoredSmtpPassword;
import net.vaier.domain.port.ForSendingTestEmail;
import net.vaier.domain.port.ForVerifyingSmtpCredentials;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SettingsServiceTest {

    @Mock ForPersistingAppConfiguration configPersistence;
    @Mock ForVerifyingSmtpCredentials smtpVerifier;
    @Mock ForReadingStoredSmtpPassword storedPasswordReader;
    @Mock ForSendingTestEmail testEmailSender;
    @Mock ConfigResolver configResolver;
    @Mock WildcardDnsStatusHolder wildcardDnsStatusHolder;
    @Mock ForReadingAppVersion appVersionReader;
    @Mock ForReadingAndroidApp androidAppReader;

    /** A zone that is not the CI JVM's default (UTC), so a hardcoded-UTC answer can't pass by accident. */
    @Spy Clock clock = Clock.fixed(Instant.parse("2026-07-10T00:00:00Z"), ZoneId.of("Europe/Oslo"));

    @InjectMocks SettingsService service;

    private VaierConfig existingConfig() {
        return VaierConfig.builder()
            .domain("example.com")
            .acmeEmail("admin@example.com")
            .build();
    }

    // --- appVersion ---

    @Test
    void appVersion_delegatesToTheVersionPort() {
        when(appVersionReader.currentVersion()).thenReturn("1.0.0");

        assertThat(service.appVersion()).isEqualTo("1.0.0");
    }

    // --- androidApp ---

    @Test
    void androidApp_isWhateverPackageTheImageCarries() {
        // The service orchestrates and nothing more: whether there is an app to offer is the package's
        // own fact, read through the port, and the domain has already decided what counts as one.
        AndroidApp app = AndroidApp.of(20_467_986L, out -> {
        }).orElseThrow();
        when(configResolver.getDomain()).thenReturn("example.com");
        when(androidAppReader.readApp(any())).thenReturn(Optional.of(app));

        assertThat(service.androidApp()).contains(app);
    }

    @Test
    void androidApp_isEmptyWhenTheImageCarriesNone() {
        when(configResolver.getDomain()).thenReturn("example.com");
        when(androidAppReader.readApp(any())).thenReturn(Optional.empty());

        assertThat(service.androidApp()).isEmpty();
    }

    @Test
    void androidApp_isServedStampedWithVaierOwnHostName() {
        // The app that comes off this deployment has to know which Vaier served it — otherwise the first
        // thing it asks a person for is an address they would have to read off a browser's URL bar. The
        // host is Vaier's own FQDN, named by the domain object rather than concatenated here.
        when(configResolver.getDomain()).thenReturn("eilertsen.family");
        when(androidAppReader.readApp(any())).thenReturn(Optional.empty());

        service.androidApp();

        ArgumentCaptor<String> host = ArgumentCaptor.forClass(String.class);
        verify(androidAppReader).readApp(host.capture());
        assertThat(host.getValue()).isEqualTo("vaier.eilertsen.family");
    }

    @Test
    void androidApp_isServedUnstampedBeforeADomainIsConfigured() {
        // A fresh install has no domain yet, and "vaier.null" is not a host. The package is served as
        // built rather than withheld — the download is the one door a phone can reach before anything else.
        when(configResolver.getDomain()).thenReturn(null);
        when(androidAppReader.readApp(null)).thenReturn(Optional.empty());

        assertThat(service.androidApp()).isEmpty();

        verify(androidAppReader).readApp(null);
    }

    // --- getSettings ---

    @Test
    void getSettings_returnsConfigFields() {
        VaierConfig config = VaierConfig.builder()
            .domain("example.com")
            .acmeEmail("admin@example.com")
            .smtpHost("smtp.example.com")
            .smtpPort(587)
            .smtpUsername("user@example.com")
            .smtpSender("noreply@example.com")
            .build();
        when(configPersistence.load()).thenReturn(Optional.of(config));

        AppSettingsResult result = service.getSettings();

        assertThat(result.domain()).isEqualTo("example.com");
        assertThat(result.acmeEmail()).isEqualTo("admin@example.com");
        assertThat(result.smtpHost()).isEqualTo("smtp.example.com");
        assertThat(result.smtpPort()).isEqualTo(587);
        assertThat(result.smtpUsername()).isEqualTo("user@example.com");
        assertThat(result.smtpSender()).isEqualTo("noreply@example.com");
    }

    @Test
    void getSettings_saysWhetherASurvivalKitWasEverWritten_andWhetherMailIsConfigured() {
        // The fleet nudge ladder (#336) asks both. The kit's only record is the fingerprint the writer keeps;
        // "mail configured" is VaierConfig's own verdict (host AND username), never re-derived downstream.
        when(configPersistence.load()).thenReturn(Optional.of(VaierConfig.builder().smtpHost("smtp.example.com").build()));
        assertThat(service.getSettings().survivalKitWritten()).isFalse();
        assertThat(service.getSettings().smtpConfigured()).as("a host without a username is not configured").isFalse();

        when(configPersistence.load()).thenReturn(Optional.of(VaierConfig.builder()
            .smtpHost("smtp.example.com").smtpUsername("user@example.com").build()
            .withSurvivalKitFingerprint("sha256:abc")));
        assertThat(service.getSettings().survivalKitWritten()).isTrue();
        assertThat(service.getSettings().smtpConfigured()).isTrue();
    }

    @Test
    void getSettings_returnsNullsWhenNoConfig() {
        when(configPersistence.load()).thenReturn(Optional.empty());

        AppSettingsResult result = service.getSettings();

        assertThat(result.domain()).isNull();
    }

    // --- the boot-time wildcard verdict (#331) ---

    /**
     * The verdict is stated where the operator can act on it, not only in a boot log line that has long
     * scrolled away — so the settings payload carries both the status and the sentence.
     */
    @Test
    void getSettings_carriesTheWildcardVerdictFromTheBootCheck() {
        when(configPersistence.load()).thenReturn(Optional.of(existingConfig()));
        when(wildcardDnsStatusHolder.report()).thenReturn(Optional.of(new WildcardDnsReport(
            WildcardDnsStatus.NOT_RESOLVING, "9f3c1a.b21d70.example.com", "52.29.74.114", List.of())));

        AppSettingsResult result = service.getSettings();

        assertThat(result.wildcardDnsStatus()).isEqualTo("NOT_RESOLVING");
        assertThat(result.wildcardDnsMessage())
            .isEqualTo("Wildcard DNS is not set up. Create one record — *.example.com A 52.29.74.114 — "
                + "and every service Vaier publishes will resolve.");
    }

    /**
     * The label and the severity are the domain's judgments, passed through verbatim. The browser used
     * to derive both from the status name, which meant a new status rendered as nothing at all.
     */
    @Test
    void getSettings_carriesTheDomainsOwnLabelAndSeverity_soTheBrowserDecidesNothing() {
        when(configPersistence.load()).thenReturn(Optional.of(existingConfig()));
        when(wildcardDnsStatusHolder.report()).thenReturn(Optional.of(new WildcardDnsReport(
            WildcardDnsStatus.RESOLVES_ELSEWHERE, "9f3c1a.b21d70.example.com", "52.29.74.114",
            List.of("1.2.3.4"))));

        AppSettingsResult result = service.getSettings();

        assertThat(result.wildcardDnsLabel()).isEqualTo("Resolves elsewhere");
        assertThat(result.wildcardDnsSeverity()).isEqualTo("ERROR");
    }

    @Test
    void getSettings_leavesTheLabelAndSeverityNullBeforeTheBootCheckHasRun() {
        when(configPersistence.load()).thenReturn(Optional.of(existingConfig()));
        when(wildcardDnsStatusHolder.report()).thenReturn(Optional.empty());

        AppSettingsResult result = service.getSettings();

        assertThat(result.wildcardDnsLabel()).isNull();
        assertThat(result.wildcardDnsSeverity()).isNull();
    }

    /** "Not checked yet" is not a problem to warn about, so it reads as absent rather than as a failure. */
    @Test
    void getSettings_leavesTheWildcardVerdictNullBeforeTheBootCheckHasRun() {
        when(configPersistence.load()).thenReturn(Optional.of(existingConfig()));
        when(wildcardDnsStatusHolder.report()).thenReturn(Optional.empty());

        AppSettingsResult result = service.getSettings();

        assertThat(result.wildcardDnsStatus()).isNull();
        assertThat(result.wildcardDnsMessage()).isNull();
    }

    @Test
    void getSettings_carriesTheWildcardVerdictEvenWithNoConfigOnDisk() {
        when(configPersistence.load()).thenReturn(Optional.empty());
        when(wildcardDnsStatusHolder.report()).thenReturn(Optional.of(new WildcardDnsReport(
            WildcardDnsStatus.COVERED, "9f3c1a.b21d70.example.com", "52.29.74.114",
            List.of("52.29.74.114"))));

        AppSettingsResult result = service.getSettings();

        assertThat(result.wildcardDnsStatus()).isEqualTo("COVERED");
        assertThat(result.wildcardDnsMessage()).contains("*.example.com");
    }

    /**
     * The nightly backup hour is resolved against the scheduler's clock zone, so the UI must be able to
     * name that zone rather than say "server local time" and leave the operator guessing. Reporting the
     * zone from the same {@link Clock} the scheduler uses means the label can never drift from the truth.
     */
    @Test
    void getSettings_reportsTheZoneTheBackupScheduleActuallyFiresIn() {
        when(configPersistence.load()).thenReturn(Optional.of(existingConfig()));

        AppSettingsResult result = service.getSettings();

        assertThat(result.backupScheduleZone()).isEqualTo("Europe/Oslo");
    }

    @Test
    void getSettings_reportsTheScheduleZoneEvenWithNoConfig() {
        when(configPersistence.load()).thenReturn(Optional.empty());

        AppSettingsResult result = service.getSettings();

        assertThat(result.backupScheduleZone()).isEqualTo("Europe/Oslo");
    }




    @Test
    void getSettings_includesDiskMonitorThreshold() {
        VaierConfig config = VaierConfig.builder()
            .domain("example.com")
            .diskMonitorThresholdPercent(70)
            .build();
        when(configPersistence.load()).thenReturn(Optional.of(config));

        AppSettingsResult result = service.getSettings();

        assertThat(result.diskMonitorThresholdPercent()).isEqualTo(70);
    }

    @Test
    void getSettings_diskMonitorThresholdDefaultsTo85WhenUnset() {
        when(configPersistence.load()).thenReturn(Optional.of(existingConfig()));

        AppSettingsResult result = service.getSettings();

        assertThat(result.diskMonitorThresholdPercent()).isEqualTo(85);
    }

    // --- updateDiskMonitorThreshold ---

    @Test
    void updateDiskMonitorThreshold_savesThresholdAndReloadsResolver() {
        when(configPersistence.load()).thenReturn(Optional.of(existingConfig()));

        service.updateDiskMonitorThreshold(70);

        ArgumentCaptor<VaierConfig> captor = ArgumentCaptor.forClass(VaierConfig.class);
        verify(configPersistence).save(captor.capture());
        assertThat(captor.getValue().getDiskMonitorThresholdPercent()).isEqualTo(70);
        verify(configResolver).reload();
    }

    @Test
    void updateDiskMonitorThreshold_preservesExistingFields() {
        when(configPersistence.load()).thenReturn(Optional.of(existingConfig()));

        service.updateDiskMonitorThreshold(60);

        ArgumentCaptor<VaierConfig> captor = ArgumentCaptor.forClass(VaierConfig.class);
        verify(configPersistence).save(captor.capture());
        assertThat(captor.getValue().getDomain()).isEqualTo("example.com");
    }

    @Test
    void updateDiskMonitorThreshold_rejectsOutOfRange() {
        assertThatThrownBy(() -> service.updateDiskMonitorThreshold(0))
            .isInstanceOf(IllegalArgumentException.class);

        verify(configPersistence, never()).save(any());
    }

    // --- updateBackupScheduleHour ---

    @Test
    void updateBackupScheduleHourPersistsAndReloads() {
        when(configPersistence.load()).thenReturn(Optional.of(existingConfig()));

        service.updateBackupScheduleHour(5);

        ArgumentCaptor<VaierConfig> captor = ArgumentCaptor.forClass(VaierConfig.class);
        verify(configPersistence).save(captor.capture());
        assertThat(captor.getValue().getBackupScheduleHour()).isEqualTo(5);
        // Existing fields carry over on the read-modify-write.
        assertThat(captor.getValue().getDomain()).isEqualTo("example.com");
        verify(configResolver).reload();
    }

    @Test
    void updateBackupScheduleHour_rejectsOutOfRange() {
        assertThatThrownBy(() -> service.updateBackupScheduleHour(24))
            .isInstanceOf(IllegalArgumentException.class);

        verify(configPersistence, never()).save(any());
    }

    // --- setSurvivalKitPassphrase ---

    @Test
    void setSurvivalKitPassphrasePersistsItAndCarriesTheRestOfTheConfigOver() {
        when(configPersistence.load()).thenReturn(Optional.of(existingConfig()));

        service.setSurvivalKitPassphrase("correct horse battery staple");

        ArgumentCaptor<VaierConfig> captor = ArgumentCaptor.forClass(VaierConfig.class);
        verify(configPersistence).save(captor.capture());
        assertThat(captor.getValue().getSurvivalKitPassphrase()).isEqualTo("correct horse battery staple");
        assertThat(captor.getValue().getDomain()).isEqualTo("example.com");
    }

    /** Refused by the entity, and nothing is written — a blank passphrase must not reach the store. */
    @Test
    void setSurvivalKitPassphrase_rejectsABlankOne() {
        assertThatThrownBy(() -> service.setSurvivalKitPassphrase("  "))
            .isInstanceOf(IllegalArgumentException.class);

        verify(configPersistence, never()).save(any());
    }


    // --- updateSmtpSettings ---

    @Test
    void updateSmtpSettings_savesNonSecretFieldsToConfig() {
        when(configPersistence.load()).thenReturn(Optional.of(existingConfig()));

        service.updateSmtpSettings("smtp.example.com", 587, "user@example.com", "pass", "noreply@example.com");

        ArgumentCaptor<VaierConfig> captor = ArgumentCaptor.forClass(VaierConfig.class);
        verify(configPersistence).save(captor.capture());
        VaierConfig saved = captor.getValue();
        assertThat(saved.getSmtpHost()).isEqualTo("smtp.example.com");
        assertThat(saved.getSmtpPort()).isEqualTo(587);
        assertThat(saved.getSmtpUsername()).isEqualTo("user@example.com");
        assertThat(saved.getSmtpSender()).isEqualTo("noreply@example.com");
    }

    @Test
    void updateSmtpSettings_savesResolvedPasswordToVaierConfig() {
        // Authelia's secrets store is gone; the SMTP password now lives in Vaier's own owner-only
        // config file, so the notifier can send admin mail without any external component.
        when(configPersistence.load()).thenReturn(Optional.of(existingConfig()));

        service.updateSmtpSettings("smtp.example.com", 587, "user@example.com", "secretpass", "noreply@example.com");

        ArgumentCaptor<VaierConfig> captor = ArgumentCaptor.forClass(VaierConfig.class);
        verify(configPersistence).save(captor.capture());
        VaierConfig saved = captor.getValue();
        assertThat(saved.getDomain()).isEqualTo("example.com");
        assertThat(saved.getSmtpPassword()).isEqualTo("secretpass");
    }

    @Test
    void updateSmtpSettings_preservesExistingConfigFields() {
        when(configPersistence.load()).thenReturn(Optional.of(existingConfig()));

        service.updateSmtpSettings("smtp.example.com", 587, "user@example.com", "pass", "sender@example.com");

        ArgumentCaptor<VaierConfig> captor = ArgumentCaptor.forClass(VaierConfig.class);
        verify(configPersistence).save(captor.capture());
        VaierConfig saved = captor.getValue();
        assertThat(saved.getDomain()).isEqualTo("example.com");
        assertThat(saved.getAcmeEmail()).isEqualTo("admin@example.com");
    }

    @Test
    void updateSmtpSettings_verifiesCredentialsBeforeSaving() {
        when(configPersistence.load()).thenReturn(Optional.of(existingConfig()));

        service.updateSmtpSettings("smtp.example.com", 587, "user@example.com", "pass", "noreply@example.com");

        InOrder order = inOrder(smtpVerifier, configPersistence);
        order.verify(smtpVerifier).verify("smtp.example.com", 587, "user@example.com", "pass");
        order.verify(configPersistence).save(any(VaierConfig.class));
    }

    @Test
    void updateSmtpSettings_doesNotTouchConfigWhenVerificationFails() {
        doThrow(new RuntimeException("SMTP AUTH failed"))
            .when(smtpVerifier).verify("smtp.example.com", 587, "user@example.com", "badpass");

        assertThatThrownBy(() -> service.updateSmtpSettings(
            "smtp.example.com", 587, "user@example.com", "badpass", "noreply@example.com"))
            .hasMessageContaining("SMTP AUTH failed");

        verify(configPersistence, never()).save(any());
    }

    @Test
    void updateSmtpSettings_fallsBackToStoredPasswordWhenBlank() {
        when(configPersistence.load()).thenReturn(Optional.of(existingConfig()));
        when(storedPasswordReader.readStoredPassword()).thenReturn(Optional.of("storedPass"));

        service.updateSmtpSettings("smtp.example.com", 587, "user@example.com", "", "noreply@example.com");

        verify(smtpVerifier).verify("smtp.example.com", 587, "user@example.com", "storedPass");
        ArgumentCaptor<VaierConfig> captor = ArgumentCaptor.forClass(VaierConfig.class);
        verify(configPersistence).save(captor.capture());
        assertThat(captor.getValue().getSmtpPassword()).isEqualTo("storedPass");
    }

    @Test
    void updateSmtpSettings_rejectsWhenPasswordBlankAndNoStoredPassword() {
        when(storedPasswordReader.readStoredPassword()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateSmtpSettings(
            "smtp.example.com", 587, "user@example.com", "", "noreply@example.com"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("password");

        verify(smtpVerifier, never()).verify(any(), anyInt(), any(), any());
        verify(configPersistence, never()).save(any());
    }

    // --- sendTestEmail ---

    @Test
    void sendTestEmail_usesProvidedPassword() {
        service.sendTestEmail("smtp.example.com", 587, "user@example.com", "livePass",
            "noreply@example.com", "admin@example.com");

        verify(testEmailSender).sendTestEmail("smtp.example.com", 587, "user@example.com", "livePass",
            "noreply@example.com", "admin@example.com");
    }

    @Test
    void sendTestEmail_fallsBackToStoredPasswordWhenProvidedIsBlank() {
        when(storedPasswordReader.readStoredPassword()).thenReturn(Optional.of("storedPass"));

        service.sendTestEmail("smtp.example.com", 587, "user@example.com", "",
            "noreply@example.com", "admin@example.com");

        verify(testEmailSender).sendTestEmail("smtp.example.com", 587, "user@example.com", "storedPass",
            "noreply@example.com", "admin@example.com");
    }

    @Test
    void sendTestEmail_rejectsWhenNoPasswordAvailable() {
        when(storedPasswordReader.readStoredPassword()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.sendTestEmail("smtp.example.com", 587, "user@example.com", "",
            "noreply@example.com", "admin@example.com"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("password");

        verify(testEmailSender, never()).sendTestEmail(any(), anyInt(), any(), any(), any(), any());
    }

    @Test
    void sendTestEmail_rejectsBlankRecipient() {
        assertThatThrownBy(() -> service.sendTestEmail("smtp.example.com", 587, "user@example.com",
            "livePass", "noreply@example.com", ""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("recipient");

        verify(testEmailSender, never()).sendTestEmail(any(), anyInt(), any(), any(), any(), any());
    }

    // --- the Anthropic API key (#360) ------------------------------------------------------------------

    @Test
    void updateAnthropicApiKey_persistsItAndCarriesTheRestOfTheConfigOver() {
        when(configPersistence.load()).thenReturn(Optional.of(existingConfig()));

        service.updateAnthropicApiKey("sk-ant-api03-the-key");

        ArgumentCaptor<VaierConfig> captor = ArgumentCaptor.forClass(VaierConfig.class);
        verify(configPersistence).save(captor.capture());
        assertThat(captor.getValue().getAnthropicApiKey()).isEqualTo("sk-ant-api03-the-key");
        assertThat(captor.getValue().getDomain()).isEqualTo("example.com");
    }

    /** Clearing the field is how the operator turns Ask off again — it is a save, not a refusal. */
    @Test
    void updateAnthropicApiKey_clearsTheStoredKeyWhenBlank() {
        when(configPersistence.load()).thenReturn(Optional.of(
            existingConfig().withAnthropicApiKey("sk-ant-api03-the-key")));

        service.updateAnthropicApiKey("  ");

        ArgumentCaptor<VaierConfig> captor = ArgumentCaptor.forClass(VaierConfig.class);
        verify(configPersistence).save(captor.capture());
        assertThat(captor.getValue().hasAnthropicApiKey()).isFalse();
    }

    /** Whether, never what: the key opens the operator's own Claude account and the browser has no use for it. */
    @Test
    void getSettings_saysWhetherAnAnthropicApiKeyIsStoredAndNeverTheKeyItself() {
        when(configPersistence.load()).thenReturn(Optional.of(
            existingConfig().withAnthropicApiKey("sk-ant-api03-the-key")));

        AppSettingsResult result = service.getSettings();

        assertThat(result.hasAnthropicApiKey()).isTrue();
        assertThat(result.toString()).doesNotContain("sk-ant-api03-the-key");
    }

    @Test
    void getSettings_hasNoAnthropicApiKeyBeforeOneIsStored() {
        when(configPersistence.load()).thenReturn(Optional.of(existingConfig()));

        assertThat(service.getSettings().hasAnthropicApiKey()).isFalse();
    }
}
