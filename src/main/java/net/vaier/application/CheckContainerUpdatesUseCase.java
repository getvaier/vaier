package net.vaier.application;

import net.vaier.domain.ContainerUpdateStatus;

import java.util.List;

public interface CheckContainerUpdatesUseCase {

    List<ContainerUpdateStatus> checkAll();

    List<ContainerUpdateStatus> getCachedResults();
}
