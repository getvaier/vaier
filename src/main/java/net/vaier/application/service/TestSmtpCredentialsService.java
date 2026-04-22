package net.vaier.application.service;

import net.vaier.application.TestSmtpCredentialsUseCase;
import net.vaier.domain.port.ForReadingStoredSmtpPassword;
import net.vaier.domain.port.ForSendingTestEmail;
import org.springframework.stereotype.Service;

@Service
public class TestSmtpCredentialsService implements TestSmtpCredentialsUseCase {

    private final ForSendingTestEmail testEmailSender;
    private final ForReadingStoredSmtpPassword storedPasswordReader;

    public TestSmtpCredentialsService(ForSendingTestEmail testEmailSender,
                                      ForReadingStoredSmtpPassword storedPasswordReader) {
        this.testEmailSender = testEmailSender;
        this.storedPasswordReader = storedPasswordReader;
    }

    @Override
    public void sendTestEmail(String host, int port, String username, String password,
                              String sender, String recipient) {
        if (recipient == null || recipient.isBlank()) {
            throw new IllegalArgumentException("recipient email address is required");
        }
        String resolvedPassword = resolvePassword(password);
        testEmailSender.sendTestEmail(host, port, username, resolvedPassword, sender, recipient);
    }

    private String resolvePassword(String provided) {
        if (provided != null && !provided.isBlank()) {
            return provided;
        }
        return storedPasswordReader.readStoredPassword()
            .filter(p -> !p.isBlank())
            .orElseThrow(() -> new IllegalArgumentException("SMTP password is required"));
    }
}
