package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VaierConfigTest {

    private static VaierConfig fullConfig() {
        return VaierConfig.builder()
            .domain("vaier.net")
            .awsKey("AKIAEXAMPLEKEY12345")
            .awsSecret("topsecret")
            .acmeEmail("ops@vaier.net")
            .smtpHost("smtp.example.com")
            .smtpPort(587)
            .smtpUsername("mailer")
            .smtpSender("noreply@vaier.net")
            .build();
    }

    @Test
    void maskedAwsKey_masksAllButLastFourCharacters() {
        assertThat(fullConfig().maskedAwsKey()).isEqualTo("****2345");
    }

    @Test
    void maskedAwsKey_returnsShortKeyUnchanged() {
        VaierConfig config = VaierConfig.builder().awsKey("ABCD").build();

        assertThat(config.maskedAwsKey()).isEqualTo("ABCD");
    }

    @Test
    void maskedAwsKey_returnsNullWhenKeyAbsent() {
        assertThat(VaierConfig.builder().build().maskedAwsKey()).isNull();
    }

    @Test
    void withAwsCredentials_replacesCredentialsAndCarriesEveryOtherFieldOver() {
        VaierConfig updated = fullConfig().withAwsCredentials("AKIANEWKEY999", "newsecret");

        assertThat(updated.getAwsKey()).isEqualTo("AKIANEWKEY999");
        assertThat(updated.getAwsSecret()).isEqualTo("newsecret");
        assertThat(updated.getDomain()).isEqualTo("vaier.net");
        assertThat(updated.getAcmeEmail()).isEqualTo("ops@vaier.net");
        assertThat(updated.getSmtpHost()).isEqualTo("smtp.example.com");
        assertThat(updated.getSmtpPort()).isEqualTo(587);
        assertThat(updated.getSmtpUsername()).isEqualTo("mailer");
        assertThat(updated.getSmtpSender()).isEqualTo("noreply@vaier.net");
    }

    @Test
    void withSmtpSettings_replacesSmtpFieldsAndCarriesEveryOtherFieldOver() {
        VaierConfig updated = fullConfig().withSmtpSettings("smtp.new.com", 2525, "newuser", "from@vaier.net");

        assertThat(updated.getSmtpHost()).isEqualTo("smtp.new.com");
        assertThat(updated.getSmtpPort()).isEqualTo(2525);
        assertThat(updated.getSmtpUsername()).isEqualTo("newuser");
        assertThat(updated.getSmtpSender()).isEqualTo("from@vaier.net");
        assertThat(updated.getDomain()).isEqualTo("vaier.net");
        assertThat(updated.getAwsKey()).isEqualTo("AKIAEXAMPLEKEY12345");
        assertThat(updated.getAwsSecret()).isEqualTo("topsecret");
        assertThat(updated.getAcmeEmail()).isEqualTo("ops@vaier.net");
    }

    @Test
    void resolveSmtpPassword_prefersTheProvidedPassword() {
        assertThat(VaierConfig.resolveSmtpPassword("fresh", Optional.of("stored")))
            .isEqualTo("fresh");
    }

    @Test
    void resolveSmtpPassword_fallsBackToStoredWhenProvidedIsBlank() {
        assertThat(VaierConfig.resolveSmtpPassword("  ", Optional.of("stored")))
            .isEqualTo("stored");
        assertThat(VaierConfig.resolveSmtpPassword(null, Optional.of("stored")))
            .isEqualTo("stored");
    }

    @Test
    void resolveSmtpPassword_throwsWhenNeitherProvidedNorStored() {
        assertThatThrownBy(() -> VaierConfig.resolveSmtpPassword(null, Optional.empty()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("SMTP password");
    }

    @Test
    void resolveSmtpPassword_throwsWhenStoredIsBlank() {
        assertThatThrownBy(() -> VaierConfig.resolveSmtpPassword("", Optional.of("   ")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("SMTP password");
    }
}
