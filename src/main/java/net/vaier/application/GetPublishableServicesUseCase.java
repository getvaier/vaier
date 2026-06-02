package net.vaier.application;

import net.vaier.domain.PublishableService;

import java.util.List;

public interface GetPublishableServicesUseCase {
    List<PublishableService> getPublishableServices();
}
