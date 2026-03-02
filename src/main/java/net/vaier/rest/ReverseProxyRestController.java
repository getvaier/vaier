package net.vaier.rest;

import net.vaier.application.AddReverseProxyRouteUseCase;
import net.vaier.application.DeleteReverseProxyRouteUseCase;
import net.vaier.domain.ReverseProxyRoute;
import net.vaier.domain.port.ForPersistingReverseProxyRoutes;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/reverse-proxy")
public class ReverseProxyRestController {

    private final AddReverseProxyRouteUseCase addReverseProxyRouteUseCase;
    private final DeleteReverseProxyRouteUseCase deleteReverseProxyRouteUseCase;
    private final ForPersistingReverseProxyRoutes reverseProxyRoutesPort;

    public ReverseProxyRestController(
            AddReverseProxyRouteUseCase addReverseProxyRouteUseCase,
            DeleteReverseProxyRouteUseCase deleteReverseProxyRouteUseCase,
            ForPersistingReverseProxyRoutes reverseProxyRoutesPort) {
        this.addReverseProxyRouteUseCase = addReverseProxyRouteUseCase;
        this.deleteReverseProxyRouteUseCase = deleteReverseProxyRouteUseCase;
        this.reverseProxyRoutesPort = reverseProxyRoutesPort;
    }

    @GetMapping("/routes")
    public List<ReverseProxyRoute> getAllRoutes() {
        return reverseProxyRoutesPort.getReverseProxyRoutes();
    }

    @PostMapping("/routes")
    public void addRoute(@RequestBody AddRouteRequest request) {
        AddReverseProxyRouteUseCase.ReverseProxyRouteUco route =
            new AddReverseProxyRouteUseCase.ReverseProxyRouteUco(
                request.dnsName(),
                request.address(),
                request.port(),
                request.requiresAuth()
            );
        addReverseProxyRouteUseCase.addReverseProxyRoute(route);
    }

    @DeleteMapping("/routes/{dnsName}")
    public void deleteRoute(@PathVariable String dnsName) {
        deleteReverseProxyRouteUseCase.deleteReverseProxyRoute(dnsName);
    }

    public record AddRouteRequest(
        String dnsName,
        String address,
        int port,
        boolean requiresAuth
    ) {}
}
