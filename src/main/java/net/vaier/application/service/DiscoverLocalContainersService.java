package net.vaier.application.service;

import lombok.RequiredArgsConstructor;
import net.vaier.application.DiscoverLocalContainersUseCase;
import net.vaier.domain.DockerService;
import net.vaier.domain.Server;
import net.vaier.domain.port.ForGettingServerInfo;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class DiscoverLocalContainersService implements DiscoverLocalContainersUseCase {

    private final ForGettingServerInfo forGettingServerInfo;

    @Override
    public List<DockerService> discover() {
        return forGettingServerInfo.getServicesWithExposedPorts(Server.local());
    }
}
