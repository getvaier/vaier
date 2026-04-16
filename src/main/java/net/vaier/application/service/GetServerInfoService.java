package net.vaier.application.service;

import lombok.RequiredArgsConstructor;
import net.vaier.application.GetServerInfoUseCase;
import net.vaier.domain.DockerService;
import net.vaier.domain.Server;
import net.vaier.domain.port.ForGettingServerInfo;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class GetServerInfoService implements GetServerInfoUseCase {

    private final ForGettingServerInfo forGettingServerInfo;

    @Override
    public List<DockerService> getServicesWithExposedPorts(Server server) {
        return forGettingServerInfo.getServicesWithExposedPorts(server);
    }
}
