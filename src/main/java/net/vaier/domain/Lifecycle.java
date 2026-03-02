package net.vaier.domain;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.DnsRecord.DnsRecordType;
import net.vaier.domain.port.ForInitialisingUserService;
import net.vaier.domain.port.ForPersistingDnsRecords;
import net.vaier.domain.port.ForPersistingUsers;
import net.vaier.domain.port.ForRestartingContainers;
import java.util.Optional;

@Slf4j
public class Lifecycle {

    private final ForInitialisingUserService forInitialisingUserService;
    private final ForPersistingUsers forPersistingUsers;
    private final ForPersistingDnsRecords forPersistingDnsRecords;
    private final ForRestartingContainers containerRestarter;

    private static final String VAIER_DOMAIN = System.getenv().get("VAIER_DOMAIN");

    public Lifecycle(
        ForInitialisingUserService forInitialisingUserService,
        ForPersistingUsers forPersistingUsers,
        ForPersistingDnsRecords forPersistingDnsRecords,
        ForRestartingContainers containerRestarter
    ) {
        this.forInitialisingUserService = forInitialisingUserService;
        this.forPersistingUsers = forPersistingUsers;
        this.forPersistingDnsRecords = forPersistingDnsRecords;
        this.containerRestarter = containerRestarter;
    }

    public void start() {
        initDns();
        initUsers();
    }

    private void initUsers() {
        forInitialisingUserService.initialiseConfiguration();

        Optional<User> admin = forPersistingUsers.getUsers().stream()
            .filter(user -> user.getName().equals("admin"))
            .findFirst();
        if(admin.isEmpty()) {
            forPersistingUsers.addUser("admin", "admin", "", "Admin");
        }

        containerRestarter.restartContainer("authelia");
    }

    private void initDns() {
        if(VAIER_DOMAIN == null) {
            throw new RuntimeException("VAIER_DOMAIN is not set");
        }
        DnsZone dnsZone = forPersistingDnsRecords.getDnsZones().stream()
            .filter(zone -> zone.name().equals(VAIER_DOMAIN))
            .findFirst()
            .orElseThrow(() -> new RuntimeException("DNS zone not found for " + VAIER_DOMAIN));

        log.info("DNS zone found: " + dnsZone.name());

        String vaierHost = "vaier." + VAIER_DOMAIN;
        String authHost = "auth." + VAIER_DOMAIN;

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
