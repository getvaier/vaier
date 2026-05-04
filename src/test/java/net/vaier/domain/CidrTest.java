package net.vaier.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CidrTest {

    @Test
    void contains_ipInsideSubnet_returnsTrue() {
        Cidr cidr = Cidr.parse("172.20.0.0/16");

        assertThat(cidr.contains("172.20.0.5")).isTrue();
        assertThat(cidr.contains("172.20.255.255")).isTrue();
    }

    @Test
    void contains_ipOutsideSubnet_returnsFalse() {
        Cidr cidr = Cidr.parse("172.20.0.0/16");

        assertThat(cidr.contains("203.0.113.99")).isFalse();
        assertThat(cidr.contains("172.21.0.1")).isFalse();
    }

    @Test
    void contains_handlesPrefixesThatDoNotAlignToByteBoundary() {
        Cidr cidr = Cidr.parse("10.0.0.0/12");

        assertThat(cidr.contains("10.15.255.255")).isTrue();
        assertThat(cidr.contains("10.16.0.0")).isFalse();
    }

    @Test
    void contains_slashZero_matchesEverything() {
        Cidr cidr = Cidr.parse("0.0.0.0/0");

        assertThat(cidr.contains("1.2.3.4")).isTrue();
        assertThat(cidr.contains("255.255.255.255")).isTrue();
    }

    @Test
    void contains_slash32_matchesOnlyExactIp() {
        Cidr cidr = Cidr.parse("192.168.1.1/32");

        assertThat(cidr.contains("192.168.1.1")).isTrue();
        assertThat(cidr.contains("192.168.1.2")).isFalse();
    }

    @Test
    void contains_mismatchedAddressFamilies_returnsFalse() {
        Cidr cidr = Cidr.parse("172.20.0.0/16");

        assertThat(cidr.contains("::1")).isFalse();
    }

    @Test
    void contains_malformedIp_returnsFalse() {
        Cidr cidr = Cidr.parse("172.20.0.0/16");

        assertThat(cidr.contains("not-an-ip")).isFalse();
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
}
