package net.vaier.application;

public interface TestSmtpCredentialsUseCase {
    void sendTestEmail(String host, int port, String username, String password,
                       String sender, String recipient);
}
