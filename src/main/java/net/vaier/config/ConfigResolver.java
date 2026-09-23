package net.vaier.config;

import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.VaierConfig;
import net.vaier.domain.port.ForPersistingAppConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class ConfigResolver {

    private final ForPersistingAppConfiguration configPersistence;
    private final Function<String, String> envLookup;
    private String domain;
    private String acmeEmail;
    private String smtpHost;
    private Integer smtpPort;
    private String smtpUsername;
    private String smtpSender;
    private int diskMonitorThresholdPercent;
    private int backupScheduleHour;
    private String googleClientId;
    private String githubClientId;

    @Autowired
    public ConfigResolver(ForPersistingAppConfiguration configPersistence) {
        this(configPersistence, System::getenv);
    }

    ConfigResolver(ForPersistingAppConfiguration configPersistence, Function<String, String> envLookup) {
        this.configPersistence = configPersistence;
        this.envLookup = envLookup;
        reload();
    }

    public void reload() {
        VaierConfig config = configPersistence.load().orElseGet(() -> VaierConfig.builder().build());
        this.domain = firstNonBlank(config.getDomain(), envLookup.apply("VAIER_DOMAIN"));
        this.acmeEmail = firstNonBlank(config.getAcmeEmail(), envLookup.apply("ACME_EMAIL"));
        this.smtpHost = config.getSmtpHost();
        this.smtpPort = config.getSmtpPort();
        this.smtpUsername = config.getSmtpUsername();
        this.smtpSender = config.getSmtpSender();
        this.diskMonitorThresholdPercent = config.effectiveDiskMonitorThresholdPercent();
        this.backupScheduleHour = config.effectiveBackupScheduleHour();
        this.googleClientId = envLookup.apply("VAIER_OIDC_GOOGLE_CLIENT_ID");
        this.githubClientId = envLookup.apply("VAIER_OIDC_GITHUB_CLIENT_ID");
        if (domain != null) {
            log.info("Configuration resolved for domain: {}", domain);
        }
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }

    public String getDomain() { return domain; }
    public String getAcmeEmail() { return acmeEmail; }
    public String getSmtpHost() { return smtpHost; }
    public Integer getSmtpPort() { return smtpPort; }
    public String getSmtpUsername() { return smtpUsername; }
    public String getSmtpSender() { return smtpSender; }
    public int getDiskMonitorThresholdPercent() { return diskMonitorThresholdPercent; }
    /** The hour of day (0–23) at which Vaier-owned nightly fleet-backup scheduling fires due jobs. */
    public int getBackupScheduleHour() { return backupScheduleHour; }
    /**
     * Whether social login (#305) is configured: true once either provider's OAuth client id is
     * present (#332 made each optional). When false the stack is on its first-run password.
     */
    public boolean isSocialAuthAvailable() {
        return firstNonBlank(googleClientId, githubClientId) != null;
    }
}
