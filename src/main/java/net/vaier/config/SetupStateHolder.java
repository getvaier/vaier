package net.vaier.config;

import net.vaier.domain.port.ForPersistingAppConfiguration;
import org.springframework.stereotype.Component;

@Component
public class SetupStateHolder {

    private final ForPersistingAppConfiguration configPersistence;
    private volatile boolean configured;

    public SetupStateHolder(ForPersistingAppConfiguration configPersistence) {
        this.configPersistence = configPersistence;
        this.configured = configPersistence.exists() || hasEnvVars();
    }

    public boolean isConfigured() {
        return configured;
    }

    public void markConfigured() {
        this.configured = true;
    }

    private boolean hasEnvVars() {
        String domain = System.getenv("VAIER_DOMAIN");
        String awsKey = System.getenv("VAIER_AWS_KEY");
        String awsSecret = System.getenv("VAIER_AWS_SECRET");
        return domain != null && !domain.isBlank()
            && awsKey != null && !awsKey.isBlank()
            && awsSecret != null && !awsSecret.isBlank();
    }
}
