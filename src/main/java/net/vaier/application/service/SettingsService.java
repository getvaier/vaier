package net.vaier.application.service;

import lombok.extern.slf4j.Slf4j;
import net.vaier.application.GetAndroidAppUseCase;
import net.vaier.application.GetAppSettingsUseCase;
import net.vaier.application.GetAppVersionUseCase;
import net.vaier.application.SetSurvivalKitPassphraseUseCase;
import net.vaier.application.TestSmtpCredentialsUseCase;
import net.vaier.application.UpdateAnthropicApiKeyUseCase;
import net.vaier.application.UpdateBackupSettingsUseCase;
import net.vaier.application.UpdateDiskMonitorSettingsUseCase;
import net.vaier.application.UpdateSmtpSettingsUseCase;
import net.vaier.config.ConfigResolver;
import net.vaier.config.WildcardDnsStatusHolder;
import net.vaier.domain.AndroidApp;
import net.vaier.domain.VaierConfig;
import net.vaier.domain.VaierHostnames;
import net.vaier.domain.WildcardDnsReport;
import net.vaier.domain.port.ForPersistingAppConfiguration;
import net.vaier.domain.port.ForReadingAndroidApp;
import net.vaier.domain.port.ForReadingAppVersion;
import net.vaier.domain.port.ForReadingStoredSmtpPassword;
import net.vaier.domain.port.ForSendingTestEmail;
import net.vaier.domain.port.ForVerifyingSmtpCredentials;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Optional;

