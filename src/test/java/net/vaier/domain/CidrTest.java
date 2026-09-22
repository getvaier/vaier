package net.vaier.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CidrTest {

    @Test
    void contains_matchesAddressAgainstCidr() {
        record Row(String description, String cidr, String address, boolean expected) {}
        List<Row> rows = List.of(
            new Row("ip inside subnet", "172.20.0.0/16", "172.20.0.5", true),
            new Row("ip inside subnet at the top of the range", "172.20.0.0/16", "172.20.255.255", true),
            new Row("ip outside subnet", "172.20.0.0/16", "203.0.113.99", false),
            new Row("ip outside subnet, adjacent network", "172.20.0.0/16", "172.21.0.1", false),
            new Row("handles prefixes not aligned to a byte boundary, inside", "10.0.0.0/12", "10.15.255.255", true),
            new Row("handles prefixes not aligned to a byte boundary, outside", "10.0.0.0/12", "10.16.0.0", false),
            new Row("/0 matches everything, low end", "0.0.0.0/0", "1.2.3.4", true),
            new Row("/0 matches everything, high end", "0.0.0.0/0", "255.255.255.255", true),
            new Row("/32 matches only the exact ip", "192.168.1.1/32", "192.168.1.1", true),
            new Row("/32 rejects a neighbouring ip", "192.168.1.1/32", "192.168.1.2", false),
            new Row("mismatched address family returns false", "172.20.0.0/16", "::1", false),
            new Row("malformed ip returns false", "172.20.0.0/16", "not-an-ip", false),
            // A container name or a partial/over-range dotted string must be rejected as a
            // strict IPv4 literal — never resolved via DNS.
            new Row("non-literal address, container name", "192.168.1.0/24", "my-container", false),
            new Row("non-literal address, incomplete dotted quad", "192.168.1.0/24", "192.168.1", false),
            new Row("non-literal address, out-of-range octet", "192.168.1.0/24", "192.168.1.999", false),
            new Row("non-literal address, zero-padded octets", "192.168.1.0/24", "192.168.001.050", false),
            new Row("non-literal address, null", "192.168.1.0/24", null, false)
        );

        for (Row row : rows) {
            Cidr cidr = Cidr.parse(row.cidr());
            assertThat(cidr.contains(row.address())).as(row.description()).isEqualTo(row.expected());
        }
    }

    @Test
    void isIpv4_trueOnlyForStrictDottedQuads() {
        record Row(String description, String input, boolean expected) {}
        List<Row> rows = List.of(
            new Row("plain dotted quad", "10.13.13.2", true),
            new Row("max dotted quad", "255.255.255.255", true),
            new Row("all-zero dotted quad", "0.0.0.0", true),
            new Row("hostname is not a literal", "my-peer", false),
            new Row("incomplete dotted quad is not a literal", "10.13.13", false),
            new Row("out-of-range octet is not a literal", "256.0.0.1", false),
            new Row("cidr suffix disqualifies it as a bare literal", "10.13.13.2/32", false),
            new Row("null is not a literal", null, false)
        );

        for (Row row : rows) {
            assertThat(Cidr.isIpv4(row.input())).as(row.description()).isEqualTo(row.expected());
        }
    }

    @Test
    void parse_malformedCidr_throws() {
        assertThatThrownBy(() -> Cidr.parse("not-a-cidr"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // --- validateLanCidr (#195) — strict IPv4 boundary check used at the controller boundary ---

    @ParameterizedTest
    @ValueSource(strings = {
        "10.0.0.0/8",
        "192.168.1.0/24",
        "192.168.3.0/24",
        "172.16.0.0/12",
        "0.0.0.0/0",
        "255.255.255.255/32",
        "10.13.13.0/24"
    })
    void validateLanCidr_acceptsWellFormedIpv4Cidr(String cidr) {
        assertThatCode(() -> Cidr.validateLanCidr(cidr)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "1.2.3.0/24; id",
        "1.2.3.0/24 | id",
        "1.2.3.0/24`id`",
        "1.2.3.0/24$(id)",
        "1.2.3.0/24\nid",
        "1.2.3.0/24 && id",
        "1.2.3.0/24'",
        "1.2.3.0/24\"",
        "1.2.3.0/24 ",
        " 1.2.3.0/24"
    })
    void validateLanCidr_rejectsShellMetacharacters(String cidr) {
        assertThatThrownBy(() -> Cidr.validateLanCidr(cidr))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("lanCidr");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "256.0.0.0/24",
        "1.2.3.4/33",
        "1.2.3/24",
        "1.2.3.4",
        "1.2.3.4/",
        "/24",
        "::1/128",
        "fe80::/10",
        "example.com/24",
        "01.02.03.04/24",
        "1.2.3.4/-1",
        "1.2.3.4/24/24"
    })
    void validateLanCidr_rejectsMalformed(String cidr) {
        assertThatThrownBy(() -> Cidr.validateLanCidr(cidr))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("lanCidr");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t"})
    void validateLanCidr_rejectsNullOrBlank(String cidr) {
        assertThatThrownBy(() -> Cidr.validateLanCidr(cidr))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("lanCidr");
    }

    // --- the network an address sits on ---

    @Test
    void networkOf_masksTheHostBitsOffAnAddress() {
        // "192.168.1.10/24" is an address on a network; "192.168.1.0/24" is the network. Only the second
        // is a lanCidr, so the arithmetic that turns one into the other lives here with the rest of it.
        assertThat(Cidr.networkOf("192.168.1.10", 24)).isEqualTo("192.168.1.0/24");
        assertThat(Cidr.networkOf("172.31.37.204", 20)).isEqualTo("172.31.32.0/20");
        assertThat(Cidr.networkOf("10.13.13.3", 32)).isEqualTo("10.13.13.3/32");
        assertThat(Cidr.networkOf("10.4.9.7", 0)).isEqualTo("0.0.0.0/0");
    }

    @Test
    void networkOf_refusesAnythingThatIsNotAnAddressAndAPrefix() {
        assertThat(Cidr.networkOf("not-an-address", 24)).isNull();
        assertThat(Cidr.networkOf(null, 24)).isNull();
        assertThat(Cidr.networkOf("192.168.1.10", 33)).isNull();
        assertThat(Cidr.networkOf("192.168.1.10", -1)).isNull();
    }
}
