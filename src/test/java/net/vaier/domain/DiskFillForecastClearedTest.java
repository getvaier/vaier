package net.vaier.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DiskFillForecastClearedTest {

    @Test
    void clearedSubject_namesMachine() {
        assertThat(new DiskFillForecastCleared("nas", 62).clearedSubject()).contains("nas");
    }

    @Test
    void clearedBody_namesMachineAndCurrentPercent_withUiLink() {
        String body = new DiskFillForecastCleared("nas", 62).clearedBody("example.com");

        assertThat(body).contains("nas").contains("62%").contains("https://");
    }

    @Test
    void clearedBody_omitsUiLink_whenBaseDomainBlank() {
        String body = new DiskFillForecastCleared("nas", 62).clearedBody("  ");

        assertThat(body).doesNotContain("https://");
    }
}
