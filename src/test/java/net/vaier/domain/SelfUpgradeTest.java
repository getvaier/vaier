package net.vaier.domain;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Vaier upgrading itself: which container is Vaier, and whether there is anything to do about it.
 *
 * <p>This is the one container Vaier is allowed to pull. {@code ImageUpdateWatcher} states the rule for every
 * other one — detection is read-only, the operator's move is the operator's — and that rule survives here,
 * because upgrading yourself is not the same act as reaching into someone else's machine and restarting their
 * service. Vaier only ever does this to itself.
 */
class SelfUpgradeTest {

    private DockerService container(String name, String image, UpdateAvailability verdict) {
        return new DockerService("cid", name, image, "1.0", List.of(), List.of(), "running",
            "sha256:local", verdict);
    }

    @Test
    void vaierFindsItselfByImage_notByContainerName() {
        // A container called "vaier" on some other machine is not this process, and a compose project renamed
        // by its directory can call this one something else. The image repository is what actually identifies
        // Vaier's own image, and it is the thing the registry is asked about.
        List<DockerService> containers = List.of(
            container("traefik", "traefik:v3.1", UpdateAvailability.UPDATE_AVAILABLE),
            container("vaier-offline", "nginx:1.27-alpine", UpdateAvailability.UP_TO_DATE),
            container("vaier", "getvaier/vaier:latest", UpdateAvailability.UPDATE_AVAILABLE));

        assertThat(SelfUpgrade.findSelf(containers)).isPresent();
        assertThat(SelfUpgrade.findSelf(containers).get().containerName()).isEqualTo("vaier");
    }

    @Test
    void anOfflinePageIsNotVaier() {
        // vaier-offline exists precisely to be up while Vaier is down. Mistaking it for Vaier would have the
        // upgrade recreate the one container that is supposed to survive the upgrade.
        assertThat(SelfUpgrade.findSelf(List.of(
            container("vaier-offline", "nginx:1.27-alpine", UpdateAvailability.UPDATE_AVAILABLE))))
            .isEmpty();
    }

    @Test
    void thereIsSomethingToDo_onlyWhenTheRegistryReallyServesSomethingElse() {
        // UNKNOWN is not a reason to recreate the container Vaier is running inside. The registry being
        // unreachable, or the image being built locally with no registry digest, must never trigger a
        // restart of the fleet's control plane — that would turn a rate limit into an outage.
        assertThat(SelfUpgrade.upgradeAvailable(List.of(
            container("vaier", "getvaier/vaier:latest", UpdateAvailability.UPDATE_AVAILABLE)))).isTrue();
        assertThat(SelfUpgrade.upgradeAvailable(List.of(
            container("vaier", "getvaier/vaier:latest", UpdateAvailability.UNKNOWN)))).isFalse();
        assertThat(SelfUpgrade.upgradeAvailable(List.of(
            container("vaier", "getvaier/vaier:latest", UpdateAvailability.UP_TO_DATE)))).isFalse();
        assertThat(SelfUpgrade.upgradeAvailable(List.of())).isFalse();
    }
}
