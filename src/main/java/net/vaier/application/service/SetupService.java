package net.vaier.application.service;

import java.util.List;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.CheckSetupStatusUseCase;
import net.vaier.application.CompleteSetupUseCase;
import net.vaier.application.ValidateAwsCredentialsUseCase;
import net.vaier.config.SetupStateHolder;
import net.vaier.domain.VaierConfig;
import net.vaier.domain.port.ForPersistingAppConfiguration;
import net.vaier.domain.port.ForPersistingUsers;
import net.vaier.domain.port.ForValidatingAwsCredentials;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class SetupService implements CheckSetupStatusUseCase, ValidateAwsCredentialsUseCase, CompleteSetupUseCase {

    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

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
        requireNonBlank(domain, "domain");
        requireNonBlank(awsKey, "awsKey");
        requireNonBlank(awsSecret, "awsSecret");
        requireEmail(acmeEmail, "acmeEmail");
        requireNonBlank(adminUsername, "adminUsername");
        requirePassword(adminPassword, "adminPassword");

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

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private static void requireEmail(String value, String field) {
        requireNonBlank(value, field);
        if (!EMAIL_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " is not a valid email format");
        }
    }

    private static void requirePassword(String value, String field) {
        requireNonBlank(value, field);
        if (value.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException(
                field + " must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
    }
}
