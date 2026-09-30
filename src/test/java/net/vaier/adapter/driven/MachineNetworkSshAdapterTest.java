package net.vaier.adapter.driven;

import net.vaier.domain.AuthMethod;
import net.vaier.domain.CommandResult;
import net.vaier.domain.HostCredential;
import net.vaier.domain.MachineId;
import net.vaier.domain.MachineNetworks;
import net.vaier.domain.SshTarget;
import net.vaier.domain.port.ForResolvingSshTargets;
import net.vaier.domain.port.ForRunningSshCommands;
import net.vaier.domain.port.ForTrackingHostKeys;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asking a machine what networks it is on. The adapter translates and nothing more: it resolves the SSH
 * target, runs the one command the domain names, pins the host key on first use, and hands the raw output
 * to the domain parser. Every judgement about the answer — which interfaces count, which network is the
 * one behind the machine — is pinned in {@code MachineNetworksTest}, not here.
 */
class MachineNetworkSshAdapterTest {

    private static final MachineId COLINA = MachineId.generate();

    private static final String IP_OUTPUT = """
        2: eth0    inet 192.168.1.10/24 brd 192.168.1.255 scope global eth0
        default via 192.168.1.1 dev eth0 proto dhcp metric 100
        """;

    private final List<String> commands = new ArrayList<>();
    private CommandResult result = new CommandResult(0, IP_OUTPUT, "", false, "SHA256:fresh");

    private final ForResolvingSshTargets targets = machineId ->
        SshTarget.on("10.13.13.3",
            new HostCredential(machineId, "geir", AuthMethod.PASSWORD, "pw", null, false), null);

    private final ForRunningSshCommands ssh = new ForRunningSshCommands() {
        @Override
        public CommandResult run(SshTarget target, String command) {
            commands.add(command);
            return result;
        }

        @Override
        public CommandResult run(SshTarget target, String command, Duration timeout) {
            return run(target, command);
        }

        @Override
        public CommandResult run(SshTarget target, String command, Duration timeout, String stdin) {
            return run(target, command);
        }
    };

    private final Map<MachineId, String> pinned = new ConcurrentHashMap<>();

    private final ForTrackingHostKeys hostKeys = new ForTrackingHostKeys() {
        @Override
        public Optional<String> getFingerprint(MachineId machineId) {
            return Optional.ofNullable(pinned.get(machineId));
        }

        @Override
        public void pin(MachineId machineId, String fingerprint) {
            pinned.put(machineId, fingerprint);
        }

        @Override
        public void clear(MachineId machineId) {
            pinned.remove(machineId);
        }
    };

    private final MachineNetworkSshAdapter adapter = new MachineNetworkSshAdapter(targets, ssh, hostKeys);

    @Test
    void readsTheNetworksWithTheCommandTheDomainNames() {
        var networks = adapter.read(COLINA);

        assertThat(commands).containsExactly(MachineNetworks.IP_COMMAND);
        assertThat(networks.lanCandidate())
            .hasValueSatisfying(n -> assertThat(n.cidr()).isEqualTo("192.168.1.0/24"));
    }

    @Test
    void pinsTheHostKeyOnFirstUse() {
        // Every path that reaches a machine over SSH must pin, or a machine touched only by this path
        // would never gain a pinned key and could never detect one changing.
        adapter.read(COLINA);

        assertThat(pinned).containsEntry(COLINA, "SHA256:fresh");
    }

    @Test
    void aCommandThatFailed_isNoReadingAtAll() {
        result = new CommandResult(127, "", "sh: ip: not found", false, null);

        assertThat(adapter.read(COLINA).lanCandidate()).isEmpty();
        assertThat(adapter.read(COLINA).networks()).isEmpty();
    }

    @Test
    void aCommandThatTimedOut_isNoReadingAtAll() {
        result = new CommandResult(0, IP_OUTPUT, "", true, null);

        assertThat(adapter.read(COLINA).lanCandidate()).isEmpty();
    }
}
