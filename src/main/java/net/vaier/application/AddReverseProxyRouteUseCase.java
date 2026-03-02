package net.vaier.application;

public interface AddReverseProxyRouteUseCase {

    void addReverseProxyRoute(ReverseProxyRouteUco route);

    record ReverseProxyRouteUco(
        String dnsName,
        String address,
        int port,
        boolean requiresAuth
    ) {}
}
