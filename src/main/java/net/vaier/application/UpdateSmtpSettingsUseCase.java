package net.vaier.application;

public interface UpdateSmtpSettingsUseCase {
    void updateSmtpSettings(String host, int port, String username, String password, String sender);
}
