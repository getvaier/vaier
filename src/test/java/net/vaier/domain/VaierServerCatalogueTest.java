package net.vaier.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VaierServerCatalogueTest {

    @Test
    void isExcluded_coversVaiersOwnInfrastructureContainers() {
        assertThat(VaierServerCatalogue.isExcluded("wireguard")).isTrue();
        assertThat(VaierServerCatalogue.isExcluded("authelia")).isTrue();
        assertThat(VaierServerCatalogue.isExcluded("vaier")).isTrue();
        assertThat(VaierServerCatalogue.isExcluded("redis")).isTrue();
    }

    @Test
    void isExcluded_isCaseInsensitiveAndFalseForOrdinaryContainers() {
        assertThat(VaierServerCatalogue.isExcluded("WireGuard")).isTrue();
        assertThat(VaierServerCatalogue.isExcluded("grafana")).isFalse();
    }

    @Test
    void isPublishablePort_restrictsKnownServicesToTheirListedPorts() {
        assertThat(VaierServerCatalogue.isPublishablePort("traefik", 8080)).isTrue();
        assertThat(VaierServerCatalogue.isPublishablePort("traefik", 80)).isFalse();
    }

    @Test
    void isPublishablePort_allowsEveryPortOfAnUnknownContainer() {
        assertThat(VaierServerCatalogue.isPublishablePort("grafana", 3000)).isTrue();
    }

    @Test
    void rootRedirectPath_returnsTheKnownPathOrNull() {
        assertThat(VaierServerCatalogue.rootRedirectPath("traefik")).isEqualTo("/dashboard/");
        assertThat(VaierServerCatalogue.rootRedirectPath("grafana")).isNull();
    }
}
