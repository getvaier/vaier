package net.vaier.domain.port;

public interface ForWritingBootstrapCredentials {

    String writeBootstrapPassword(String username, String password);
}
