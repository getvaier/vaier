package net.vaier.domain;

import java.security.SecureRandom;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.DnsRecord.DnsRecordType;
import net.vaier.domain.port.ForInitialisingUserService;
import net.vaier.domain.port.ForPersistingDnsRecords;
import net.vaier.domain.port.ForPersistingUsers;
import net.vaier.domain.port.ForRestartingContainers;
import net.vaier.config.ServiceNames;

@Slf4j
public class Lifecycle {

    private final ForInitialisingUserService forInitialisingUserService;
    private final ForPersistingUsers forPersistingUsers;
    private final ForPersistingDnsRecords forPersistingDnsRecords;
    private final ForRestartingContainers containerRestarter;
    private final String vaierDomain;

    public Lifecycle(
        ForInitialisingUserService forInitialisingUserService,
        ForPersistingUsers forPersistingUsers,
        ForPersistingDnsRecords forPersistingDnsRecords,
        ForRestartingContainers containerRestarter,
        String vaierDomain
    ) {
        this.forInitialisingUserService = forInitialisingUserService;
        this.forPersistingUsers = forPersistingUsers;
        this.forPersistingDnsRecords = forPersistingDnsRecords;
        this.containerRestarter = containerRestarter;
        this.vaierDomain = vaierDomain;
    }

    public void start() {
        initDns();
        initUsers();
    }

    void initUsers() {
        boolean configChanged = forInitialisingUserService.initialiseConfiguration();

        boolean adminCreated = false;
        if (!forPersistingUsers.isDatabaseInitialised()) {
            String password = generateRandomPassword();
            forPersistingUsers.addUser(ServiceNames.DEFAULT_ADMIN_USERNAME, password, "", "Admin");
            log.info("==========================================================");
            log.info("ADMIN USER CREATED");
            log.info("Username: {}", ServiceNames.DEFAULT_ADMIN_USERNAME);
            log.info("Password: {}", password);
            log.info("PLEASE CHANGE THIS PASSWORD IMMEDIATELY");
            log.info("==========================================================");
            adminCreated = true;
        }

        if (configChanged || adminCreated) {
            containerRestarter.restartContainer(ServiceNames.AUTHELIA);
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

        String vaierHost = ServiceNames.VAIER + "." + vaierDomain;
        String authHost = ServiceNames.AUTH + "." + vaierDomain;

        DnsRecord dnsRecord = forPersistingDnsRecords.getDnsRecords(dnsZone).stream()
            .filter(record -> record.name().equals(vaierHost))
            .findFirst()
            .orElseThrow(() -> new RuntimeException("DNS record not found for " + vaierHost));

        log.info("DNS record found: " + dnsRecord.name());

        Optional<DnsRecord> authRecord = forPersistingDnsRecords.getDnsRecords(dnsZone).stream()
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
}
