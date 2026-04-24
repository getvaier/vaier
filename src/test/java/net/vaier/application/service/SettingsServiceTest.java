package net.vaier.application.service;

import net.vaier.application.GetAppSettingsUseCase.AppSettingsResult;
import net.vaier.config.ServiceNames;
import net.vaier.domain.VaierConfig;
import net.vaier.domain.port.ForConfiguringSmtpNotifier;
import net.vaier.domain.port.ForPersistingAppConfiguration;
import net.vaier.domain.port.ForReadingStoredSmtpPassword;
import net.vaier.domain.port.ForRestartingContainers;
import net.vaier.domain.port.ForSendingTestEmail;
import net.vaier.domain.port.ForValidatingAwsCredentials;
import net.vaier.domain.port.ForVerifyingSmtpCredentials;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SettingsServiceTest {

    @Mock ForPersistingAppConfiguration configPersistence;
    @Mock ForValidatingAwsCredentials forValidatingAwsCredentials;
    @Mock ForConfiguringSmtpNotifier smtpNotifierConfig;
    @Mock ForRestartingContainers containerRestarter;
    @Mock ForVerifyingSmtpCredentials smtpVerifier;
    @Mock ForReadingStoredSmtpPassword storedPasswordReader;
    @Mock ForSendingTestEmail testEmailSender;

    @InjectMocks SettingsService service;

    private VaierConfig existingConfig() {
        return VaierConfig.builder()
            .domain("example.com")
            .awsKey("AKID")
            .awsSecret("secret")
            .acmeEmail("admin@example.com")
            .build();
    }

    // --- getSettings ---

    @Test
    void getSettings_returnsConfigFields() {
        VaierConfig config = VaierConfig.builder()
            .domain("example.com")
            .awsKey("AKIAIOSFODNN7EXAMPLE")
            .awsSecret("secret")
            .acmeEmail("admin@example.com")
            .smtpHost("smtp.example.com")
            .smtpPort(587)
            .smtpUsername("user@example.com")
            .smtpSender("noreply@example.com")
            .build();
        when(configPersistence.load()).thenReturn(Optional.of(config));

        AppSettingsResult result = service.getSettings();

        assertThat(result.domain()).isEqualTo("example.com");
        assertThat(result.acmeEmail()).isEqualTo("admin@example.com");
        assertThat(result.smtpHost()).isEqualTo("smtp.example.com");
        assertThat(result.smtpPort()).isEqualTo(587);
        assertThat(result.smtpUsername()).isEqualTo("user@example.com");
        assertThat(result.smtpSender()).isEqualTo("noreply@example.com");
    }

    @Test
    void getSettings_masksAwsKey() {
        VaierConfig config = VaierConfig.builder()
            .domain("example.com")
            .awsKey("AKIAIOSFODNN7EXAMPLE")
            .awsSecret("secret")
            .acmeEmail("admin@example.com")
            .build();
        when(configPersistence.load()).thenReturn(Optional.of(config));

        AppSettingsResult result = service.getSettings();

        assertThat(result.awsKeyHint()).doesNotContain("AKIAIOSFODNN7EXAMPLE");
        assertThat(result.awsKeyHint()).contains("MPLE");
    }

    @Test
    void getSettings_returnsNullsWhenNoConfig() {
        when(configPersistence.load()).thenReturn(Optional.empty());

        AppSettingsResult result = service.getSettings();

        assertThat(result.domain()).isNull();
        assertThat(result.awsKeyHint()).isNull();
    }

    @Test
    void getSettings_handlesShortAwsKey() {
        VaierConfig config = VaierConfig.builder()
            .domain("example.com")
            .awsKey("ABC")
            .awsSecret("s")
            .acmeEmail("a@b.com")
            .build();
        when(configPersistence.load()).thenReturn(Optional.of(config));

        AppSettingsResult result = service.getSettings();

        assertThat(result.awsKeyHint()).isNotNull();
    }

    // --- updateAwsCredentials ---

    @Test
    void updateAwsCredentials_validatesCredentialsBeforeSaving() {
        when(forValidatingAwsCredentials.listHostedZones("NEW_KEY", "NEW_SECRET"))
            .thenReturn(List.of("example.com"));
        when(configPersistence.load()).thenReturn(Optional.of(existingConfig()));

        service.updateAwsCredentials("NEW_KEY", "NEW_SECRET");

        verify(forValidatingAwsCredentials).listHostedZones("NEW_KEY", "NEW_SECRET");
    }

    @Test
    void updateAwsCredentials_savesNewCredentialsWhenValid() {
        when(forValidatingAwsCredentials.listHostedZones("NEW_KEY", "NEW_SECRET"))
            .thenReturn(List.of("example.com"));
        when(configPersistence.load()).thenReturn(Optional.of(existingConfig()));

        service.updateAwsCredentials("NEW_KEY", "NEW_SECRET");

        ArgumentCaptor<VaierConfig> captor = ArgumentCaptor.forClass(VaierConfig.class);
        verify(configPersistence).save(captor.capture());
        VaierConfig saved = captor.getValue();
        assertThat(saved.getAwsKey()).isEqualTo("NEW_KEY");
        assertThat(saved.getAwsSecret()).isEqualTo("NEW_SECRET");
    }

    @Test
    void updateAwsCredentials_preservesOtherConfigFieldsWhenSaving() {
        when(forValidatingAwsCredentials.listHostedZones("NEW_KEY", "NEW_SECRET"))
            .thenReturn(List.of("example.com"));
        when(configPersistence.load()).thenReturn(Optional.of(existingConfig()));

        service.updateAwsCredentials("NEW_KEY", "NEW_SECRET");

        ArgumentCaptor<VaierConfig> captor = ArgumentCaptor.forClass(VaierConfig.class);
        verify(configPersistence).save(captor.capture());
        VaierConfig saved = captor.getValue();
        assertThat(saved.getDomain()).isEqualTo("example.com");
        assertThat(saved.getAcmeEmail()).isEqualTo("admin@example.com");
    }

    @Test
    void updateAwsCredentials_throwsAndDoesNotSaveWhenValidationFails() {
        when(forValidatingAwsCredentials.listHostedZones("BAD_KEY", "BAD_SECRET"))
            .thenThrow(new RuntimeException("Invalid AWS credentials"));

        assertThatThrownBy(() -> service.updateAwsCredentials("BAD_KEY", "BAD_SECRET"))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Invalid AWS credentials");

        verify(configPersistence, never()).save(any());
    }

    // --- updateSmtpSettings ---

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

        ArgumentCaptor<VaierConfig> captor = ArgumentCaptor.forClass(VaierConfig.class);
        verify(configPersistence).save(captor.capture());
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
        order.verify(configPersistence).save(any(VaierConfig.class));
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

        verify(configPersistence, never()).save(any());
        verify(smtpNotifierConfig, never()).updateSmtpConfig(any(), anyInt(), any(), any(), any());
        verify(containerRestarter, never()).restartContainer(any());
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

        verify(smtpVerifier, never()).verify(any(), anyInt(), any(), any());
        verify(configPersistence, never()).save(any());
    }

    // --- sendTestEmail ---

    @Test
    void sendTestEmail_usesProvidedPassword() {
        service.sendTestEmail("smtp.example.com", 587, "user@example.com", "livePass",
            "noreply@example.com", "admin@example.com");

        verify(testEmailSender).sendTestEmail("smtp.example.com", 587, "user@example.com", "livePass",
            "noreply@example.com", "admin@example.com");
    }

    @Test
    void sendTestEmail_fallsBackToStoredPasswordWhenProvidedIsBlank() {
        when(storedPasswordReader.readStoredPassword()).thenReturn(Optional.of("storedPass"));

        service.sendTestEmail("smtp.example.com", 587, "user@example.com", "",
            "noreply@example.com", "admin@example.com");

        verify(testEmailSender).sendTestEmail("smtp.example.com", 587, "user@example.com", "storedPass",
            "noreply@example.com", "admin@example.com");
    }

    @Test
    void sendTestEmail_rejectsWhenNoPasswordAvailable() {
        when(storedPasswordReader.readStoredPassword()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.sendTestEmail("smtp.example.com", 587, "user@example.com", "",
            "noreply@example.com", "admin@example.com"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("password");

        verify(testEmailSender, never()).sendTestEmail(any(), anyInt(), any(), any(), any(), any());
    }

    @Test
    void sendTestEmail_rejectsBlankRecipient() {
        assertThatThrownBy(() -> service.sendTestEmail("smtp.example.com", 587, "user@example.com",
            "livePass", "noreply@example.com", ""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("recipient");

        verify(testEmailSender, never()).sendTestEmail(any(), anyInt(), any(), any(), any(), any());
    }
}
