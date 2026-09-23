package net.vaier.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The first-run password is what dex-init mints when no identity provider is configured, and the
 * banner is how the operator learns it: from `docker compose logs vaier`, in one block that says who,
 * what and where.
 */
class FirstRunPasswordTest {

    @Test
    void banner_saysWhoSignsInWithWhatAndWhere_inOneBorderedBlock() {
        String banner = new FirstRunPassword("you@example.com", "s3cret-s3cret-s3cret").banner("example.com");

        assertThat(banner)
            .contains("https://vaier.example.com")
            .contains("you@example.com")
            .contains("s3cret-s3cret-s3cret")
            .contains("first-run password");
        assertThat(banner.lines().filter(l -> l.startsWith("====")).count())
            .as("a border above and below, so the block is found in a scrolling log").isEqualTo(2);
    }
}
