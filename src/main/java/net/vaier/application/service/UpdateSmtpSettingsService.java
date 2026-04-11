package net.vaier.application.service;

import lombok.extern.slf4j.Slf4j;
import net.vaier.application.UpdateSmtpSettingsUseCase;
import net.vaier.domain.VaierConfig;
import net.vaier.domain.port.ForConfiguringSmtpNotifier;
import net.vaier.domain.port.ForPersistingAppConfiguration;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class UpdateSmtpSettingsService implements UpdateSmtpSettingsUseCase {

    private final ForPersistingAppConfiguration configPersistence;
    private final ForConfiguringSmtpNotifier smtpNotifierConfig;

    public UpdateSmtpSettingsService(ForPersistingAppConfiguration configPersistence,
                                     ForConfiguringSmtpNotifier smtpNotifierConfig) {
        this.configPersistence = configPersistence;
        this.smtpNotifierConfig = smtpNotifierConfig;
    }

    @Override
    public void updateSmtpSettings(String host, int port, String username, String password, String sender) {
        VaierConfig current = configPersistence.load().orElse(VaierConfig.builder().build());
        VaierConfig updated = VaierConfig.builder()
            .domain(current.getDomain())
            .awsKey(current.getAwsKey())
            .awsSecret(current.getAwsSecret())
            .acmeEmail(current.getAcmeEmail())
            .smtpHost(host)
            .smtpPort(port)
            .smtpUsername(username)
            .smtpSender(sender)
            .build();

        configPersistence.save(updated);
        log.info("SMTP settings updated for host: {}", host);

        smtpNotifierConfig.updateSmtpConfig(host, port, username, password, sender);
    }
}
