package net.vaier.adapter.driven;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import net.vaier.domain.AuthMethod;
import net.vaier.domain.CommandResult;
import net.vaier.domain.MachineId;
import net.vaier.domain.SshTarget;
import net.vaier.domain.SurvivalKit;
import net.vaier.domain.port.ForResolvingSshTargets;
import net.vaier.domain.port.ForRunningSshCommands;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Getting the kit onto a machine.
 *
 * <p>The kit is prose, base64 and quote characters all at once, and it is written by a command that must
 * survive every one of them. So it travels base64-encoded and is decoded on the far side: the bytes that
 * cross the wire are {@code [A-Za-z0-9+/=]}, which no shell can misread. A kit that arrived subtly mangled
 * would decrypt to nothing on the day it was needed, and nothing before then would say so.
 */
class SurvivalKitKeeperAdapterTest {

    private static final String KIT = "VAIER SURVIVAL KIT\nsed '1,/^-----BEGIN$/d' | openssl \"x\"\n$(rm -rf /)\n";

    @TempDir
    Path configDir;

    private final List<String> commands = new ArrayList<>();
    private int exitCode;

    private static final MachineId COLINA = MachineId.generate();

    private final List<MachineId> resolved = new ArrayList<>();

    private final ForResolvingSshTargets targets = machineId -> {
        resolved.add(machineId);
        return new SshTarget("10.0.0.9", 22, "geir", AuthMethod.PASSWORD, "pw", null, null);
    };

    private final ForRunningSshCommands ssh = new ForRunningSshCommands() {
        @Override
        public CommandResult run(SshTarget target, String command) {
            commands.add(command);
            return new CommandResult(exitCode, "", exitCode == 0 ? "" : "Permission denied", false, null);
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

    private SurvivalKitKeeperAdapter adapter() {
        return new SurvivalKitKeeperAdapter(targets, ssh, configDir.toString());
    }

    @Test
    void theKitCrossesTheWireAsBase64_soNoShellCanMangleIt() {
        // Quotes, $(...), backticks and newlines all appear in a real kit. Only the decoded form may ever
        // be interpreted, and only by base64 itself.
        adapter().keepOn(COLINA, KIT);

        // The machine is reached by identity — no name is resolved along the way to be renamed out from
        // under the rollout.
        assertThat(resolved).containsExactly(COLINA);
        assertThat(commands).hasSize(1);
        assertThat(commands.get(0)).doesNotContain("rm -rf");
        assertThat(commands.get(0)).contains("base64 -d");
        assertThat(commands.get(0)).contains(SurvivalKit.FILE_NAME);
    }

    @Test
    void aMachineThatRefusesTheWriteThrows_ratherThanReportingACopyThatIsNotThere() {
        // The rollout counts copies. A silent failure here would inflate that count, and an operator would
        // believe they had three kits and own one.
        exitCode = 1;

        assertThatThrownBy(() -> adapter().keepOn(COLINA, KIT))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Permission denied");
    }

    @Test
    void theLocalCopyLandsBesideVaiersConfig_readableOnlyByVaier() throws Exception {
        adapter().keepOnTheVaierServer(KIT);

        Path written = configDir.resolve(SurvivalKit.FILE_NAME);
        assertThat(Files.readString(written)).isEqualTo(KIT);
        // Every passphrase in the fleet is behind this file's encryption, but the file itself should still
        // not be world-readable on a host that may have other accounts.
        assertThat(Files.getPosixFilePermissions(written))
            .allMatch(p -> p.name().startsWith("OWNER"));
    }

    @Test
    void rewritingReplacesTheOldKit_soAStaleOneCannotSurviveBesideTheNewOne() throws Exception {
        adapter().keepOnTheVaierServer("old kit");
        adapter().keepOnTheVaierServer(KIT);

        assertThat(Files.readString(configDir.resolve(SurvivalKit.FILE_NAME))).isEqualTo(KIT);
    }
}
