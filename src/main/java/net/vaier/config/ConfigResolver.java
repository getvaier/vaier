package net.vaier.config;

import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.DnsProvider;
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
    private String awsKey;
    private String awsSecret;
    private String acmeEmail;
    private String smtpHost;
    private Integer smtpPort;
    private String smtpUsername;
    private String smtpSender;
    private int diskMonitorThresholdPercent;
    private int backupScheduleHour;
    private String googleClientId;

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
        this.awsKey = firstNonBlank(config.getAwsKey(), envLookup.apply("VAIER_AWS_KEY"));
        this.awsSecret = firstNonBlank(config.getAwsSecret(), envLookup.apply("VAIER_AWS_SECRET"));
        this.acmeEmail = firstNonBlank(config.getAcmeEmail(), envLookup.apply("ACME_EMAIL"));
        this.smtpHost = config.getSmtpHost();
        this.smtpPort = config.getSmtpPort();
        this.smtpUsername = config.getSmtpUsername();
        this.smtpSender = config.getSmtpSender();
        this.diskMonitorThresholdPercent = config.effectiveDiskMonitorThresholdPercent();
        this.backupScheduleHour = config.effectiveBackupScheduleHour();
        this.googleClientId = envLookup.apply("VAIER_OIDC_GOOGLE_CLIENT_ID");
        if (domain != null) {
            log.info("Configuration resolved for domain: {} (DNS provider: {})", domain, getDnsProvider());
        }
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }

    public String getDomain() { return domain; }
    public String getAwsKey() { return awsKey; }
    public String getAwsSecret() { return awsSecret; }
    public String getAcmeEmail() { return acmeEmail; }
    public String getSmtpHost() { return smtpHost; }
    public Integer getSmtpPort() { return smtpPort; }
    public String getSmtpUsername() { return smtpUsername; }
    public String getSmtpSender() { return smtpSender; }
    public int getDiskMonitorThresholdPercent() { return diskMonitorThresholdPercent; }
    /** The hour of day (0–23) at which Vaier-owned nightly fleet-backup scheduling fires due jobs. */
    public int getBackupScheduleHour() { return backupScheduleHour; }
    /**
     * Whether social login (#305) is configured: true once a Google OAuth client id is present. When
     * false, the {@code social} auth mode isn't offered in the UI and oauth2-proxy need not run.
     */
    public boolean isSocialAuthAvailable() {
        return googleClientId != null && !googleClientId.isBlank();
    }

    public DnsProvider getDnsProvider() {
        boolean hasAwsCredentials = awsKey != null && !awsKey.isBlank()
            && awsSecret != null && !awsSecret.isBlank();
        return hasAwsCredentials ? DnsProvider.ROUTE53 : DnsProvider.MANUAL;
    }
}
