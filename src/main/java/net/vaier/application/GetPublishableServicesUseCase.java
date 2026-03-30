package net.vaier.application;

import net.vaier.application.PublishPeerServiceUseCase.PublishableService;

import java.util.List;

public interface GetPublishableServicesUseCase {
    List<PublishableService> getPublishableServices();
}
