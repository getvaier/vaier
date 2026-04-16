package net.vaier.application.service;

import lombok.RequiredArgsConstructor;
import net.vaier.application.GetVpnClientsUseCase;
import net.vaier.domain.VpnClient;
import net.vaier.domain.port.ForGettingVpnClients;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class GetVpnClientsService implements GetVpnClientsUseCase {

    private final ForGettingVpnClients forGettingVpnClients;

    @Override
    public List<VpnClient> getClients() {
        return forGettingVpnClients.getClients();
    }
}
