package net.vaier.domain.port;

import java.util.Map;

public interface ForFetchingPeerMetrics {
    Map<String, Map<String, Double>> fetchMetrics(String peerIp);
}
