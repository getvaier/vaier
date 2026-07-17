package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ImageUpdateRollupTest {

    @Test
    void subjectNamesTheSingleImageWhenOnlyOneWentOutOfDate() {
        ImageUpdateRollup rollup = new ImageUpdateRollup(List.of("vaultwarden/server:latest"));

        assertThat(rollup.subject()).isEqualTo("[Vaier] Update available: vaultwarden/server:latest");
    }

    @Test
    void subjectCountsTheImagesWhenSeveralWentOutOfDateInOneSweep() {
        ImageUpdateRollup rollup = new ImageUpdateRollup(List.of("a:1", "b:1", "c:1"));

        assertThat(rollup.subject()).isEqualTo("[Vaier] Update available: 3 images");
    }

    @Test
    void bodyListsEveryImageAndUsesTheCanonicalTerm() {
        ImageUpdateRollup rollup = new ImageUpdateRollup(List.of("vaultwarden/server:latest", "redis:7.2"));

        String body = rollup.body("example.com");

        assertThat(body).contains("Update available");
        assertThat(body).contains("vaultwarden/server:latest");
        assertThat(body).contains("redis:7.2");
    }

    @Test
    void bodySaysVaierWillNotPullSoTheOperatorKnowsItIsTheirMove() {
        String body = new ImageUpdateRollup(List.of("a:1")).body("example.com");

        assertThat(body).containsIgnoringCase("does not pull");
    }

    @Test
    void bodyLinksTheVaierUiWhenABaseDomainIsConfigured() {
        String body = new ImageUpdateRollup(List.of("a:1")).body("example.com");

        assertThat(body).contains("https://vaier.example.com/");
    }

    @Test
    void bodyOmitsTheLinkWhenNoBaseDomainIsConfigured() {
        assertThat(new ImageUpdateRollup(List.of("a:1")).body(null)).doesNotContain("https://");
        assertThat(new ImageUpdateRollup(List.of("a:1")).body("  ")).doesNotContain("https://");
    }

    @Test
    void aRollupOfNothingIsNotWorthSending() {
        assertThat(new ImageUpdateRollup(List.of()).worthSending()).isFalse();
        assertThat(new ImageUpdateRollup(List.of("a:1")).worthSending()).isTrue();
    }
}
