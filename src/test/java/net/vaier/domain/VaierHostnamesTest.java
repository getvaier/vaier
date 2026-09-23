package net.vaier.domain;

import org.junit.jupiter.api.Test;

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
    void dexHost_prependsTheDexSubdomain_andEveryProviderCallsBackThere() {
        assertThat(new VaierHostnames("example.com").dexHost())
            .isEqualTo("dex.example.com");
        assertThat(new VaierHostnames("example.com").dexCallbackUrl())
            .isEqualTo("https://dex.example.com/callback");
    }

    @Test
    void configuredVaierServerFqdn_isTheServerNameOnceThereIsADomainToBuildItFrom() {
        assertThat(new VaierHostnames("example.com").configuredVaierServerFqdn())
            .contains("vaier.example.com");
    }

    @Test
    void configuredVaierServerFqdn_isNothingAtAllBeforeADomainIsConfigured() {
        // A fresh install has no domain yet, and "vaier.null" is not a host. Whether this deployment can
        // name itself is the one judgement here, so it is made once, where the name is made.
        assertThat(new VaierHostnames(null).configuredVaierServerFqdn()).isEmpty();
        assertThat(new VaierHostnames("  ").configuredVaierServerFqdn()).isEmpty();
    }

    @Test
    void oauth2SignOutUrl_clearsTheDomainWideCookieAndRedirectsBack() {
        assertThat(new VaierHostnames("example.com").oauth2SignOutUrl("https://vaier.example.com/"))
            .isEqualTo("https://oauth2.example.com/oauth2/sign_out?rd=https%3A%2F%2Fvaier.example.com%2F");
    }
}
