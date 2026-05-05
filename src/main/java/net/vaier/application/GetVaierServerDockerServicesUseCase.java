package net.vaier.application;

import net.vaier.application.PublishPeerServiceUseCase.PublishableService;
import net.vaier.domain.ReverseProxyRoute;

import java.util.List;

public interface GetVaierServerDockerServicesUseCase {

    List<PublishableService> getUnpublishedVaierServerServices(List<ReverseProxyRoute> existingRoutes);
}
