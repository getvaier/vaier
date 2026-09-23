package net.vaier.config;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.IdentityProvider;
import net.vaier.domain.VaierConfig;
import net.vaier.domain.port.ForPersistingAppConfiguration;
import net.vaier.domain.port.ForPersistingSignInSettings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class ConfigResolver {

    private final ForPersistingAppConfiguration configPersistence;
    private final ForPersistingSignInSettings signInSettings;
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
    public ConfigResolver(ForPersistingAppConfiguration configPersistence, ForPersistingSignInSettings signInSettings) {
        this(configPersistence, signInSettings, System::getenv);
    }

    ConfigResolver(ForPersistingAppConfiguration configPersistence, ForPersistingSignInSettings signInSettings,
                   Function<String, String> envLookup) {
        this.configPersistence = configPersistence;
        this.signInSettings = signInSettings;
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
     * Whether social login (#305) is configured: true once either provider's OAuth client id is present
     * in .env (#332 made each optional), or a provider was added from Settings (#264).
     */
    public boolean isSocialAuthAvailable() {
        return firstNonBlank(googleClientId, githubClientId) != null || signInSettings.read().hasAnyProvider();
    }

    /**
     * The providers whose client id and secret are both set in .env, by client id. Compose hands Vaier only
     * whether the secret is set ({@code *_CLIENT_SECRET_PRESENT}), never the secret itself.
     */
    public Map<IdentityProvider, String> providersSetInEnvironment() {
        Map<IdentityProvider, String> set = new EnumMap<>(IdentityProvider.class);
        for (IdentityProvider provider : IdentityProvider.values()) {
            String prefix = "VAIER_OIDC_" + provider.name().toUpperCase(Locale.ROOT) + "_CLIENT_";
            String clientId = firstNonBlank(envLookup.apply(prefix + "ID"), null);
            if (clientId != null && firstNonBlank(envLookup.apply(prefix + "SECRET_PRESENT"), null) != null) {
                set.put(provider, clientId);
            }
        }
        return set;
    }

    /** Whether the first-run password still opens Dex, by the rule dex-init renders with. */
    public boolean isFirstRunDoorOpen() {
        return signInSettings.read().isFirstRunDoorOpen(providersSetInEnvironment().keySet());
    }
}
