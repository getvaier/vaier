package net.vaier.application.service;

import lombok.RequiredArgsConstructor;
import net.vaier.application.ResolveVpnPeerNameUseCase;
import net.vaier.domain.port.ForResolvingPeerNames;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ResolveVpnPeerNameService implements ResolveVpnPeerNameUseCase {

    private final ForResolvingPeerNames forResolvingPeerNames;

    @Override
    public String resolvePeerNameByIp(String ipAddress) {
        return forResolvingPeerNames.resolvePeerNameByIp(ipAddress);
    }
}
