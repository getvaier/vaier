package net.vaier.application.service;

import net.vaier.domain.port.ForReadingStoredSmtpPassword;
import net.vaier.domain.port.ForSendingTestEmail;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TestSmtpCredentialsServiceTest {

    @Mock ForSendingTestEmail testEmailSender;
    @Mock ForReadingStoredSmtpPassword storedPasswordReader;

    @InjectMocks TestSmtpCredentialsService service;

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
