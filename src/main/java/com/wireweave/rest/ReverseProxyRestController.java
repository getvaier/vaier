package com.wireweave.rest;

import com.wireweave.application.AddReverseProxyRouteUseCase;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/reverse-proxy")
public class ReverseProxyRestController {

    private final AddReverseProxyRouteUseCase addReverseProxyRouteUseCase;

    public ReverseProxyRestController(AddReverseProxyRouteUseCase addReverseProxyRouteUseCase) {
        this.addReverseProxyRouteUseCase = addReverseProxyRouteUseCase;
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

    public record AddRouteRequest(
        String dnsName,
        String address,
        int port,
        boolean requiresAuth
    ) {}
}
