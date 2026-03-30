package net.vaier.application;

import net.vaier.domain.ReverseProxyRoute;

import java.util.List;

public interface GetReverseProxyRoutesUseCase {
    List<ReverseProxyRoute> getReverseProxyRoutes();
}
