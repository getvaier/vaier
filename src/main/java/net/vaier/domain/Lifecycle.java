package net.vaier.domain;

import java.util.List;
import net.vaier.domain.DnsRecord.DnsRecordType;
import net.vaier.domain.port.ForInitialisingUserService;
import net.vaier.domain.port.ForPersistingDnsRecords;
import net.vaier.domain.port.ForPersistingUsers;
import net.vaier.domain.port.ForRestartingContainers;
import java.util.Optional;

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

        Optional<DnsRecord> vaierDnsRecord = forPersistingDnsRecords.getDnsRecords(dnsZone).stream()
            .filter(record -> record.name().equals("vaier." + VAIER_DOMAIN))
            .findFirst();

        Optional<DnsRecord> authRecord = forPersistingDnsRecords.getDnsRecords(dnsZone).stream()
            .filter(record -> record.name().equals("auth." + VAIER_DOMAIN))
            .findFirst();

        if(vaierDnsRecord.isEmpty()) {
            forPersistingDnsRecords.addDnsRecord(new DnsRecord("vaier." + VAIER_DOMAIN, DnsRecordType.CNAME, 300L, List.of(
                VAIER_DOMAIN)), dnsZone);
        }

        if(authRecord.isEmpty()) {
            forPersistingDnsRecords.addDnsRecord(new DnsRecord("auth." + VAIER_DOMAIN, DnsRecordType.CNAME, 300L, List.of(
                VAIER_DOMAIN)), dnsZone);
        }
    }
}
