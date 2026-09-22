package net.vaier.adapter.driven;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.vaier.domain.port.ForExecutingInContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

class WireGuardVpnAdapterTest {

    private static final String DUMP_HEADER = "srvpriv\tsrvpub\t51820\toff\n";
    private static final String OTHER_PEER = "otherkey\t(none)\t1.2.3.4:1\t10.13.13.9/32\t0\t0\t0\toff\n";
    private static final String RUTEN = "rutenkey\t(none)\t1.2.3.4:2\t10.13.13.8/32\t0\t0\t0\toff\n";

    @TempDir
    Path configDir;

    ForExecutingInContainer exec;
    MutableClock clock;
    WireGuardVpnAdapter adapter;

    /** The house pattern for a clock a test can step (see {@code RegistryV2ImageAdapterTest}). */
    private static class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-22T18:00:00Z");

        void advance(Duration by) { now = now.plus(by); }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @BeforeEach
    void setUp() throws IOException {
        exec = mock(ForExecutingInContainer.class);
        clock = new MutableClock();
        adapter = new WireGuardVpnAdapter(exec, clock);
        ReflectionTestUtils.setField(adapter, "wireguardConfigPath", configDir.toString());
        ReflectionTestUtils.setField(adapter, "wireguardContainerName", "wireguard");
        ReflectionTestUtils.setField(adapter, "wireguardInterface", "wg0");
        Path dir = Files.createDirectory(configDir.resolve("Ruten"));
        Files.writeString(dir.resolve("Ruten.conf"), "[Interface]\nAddress = 10.13.13.8/32\n");
        Files.writeString(dir.resolve("Ruten.conf.viewed"), "");
    }

    @Test
    void theTunnelAndTheServersKey_areReadOnceForEveryoneInsideAStatsTick_andAgainAfterItChangesTheTunnel() {
        // Twenty-two callers read the tunnel — the machine list alone walks it on every call, and one
        // page load asks for the machine list a dozen times, each a Docker exec into the wireguard
        // container. The peer-stats tick already reads it every ten seconds for the stream, so inside
        // that window every caller gets the tick's answer and the tunnel is asked exactly once.
        when(exec.execute("wireguard", "wg", "show", "interfaces")).thenReturn("wg0\n");
        when(exec.execute("wireguard", "wg", "show", "wg0", "dump")).thenReturn(DUMP_HEADER + RUTEN);
        // The server's own key rides the same read: the peer list renders every peer's config against it,
        // and it used to be one more exec per request.
        when(exec.execute("wireguard", "wg", "show", "wg0", "public-key")).thenReturn("srvpub\n");

        assertThat(adapter.getClients()).hasSize(1);
        assertThat(adapter.getClients()).hasSize(1);
        assertThat(adapter.getServerPublicKey()).isEqualTo("srvpub");
        verify(exec, times(1)).execute("wireguard", "wg", "show", "wg0", "dump");
        verify(exec, times(1)).execute("wireguard", "wg", "show", "wg0", "public-key");

        // The next tick reads again.
        clock.advance(WireGuardVpnAdapter.TUNNEL_READ_MEMO);
        adapter.getServerPublicKey();
        verify(exec, times(2)).execute("wireguard", "wg", "show", "wg0", "dump");
        verify(exec, times(2)).execute("wireguard", "wg", "show", "wg0", "public-key");

        // ...and so does the first read after this adapter itself changed the tunnel: a peer that was
        // just removed must not linger in anyone's fleet for the rest of the tick. (deletePeer's own
        // dump read to find the key is the third exec; the read after it is the fourth.)
        adapter.deletePeer("Ruten");
        adapter.getClients();
        verify(exec, times(4)).execute("wireguard", "wg", "show", "wg0", "dump");
    }

    @Test
    void deletePeer_thePeerTheInterfaceHasAlreadyForgotten_isStillRemovedFromDisk() {
        // The live orphan: wg0 no longer had the phone, so the old code threw "not found in WireGuard
        // interface" and left the directory — which the fleet page then could not delete either.
        when(exec.execute(eq("wireguard"), eq("wg"), eq("show"), eq("wg0"), eq("dump")))
            .thenReturn(DUMP_HEADER + OTHER_PEER);

        adapter.deletePeer("Ruten");

        assertThat(configDir.resolve("Ruten")).doesNotExist();
        verify(exec, never()).execute(eq("wireguard"), eq("wg"), eq("set"), any(), any(), any(), any());
        verify(exec, never()).execute(eq("wireguard"), eq("wg-quick"), eq("save"), any());
        // Bring-up may have left its host route behind; that goes too.
        verify(exec).execute("wireguard", "ip", "route", "del", "10.13.13.8/32", "dev", "wg0");
    }

    @Test
    void deletePeer_dropsTheRoutesThePeerHad_lanIncluded() {
        when(exec.execute(eq("wireguard"), eq("wg"), eq("show"), eq("wg0"), eq("dump")))
            .thenReturn(DUMP_HEADER + OTHER_PEER
                + "rutenkey\t(none)\t1.2.3.4:2\t10.13.13.8/32,192.168.9.0/24\t0\t0\t0\toff\n");

        adapter.deletePeer("Ruten");

        verify(exec).execute("wireguard", "ip", "route", "del", "10.13.13.8/32", "dev", "wg0");
        verify(exec).execute("wireguard", "ip", "route", "del", "192.168.9.0/24", "dev", "wg0");
    }

    @Test
    void installRoutes_givesANewPeerItsHostRoute_andARelayItsLan_withoutTouchingTheInterface() {
        // wg set installs no route; this is what the restart-on-every-add used to do for the whole fleet.
        adapter.installRoutes("10.13.13.8/32, 192.168.9.0/24");

        verify(exec).execute("wireguard", "ip", "route", "replace", "10.13.13.8/32", "dev", "wg0");
        verify(exec).execute("wireguard", "ip", "route", "replace", "192.168.9.0/24", "dev", "wg0");
        verify(exec, never()).execute(eq("wireguard"), eq("wg-quick"), any(), any());
    }

    @Test
    void deletePeer_aPeerTheInterfaceKnows_isRemovedThereAndSavedBeforeItsDirectoryGoes() {
        when(exec.execute(eq("wireguard"), eq("wg"), eq("show"), eq("wg0"), eq("dump")))
            .thenReturn(DUMP_HEADER + OTHER_PEER + RUTEN);

        adapter.deletePeer("Ruten");

        verify(exec).execute("wireguard", "wg", "set", "wg0", "peer", "rutenkey", "remove");
        verify(exec).execute("wireguard", "wg-quick", "save", "wg0");
        assertThat(configDir.resolve("Ruten")).doesNotExist();
    }
}
