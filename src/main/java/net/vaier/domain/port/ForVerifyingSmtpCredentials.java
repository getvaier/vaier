package net.vaier.domain.port;

public interface ForVerifyingSmtpCredentials {
    void verify(String host, int port, String username, String password);
}
