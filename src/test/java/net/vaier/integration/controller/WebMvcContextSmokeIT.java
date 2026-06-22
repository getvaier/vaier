package net.vaier.integration.controller;

import net.vaier.integration.base.VaierWebMvcIntegrationBase;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Canary for the shared {@code @WebMvcTest} context. Every controller IT depends on
 * {@link VaierWebMvcIntegrationBase} loading cleanly; this fails fast and unmistakably if a
 * newly-added web bean dependency is missing a {@code @MockBean} in the base. The whole
 * controller IT suite had silently rotted that way once (#276) — and because {@code *IT}
 * now runs under Surefire in {@code mvn test}, this canary actually guards the build.
 */
class WebMvcContextSmokeIT extends VaierWebMvcIntegrationBase {

    @Test
    void webMvcContextLoads() {
        assertThat(mockMvc).isNotNull();
    }
}
