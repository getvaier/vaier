package net.vaier.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PublishingConstantsTest {

    @Test
    void isMandatory_trueForTheVaierServerFqdn() {
        assertThat(PublishingConstants.isMandatory("vaier.example.com", "example.com")).isTrue();
    }

    @Test
    void isMandatory_trueForTheOauth2ProxyFqdn() {
        // oauth2.<domain> is Vaier infrastructure (the sign-in gateway), never an ordinary service.
        assertThat(PublishingConstants.isMandatory("oauth2.example.com", "example.com")).isTrue();
    }

    @Test
    void isMandatory_trueForTheDexBrokerFqdn() {
        // dex.<domain> is Vaier infrastructure (the OIDC broker), never an ordinary service.
        assertThat(PublishingConstants.isMandatory("dex.example.com", "example.com")).isTrue();
    }

    @Test
    void isMandatory_falseForTheDecommissionedAutheliaLoginFqdn() {
        // Authelia is decommissioned; login.<domain> is no longer Vaier infrastructure.
        assertThat(PublishingConstants.isMandatory("login.example.com", "example.com")).isFalse();
    }

    @Test
    void isMandatory_falseForAnOrdinaryPublishedService() {
        assertThat(PublishingConstants.isMandatory("grafana.example.com", "example.com")).isFalse();
    }

    @Test
    void isMandatory_falseWhenSubdomainMerelyPrefixesAMandatoryName() {
        // "vaier-test.example.com" must not be mistaken for the mandatory "vaier.example.com".
        assertThat(PublishingConstants.isMandatory("vaier-test.example.com", "example.com")).isFalse();
    }

    @Test
    void isMandatory_falseForAMandatorySubdomainUnderTheWrongBaseDomain() {
        assertThat(PublishingConstants.isMandatory("vaier.other.com", "example.com")).isFalse();
    }
}
