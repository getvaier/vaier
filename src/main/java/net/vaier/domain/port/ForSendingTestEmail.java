package net.vaier.domain.port;

public interface ForSendingTestEmail {
    void sendTestEmail(String host, int port, String username, String password,
                       String sender, String recipient);
}
