package net.vaier.application.service;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.CheckSetupStatusUseCase;
import net.vaier.application.CompleteSetupUseCase;
import net.vaier.application.ValidateAwsCredentialsUseCase;
import net.vaier.config.SetupStateHolder;
import net.vaier.domain.User;
import net.vaier.domain.VaierConfig;
import net.vaier.domain.port.ForPersistingAppConfiguration;
import net.vaier.domain.port.ForPersistingUsers;
import net.vaier.domain.port.ForValidatingAwsCredentials;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class SetupService implements CheckSetupStatusUseCase, ValidateAwsCredentialsUseCase, CompleteSetupUseCase {

    private final SetupStateHolder setupStateHolder;
    private final ForPersistingAppConfiguration configPersistence;
    private final ForValidatingAwsCredentials forValidatingAwsCredentials;
    private final ForPersistingUsers forPersistingUsers;
    private final LifecycleService lifecycleService;

    public SetupService(
        SetupStateHolder setupStateHolder,
        ForPersistingAppConfiguration configPersistence,
        ForValidatingAwsCredentials forValidatingAwsCredentials,
        ForPersistingUsers forPersistingUsers,
        LifecycleService lifecycleService
    ) {
        this.setupStateHolder = setupStateHolder;
        this.configPersistence = configPersistence;
        this.forValidatingAwsCredentials = forValidatingAwsCredentials;
        this.forPersistingUsers = forPersistingUsers;
        this.lifecycleService = lifecycleService;
    }

    @Override
    public boolean isConfigured() {
        return setupStateHolder.isConfigured();
    }

    @Override
    public List<String> validateAndListZones(String awsKey, String awsSecret) {
        return forValidatingAwsCredentials.listHostedZones(awsKey, awsSecret);
    }

    @Override
    public void completeSetup(String domain, String awsKey, String awsSecret, String acmeEmail,
                              String adminUsername, String adminPassword) {
        VaierConfig.validateForSetup(domain, awsKey, awsSecret, acmeEmail);
        User.validateUsername(adminUsername);
        User.validatePassword(adminPassword);

        if (setupStateHolder.isConfigured()) {
            throw new IllegalStateException("Setup has already been completed");
        }

        VaierConfig config = VaierConfig.builder()
            .domain(domain)
            .awsKey(awsKey)
            .awsSecret(awsSecret)
            .acmeEmail(acmeEmail)
            .build();

        configPersistence.save(config);
        log.info("Configuration saved for domain: {}", domain);

        forPersistingUsers.addUser(adminUsername, adminPassword, "", adminUsername);
        log.info("Admin user '{}' created", adminUsername);

        setupStateHolder.markConfigured();
        lifecycleService.runLifecycle();
    }
}
