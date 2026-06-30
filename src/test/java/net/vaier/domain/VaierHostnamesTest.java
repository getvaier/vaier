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

    @Test
    void autheliaLogoutUrl_usesTheLoginPortal() {
        assertThat(new VaierHostnames("example.com").autheliaLogoutUrl("https://vaier.example.com/"))
            .isEqualTo("https://login.example.com/logout?rd=https://vaier.example.com/");
    }

    @Test
    void logoutUrl_isAuthModeAware() {
        VaierHostnames hosts = new VaierHostnames("example.com");
        assertThat(hosts.logoutUrl(AuthMode.AUTHELIA, "https://vaier.example.com/"))
            .isEqualTo("https://login.example.com/logout?rd=https://vaier.example.com/");
        assertThat(hosts.logoutUrl(AuthMode.SOCIAL, "https://vaier.example.com/"))
            .isEqualTo("https://oauth2.example.com/oauth2/sign_out?rd=https%3A%2F%2Fvaier.example.com%2F");
        assertThat(hosts.logoutUrl(AuthMode.NONE, "https://vaier.example.com/")).isNull();
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
