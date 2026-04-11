package net.vaier.application.service;

import net.vaier.application.GetAppSettingsUseCase;
import net.vaier.domain.VaierConfig;
import net.vaier.domain.port.ForPersistingAppConfiguration;
import org.springframework.stereotype.Service;

@Service
public class GetAppSettingsService implements GetAppSettingsUseCase {

    private final ForPersistingAppConfiguration configPersistence;

    public GetAppSettingsService(ForPersistingAppConfiguration configPersistence) {
        this.configPersistence = configPersistence;
    }

    @Override
    public AppSettingsResult getSettings() {
        return configPersistence.load()
            .map(this::toResult)
            .orElse(new AppSettingsResult(null, null, null, null, null, null, null));
    }

    private AppSettingsResult toResult(VaierConfig config) {
        return new AppSettingsResult(
            config.getDomain(),
            maskAwsKey(config.getAwsKey()),
            config.getAcmeEmail(),
            config.getSmtpHost(),
            config.getSmtpPort(),
            config.getSmtpUsername(),
            config.getSmtpSender()
        );
    }

    private String maskAwsKey(String key) {
        if (key == null || key.length() <= 4) {
            return key;
        }
        return "****" + key.substring(key.length() - 4);
    }
}
