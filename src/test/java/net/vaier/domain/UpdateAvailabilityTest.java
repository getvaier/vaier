package net.vaier.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UpdateAvailabilityTest {

    @Test
    void digestsThatDifferMeanAnUpdateIsAvailable() {
        assertThat(UpdateAvailability.compare("sha256:local", "sha256:registry"))
            .isEqualTo(UpdateAvailability.UPDATE_AVAILABLE);
    }

    @Test
    void identicalDigestsMeanUpToDate() {
        assertThat(UpdateAvailability.compare("sha256:same", "sha256:same"))
            .isEqualTo(UpdateAvailability.UP_TO_DATE);
    }

    @Test
    void anUnresolvableRegistryDigestIsUnknownNeverOutdated() {
        // The registry was unreachable, rate-limited, or does not know the tag. Unknown is not outdated:
        // Vaier must never raise "update available" on the strength of a failed lookup.
        assertThat(UpdateAvailability.compare("sha256:local", null)).isEqualTo(UpdateAvailability.UNKNOWN);
        assertThat(UpdateAvailability.compare("sha256:local", "  ")).isEqualTo(UpdateAvailability.UNKNOWN);
    }

    @Test
    void anUnresolvableLocalDigestIsUnknownNeverUpToDate() {
        // A locally-built image has nothing to compare. It is not up to date — it is unknowable.
        assertThat(UpdateAvailability.compare(null, "sha256:registry")).isEqualTo(UpdateAvailability.UNKNOWN);
        assertThat(UpdateAvailability.compare("", "sha256:registry")).isEqualTo(UpdateAvailability.UNKNOWN);
    }

    @Test
    void onlyUpdateAvailableCountsAsUpdateAvailable() {
        assertThat(UpdateAvailability.UPDATE_AVAILABLE.isUpdateAvailable()).isTrue();
        assertThat(UpdateAvailability.UP_TO_DATE.isUpdateAvailable()).isFalse();
        assertThat(UpdateAvailability.UNKNOWN.isUpdateAvailable()).isFalse();
    }
}
