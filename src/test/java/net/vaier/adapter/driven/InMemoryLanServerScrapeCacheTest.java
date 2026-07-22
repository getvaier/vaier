package net.vaier.adapter.driven;

import net.vaier.domain.port.ForDiscoveringLanServerContainers.LanServerContainers;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryLanServerScrapeCacheTest {

    private final InMemoryLanServerScrapeCache cache = new InMemoryLanServerScrapeCache();

    private static LanServerContainers containers(String name, String status) {
        return new LanServerContainers(name, "192.168.3.50", 2375, "apalveien5", status, List.of());
    }

    @Test
    void startsEmpty() {
        assertThat(cache.getLanServerContainers()).isEmpty();
        assertThat(cache.get("nas")).isNull();
    }

    @Test
    void putThenGetAndList() {
        cache.put("nas", containers("nas", "OK"));

        assertThat(cache.get("nas").status()).isEqualTo("OK");
        assertThat(cache.getLanServerContainers()).extracting(LanServerContainers::name)
            .containsExactly("nas");
    }

    @Test
    void retainOnlyDropsUnlistedNames() {
        cache.put("nas", containers("nas", "OK"));
        cache.put("printer", containers("printer", "UNREACHABLE"));

        cache.retainOnly(Set.of("nas"));

        assertThat(cache.getLanServerContainers()).extracting(LanServerContainers::name)
            .containsExactly("nas");
    }
}
