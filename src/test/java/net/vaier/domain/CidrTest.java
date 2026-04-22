package net.vaier.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
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
}
