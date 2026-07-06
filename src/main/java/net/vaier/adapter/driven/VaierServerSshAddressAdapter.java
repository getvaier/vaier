package net.vaier.adapter.driven;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.SshConnectException;
import net.vaier.domain.port.ForResolvingVaierServerSshAddress;
import org.springframework.stereotype.Component;

/**
 * Resolves the Vaier server host's SSH address as seen from inside the vaier container (#308):
 * {@code VAIER_HOST_SSH_ADDRESS} when the operator sets one, otherwise the container's default-gateway
 * IP (from {@code /proc/net/route}) — which is the Docker bridge's host endpoint. This is the seam noted
 * in {@code MachineService}: the web terminal SSHes to the host over that gateway.
 */
@Component
@Slf4j
public class VaierServerSshAddressAdapter implements ForResolvingVaierServerSshAddress {

    private static final String ROUTE_FILE = "/proc/net/route";
    private final String override;

    public VaierServerSshAddressAdapter() {
        this(System.getenv("VAIER_HOST_SSH_ADDRESS"));
    }

    public VaierServerSshAddressAdapter(String override) {
        this.override = override;
    }

    @Override
    public String resolve() {
        if (override != null && !override.isBlank()) {
            return override.trim();
        }
        return readProcNetRoute()
            .flatMap(VaierServerSshAddressAdapter::parseDefaultGateway)
            .orElseThrow(() -> new SshConnectException(
                "Could not determine the Vaier host's SSH address. Set VAIER_HOST_SSH_ADDRESS to the host's "
                    + "reachable IP (the container's default gateway)."));
    }

    private static Optional<String> readProcNetRoute() {
        try {
            return Optional.of(Files.readString(Path.of(ROUTE_FILE)));
        } catch (IOException e) {
            log.warn("Could not read {}: {}", ROUTE_FILE, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * The default-gateway IPv4 from {@code /proc/net/route} content: the row whose {@code Destination}
     * is {@code 00000000}, decoding its little-endian hex {@code Gateway} to dotted-quad. Empty when
     * there is no default route.
     */
    static Optional<String> parseDefaultGateway(String procNetRoute) {
        if (procNetRoute == null) return Optional.empty();
        for (String line : procNetRoute.split("\n")) {
            String[] cols = line.trim().split("\\s+");
            if (cols.length < 3) continue;
            if (!"00000000".equalsIgnoreCase(cols[1])) continue;
            String hex = cols[2];
            if (hex.length() != 8) continue;
            try {
                // Little-endian: last hex byte is the first octet.
                int b1 = Integer.parseInt(hex.substring(6, 8), 16);
                int b2 = Integer.parseInt(hex.substring(4, 6), 16);
                int b3 = Integer.parseInt(hex.substring(2, 4), 16);
                int b4 = Integer.parseInt(hex.substring(0, 2), 16);
                if (b1 == 0 && b2 == 0 && b3 == 0 && b4 == 0) continue;
                return Optional.of(b1 + "." + b2 + "." + b3 + "." + b4);
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }
}
