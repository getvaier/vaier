package net.vaier.config;

import java.util.function.Function;
import net.vaier.domain.port.ForPersistingAppConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class SetupStateHolder {

    private final ForPersistingAppConfiguration configPersistence;
    private final Function<String, String> envLookup;
    private volatile boolean configured;

    @Autowired
    public SetupStateHolder(ForPersistingAppConfiguration configPersistence) {
        this(configPersistence, System::getenv);
    }

    SetupStateHolder(ForPersistingAppConfiguration configPersistence, Function<String, String> envLookup) {
        this.configPersistence = configPersistence;
        this.envLookup = envLookup;
        this.configured = configPersistence.exists() || hasDomainEnvVar();
    }

    public boolean isConfigured() {
        return configured;
    }

    public void markConfigured() {
        this.configured = true;
    }

    private boolean hasDomainEnvVar() {
        String domain = envLookup.apply("VAIER_DOMAIN");
        return domain != null && !domain.isBlank();
    }
}
