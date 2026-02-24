package com.wireweave.domain;

import java.util.List;

public record DockerService(
        String containerId,
        String containerName,
        String image,
        List<PortMapping> ports
) {

    public boolean listensOnPort(int port) {
        return ports.stream()
            .filter(mapping -> mapping.publicPort() != null)
            .anyMatch(mapping -> mapping.publicPort() == port);
    }

    public record PortMapping(
            int privatePort,
            Integer publicPort,
            String type,
            String ip
    ) {
        public String toString() {
            if (publicPort != null) {
                String ipPart = (ip != null && !ip.isEmpty()) ? ip + ":" : "";
                return ipPart + publicPort + "->" + privatePort + "/" + type;
            }
            return privatePort + "/" + type;
        }
    }
}
