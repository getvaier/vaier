package com.wireweave.domain.port;

import com.wireweave.domain.WireGuardPeer;
import java.util.List;

public interface ForGettingWireGuardPeers {
    List<WireGuardPeer> getPeers(String interfaceName);
}
