package net.vaier.application;

public interface ToggleServiceAuthUseCase {
    void setAuthentication(String dnsName, boolean requiresAuth);
}
