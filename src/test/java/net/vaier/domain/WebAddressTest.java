package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The one rule behind the <b>web read</b> (#360): Marvin reads the public internet and nothing else. Every
 * address below is a way the fleet's own network could be reached through a tool the model drives, so each
 * one is pinned here — an address Vaier would follow to 169.254.169.254 or to 10.13.13.6 is the whole feature
 * turned into a way out of the hexagon.
 */
class WebAddressTest {

    @Test
    void itTakesAnOrdinaryPublicAddress() {
        WebAddress address = WebAddress.of("https://www.wireguard.com/quickstart/");

        assertThat(address.value()).isEqualTo("https://www.wireguard.com/quickstart/");
        assertThat(address.host()).isEqualTo("www.wireguard.com");
    }

    @Test
    void itTrimsWhatTheModelSaid() {
        assertThat(WebAddress.of("  https://example.com/a  ").value()).isEqualTo("https://example.com/a");
    }

    @Test
    void itRefusesNothingAtAll() {
        assertThatThrownBy(() -> WebAddress.of("   "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Say which page to read.");
        assertThatThrownBy(() -> WebAddress.of(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void itRefusesAnAddressThatIsNotAFullOne() {
        assertThatThrownBy(() -> WebAddress.of("wireguard.com/quickstart"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Give the page's full address, beginning http:// or https://.");
        assertThatThrownBy(() -> WebAddress.of("/quickstart"))
            .hasMessage("Give the page's full address, beginning http:// or https://.");
        assertThatThrownBy(() -> WebAddress.of("https:///quickstart"))
            .hasMessage("Give the page's full address, beginning http:// or https://.");
    }

    /** A file, a socket or a script is not a web page, and each one of them is a way out of the browser. */
    @Test
    void itRefusesEverySchemeButHttpAndHttps() {
        assertThatThrownBy(() -> WebAddress.of("file:///etc/shadow"))
            .hasMessage("Marvin reads only http and https addresses, not file.");
        assertThatThrownBy(() -> WebAddress.of("ftp://example.com/x")).hasMessageContaining("not ftp");
        assertThatThrownBy(() -> WebAddress.of("javascript:alert(1)")).hasMessageContaining("not javascript");
        assertThatThrownBy(() -> WebAddress.of("gopher://example.com")).hasMessageContaining("not gopher");
    }

    /** A user name in front of a host is how a credential is smuggled into a log, and it reads as the host. */
    @Test
    void itRefusesAnAddressWithAUserNameInIt() {
        assertThatThrownBy(() -> WebAddress.of("https://admin:hunter2@example.com/"))
            .hasMessage("An address with a user name in it is refused; give the page's plain address.");
    }

    @Test
    void itRefusesAnAddressLongerThanTwoThousandAndFortyEightCharacters() {
        String tooLong = "https://example.com/" + "a".repeat(2049);

        assertThatThrownBy(() -> WebAddress.of(tooLong))
            .hasMessage("That address is longer than 2048 characters; Marvin will not follow it.");
        assertThat(WebAddress.of("https://example.com/" + "a".repeat(2048 - 20)).value()).hasSize(2048);
    }

    // --- an address that names an address, rather than a name -------------------------------------------

    /** No lookup can save this one: the fleet's own ranges written out are refused where they are read. */
    @Test
    void itRefusesAnIpLiteralThatIsNotOnThePublicInternet() {
        assertThatThrownBy(() -> WebAddress.of("http://127.0.0.1:8080/"))
            .hasMessage("Marvin reads only the public internet; 127.0.0.1 is not on it.");
        assertThatThrownBy(() -> WebAddress.of("http://169.254.169.254/latest/meta-data/"))
            .hasMessageContaining("169.254.169.254 is not on it");
        assertThatThrownBy(() -> WebAddress.of("http://10.13.13.6:2375/containers/json"))
            .hasMessageContaining("10.13.13.6 is not on it");
        assertThatThrownBy(() -> WebAddress.of("http://192.168.1.113/")).hasMessageContaining("is not on it");
        assertThatThrownBy(() -> WebAddress.of("http://172.31.17.253/")).hasMessageContaining("is not on it");
        assertThatThrownBy(() -> WebAddress.of("http://[::1]/")).hasMessageContaining("is not on it");
    }

    @Test
    void itTakesAnIpLiteralThatIsOnThePublicInternet() {
        assertThat(WebAddress.of("http://93.184.216.34/").host()).isEqualTo("93.184.216.34");
        assertThat(WebAddress.of("https://[2606:2800:220:1:248:1893:25c8:1946]/").host())
            .isEqualTo("[2606:2800:220:1:248:1893:25c8:1946]");
    }

    // --- what counts as the public internet ------------------------------------------------------------

    private static InetAddress ip(String literal) throws UnknownHostException {
        return InetAddress.getByName(literal);
    }

    @Test
    void thePublicInternetIsWhatIsLeftOverAfterEveryPrivateRange() throws UnknownHostException {
        assertThat(WebAddress.isPublic(ip("93.184.216.34"))).isTrue();
        assertThat(WebAddress.isPublic(ip("8.8.8.8"))).isTrue();
        assertThat(WebAddress.isPublic(ip("52.29.74.114"))).isTrue();
        assertThat(WebAddress.isPublic(ip("2606:2800:220:1:248:1893:25c8:1946"))).isTrue();
        assertThat(WebAddress.isPublic(ip("2001:4860:4860::8888"))).isTrue();
    }

    @Test
    void loopbackIsNotPublic() throws UnknownHostException {
        assertThat(WebAddress.isPublic(ip("127.0.0.1"))).isFalse();
        assertThat(WebAddress.isPublic(ip("127.13.13.13"))).isFalse();
        assertThat(WebAddress.isPublic(ip("::1"))).isFalse();
    }

    @Test
    void theUnspecifiedAddressAndTheWholeZeroRangeAreNotPublic() throws UnknownHostException {
        assertThat(WebAddress.isPublic(ip("0.0.0.0"))).isFalse();
        assertThat(WebAddress.isPublic(ip("0.1.2.3"))).isFalse();
        assertThat(WebAddress.isPublic(ip("::"))).isFalse();
    }

    /** 169.254.169.254 is the cloud's own metadata address, and this box is in a cloud. */
    @Test
    void linkLocalIsNotPublic_whichIsWhatKeepsTheMetadataAddressOut() throws UnknownHostException {
        assertThat(WebAddress.isPublic(ip("169.254.169.254"))).isFalse();
        assertThat(WebAddress.isPublic(ip("169.254.0.1"))).isFalse();
        assertThat(WebAddress.isPublic(ip("fe80::1"))).isFalse();
    }

    @Test
    void thePrivateRangesAreNotPublic() throws UnknownHostException {
        assertThat(WebAddress.isPublic(ip("10.13.13.6"))).isFalse();
        assertThat(WebAddress.isPublic(ip("172.16.0.1"))).isFalse();
        assertThat(WebAddress.isPublic(ip("172.31.17.253"))).isFalse();
        assertThat(WebAddress.isPublic(ip("192.168.1.113"))).isFalse();
        assertThat(WebAddress.isPublic(ip("172.15.0.1"))).isTrue();
        assertThat(WebAddress.isPublic(ip("172.32.0.1"))).isTrue();
    }

    /** A carrier's own shared range: the phone's address on a mobile network, and never a site. */
    @Test
    void carrierGradeNatIsNotPublic() throws UnknownHostException {
        assertThat(WebAddress.isPublic(ip("100.64.0.1"))).isFalse();
        assertThat(WebAddress.isPublic(ip("100.127.255.255"))).isFalse();
        assertThat(WebAddress.isPublic(ip("100.63.255.255"))).isTrue();
        assertThat(WebAddress.isPublic(ip("100.128.0.1"))).isTrue();
    }

    @Test
    void multicastAndBroadcastAreNotPublic() throws UnknownHostException {
        assertThat(WebAddress.isPublic(ip("224.0.0.1"))).isFalse();
        assertThat(WebAddress.isPublic(ip("239.255.255.250"))).isFalse();
        assertThat(WebAddress.isPublic(ip("255.255.255.255"))).isFalse();
        assertThat(WebAddress.isPublic(ip("ff02::1"))).isFalse();
    }

    @Test
    void theIpv6PrivateRangeIsNotPublic() throws UnknownHostException {
        assertThat(WebAddress.isPublic(ip("fc00::1"))).isFalse();
        assertThat(WebAddress.isPublic(ip("fd12:3456::1"))).isFalse();
        assertThat(WebAddress.isPublic(ip("fe00::1"))).isTrue();
    }

    /** ::ffff:10.13.13.6 is 10.13.13.6 wearing a v6 hat; the hat decides nothing. */
    @Test
    void anIpv4AddressMappedIntoIpv6IsJudgedByItsIpv4Part() throws UnknownHostException {
        assertThat(WebAddress.isPublic(mapped(127, 0, 0, 1))).isFalse();
        assertThat(WebAddress.isPublic(mapped(10, 13, 13, 6))).isFalse();
        assertThat(WebAddress.isPublic(mapped(169, 254, 169, 254))).isFalse();
        assertThat(WebAddress.isPublic(mapped(93, 184, 216, 34))).isTrue();
    }

    private static InetAddress mapped(int a, int b, int c, int d) throws UnknownHostException {
        byte[] bytes = new byte[16];
        bytes[10] = (byte) 0xFF;
        bytes[11] = (byte) 0xFF;
        bytes[12] = (byte) a;
        bytes[13] = (byte) b;
        bytes[14] = (byte) c;
        bytes[15] = (byte) d;
        return Inet6Address.getByAddress(null, bytes, 0);
    }

    @Test
    void nothingAtAllIsNotPublic() {
        assertThat(WebAddress.isPublic(null)).isFalse();
    }

    // --- what the adapter must ask before it connects ---------------------------------------------------

    @Test
    void requirePublic_isHappyWhenEverythingTheNameResolvedToIsPublic() throws UnknownHostException {
        WebAddress address = WebAddress.of("https://www.wireguard.com/");

        address.requirePublic(List.of(ip("93.184.216.34"), ip("2606:2800:220:1::1")));
    }

    /** One private answer among many is enough: a name that resolves to both is a name aimed at the fleet. */
    @Test
    void requirePublic_refusesWhenAnyOneAnswerIsNotPublic() throws UnknownHostException {
        WebAddress address = WebAddress.of("https://inside.example.com/");

        assertThatThrownBy(() -> address.requirePublic(List.of(ip("93.184.216.34"), ip("10.13.13.6"))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Marvin reads only the public internet; inside.example.com is not on it.");
    }

    /**
     * A name nothing answered for is not a private name — it is a name Vaier could not look up, and saying
     * so is the difference between "this is forbidden" and "this did not work".
     */
    @Test
    void requirePublic_saysANameWasNotResolved_ratherThanCallingItPrivate() {
        WebAddress address = WebAddress.of("https://nothing.example/");

        assertThatThrownBy(() -> address.requirePublic(List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Vaier could not look up nothing.example, so it did not try to reach it.")
            .hasMessageNotContaining("public");
        assertThatThrownBy(() -> address.requirePublic(null))
            .hasMessageContaining("could not look up");
    }

    // --- every redirect is a new address, judged again --------------------------------------------------

    @Test
    void aRedirectTargetIsAnAddressJudgedByTheSameRules() {
        WebAddress here = WebAddress.of("https://example.com/a/b");

        assertThat(here.redirectedTo("/c").value()).isEqualTo("https://example.com/c");
        assertThat(here.redirectedTo("https://elsewhere.example/d").value())
            .isEqualTo("https://elsewhere.example/d");
        assertThatThrownBy(() -> here.redirectedTo("http://169.254.169.254/latest/meta-data/"))
            .hasMessageContaining("169.254.169.254 is not on it");
        assertThatThrownBy(() -> here.redirectedTo("file:///etc/shadow")).hasMessageContaining("not file");
    }

    /** The domain asks the port; the service only hands it in. */
    @Test
    void read_asksThePortForThePageAtThisAddress() {
        WebAddress address = WebAddress.of("https://example.com/a");
        WebPage answered = WebPage.fromText("https://example.com/a", "hello");

        assertThat(address.read(asked -> asked == address ? answered : null)).isSameAs(answered);
    }
}
