package net.vaier.domain.port;

public interface ForConfiguringSmtpNotifier {
    void updateSmtpConfig(String host, int port, String username, String password, String sender);
}
