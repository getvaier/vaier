package net.vaier.application.service;

import net.vaier.config.ServiceNames;
import net.vaier.domain.VaierConfig;
import net.vaier.domain.port.ForConfiguringSmtpNotifier;
import net.vaier.domain.port.ForPersistingAppConfiguration;
import net.vaier.domain.port.ForReadingStoredSmtpPassword;
import net.vaier.domain.port.ForRestartingContainers;
import net.vaier.domain.port.ForVerifyingSmtpCredentials;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UpdateSmtpSettingsServiceTest {

    @Mock ForPersistingAppConfiguration configPersistence;
    @Mock ForConfiguringSmtpNotifier smtpNotifierConfig;
    @Mock ForRestartingContainers containerRestarter;
    @Mock ForVerifyingSmtpCredentials smtpVerifier;
    @Mock ForReadingStoredSmtpPassword storedPasswordReader;

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

    @Test
    void updateSmtpSettings_restartsAutheliaAfterConfigRegeneration() {
        when(configPersistence.load()).thenReturn(Optional.of(existingConfig()));

        service.updateSmtpSettings("smtp.example.com", 587, "user@example.com", "pass", "noreply@example.com");

        InOrder order = inOrder(smtpNotifierConfig, containerRestarter);
        order.verify(smtpNotifierConfig).updateSmtpConfig("smtp.example.com", 587, "user@example.com", "pass", "noreply@example.com");
        order.verify(containerRestarter).restartContainer(ServiceNames.AUTHELIA);
    }

    @Test
    void updateSmtpSettings_verifiesCredentialsBeforeAnythingElse() {
        when(configPersistence.load()).thenReturn(Optional.of(existingConfig()));

        service.updateSmtpSettings("smtp.example.com", 587, "user@example.com", "pass", "noreply@example.com");

        InOrder order = inOrder(smtpVerifier, configPersistence, smtpNotifierConfig, containerRestarter);
        order.verify(smtpVerifier).verify("smtp.example.com", 587, "user@example.com", "pass");
        order.verify(configPersistence).save(org.mockito.ArgumentMatchers.any(VaierConfig.class));
        order.verify(smtpNotifierConfig).updateSmtpConfig("smtp.example.com", 587, "user@example.com", "pass", "noreply@example.com");
        order.verify(containerRestarter).restartContainer(ServiceNames.AUTHELIA);
    }

    @Test
    void updateSmtpSettings_doesNotTouchConfigOrAutheliaWhenVerificationFails() {
        doThrow(new RuntimeException("SMTP AUTH failed"))
            .when(smtpVerifier).verify("smtp.example.com", 587, "user@example.com", "badpass");

        assertThatThrownBy(() -> service.updateSmtpSettings(
            "smtp.example.com", 587, "user@example.com", "badpass", "noreply@example.com"))
            .hasMessageContaining("SMTP AUTH failed");

        verify(configPersistence, never()).save(org.mockito.ArgumentMatchers.any());
        verify(smtpNotifierConfig, never()).updateSmtpConfig(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString());
        verify(containerRestarter, never()).restartContainer(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void updateSmtpSettings_fallsBackToStoredPasswordWhenBlank() {
        when(configPersistence.load()).thenReturn(Optional.of(existingConfig()));
        when(storedPasswordReader.readStoredPassword()).thenReturn(Optional.of("storedPass"));

        service.updateSmtpSettings("smtp.example.com", 587, "user@example.com", "", "noreply@example.com");

        verify(smtpVerifier).verify("smtp.example.com", 587, "user@example.com", "storedPass");
        verify(smtpNotifierConfig).updateSmtpConfig("smtp.example.com", 587, "user@example.com", "storedPass", "noreply@example.com");
    }

    @Test
    void updateSmtpSettings_rejectsWhenPasswordBlankAndNoStoredPassword() {
        when(storedPasswordReader.readStoredPassword()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateSmtpSettings(
            "smtp.example.com", 587, "user@example.com", "", "noreply@example.com"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("password");

        verify(smtpVerifier, never()).verify(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString());
        verify(configPersistence, never()).save(org.mockito.ArgumentMatchers.any());
    }
}
