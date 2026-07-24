package net.vaier.adapter.driven;

import lombok.RequiredArgsConstructor;
import net.vaier.domain.LanAnchor;
import net.vaier.domain.LanServer;
import net.vaier.domain.MachineId;
import net.vaier.domain.VaierConfig;
import net.vaier.domain.port.ForGettingPeerConfigurations;
import net.vaier.domain.port.ForPersistingAppConfiguration;
import net.vaier.domain.port.ForPersistingLanServers;
import net.vaier.domain.port.ForResolvingMachineIds;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Resolves a machine's display name to its {@link MachineId} by composing the three stores a machine
 * can live in — peer configs, {@code lan-servers.yml}, and the Vaier config for the Vaier server itself.
 *
 * <p>An adapter rather than a service: this is a cross-domain <em>read</em> over several driven ports,
 * and a {@code *Service} implementing a driven port is the service-to-service coupling the architecture
 * forbids. Composing the lower-level ports here keeps every consumer — including other adapters —
 * injecting a port rather than each other.
 *
 * <p>It holds no rules of its own. Name comparison is delegated to {@link net.vaier.domain.Machine},
 * which already owns the rule that makes names unique across Vaier; if the two disagreed, a machine
 * could be rejected at creation for colliding with a name that could not then find it.
 */
@Component
@RequiredArgsConstructor
public class MachineIdRegistryAdapter implements ForResolvingMachineIds {

    private final ForGettingPeerConfigurations forGettingPeerConfigurations;
    private final ForPersistingLanServers forPersistingLanServers;
    private final ForPersistingAppConfiguration forPersistingAppConfiguration;

    @Override
    public Optional<MachineId> idForName(String machineName) {
        if (machineName == null || machineName.isBlank()) {
            return Optional.empty();
        }
        if (matches(LanAnchor.VAIER_SERVER_NAME, machineName)) {
            return vaierServerMachineId();
        }
        Optional<MachineId> peer = forGettingPeerConfigurations.getAllPeerConfigs().stream()
            .filter(p -> matches(p.name(), machineName))
            .map(ForGettingPeerConfigurations.PeerConfiguration::machineId)
            .findFirst();
        if (peer.isPresent()) {
            return peer;
        }
        return forPersistingLanServers.getAll().stream()
            .filter(s -> matches(s.name(), machineName))
            .map(LanServer::machineId)
            .findFirst();
    }

    /**
     * The Vaier server's configured id, or empty when it has not been assigned one yet. Empty rather
     * than minted: assigning identity is not a lookup's job, and a read that quietly created a machine
     * would hand back an id nothing else in the config knows about.
     */
    private Optional<MachineId> vaierServerMachineId() {
        return forPersistingAppConfiguration.load()
            .map(VaierConfig::getVaierServerMachineId)
            .filter(id -> id != null && !id.isBlank())
            .flatMap(id -> {
                try {
                    return Optional.of(MachineId.of(id));
                } catch (IllegalArgumentException e) {
                    return Optional.empty();
                }
            });
    }

    /** Name equality is the domain's rule, not this adapter's — it only asks. */
    private static boolean matches(String candidate, String wanted) {
        return net.vaier.domain.Machine.hasSameName(candidate, wanted);
    }
}
