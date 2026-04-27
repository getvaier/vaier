package net.vaier.domain.port;

import java.util.List;

public interface ForSendingNotificationEmail {
    void sendEmail(String host, int port, String username, String password,
                   String sender, List<String> recipients, String subject, String body);
}
