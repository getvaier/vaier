package net.vaier.application.service;

import lombok.extern.slf4j.Slf4j;
import net.vaier.application.UpdateAwsCredentialsUseCase;
import net.vaier.domain.VaierConfig;
import net.vaier.domain.port.ForPersistingAppConfiguration;
import net.vaier.domain.port.ForValidatingAwsCredentials;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class UpdateAwsCredentialsService implements UpdateAwsCredentialsUseCase {

    private final ForPersistingAppConfiguration configPersistence;
    private final ForValidatingAwsCredentials forValidatingAwsCredentials;

    public UpdateAwsCredentialsService(ForPersistingAppConfiguration configPersistence,
                                       ForValidatingAwsCredentials forValidatingAwsCredentials) {
        this.configPersistence = configPersistence;
        this.forValidatingAwsCredentials = forValidatingAwsCredentials;
    }

    @Override
    public void updateAwsCredentials(String awsKey, String awsSecret) {
        forValidatingAwsCredentials.listHostedZones(awsKey, awsSecret);

        VaierConfig current = configPersistence.load().orElse(VaierConfig.builder().build());
        VaierConfig updated = VaierConfig.builder()
            .domain(current.getDomain())
            .awsKey(awsKey)
            .awsSecret(awsSecret)
            .cloudflareToken(current.getCloudflareToken())
            .acmeEmail(current.getAcmeEmail())
            .smtpHost(current.getSmtpHost())
            .smtpPort(current.getSmtpPort())
            .smtpUsername(current.getSmtpUsername())
            .smtpSender(current.getSmtpSender())
            .build();

        configPersistence.save(updated);
        log.info("AWS credentials updated");
    }
}
