package net.vaier.application;

import net.vaier.domain.VpnClient;
import java.util.List;

public interface GetVpnClientsUseCase {
    List<VpnClient> getClients();
}
