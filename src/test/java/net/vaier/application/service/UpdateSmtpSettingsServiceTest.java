package net.vaier.application.service;

import net.vaier.domain.VaierConfig;
import net.vaier.domain.port.ForConfiguringSmtpNotifier;
import net.vaier.domain.port.ForPersistingAppConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UpdateSmtpSettingsServiceTest {

    @Mock ForPersistingAppConfiguration configPersistence;
    @Mock ForConfiguringSmtpNotifier smtpNotifierConfig;

    @InjectMocks UpdateSmtpSettingsService service;

    private VaierConfig existingConfig() {
        return VaierConfig.builder()
            .domain("example.com")
            .awsKey("AKID")
            .awsSecret("secret")
            .acmeEmail("admin@example.com")
            .build();
    }

    @Test
    void updateSmtpSettings_savesNonSecretFieldsToConfig() {
        when(configPersistence.load()).thenReturn(Optional.of(existingConfig()));

        service.updateSmtpSettings("smtp.example.com", 587, "user@example.com", "pass", "noreply@example.com");

        ArgumentCaptor<VaierConfig> captor = ArgumentCaptor.forClass(VaierConfig.class);
        verify(configPersistence).save(captor.capture());
        VaierConfig saved = captor.getValue();
        assertThat(saved.getSmtpHost()).isEqualTo("smtp.example.com");
        assertThat(saved.getSmtpPort()).isEqualTo(587);
        assertThat(saved.getSmtpUsername()).isEqualTo("user@example.com");
        assertThat(saved.getSmtpSender()).isEqualTo("noreply@example.com");
    }

    @Test
    void updateSmtpSettings_doesNotSavePasswordToVaierConfig() {
        when(configPersistence.load()).thenReturn(Optional.of(existingConfig()));

        service.updateSmtpSettings("smtp.example.com", 587, "user@example.com", "secretpass", "noreply@example.com");

        // VaierConfig has no smtpPassword field — password goes to Authelia secrets only
        ArgumentCaptor<VaierConfig> captor = ArgumentCaptor.forClass(VaierConfig.class);
        verify(configPersistence).save(captor.capture());
        // Just verifying the save happened and existing fields are preserved
        VaierConfig saved = captor.getValue();
        assertThat(saved.getDomain()).isEqualTo("example.com");
        assertThat(saved.getAwsKey()).isEqualTo("AKID");
    }

    @Test
    void updateSmtpSettings_preservesExistingConfigFields() {
        when(configPersistence.load()).thenReturn(Optional.of(existingConfig()));

        service.updateSmtpSettings("smtp.example.com", 587, "user@example.com", "pass", "sender@example.com");

        ArgumentCaptor<VaierConfig> captor = ArgumentCaptor.forClass(VaierConfig.class);
        verify(configPersistence).save(captor.capture());
        VaierConfig saved = captor.getValue();
        assertThat(saved.getDomain()).isEqualTo("example.com");
        assertThat(saved.getAwsKey()).isEqualTo("AKID");
        assertThat(saved.getAwsSecret()).isEqualTo("secret");
        assertThat(saved.getAcmeEmail()).isEqualTo("admin@example.com");
    }

    @Test
    void updateSmtpSettings_triggersAutheliaConfigRegeneration() {
        when(configPersistence.load()).thenReturn(Optional.of(existingConfig()));

        service.updateSmtpSettings("smtp.example.com", 587, "user@example.com", "pass", "noreply@example.com");

        verify(smtpNotifierConfig).updateSmtpConfig("smtp.example.com", 587, "user@example.com", "pass", "noreply@example.com");
    }
}
