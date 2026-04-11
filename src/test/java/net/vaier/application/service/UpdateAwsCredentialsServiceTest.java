package net.vaier.application.service;

import net.vaier.domain.VaierConfig;
import net.vaier.domain.port.ForPersistingAppConfiguration;
import net.vaier.domain.port.ForValidatingAwsCredentials;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UpdateAwsCredentialsServiceTest {

    @Mock ForPersistingAppConfiguration configPersistence;
    @Mock ForValidatingAwsCredentials forValidatingAwsCredentials;

    @InjectMocks UpdateAwsCredentialsService service;

    private VaierConfig existingConfig() {
        return VaierConfig.builder()
            .domain("example.com")
            .awsKey("OLD_KEY")
            .awsSecret("OLD_SECRET")
            .acmeEmail("admin@example.com")
            .build();
    }

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

        verify(configPersistence, never()).save(org.mockito.ArgumentMatchers.any());
    }
}
