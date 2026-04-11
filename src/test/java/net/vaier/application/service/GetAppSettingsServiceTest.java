package net.vaier.application.service;

import net.vaier.application.GetAppSettingsUseCase.AppSettingsResult;
import net.vaier.domain.VaierConfig;
import net.vaier.domain.port.ForPersistingAppConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetAppSettingsServiceTest {

    @Mock ForPersistingAppConfiguration configPersistence;

    @InjectMocks GetAppSettingsService service;

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
        assertThat(result.awsKeyHint()).contains("MPLE"); // last 4 chars
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

        // Short key: show full key as hint rather than crash
        assertThat(result.awsKeyHint()).isNotNull();
    }
}
