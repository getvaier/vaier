package net.vaier.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VaierHostnamesTest {

    @Test
    void vaierServerFqdn_prependsTheVaierSubdomain() {
        assertThat(new VaierHostnames("example.com").vaierServerFqdn())
            .isEqualTo("vaier.example.com");
    }

    @Test
    void autheliaHost_prependsTheLoginSubdomain() {
        assertThat(new VaierHostnames("example.com").autheliaHost())
            .isEqualTo("login.example.com");
    }
}
