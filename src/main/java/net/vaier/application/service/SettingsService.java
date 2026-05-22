package net.vaier.application.service;

import lombok.extern.slf4j.Slf4j;
import net.vaier.application.GetAppSettingsUseCase;
import net.vaier.application.TestSmtpCredentialsUseCase;
import net.vaier.application.UpdateAwsCredentialsUseCase;
import net.vaier.application.UpdateSmtpSettingsUseCase;
import net.vaier.config.ConfigResolver;
import net.vaier.config.ServiceNames;
import net.vaier.domain.VaierConfig;
import net.vaier.domain.port.ForConfiguringSmtpNotifier;
import net.vaier.domain.port.ForPersistingAppConfiguration;
import net.vaier.domain.port.ForReadingStoredSmtpPassword;
import net.vaier.domain.port.ForRestartingContainers;
import net.vaier.domain.port.ForSendingTestEmail;
import net.vaier.domain.port.ForValidatingAwsCredentials;
import net.vaier.domain.port.ForVerifyingSmtpCredentials;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class SettingsService implements
    GetAppSettingsUseCase,
    UpdateAwsCredentialsUseCase,
    UpdateSmtpSettingsUseCase,
    TestSmtpCredentialsUseCase {

    private final ForPersistingAppConfiguration configPersistence;
    private final ForValidatingAwsCredentials forValidatingAwsCredentials;
    private final ForConfiguringSmtpNotifier smtpNotifierConfig;
    private final ForRestartingContainers containerRestarter;
    private final ForVerifyingSmtpCredentials smtpVerifier;
    private final ForReadingStoredSmtpPassword storedPasswordReader;
    private final ForSendingTestEmail testEmailSender;
    private final ConfigResolver configResolver;

    public SettingsService(ForPersistingAppConfiguration configPersistence,
                           ForValidatingAwsCredentials forValidatingAwsCredentials,
                           ForConfiguringSmtpNotifier smtpNotifierConfig,
                           ForRestartingContainers containerRestarter,
                           ForVerifyingSmtpCredentials smtpVerifier,
                           ForReadingStoredSmtpPassword storedPasswordReader,
                           ForSendingTestEmail testEmailSender,
                           ConfigResolver configResolver) {
        this.configPersistence = configPersistence;
        this.forValidatingAwsCredentials = forValidatingAwsCredentials;
        this.smtpNotifierConfig = smtpNotifierConfig;
        this.containerRestarter = containerRestarter;
        this.smtpVerifier = smtpVerifier;
        this.storedPasswordReader = storedPasswordReader;
        this.testEmailSender = testEmailSender;
        this.configResolver = configResolver;
    }

    @Override
    public AppSettingsResult getSettings() {
        return configPersistence.load()
            .map(this::toResult)
            .orElse(new AppSettingsResult(null, null, null, null, null, null, null, dnsProviderName()));
    }

    private String dnsProviderName() {
        return configResolver.getDnsProvider() == null ? null : configResolver.getDnsProvider().name();
    }

    @Override
    public void updateAwsCredentials(String awsKey, String awsSecret) {
        forValidatingAwsCredentials.listHostedZones(awsKey, awsSecret);

        VaierConfig current = configPersistence.load().orElse(VaierConfig.builder().build());
        configPersistence.save(current.withAwsCredentials(awsKey, awsSecret));
        log.info("AWS credentials updated");
    }

    @Override
    public void updateSmtpSettings(String host, int port, String username, String password, String sender) {
        String resolvedPassword = resolveSmtpPassword(password);
        smtpVerifier.verify(host, port, username, resolvedPassword);

        VaierConfig current = configPersistence.load().orElse(VaierConfig.builder().build());
        configPersistence.save(current.withSmtpSettings(host, port, username, sender));
        log.info("SMTP settings updated for host: {}", host);

        smtpNotifierConfig.updateSmtpConfig(host, port, username, resolvedPassword, sender);
        containerRestarter.restartContainer(ServiceNames.AUTHELIA);
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
            config.maskedAwsKey(),
            config.getAcmeEmail(),
            config.getSmtpHost(),
            config.getSmtpPort(),
            config.getSmtpUsername(),
            config.getSmtpSender(),
            dnsProviderName()
        );
    }

    private String resolveSmtpPassword(String provided) {
        return VaierConfig.resolveSmtpPassword(provided, storedPasswordReader.readStoredPassword());
    }
}
