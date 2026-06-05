package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DiscoveredLanMachineTest {

    private static DiscoveredLanMachine machine(String ip, String hostname, List<Integer> ports) {
        return new DiscoveredLanMachine(ip, hostname, ports, "apalveien5");
    }

    @Test
    void guessesDockerHostFromTheDockerApiPort() {
        assertThat(machine("192.168.3.10", null, List.of(2375, 22)).guessedRole())
            .isEqualTo(LanMachineRole.DOCKER_HOST);
    }

    @Test
    void guessesWebUiFromAnHttpPort() {
        assertThat(machine("192.168.3.11", null, List.of(80, 22)).guessedRole())
            .isEqualTo(LanMachineRole.WEB_UI);
    }

    @Test
    void guessesPrinterFromAPrintPort() {
        assertThat(machine("192.168.3.12", null, List.of(9100)).guessedRole())
            .isEqualTo(LanMachineRole.PRINTER);
    }

    @Test
    void fallsBackToUnknownWhenNoTellingPortIsOpen() {
        assertThat(machine("192.168.3.13", null, List.of()).guessedRole())
            .isEqualTo(LanMachineRole.UNKNOWN);
    }

    @Test
    void dockerWinsOverWebUiWhenBothPortsAreOpen() {
        assertThat(machine("192.168.3.14", null, List.of(443, 2375)).guessedRole())
            .isEqualTo(LanMachineRole.DOCKER_HOST);
    }

    @Test
    void isAlreadyRegisteredWhenItsAddressIsAlreadyClaimed() {
        DiscoveredLanMachine m = machine("192.168.3.50", "nas", List.of(5000));

        // A registered machine — LAN server or VPN peer — already owns this address.
        assertThat(m.isAlreadyRegistered(List.of("192.168.3.50"))).isTrue();
        assertThat(m.isAlreadyRegistered(List.of("192.168.3.50", "192.168.3.99"))).isTrue();
        assertThat(m.isAlreadyRegistered(List.of("192.168.3.99"))).isFalse();
        assertThat(m.isAlreadyRegistered(List.of())).isFalse();
    }

    @Test
    void ignoreKeyIsStablePerRelayAndAddress() {
        assertThat(machine("192.168.3.10", "a", List.of(80)).ignoreKey())
            .isEqualTo("apalveien5|192.168.3.10");
        // Hostname and ports don't change the key — the operator ignores a host, not a snapshot.
        assertThat(machine("192.168.3.10", "renamed", List.of(22)).ignoreKey())
            .isEqualTo("apalveien5|192.168.3.10");
    }
}
