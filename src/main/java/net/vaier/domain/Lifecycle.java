package net.vaier.domain;

import java.security.SecureRandom;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.DnsRecord.DnsRecordType;
import net.vaier.domain.port.ForInitialisingUserService;
import net.vaier.domain.port.ForPersistingDnsRecords;
import net.vaier.domain.port.ForPersistingUsers;
import net.vaier.domain.port.ForPublishingAutheliaAssets;
import net.vaier.domain.port.ForResolvingPublicHost;
import net.vaier.domain.port.ForResolvingPublicHost.PublicHost;
import net.vaier.domain.port.ForRestartingContainers;
import net.vaier.domain.port.ForWritingBootstrapCredentials;

@Slf4j
public class Lifecycle {

    private final ForInitialisingUserService forInitialisingUserService;
    private final ForPersistingUsers forPersistingUsers;
    private final ForPersistingDnsRecords forPersistingDnsRecords;
    private final ForRestartingContainers containerRestarter;
    private final ForWritingBootstrapCredentials bootstrapCredentialsWriter;
    private final ForPublishingAutheliaAssets autheliaAssetsPublisher;
    private final ForResolvingPublicHost publicHostResolver;
    private final String vaierDomain;
    private final String defaultAdminUsername;
    private final String autheliaContainerName;
    private final String vaierSubdomain;
    private final String authSubdomain;

    public Lifecycle(
        ForInitialisingUserService forInitialisingUserService,
        ForPersistingUsers forPersistingUsers,
        ForPersistingDnsRecords forPersistingDnsRecords,
        ForRestartingContainers containerRestarter,
        ForWritingBootstrapCredentials bootstrapCredentialsWriter,
        ForPublishingAutheliaAssets autheliaAssetsPublisher,
        ForResolvingPublicHost publicHostResolver,
        String vaierDomain,
        String defaultAdminUsername,
        String autheliaContainerName,
        String vaierSubdomain,
        String authSubdomain
    ) {
        this.forInitialisingUserService = forInitialisingUserService;
        this.forPersistingUsers = forPersistingUsers;
        this.forPersistingDnsRecords = forPersistingDnsRecords;
        this.containerRestarter = containerRestarter;
        this.bootstrapCredentialsWriter = bootstrapCredentialsWriter;
        this.autheliaAssetsPublisher = autheliaAssetsPublisher;
        this.publicHostResolver = publicHostResolver;
        this.vaierDomain = vaierDomain;
        this.defaultAdminUsername = defaultAdminUsername;
        this.autheliaContainerName = autheliaContainerName;
        this.vaierSubdomain = vaierSubdomain;
        this.authSubdomain = authSubdomain;
    }

    public void start() {
        initDns();
        initUsers();
    }

    void initUsers() {
        autheliaAssetsPublisher.publishAssets();
        boolean configChanged = forInitialisingUserService.initialiseConfiguration();

        boolean adminCreated = false;
        if (!forPersistingUsers.isDatabaseInitialised()) {
            String password = generateRandomPassword();
            forPersistingUsers.addUser(defaultAdminUsername, password, "", "Admin", java.util.List.of("admins"));
            String passwordFilePath = bootstrapCredentialsWriter
                .writeBootstrapPassword(defaultAdminUsername, password);
            log.info("==========================================================");
            log.info("ADMIN USER CREATED");
            log.info("Username: {}", defaultAdminUsername);
            log.info("Bootstrap password written to: {}", passwordFilePath);
            log.info("Read the password, log in, change it, then delete the file.");
            log.info("==========================================================");
            adminCreated = true;
        }

        if (configChanged || adminCreated) {
            containerRestarter.restartContainer(autheliaContainerName);
        }
    }

    private String generateRandomPassword() {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        SecureRandom random = new SecureRandom();
        StringBuilder password = new StringBuilder(20);
        for (int i = 0; i < 20; i++) {
            password.append(chars.charAt(random.nextInt(chars.length())));
        }
        return password.toString();
    }

    private void initDns() {
        if(vaierDomain == null || vaierDomain.isBlank()) {
            throw new RuntimeException("VAIER_DOMAIN is not set");
        }
        DnsZone dnsZone = forPersistingDnsRecords.getDnsZones().stream()
            .filter(zone -> zone.name().equals(vaierDomain))
            .findFirst()
            .orElseThrow(() -> new RuntimeException("DNS zone not found for " + vaierDomain));

        log.info("DNS zone found: " + dnsZone.name());

        String vaierHost = vaierSubdomain + "." + vaierDomain;
        String authHost = authSubdomain + "." + vaierDomain;

        List<DnsRecord> records = forPersistingDnsRecords.getDnsRecords(dnsZone);

        Optional<DnsRecord> vaierRecord = records.stream()
            .filter(record -> record.name().equals(vaierHost))
            .findFirst();

        if (vaierRecord.isPresent()) {
            log.info("DNS record found: " + vaierRecord.get().name());
        } else if (!ensureVaierRecord(vaierHost, dnsZone)) {
            return;
        }

        Optional<DnsRecord> authRecord = records.stream()
            .filter(record -> record.name().equals(authHost))
            .findFirst();

        if(authRecord.isEmpty()) {
            forPersistingDnsRecords.addDnsRecord(new DnsRecord(authHost, DnsRecordType.CNAME, 300L, List.of(
                vaierHost)), dnsZone);
            log.info("Added " + authHost + " CNAME record");
        } else {
            log.info("Found " + authHost + " CNAME record");
        }
    }

    private boolean ensureVaierRecord(String vaierHost, DnsZone dnsZone) {
        Optional<PublicHost> resolved = publicHostResolver.resolve();
        if (resolved.isEmpty()) {
            log.warn("==========================================================");
            log.warn("DNS record missing for {} and this server's public address", vaierHost);
            log.warn("could not be determined automatically.");
            log.warn("Create the record manually in Route53, or set");
            log.warn("VAIER_PUBLIC_HOST (CNAME target) or VAIER_PUBLIC_IP (A target)");
            log.warn("in .env and restart the stack.");
            log.warn("==========================================================");
            return false;
        }
        PublicHost publicHost = resolved.get();
        forPersistingDnsRecords.addDnsRecord(
            new DnsRecord(vaierHost, publicHost.type(), 300L, List.of(publicHost.value())),
            dnsZone
        );
        log.info("Added {} {} record → {}", vaierHost, publicHost.type(), publicHost.value());
        return true;
    }
}
