package com.wireweave.domain.port;

import com.wireweave.domain.VpnClient;
import java.util.List;

public interface ForGettingVpnClients {
    List<VpnClient> getClients();
}
