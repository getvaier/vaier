package net.vaier.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BorgServerImageTest {

    @Test
    void expected_isThePinnedBorgOneImage() {
        assertThat(BorgServerImage.EXPECTED).isEqualTo("horaceworblehat/borg-server:2.8.6");
    }

    @Test
    void expected_isAPinnedRepoTag_neverLatest() {
        assertThat(BorgServerImage.EXPECTED).doesNotEndWith(":latest");
        // A concrete repo:tag pin: exactly one repo part and one non-blank tag part.
        String[] parts = BorgServerImage.EXPECTED.split(":");
        assertThat(parts).hasSize(2);
        assertThat(parts[1]).isNotBlank();
        assertThat(BorgServerImage.isPinned()).isTrue();
    }

    @Test
    void isFloatingTag_trueForLatestAndUntagged_falseForAConcreteTag() {
        assertThat(BorgServerImage.isFloatingTag("horaceworblehat/borg-server:latest")).isTrue();
        assertThat(BorgServerImage.isFloatingTag("horaceworblehat/borg-server")).isTrue();
        assertThat(BorgServerImage.isFloatingTag(null)).isTrue();
        assertThat(BorgServerImage.isFloatingTag(BorgServerImage.EXPECTED)).isFalse();
    }

    @Test
    void borgVersion_isTheBorgShippedByThePinnedTag() {
        // With a forced borg-serve command Vaier cannot ask a managed server its version, so it derives it
        // from the pin: the 2.8.6 tag ships borg 1.4.3 (verified by running the image).
        assertThat(BorgServerImage.borgVersion()).isEqualTo(new BorgVersion(1, 4, 3));
    }

    @Test
    void borgVersion_isTiedToThePinnedTag_soMovingThePinForcesUpdatingTheVersion() {
        // Pin-drift guard: the derived borg version is only honest while the pin is unchanged. If someone
        // bumps EXPECTED without re-verifying and updating borgVersion(), this test fails on purpose — the
        // two must always move together.
        assertThat(BorgServerImage.EXPECTED).isEqualTo("horaceworblehat/borg-server:2.8.6");
        assertThat(BorgServerImage.borgVersion()).isEqualTo(new BorgVersion(1, 4, 3));
    }
}
