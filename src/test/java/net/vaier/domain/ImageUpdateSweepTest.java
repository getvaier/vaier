package net.vaier.domain;

import net.vaier.domain.port.ForResolvingRegistryDigest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ImageUpdateSweepTest {

    /** A fake registry: canonical reference → the digest it serves. Records every reference it was asked. */
    private static class FakeRegistry implements ForResolvingRegistryDigest {
        private final Map<String, String> digests;
        private final List<String> asked = new ArrayList<>();
        private RuntimeException blowUp;

        FakeRegistry(Map<String, String> digests) {
            this.digests = digests;
        }

        @Override
        public Optional<String> resolveDigest(ImageReference reference) {
            asked.add(reference.canonical());
            if (blowUp != null) throw blowUp;
            return Optional.ofNullable(digests.get(reference.canonical()));
        }
    }

    private static DockerService container(String name, String image, String localDigest) {
        return new DockerService("id-" + name, name, image, "v", List.of(), List.of("bridge"), "running",
            localDigest, UpdateAvailability.UNKNOWN);
    }

    @Test
    void flagsAContainerWhoseRegistryServesANewerDigestForItsTag() {
        FakeRegistry registry = new FakeRegistry(
            Map.of("registry-1.docker.io/vaultwarden/server:latest", "sha256:new"));

        Map<String, UpdateAvailability> verdicts = ImageUpdateSweep.sweep(
            List.of(container("vaultwarden", "vaultwarden/server:latest", "sha256:old")), registry);

        assertThat(verdicts).containsEntry("vaultwarden/server:latest", UpdateAvailability.UPDATE_AVAILABLE);
    }

    @Test
    void leavesAContainerOnTheServedDigestUpToDate() {
        FakeRegistry registry = new FakeRegistry(
            Map.of("registry-1.docker.io/vaultwarden/server:latest", "sha256:same"));

        Map<String, UpdateAvailability> verdicts = ImageUpdateSweep.sweep(
            List.of(container("vaultwarden", "vaultwarden/server:latest", "sha256:same")), registry);

        assertThat(verdicts).containsEntry("vaultwarden/server:latest", UpdateAvailability.UP_TO_DATE);
    }

    @Test
    void anUnreachableRegistryLeavesTheImageUnknownNotOutdated() {
        // Registry down, rate-limited, or no egress from the Vaier container: degrade quietly.
        FakeRegistry registry = new FakeRegistry(Map.of());
        registry.blowUp = new RuntimeException("connection refused");

        Map<String, UpdateAvailability> verdicts = ImageUpdateSweep.sweep(
            List.of(container("vaultwarden", "vaultwarden/server:latest", "sha256:old")), registry);

        assertThat(verdicts).containsEntry("vaultwarden/server:latest", UpdateAvailability.UNKNOWN);
    }

    @Test
    void oneRegistryFailureDoesNotAbortTheRestOfTheSweep() {
        FakeRegistry registry = new FakeRegistry(
            Map.of("registry-1.docker.io/library/redis:7.2", "sha256:new")) {
            @Override
            public Optional<String> resolveDigest(ImageReference reference) {
                if (reference.repository().equals("vaultwarden/server")) {
                    throw new RuntimeException("rate limited");
                }
                return super.resolveDigest(reference);
            }
        };

        Map<String, UpdateAvailability> verdicts = ImageUpdateSweep.sweep(List.of(
            container("vaultwarden", "vaultwarden/server:latest", "sha256:old"),
            container("redis", "redis:7.2", "sha256:old")), registry);

        assertThat(verdicts).containsEntry("vaultwarden/server:latest", UpdateAvailability.UNKNOWN);
        assertThat(verdicts).containsEntry("redis:7.2", UpdateAvailability.UPDATE_AVAILABLE);
    }

    @Test
    void aTagTheRegistryDoesNotServeIsUnknown() {
        Map<String, UpdateAvailability> verdicts = ImageUpdateSweep.sweep(
            List.of(container("app", "vaultwarden/server:latest", "sha256:old")), new FakeRegistry(Map.of()));

        assertThat(verdicts).containsEntry("vaultwarden/server:latest", UpdateAvailability.UNKNOWN);
    }

    @Test
    void aLocallyBuiltImageIsUnknownAndTheRegistryIsNeverAsked() {
        FakeRegistry registry = new FakeRegistry(Map.of());

        Map<String, UpdateAvailability> verdicts = ImageUpdateSweep.sweep(
            List.of(container("built", "my-local-build:latest", null)), registry);

        assertThat(verdicts).containsEntry("my-local-build:latest", UpdateAvailability.UNKNOWN);
        assertThat(registry.asked).isEmpty();
    }

    @Test
    void aDigestPinnedImageIsUnknownAndTheRegistryIsNeverAsked() {
        FakeRegistry registry = new FakeRegistry(Map.of());

        Map<String, UpdateAvailability> verdicts = ImageUpdateSweep.sweep(
            List.of(container("pinned", "vaultwarden/server@sha256:abc", "sha256:abc")), registry);

        assertThat(verdicts).containsEntry("vaultwarden/server@sha256:abc", UpdateAvailability.UNKNOWN);
        assertThat(registry.asked).isEmpty();
    }

    @Test
    void asksTheRegistryOncePerDistinctImageNoMatterHowManyContainersRunIt() {
        // Registry manifest requests are rate-limited; ten containers of one image is one question.
        FakeRegistry registry = new FakeRegistry(
            Map.of("registry-1.docker.io/library/redis:7.2", "sha256:new"));

        ImageUpdateSweep.sweep(List.of(
            container("redis-a", "redis:7.2", "sha256:old"),
            container("redis-b", "redis:7.2", "sha256:old"),
            container("redis-c", "redis:7.2", "sha256:old")), registry);

        assertThat(registry.asked).containsExactly("registry-1.docker.io/library/redis:7.2");
    }

    @Test
    void skipsContainersThatAreNotRunning() {
        // A stopped container's image is not serving anything; telling the operator to pull it is noise.
        FakeRegistry registry = new FakeRegistry(
            Map.of("registry-1.docker.io/library/redis:7.2", "sha256:new"));

        Map<String, UpdateAvailability> verdicts = ImageUpdateSweep.sweep(List.of(
            new DockerService("id", "redis", "redis:7.2", "v", List.of(), List.of("bridge"), "exited",
                "sha256:old", UpdateAvailability.UNKNOWN)), registry);

        assertThat(verdicts).isEmpty();
        assertThat(registry.asked).isEmpty();
    }

    @Test
    void anEmptyScrapeAsksNothingAndDecidesNothing() {
        FakeRegistry registry = new FakeRegistry(Map.of());

        assertThat(ImageUpdateSweep.sweep(List.of(), registry)).isEmpty();
        assertThat(registry.asked).isEmpty();
    }
}
