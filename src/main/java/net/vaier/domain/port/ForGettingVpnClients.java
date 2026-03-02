package net.vaier.domain.port;

import net.vaier.domain.VpnClient;
import java.util.List;

public interface ForGettingVpnClients {
    List<VpnClient> getClients();
}
