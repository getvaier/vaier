package net.vaier.application;

import java.util.Set;

public interface DeleteHostedServiceUseCase {

    Set<String> MANDATORY_SUBDOMAINS = Set.of("vaier", "auth");

    void deleteService(String subdomain);
}
