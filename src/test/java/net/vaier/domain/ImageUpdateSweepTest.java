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
        /** References asked the forced way — the ones a remembered answer is not allowed to satisfy. */
        private final List<String> askedNow = new ArrayList<>();
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

        @Override
        public Optional<String> resolveDigestNow(ImageReference reference) {
            askedNow.add(reference.canonical());
            return resolveDigest(reference);
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

    // --- #57 slice 3: the sweep the operator asked for -------------------------------------------------

    @Test
    void theForcedSweep_insistsOnAFreshRegistryAnswerForEveryImage() {
        // The daily sweep may be answered from a remembered digest; a check the operator asked for may not.
        // They pull precisely BECAUSE Vaier said an update existed, so the remembered answer is the one thing
        // guaranteed to be capable of being wrong here — see sweepFresh's own note.
        FakeRegistry registry = new FakeRegistry(
            Map.of("registry-1.docker.io/vaultwarden/server:latest", "sha256:same"));

        ImageUpdateSweep.sweepFresh(
            List.of(container("vaultwarden", "vaultwarden/server:latest", "sha256:same")), registry);

        assertThat(registry.askedNow).containsExactly("registry-1.docker.io/vaultwarden/server:latest");
    }

    @Test
    void theForcedSweep_findsTheJustPulledImageUpToDate_ratherThanReportingItAgain() {
        // The inversion, end to end in the domain: the operator pulled, so local is now the digest the
        // registry serves. A sweep that asked freshly agrees with them. (The adapter's own test proves the
        // cache cannot answer here; this proves the verdict that follows.)
        FakeRegistry registry = new FakeRegistry(
            Map.of("registry-1.docker.io/vaultwarden/server:latest", "sha256:Y"));

        Map<String, UpdateAvailability> verdicts = ImageUpdateSweep.sweepFresh(
            List.of(container("vaultwarden", "vaultwarden/server:latest", "sha256:Y")), registry);

        assertThat(verdicts).containsEntry("vaultwarden/server:latest", UpdateAvailability.UP_TO_DATE);
    }

    @Test
    void theForcedSweep_isTotalTheSameWayTheDailyOneIs() {
        // A button that 500s because a registry is down would be a worse lie than the mark it is fixing.
        FakeRegistry registry = new FakeRegistry(Map.of());
        registry.blowUp = new RuntimeException("rate limited");

        Map<String, UpdateAvailability> verdicts = ImageUpdateSweep.sweepFresh(
            List.of(container("vaultwarden", "vaultwarden/server:latest", "sha256:old")), registry);

        assertThat(verdicts).containsEntry("vaultwarden/server:latest", UpdateAvailability.UNKNOWN);
    }

    @Test
    void theForcedSweep_stillAsksOncePerDistinctImage() {
        // Forcing bypasses the cache, so this is the only thing left standing between an impatient click and
        // the anonymous rate limit. It matters more here than on the daily path, not less.
        FakeRegistry registry = new FakeRegistry(
            Map.of("registry-1.docker.io/library/redis:7.2", "sha256:new"));

        ImageUpdateSweep.sweepFresh(List.of(
            container("redis-a", "redis:7.2", "sha256:old"),
            container("redis-b", "redis:7.2", "sha256:old"),
            container("redis-c", "redis:7.2", "sha256:old")), registry);

        assertThat(registry.askedNow).containsExactly("registry-1.docker.io/library/redis:7.2");
    }

    @Test
    void theForcedSweep_neverAsksAboutAnImageThatCannotDrift() {
        // Same rule as the daily sweep: a locally built image has no registry to ask, and asking anyway would
        // spend the rate limit on a question with no answer.
        FakeRegistry registry = new FakeRegistry(Map.of());

        Map<String, UpdateAvailability> verdicts = ImageUpdateSweep.sweepFresh(List.of(
            container("built", "my-local-build:latest", null),
            container("pinned", "vaultwarden/server@sha256:abc", "sha256:abc")), registry);

        assertThat(verdicts).containsEntry("my-local-build:latest", UpdateAvailability.UNKNOWN);
        assertThat(verdicts).containsEntry("vaultwarden/server@sha256:abc", UpdateAvailability.UNKNOWN);
        assertThat(registry.askedNow).isEmpty();
        assertThat(registry.asked).isEmpty();
    }
}
