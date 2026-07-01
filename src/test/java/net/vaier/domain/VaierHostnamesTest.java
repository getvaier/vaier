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
    void oauth2Host_prependsTheOauth2Subdomain() {
        assertThat(new VaierHostnames("example.com").oauth2Host())
            .isEqualTo("oauth2.example.com");
    }

    @Test
    void oauth2SignOutUrl_clearsTheDomainWideCookieAndRedirectsBack() {
        assertThat(new VaierHostnames("example.com").oauth2SignOutUrl("https://vaier.example.com/"))
            .isEqualTo("https://oauth2.example.com/oauth2/sign_out?rd=https%3A%2F%2Fvaier.example.com%2F");
    }

    // --- mandatoryDnsRecords (#229) ---

    @Test
    void mandatoryDnsRecords_containsOnlyTheVaierCnamePointingAtTheVaierServer() {
        List<DnsRecord> records = new VaierHostnames("example.com").mandatoryDnsRecords();

        assertThat(records).extracting(DnsRecord::name)
            .containsExactly("vaier.example.com");
        assertThat(records).allSatisfy(r -> {
            assertThat(r.type()).isEqualTo(DnsRecordType.CNAME);
            assertThat(r.ttl()).isEqualTo(300L);
            assertThat(r.values()).containsExactly("vaier.example.com");
        });
    }
}
