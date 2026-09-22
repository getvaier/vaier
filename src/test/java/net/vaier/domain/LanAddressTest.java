package net.vaier.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A LAN address is a dotted-quad IPv4 literal or nothing at all. The local path a launchpad tile takes on the
 * home network is {@code http://<lanAddress>:<port>}, and that survives HSTS only because a browser never notes
 * an IP literal as an HSTS host (RFC 6797 §8.1.1). A hostname under the base domain here would break for the
 * whole max-age with no server-side fix — so the invariant is enforced at the boundary, not assumed (#342).
 */
class LanAddressTest {

    @Test
    void validate_acceptsADottedQuad_orABlankThatClearsTheAddress() {
        for (String ok : new String[] { "192.168.3.121", "10.0.0.1", null, "", "   " }) {
            assertThatCode(() -> LanAddress.validate(ok)).as(String.valueOf(ok)).doesNotThrowAnyException();
        }
    }

    @Test
    void validate_rejectsHostnames_andAnythingThatIsNotExactlyFourOctets() {
        // InetAddress.getByName would happily resolve a hostname — and do a DNS lookup on a request path to do it.
        assertThatThrownBy(() -> LanAddress.validate("nas.home.example.com"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("IPv4").hasMessageContaining("nas.home.example.com");
        for (String bad : new String[] { "localhost", "192.168.3", "192.168.3.121.1", "192.168.3.256", "192.168.03.1",
                                         " 192.168.3.1", "192.168.3.1:80", "::1", "192.168.3.0/24" }) {
            assertThatThrownBy(() -> LanAddress.validate(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
