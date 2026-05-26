package net.vaier.domain;

import net.vaier.domain.DnsRecord.DnsRecordType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VaierHostnamesTest {

    @Test
    void vaierServerFqdn_prependsTheVaierSubdomain() {
        assertThat(new VaierHostnames("example.com").vaierServerFqdn())
            .isEqualTo("vaier.example.com");
    }

    @Test
    void autheliaHost_prependsTheLoginSubdomain() {
        assertThat(new VaierHostnames("example.com").autheliaHost())
            .isEqualTo("login.example.com");
    }

    // --- mandatoryDnsRecords (#229) ---

    @Test
    void mandatoryDnsRecords_containsVaierAndLoginCnamesPointingAtTheVaierServer() {
        List<DnsRecord> records = new VaierHostnames("example.com").mandatoryDnsRecords();

        assertThat(records).extracting(DnsRecord::name)
            .containsExactlyInAnyOrder("vaier.example.com", "login.example.com");
        assertThat(records).allSatisfy(r -> {
            assertThat(r.type()).isEqualTo(DnsRecordType.CNAME);
            assertThat(r.ttl()).isEqualTo(300L);
            assertThat(r.values()).containsExactly("vaier.example.com");
        });
    }
}
