package net.vaier.domain.port;

import net.vaier.domain.PublishableService;
import net.vaier.domain.ReverseProxyRoute;

import java.util.List;

/**
 * Driven query port exposing the unpublished Vaier-server services. Mirror of the inbound
 * {@code GetVaierServerDockerServicesUseCase}; used by the publishing service to read which
 * Vaier-server containers are not yet published without coupling to the inbound use case.
 */
public interface ForGettingVaierServerDockerServices {

    List<PublishableService> getUnpublishedVaierServerServices(List<ReverseProxyRoute> existingRoutes);
}
