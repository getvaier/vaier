package net.vaier.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PublishingConstantsTest {

    @Test
    void isMandatory_trueForTheVaierServerFqdn() {
        assertThat(PublishingConstants.isMandatory("vaier.example.com", "example.com")).isTrue();
    }

    @Test
    void isMandatory_trueForTheAutheliaLoginFqdn() {
        assertThat(PublishingConstants.isMandatory("login.example.com", "example.com")).isTrue();
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
