package net.vaier.rest;

import net.vaier.domain.DockerService;
import net.vaier.domain.Server;
import net.vaier.domain.port.ForGettingServerInfo;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/docker-services")
public class DockerServiceRestController {

    private final ForGettingServerInfo forGettingServerInfo;

    public DockerServiceRestController(ForGettingServerInfo forGettingServerInfo) {
        this.forGettingServerInfo = forGettingServerInfo;
    }

    @GetMapping
    public List<DockerService> getDockerServices(
        @RequestParam String address,
        @RequestParam(required = false) Integer port,
        @RequestParam(defaultValue = "false") boolean tlsEnabled
    ) {
        Server server = new Server(address, port, tlsEnabled);
        return forGettingServerInfo.getServicesWithExposedPorts(server);
    }
}
