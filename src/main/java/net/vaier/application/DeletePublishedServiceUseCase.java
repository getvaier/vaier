package net.vaier.application;

import java.util.Set;
import net.vaier.config.ServiceNames;

public interface DeletePublishedServiceUseCase {

    Set<String> MANDATORY_SUBDOMAINS = Set.of(ServiceNames.VAIER, ServiceNames.AUTH);

    void deleteService(String subdomain);
}
