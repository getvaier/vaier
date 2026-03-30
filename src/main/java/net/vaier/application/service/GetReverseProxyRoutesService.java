package net.vaier.application.service;

import lombok.RequiredArgsConstructor;
import net.vaier.application.GetReverseProxyRoutesUseCase;
import net.vaier.domain.ReverseProxyRoute;
import net.vaier.domain.port.ForPersistingReverseProxyRoutes;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class GetReverseProxyRoutesService implements GetReverseProxyRoutesUseCase {

    private final ForPersistingReverseProxyRoutes reverseProxyRoutesPort;

    @Override
    public List<ReverseProxyRoute> getReverseProxyRoutes() {
        return reverseProxyRoutesPort.getReverseProxyRoutes();
    }
}
