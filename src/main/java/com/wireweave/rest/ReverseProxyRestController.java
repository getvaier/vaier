package com.wireweave.rest;

import com.wireweave.application.AddReverseProxyRouteUseCase;
import com.wireweave.application.DeleteReverseProxyRouteUseCase;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/reverse-proxy")
public class ReverseProxyRestController {

    private final AddReverseProxyRouteUseCase addReverseProxyRouteUseCase;
    private final DeleteReverseProxyRouteUseCase deleteReverseProxyRouteUseCase;

    public ReverseProxyRestController(
            AddReverseProxyRouteUseCase addReverseProxyRouteUseCase,
            DeleteReverseProxyRouteUseCase deleteReverseProxyRouteUseCase) {
        this.addReverseProxyRouteUseCase = addReverseProxyRouteUseCase;
        this.deleteReverseProxyRouteUseCase = deleteReverseProxyRouteUseCase;
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
