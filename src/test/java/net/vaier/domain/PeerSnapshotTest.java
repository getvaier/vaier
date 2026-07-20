package net.vaier.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PeerSnapshotTest {

    @Test
    void notificationSubject_reflectsConnectedState() {
        PeerSnapshot connected = new PeerSnapshot("file-server", MachineType.UBUNTU_SERVER, true, 0, null);
        PeerSnapshot disconnected = new PeerSnapshot("file-server", MachineType.UBUNTU_SERVER, false, 0, null);

        assertThat(connected.notificationSubject()).isEqualTo("[Vaier] file-server is now connected");
        assertThat(disconnected.notificationSubject()).isEqualTo("[Vaier] file-server is now disconnected");
    }

    @Test
    void notificationBody_includesMachineDetailsAndVaierUiLink() {
        PeerSnapshot snapshot = new PeerSnapshot("file-server", MachineType.UBUNTU_SERVER, true,
            1_700_000_000L, "192.168.1.50");

        String body = snapshot.notificationBody("example.com");

        assertThat(body).contains("Machine: file-server");
        assertThat(body).contains("Type: UBUNTU_SERVER");
        assertThat(body).contains("Status: connected");
        assertThat(body).contains("Last handshake: ");
        assertThat(body).contains("LAN address: 192.168.1.50");
        assertThat(body).contains("https://vaier.example.com/explorer.html");
    }

    @Test
    void notificationBody_omitsHandshakeLanAndLinkWhenAbsent() {
        PeerSnapshot snapshot = new PeerSnapshot("laptop", MachineType.WINDOWS_CLIENT, false, 0, null);

        String body = snapshot.notificationBody(null);

        assertThat(body).contains("Machine: laptop");
        assertThat(body).contains("Status: disconnected");
        assertThat(body).doesNotContain("Last handshake");
        assertThat(body).doesNotContain("LAN address");
        assertThat(body).doesNotContain("Vaier UI");
    }
}
