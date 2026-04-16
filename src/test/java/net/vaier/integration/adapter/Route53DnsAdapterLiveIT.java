package net.vaier.integration.adapter;

import net.vaier.adapter.driven.Route53DnsAdapter;
import net.vaier.domain.DnsRecord;
import net.vaier.domain.DnsRecord.DnsRecordType;
import net.vaier.domain.DnsZone;
import org.junit.jupiter.api.*;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.route53.Route53Client;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Live integration tests for Route53DnsAdapter against the real vaier.net hosted zone.
 *
 * Requires VAIER_AWS_KEY and VAIER_AWS_SECRET environment variables.
 * Tagged "integration-live" so they are excluded from mvn test.
 * Run with: mvn verify -Dgroups=integration-live
 *
 * All test records use the prefix "it-test-{timestamp}" and are cleaned up in @AfterEach.
 */
@Tag("integration-live")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Route53DnsAdapterLiveIT {

    private static final String TEST_ZONE = "vaier.net";
    private static final String TEST_PREFIX = "it-test-" + System.currentTimeMillis();

    static Route53Client route53Client;
    static Route53DnsAdapter adapter;
    static DnsZone zone;

    @BeforeAll
    static void setUp() {
        String awsKey = System.getenv("VAIER_AWS_KEY");
        String awsSecret = System.getenv("VAIER_AWS_SECRET");
        assumeTrue(awsKey != null && !awsKey.isBlank(),
                "Skipping live tests: VAIER_AWS_KEY not set");
        assumeTrue(awsSecret != null && !awsSecret.isBlank(),
                "Skipping live tests: VAIER_AWS_SECRET not set");

        route53Client = Route53Client.builder()
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(awsKey, awsSecret)))
                .region(Region.AWS_GLOBAL)
                .build();

        adapter = new Route53DnsAdapter(route53Client);
        zone = new DnsZone(TEST_ZONE);
    }

    @AfterAll
    static void tearDown() {
        if (route53Client != null) {
            route53Client.close();
        }
    }

    @AfterEach
    void cleanupTestRecords() {
        if (adapter == null) return;
        try {
            adapter.getDnsRecords(zone).stream()
                   .filter(r -> r.name().startsWith(TEST_PREFIX))
                   .forEach(r -> {
                       try {
                           adapter.deleteDnsRecord(r.name(), r.type(), zone);
                       } catch (Exception e) {
                           // best effort cleanup
                       }
                   });
        } catch (Exception e) {
            // best effort cleanup
        }
    }

    @Test
    @Order(1)
    void getDnsZones_includesVaierNet() {
        List<DnsZone> zones = adapter.getDnsZones();

        assertThat(zones).extracting(DnsZone::name).contains(TEST_ZONE);
    }

    @Test
    @Order(2)
    void addCnameRecord_canBeListedAndDeleted() {
        String recordName = TEST_PREFIX + ".vaier.net";
        DnsRecord record = new DnsRecord(
                recordName, DnsRecordType.CNAME, 60L, List.of("target.example.com"));

        adapter.addDnsRecord(record, zone);

        List<DnsRecord> records = adapter.getDnsRecords(zone);
        assertThat(records).extracting(DnsRecord::name).contains(recordName);

        adapter.deleteDnsRecord(recordName, DnsRecordType.CNAME, zone);

        List<DnsRecord> afterDelete = adapter.getDnsRecords(zone);
        assertThat(afterDelete).extracting(DnsRecord::name).doesNotContain(recordName);
    }

    @Test
    @Order(3)
    void addDnsRecord_nonExistentZone_throwsRuntimeException() {
        DnsZone nonExistent = new DnsZone("definitely-does-not-exist-987654321.com");
        DnsRecord record = new DnsRecord(
                "test.definitely-does-not-exist-987654321.com", DnsRecordType.CNAME,
                60L, List.of("target.example.com"));

        assertThatThrownBy(() -> adapter.addDnsRecord(record, nonExistent))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("not found");
    }

    @Test
    @Order(4)
    void deleteDnsRecord_nonExistentRecord_throwsRuntimeException() {
        assertThatThrownBy(() -> adapter.deleteDnsRecord(
                TEST_PREFIX + "-nonexistent.vaier.net", DnsRecordType.CNAME, zone))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @Order(5)
    void listHostedZones_withValidCredentials_returnsZoneList() {
        String awsKey = System.getenv("VAIER_AWS_KEY");
        String awsSecret = System.getenv("VAIER_AWS_SECRET");

        List<String> zones = adapter.listHostedZones(awsKey, awsSecret);

        assertThat(zones).contains(TEST_ZONE);
    }

    @Test
    @Order(6)
    void listHostedZones_withInvalidCredentials_throwsException() {
        assertThatThrownBy(() -> adapter.listHostedZones("INVALID_KEY", "INVALID_SECRET"))
                .isInstanceOf(Exception.class);
    }
}
