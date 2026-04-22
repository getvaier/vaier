package net.vaier.application.service;

import lombok.extern.slf4j.Slf4j;
import net.vaier.application.UpdateSmtpSettingsUseCase;
import net.vaier.config.ServiceNames;
import net.vaier.domain.VaierConfig;
import net.vaier.domain.port.ForConfiguringSmtpNotifier;
import net.vaier.domain.port.ForPersistingAppConfiguration;
import net.vaier.domain.port.ForReadingStoredSmtpPassword;
import net.vaier.domain.port.ForRestartingContainers;
import net.vaier.domain.port.ForVerifyingSmtpCredentials;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class UpdateSmtpSettingsService implements UpdateSmtpSettingsUseCase {

    private final ForPersistingAppConfiguration configPersistence;
    private final ForConfiguringSmtpNotifier smtpNotifierConfig;
    private final ForRestartingContainers containerRestarter;
    private final ForVerifyingSmtpCredentials smtpVerifier;
    private final ForReadingStoredSmtpPassword storedPasswordReader;

    public UpdateSmtpSettingsService(ForPersistingAppConfiguration configPersistence,
                                     ForConfiguringSmtpNotifier smtpNotifierConfig,
                                     ForRestartingContainers containerRestarter,
                                     ForVerifyingSmtpCredentials smtpVerifier,
                                     ForReadingStoredSmtpPassword storedPasswordReader) {
        this.configPersistence = configPersistence;
        this.smtpNotifierConfig = smtpNotifierConfig;
        this.containerRestarter = containerRestarter;
        this.smtpVerifier = smtpVerifier;
        this.storedPasswordReader = storedPasswordReader;
    }

    @Override
    public void updateSmtpSettings(String host, int port, String username, String password, String sender) {
        String resolvedPassword = resolvePassword(password);
        smtpVerifier.verify(host, port, username, resolvedPassword);

        VaierConfig current = configPersistence.load().orElse(VaierConfig.builder().build());
        VaierConfig updated = VaierConfig.builder()
            .domain(current.getDomain())
            .awsKey(current.getAwsKey())
            .awsSecret(current.getAwsSecret())
            .cloudflareToken(current.getCloudflareToken())
            .acmeEmail(current.getAcmeEmail())
            .smtpHost(host)
            .smtpPort(port)
            .smtpUsername(username)
            .smtpSender(sender)
            .build();

        configPersistence.save(updated);
        log.info("SMTP settings updated for host: {}", host);

        smtpNotifierConfig.updateSmtpConfig(host, port, username, resolvedPassword, sender);
        containerRestarter.restartContainer(ServiceNames.AUTHELIA);
    }

    private String resolvePassword(String provided) {
        if (provided != null && !provided.isBlank()) {
            return provided;
        }
        return storedPasswordReader.readStoredPassword()
            .filter(p -> !p.isBlank())
            .orElseThrow(() -> new IllegalArgumentException("SMTP password is required"));
    }
}