@Service
@Slf4j
public class SettingsService implements
    GetAppSettingsUseCase,
    GetAppVersionUseCase,
    GetAndroidAppUseCase,
    UpdateSmtpSettingsUseCase,
    UpdateDiskMonitorSettingsUseCase,
    UpdateBackupSettingsUseCase,
    SetSurvivalKitPassphraseUseCase,
    UpdateAnthropicApiKeyUseCase,
    TestSmtpCredentialsUseCase {

    private final ForPersistingAppConfiguration configPersistence;
    private final ForVerifyingSmtpCredentials smtpVerifier;
    private final ForReadingStoredSmtpPassword storedPasswordReader;
    private final ForSendingTestEmail testEmailSender;
    private final ConfigResolver configResolver;
    private final WildcardDnsStatusHolder wildcardDnsStatusHolder;
    private final ForReadingAppVersion appVersionReader;
    private final ForReadingAndroidApp androidAppReader;
    private final Clock clock;

    public SettingsService(ForPersistingAppConfiguration configPersistence,
                           ForVerifyingSmtpCredentials smtpVerifier,
                           ForReadingStoredSmtpPassword storedPasswordReader,
                           ForSendingTestEmail testEmailSender,
                           ConfigResolver configResolver,
                           WildcardDnsStatusHolder wildcardDnsStatusHolder,
                           ForReadingAppVersion appVersionReader,
                           ForReadingAndroidApp androidAppReader,
                           Clock clock) {
        this.configPersistence = configPersistence;
        this.smtpVerifier = smtpVerifier;
        this.storedPasswordReader = storedPasswordReader;
        this.testEmailSender = testEmailSender;
        this.configResolver = configResolver;
        this.wildcardDnsStatusHolder = wildcardDnsStatusHolder;
        this.appVersionReader = appVersionReader;
        this.androidAppReader = androidAppReader;
        this.clock = clock;
    }

    @Override
    public String appVersion() {
        return appVersionReader.currentVersion();
    }

    /**
     * The deployment's own Android package, read through its port and stamped with this Vaier's own host
     * name so the app arrives already knowing where it came from. The Vaier app belongs with the other
     * facts about this deployment's build — its version, its configuration — rather than with the VPN:
     * serving it is not a tunnel operation, it is Vaier handing out a copy of itself.
     *
     * <p>Before a domain is configured there is no host to stamp with, and the package is served as built.
     */
    @Override
    public Optional<AndroidApp> androidApp() {
        return androidAppReader.readApp(new VaierHostnames(configResolver.getDomain())
            .configuredVaierServerFqdn()
            .orElse(null));
    }

    @Override
    public AppSettingsResult getSettings() {
        return configPersistence.load()
            .map(this::toResult)
            .orElse(new AppSettingsResult(null, null, null, null, null, null,
                wildcardDnsStatus(), wildcardDnsLabel(), wildcardDnsSeverity(), wildcardDnsMessage(),
                VaierConfig.DEFAULT_DISK_MONITOR_THRESHOLD_PERCENT, configResolver.isSocialAuthAvailable(),
                VaierConfig.DEFAULT_BACKUP_SCHEDULE_HOUR, backupScheduleZone(), false, false, false, false));
    }

    @Override
    public void updateDiskMonitorThreshold(int thresholdPercent) {
        VaierConfig current = configPersistence.load().orElse(VaierConfig.builder().build());
        configPersistence.save(current.withDiskMonitorThreshold(thresholdPercent));
        configResolver.reload();
        log.info("Host disk monitor threshold updated to {}%", thresholdPercent);
    }

    @Override
    public void updateBackupScheduleHour(int hour) {
        VaierConfig current = configPersistence.load().orElse(VaierConfig.builder().build());
        configPersistence.save(current.withBackupScheduleHour(hour));
        configResolver.reload();
        log.info("Fleet-backup nightly schedule hour updated to {}", hour);
    }

    @Override
    public void setSurvivalKitPassphrase(String passphrase) {
        VaierConfig current = configPersistence.load().orElse(VaierConfig.builder().build());
        configPersistence.save(current.withSurvivalKitPassphrase(passphrase));
        // Never logged, not even masked, and never its length: this one opens every backup in the fleet.
        log.info("Survival kit passphrase set");
    }

    @Override
    public void updateAnthropicApiKey(String apiKey) {
        VaierConfig current = configPersistence.load().orElse(VaierConfig.builder().build());
        VaierConfig updated = current.withAnthropicApiKey(apiKey);
        configPersistence.save(updated);
        // Never the key, not even masked, and never its length: it bills the operator's own Claude account.
        log.info("Anthropic API key {}", updated.hasAnthropicApiKey() ? "stored" : "cleared");
    }

    /**
     * What the boot-time wildcard check found, as the status name — null before it has run. The verdict
     * is stated where the operator can act on it rather than only in a log line that has scrolled away.
     */
    private String wildcardDnsStatus() {
        return wildcardDnsStatusHolder.report()
            .map(r -> r.status().name())
            .orElse(null);
    }

    /** The verdict in the operator's words, worded by the domain — null before the check has run. */
    private String wildcardDnsLabel() {
        return wildcardDnsStatusHolder.report()
            .map(r -> r.status().getLabel())
            .orElse(null);
    }

    /**
     * How much attention the verdict deserves, as the severity name — null before the check has run.
     * The domain grades it; this only passes the grade on.
     */
    private String wildcardDnsSeverity() {
        return wildcardDnsStatusHolder.report()
            .map(r -> r.status().getSeverity().name())
            .orElse(null);
    }

    /** The same verdict as a sentence, worded by the domain — null before the check has run. */
    private String wildcardDnsMessage() {
        return wildcardDnsStatusHolder.report()
            .map(WildcardDnsReport::message)
            .orElse(null);
    }

    @Override
    public void updateSmtpSettings(String host, int port, String username, String password, String sender) {
        String resolvedPassword = resolveSmtpPassword(password);
        smtpVerifier.verify(host, port, username, resolvedPassword);

        VaierConfig current = configPersistence.load().orElse(VaierConfig.builder().build());
        configPersistence.save(current.withSmtpSettings(host, port, username, sender, resolvedPassword));
        log.info("SMTP settings updated for host: {}", host);
    }

    @Override
    public void sendTestEmail(String host, int port, String username, String password,
                              String sender, String recipient) {
        if (recipient == null || recipient.isBlank()) {
            throw new IllegalArgumentException("recipient email address is required");
        }
        String resolvedPassword = resolveSmtpPassword(password);
        testEmailSender.sendTestEmail(host, port, username, resolvedPassword, sender, recipient);
    }

    private AppSettingsResult toResult(VaierConfig config) {
        return new AppSettingsResult(
            config.getDomain(),
            config.getAcmeEmail(),
            config.getSmtpHost(),
            config.getSmtpPort(),
            config.getSmtpUsername(),
            config.getSmtpSender(),
            wildcardDnsStatus(),
            wildcardDnsLabel(),
            wildcardDnsSeverity(),
            wildcardDnsMessage(),
            config.effectiveDiskMonitorThresholdPercent(),
            configResolver.isSocialAuthAvailable(),
            config.effectiveBackupScheduleHour(),
            backupScheduleZone(),
            config.hasSurvivalKitPassphrase(),
            config.hasAnthropicApiKey(),
            config.survivalKitEverWritten(),
            config.isSmtpConfigured()
        );
    }

    /**
     * The zone the nightly backup schedule fires in, taken from the same clock the scheduler reads, so the
     * label the UI shows can never drift from the hour that actually runs.
     */
    private String backupScheduleZone() {
        return clock.getZone().getId();
    }

    private String resolveSmtpPassword(String provided) {
        return VaierConfig.resolveSmtpPassword(provided, storedPasswordReader.readStoredPassword());
    }
}
