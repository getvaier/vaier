package net.vaier.config;

import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.VaierConfig;
import net.vaier.domain.port.ForPersistingAppConfiguration;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class ConfigResolver {

    private final ForPersistingAppConfiguration configPersistence;
    private String domain;
    private String awsKey;
    private String awsSecret;
    private String acmeEmail;
    private String smtpHost;
    private Integer smtpPort;
    private String smtpUsername;
    private String smtpSender;

    public ConfigResolver(ForPersistingAppConfiguration configPersistence) {
        this.configPersistence = configPersistence;
        reload();
    }

    public void reload() {
        configPersistence.load().ifPresentOrElse(
            config -> {
                this.domain = config.getDomain();
                this.awsKey = config.getAwsKey();
                this.awsSecret = config.getAwsSecret();
                this.acmeEmail = config.getAcmeEmail();
                this.smtpHost = config.getSmtpHost();
                this.smtpPort = config.getSmtpPort();
                this.smtpUsername = config.getSmtpUsername();
                this.smtpSender = config.getSmtpSender();
                log.info("Configuration loaded from file for domain: {}", domain);
            },
            () -> {
                this.domain = System.getenv("VAIER_DOMAIN");
                this.awsKey = System.getenv("VAIER_AWS_KEY");
                this.awsSecret = System.getenv("VAIER_AWS_SECRET");
                this.acmeEmail = System.getenv("ACME_EMAIL");
                if (domain != null) {
                    log.info("Configuration loaded from environment variables for domain: {}", domain);
                }
            }
        );
    }

    public String getDomain() { return domain; }
    public String getAwsKey() { return awsKey; }
    public String getAwsSecret() { return awsSecret; }
    public String getAcmeEmail() { return acmeEmail; }
    public String getSmtpHost() { return smtpHost; }
    public Integer getSmtpPort() { return smtpPort; }
    public String getSmtpUsername() { return smtpUsername; }
    public String getSmtpSender() { return smtpSender; }
}
