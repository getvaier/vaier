package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The guard that keeps a generated setup script from running on the wrong machine. Written after a
 * peer setup script was pasted into the wrong terminal and reconfigured a Vaier server (2026-07-23):
 * it took the stack down, rewrote the Docker daemon config, and routed the host's own subnet into a
 * tunnel that could never come up — severing its uplink.
 */
class SetupScriptGuardTest {

    // --- shape ---

    @Test
    void preamble_carriesMarkerMachineNameAndForceEscape() {
        String s = SetupScriptGuard.preamble("NUC 02", List.of(), null);

        assertThat(s).contains(SetupScriptGuard.MARKER);
        assertThat(s).contains("NUC 02");
        assertThat(s).contains("VAIER_FORCE");
        // The refusal must say nothing was touched — the operator's next question is always
        // "did it get halfway through?".
        assertThat(s).contains("Nothing has been changed on this host");
        assertThat(s).contains("exit 3");
    }

    // --- check 1: never on the Vaier server itself ---

    @Test
    void preamble_refusesOnAHostRunningVaier() {
        String s = SetupScriptGuard.preamble("NUC 02", List.of(), null);

        assertThat(s).contains("getvaier/vaier");
        assertThat(s).contains("docker ps");
        // Catches a Vaier server whose stack happens to be down, which the container check cannot.
        assertThat(s).contains("$HOME/vaier/docker-compose.yml");
    }

    // --- check 2: never a script for one machine on a different registered machine ---

    @Test
    void preamble_refusesWhenTheHostIsStampedAsADifferentMachine() {
        String s = SetupScriptGuard.preamble("NUC 02", List.of(), null);

        assertThat(s).contains(SetupScriptGuard.STAMP_PATH);
    }

    @Test
    void stamp_recordsTheMachineNameSoALaterWrongScriptIsRefused() {
        String s = SetupScriptGuard.stamp("NUC 02");

        assertThat(s).contains(SetupScriptGuard.STAMP_PATH);
        assertThat(s).contains("'NUC 02'");
    }

    // --- check 3: never route the host's own network into the tunnel ---

    @Test
    void preamble_emitsSelfBlackholeCheckForEveryTunneledCidr() {
        String s = SetupScriptGuard.preamble("NUC 02", List.of("10.13.13.0/24", "172.31.32.0/20"), null);

        assertThat(s).contains("10.13.13.0/24");
        assertThat(s).contains("172.31.32.0/20");
        assertThat(s).contains("ip route show default");
    }

    @Test
    void preamble_withNoTunneledCidrs_omitsTheSelfBlackholeLoop() {
        String s = SetupScriptGuard.preamble("NUC 02", List.of(), null);

        assertThat(s).doesNotContain("ip route show default");
    }

    // --- check 4: the address Vaier recorded for this machine ---

    @Test
    void preamble_withExpectedAddress_assertsTheHostOwnsIt() {
        String s = SetupScriptGuard.preamble("NAS", List.of(), "192.168.3.3");

        assertThat(s).contains("192.168.3.3");
    }

    @Test
    void preamble_withoutExpectedAddress_omitsTheAddressCheck() {
        String s = SetupScriptGuard.preamble("NUC 02", List.of(), null);

        assertThat(s).doesNotContain("Vaier has recorded");
    }

    // --- a machine name is operator-typed text, never shell ---

    @Test
    void preamble_singleQuotesTheMachineNameSoItCannotBreakOutIntoShell() {
        String s = SetupScriptGuard.preamble("evil'; rm -rf /tmp/pwned; #", List.of(), null);

        // The whole name must land inside one single-quoted literal, with its own quote escaped as
        // '\'' — so the shell sees data, never a command separator.
        assertThat(s).contains("VAIER_MACHINE='evil'\\''; rm -rf /tmp/pwned; #'");
    }

    // --- the CIDR arithmetic actually works (the guard is shell, so run the shell) ---

    @Test
    void inCidrArithmetic_matchesOnlyAddressesInsideTheRange() throws Exception {
        // The staging case: the host sat at 172.31.34.155 inside a routed 172.31.32.0/20.
        assertThat(runInCidr("172.31.34.155", "172.31.32.0/20")).isTrue();
        // Production sat in a different /20 and was unaffected.
        assertThat(runInCidr("172.31.17.253", "172.31.32.0/20")).isFalse();
        assertThat(runInCidr("10.13.13.6", "10.13.13.0/24")).isTrue();
        assertThat(runInCidr("192.168.1.50", "10.13.13.0/24")).isFalse();
        assertThat(runInCidr("10.0.0.1", "0.0.0.0/0")).isTrue();
        assertThat(runInCidr("10.0.0.1", "10.0.0.1/32")).isTrue();
        assertThat(runInCidr("10.0.0.2", "10.0.0.1/32")).isFalse();
    }

    /** Extracts the guard's shell helpers and runs {@code vaier_in_cidr} for real under bash. */
    private boolean runInCidr(String ip, String cidr) throws IOException, InterruptedException {
        String script = SetupScriptGuard.cidrHelpers()
            + "\nif vaier_in_cidr '" + ip + "' '" + cidr + "'; then echo YES; else echo NO; fi\n";
        Path f = Files.createTempFile("vaier-guard-", ".sh");
        try {
            Files.writeString(f, "#!/bin/bash\nset -euo pipefail\n" + script);
            Process p = new ProcessBuilder("bash", f.toString()).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            assertThat(p.waitFor()).as("guard helpers must run cleanly under set -euo pipefail").isZero();
            return out.endsWith("YES");
        } finally {
            Files.deleteIfExists(f);
        }
    }

    // --- the peer script's tunneled CIDRs come out of the config it writes ---

    @Test
    void tunneledCidrs_readsClientSideAllowedIpsFromTheWireGuardConfig() {
        String conf = """
            [Interface]
            Address = 10.13.13.2/32
            PrivateKey = xxx

            [Peer]
            PublicKey = yyy
            AllowedIPs = 10.13.13.0/24,172.31.32.0/20
            Endpoint = vaier.vaier.net:51820
            """;

        assertThat(SetupScriptGuard.tunneledCidrs(conf, "10.13.13.0/24"))
            .containsExactly("10.13.13.0/24", "172.31.32.0/20");
    }

    @Test
    void tunneledCidrs_mirrorsTheSplitTunnelRewriteTheScriptPerforms() {
        // PeerSetupScript seds a full-tunnel AllowedIPs line down to the VPN subnet, so the guard
        // must check what the config will say *after* that rewrite, not before.
        String conf = "[Peer]\nAllowedIPs = 0.0.0.0/0\n";

        assertThat(SetupScriptGuard.tunneledCidrs(conf, "10.13.13.0/24"))
            .containsExactly("10.13.13.0/24");
    }

    @Test
    void tunneledCidrs_ignoresTheInterfaceAddressAndIpv6() {
        String conf = "[Interface]\nAddress = 10.13.13.2/32\n[Peer]\nAllowedIPs = 10.13.13.0/24, ::/0\n";

        assertThat(SetupScriptGuard.tunneledCidrs(conf, "10.13.13.0/24"))
            .containsExactly("10.13.13.0/24");
    }
}
