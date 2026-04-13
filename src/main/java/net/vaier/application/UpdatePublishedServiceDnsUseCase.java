package net.vaier.application;

public interface UpdatePublishedServiceDnsUseCase {
    void updateDns(String currentFqdn, String newSubdomain);
}
