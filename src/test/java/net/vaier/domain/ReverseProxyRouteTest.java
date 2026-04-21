package net.vaier.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

class ReverseProxyRouteTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t"})
    void validateForPublication_rejectsBlankDnsName(String dnsName) {
        assertThatThrownBy(() -> ReverseProxyRoute.validateForPublication(dnsName, "10.0.0.1", 8080))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dnsName");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void validateForPublication_rejectsBlankAddress(String address) {
        assertThatThrownBy(() -> ReverseProxyRoute.validateForPublication("app.example.com", address, 8080))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("address");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 65536, Integer.MAX_VALUE, Integer.MIN_VALUE})
    void validateForPublication_rejectsOutOfRangePort(int port) {
        assertThatThrownBy(() -> ReverseProxyRoute.validateForPublication("app.example.com", "10.0.0.1", port))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("port");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 80, 443, 8080, 65535})
    void validateForPublication_acceptsValidInputs(int port) {
        assertThatCode(() -> ReverseProxyRoute.validateForPublication("app.example.com", "10.0.0.1", port))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void validateDnsName_rejectsBlank(String dnsName) {
        assertThatThrownBy(() -> ReverseProxyRoute.validateDnsName(dnsName))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dnsName");
    }
}
