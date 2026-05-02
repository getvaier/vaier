package net.vaier.domain;

import java.util.List;
import java.util.Map;

public record DockerService(
        String containerId,
        String containerName,
        String image,
        String version,
        List<PortMapping> ports,
        List<String> networks,
        String state
) {

    public boolean isRunning() {
        return "running".equalsIgnoreCase(state);
    }

    public boolean listensOnPort(int port) {
        return ports.stream()
            .anyMatch(mapping -> mapping.containsPort(port) ||
                      (mapping.publicPort() != null && mapping.publicPort() == port));
    }

    public static String versionFromLabels(Map<String, String> labels, String image) {
        if (labels != null) {
            String oci = labels.get("org.opencontainers.image.version");
            if (oci != null && !oci.isBlank()) return oci;

            String labelSchema = labels.get("org.label-schema.version");
            if (labelSchema != null && !labelSchema.isBlank()) return labelSchema;

            String buildVersion = labels.get("build_version");
            if (buildVersion != null) {
                // LinuxServer.io format: "Version:- 1.0.20210914 Build-date:- ..."
                int idx = buildVersion.indexOf("Version:- ");
                if (idx >= 0) {
                    String rest = buildVersion.substring(idx + "Version:- ".length()).trim();
                    int space = rest.indexOf(' ');
                    return space > 0 ? rest.substring(0, space) : rest;
                }
            }
        }
        // Fallback: extract tag from image string
        int slashIdx = image.lastIndexOf('/');
        String nameAndTag = slashIdx >= 0 ? image.substring(slashIdx + 1) : image;
        int colonIdx = nameAndTag.indexOf(':');
        return colonIdx >= 0 ? nameAndTag.substring(colonIdx + 1) : "latest";
    }

    public record PortMapping(
            int privatePort,
            Integer lastPrivatePort,
            Integer publicPort,
            String type,
            String ip
    ) {
        public PortMapping(int privatePort, Integer publicPort, String type, String ip) {
            this(privatePort, null, publicPort, type, ip);
        }

        public boolean isRange() {
            return lastPrivatePort != null && lastPrivatePort > privatePort;
        }

        public boolean containsPort(int port) {
            int last = lastPrivatePort != null ? lastPrivatePort : privatePort;
            return port >= privatePort && port <= last;
        }

        public String toString() {
            if (isRange()) {
                return privatePort + "-" + lastPrivatePort + "/" + type;
            }
            if (publicPort != null) {
                String ipPart = (ip != null && !ip.isEmpty()) ? ip + ":" : "";
                return ipPart + publicPort + "->" + privatePort + "/" + type;
            }
            return privatePort + "/" + type;
        }
    }
}
