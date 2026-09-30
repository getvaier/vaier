package net.vaier.domain;

import net.vaier.domain.DockerService.PortMapping;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ContainerUpdateEligibilityTest {

    private static final Map<String, String> COMPOSE_LABELS = Map.of(
        "com.docker.compose.project", "apps",
        "com.docker.compose.service", "app",
        "com.docker.compose.project.config_files", "/srv/apps/docker-compose.yml",
        "com.docker.compose.project.working_dir", "/srv/apps");

    private static DockerService container(String name, Map<String, String> labels) {
        return DockerService.builder()
            .containerId("id-" + name)
            .containerName(name)
            .image(name + ":latest")
            .version("v")
            .ports(List.of(new PortMapping(80, 8080, "tcp", "0.0.0.0")))
            .networks(List.of("bridge"))
            .state("running")
            .composeCoordinates(ComposeCoordinates.fromLabels(labels).orElse(null))
            .build();
    }

    private static ContainerUpdateEligibility onVaierServer(DockerService container) {
        return onVaierServer(container, DockerCommandAccess.GRANTED);
    }

    private static ContainerUpdateEligibility onVaierServer(DockerService container, DockerCommandAccess access) {
        return ContainerUpdateEligibility.judgeVaierServerContainers(List.of(container), access)
            .get(0).updateEligibility();
    }

    private static ContainerUpdateEligibility onOperatorMachine(DockerService container) {
        return onOperatorMachine(container, DockerCommandAccess.GRANTED);
    }

    private static ContainerUpdateEligibility onOperatorMachine(DockerService container, DockerCommandAccess access) {
        return ContainerUpdateEligibility.judgeOperatorContainers(List.of(container), access)
            .get(0).updateEligibility();
    }

    @Test
    void aComposeManagedContainerOfTheOperatorsIsUpdatable() {
        assertThat(onOperatorMachine(container("vaultwarden", COMPOSE_LABELS)))
            .isEqualTo(ContainerUpdateEligibility.UPDATABLE);
        assertThat(onVaierServer(container("vaultwarden", COMPOSE_LABELS)))
            .isEqualTo(ContainerUpdateEligibility.UPDATABLE);
    }

    // --- the machine's own Docker access (#352) ---

    @Test
    void aContainerOnAMachineWhoseDockerVaierCannotDriveIsNotUpdatable() {
        // Colina 27: the scrape reads Docker's API over the tunnel and needs no group, so the machine looks
        // healthy while every `docker compose` Vaier would run there dies on permission denied. Withhold
        // the button rather than let five updates fail one after another.
        assertThat(onOperatorMachine(container("netdata", COMPOSE_LABELS), DockerCommandAccess.REFUSED))
            .isEqualTo(ContainerUpdateEligibility.NO_DOCKER_ACCESS);
        assertThat(onVaierServer(container("mealie", COMPOSE_LABELS), DockerCommandAccess.REFUSED))
            .isEqualTo(ContainerUpdateEligibility.NO_DOCKER_ACCESS);
    }

    @Test
    void aMachineNobodyHasSweptYetKeepsTheButton_becauseUnknownIsNotNo() {
        // Deliberately the OPPOSITE of ContainerUpdate's "no verdict is never permission" for compose
        // coordinates, and the difference is the evidence. Missing coordinates mean Vaier genuinely does
        // not know how to recreate the container — a fact about the container that will not improve by
        // asking again. UNKNOWN Docker access means nobody has looked yet, which is the state of the whole
        // fleet for the first minutes after a restart. Withholding on it would make the feature dead by
        // default, and the runtime diagnostic is the backstop if the attempt does fail.
        assertThat(onOperatorMachine(container("netdata", COMPOSE_LABELS), DockerCommandAccess.UNKNOWN))
            .isEqualTo(ContainerUpdateEligibility.UPDATABLE);
    }

    @Test
    void aContainersOwnPermanentBlockerOutranksTheMachines() {
        // A hand-started container stays not-compose-managed even where Docker is out of reach: fixing the
        // docker group would not make it updatable, and the reason shown should be the one that lasts.
        assertThat(onOperatorMachine(container("openhab", Map.of()), DockerCommandAccess.REFUSED))
            .isEqualTo(ContainerUpdateEligibility.NOT_COMPOSE_MANAGED);
    }

    @Test
    void vaiersOwnStackOutranksEverything_becauseTheReasonIsNotAboutTheHostAtAll() {
        assertThat(onVaierServer(container("wireguard", COMPOSE_LABELS), DockerCommandAccess.REFUSED))
            .isEqualTo(ContainerUpdateEligibility.VAIER_OWN_STACK);
    }

    @Test
    void theRefusalNamesTheRemedy_notOnlyTheFault() {
        // Same instinct as the terminal's "Clear pinned key" on a host-key mismatch: say what to do. It
        // stays generic about WHICH user — the Explorer knows which user Vaier acts as on each machine.
        assertThat(ContainerUpdateEligibility.NO_DOCKER_ACCESS.refusal("netdata"))
            .contains("netdata")
            .contains("docker group");
    }

    @Test
    void aContainerWithoutComposeCoordinatesIsNotUpdatable() {
        // Started with plain `docker run`: Vaier cannot recreate it without knowing how it was started,
        // and a recreate that silently drops config is worse than no button.
        assertThat(onOperatorMachine(container("openhab", Map.of())))
            .isEqualTo(ContainerUpdateEligibility.NOT_COMPOSE_MANAGED);
    }

    @Test
    void vaiersOwnStackOnTheVaierServerIsNotUpdatable() {
        // Version-pinned by a Vaier release; a per-container button would be a second, conflicting
        // update path for the same images.
        assertThat(onVaierServer(container("wireguard", COMPOSE_LABELS)))
            .isEqualTo(ContainerUpdateEligibility.VAIER_OWN_STACK);
        assertThat(onVaierServer(container("vaier", COMPOSE_LABELS)))
            .isEqualTo(ContainerUpdateEligibility.VAIER_OWN_STACK);
    }

    @Test
    void anOfferedContainerOfVaiersOwnStackIsStillVaiersOwn() {
        // Traefik is offered for publishing rather than excluded — offered says nothing about whose
        // container it is, and it is still pinned by a Vaier release.
        assertThat(onVaierServer(container("traefik", COMPOSE_LABELS)))
            .isEqualTo(ContainerUpdateEligibility.VAIER_OWN_STACK);
    }

    @Test
    void thatVerdictIsScopedToTheVaierServerAlone() {
        // The catalogue is about containers running on the Vaier server. A peer or LAN server running its
        // OWN traefik or redis is the operator's container, and theirs to update.
        assertThat(onOperatorMachine(container("traefik", COMPOSE_LABELS)))
            .isEqualTo(ContainerUpdateEligibility.UPDATABLE);
        assertThat(onOperatorMachine(container("redis", COMPOSE_LABELS)))
            .isEqualTo(ContainerUpdateEligibility.UPDATABLE);
        assertThat(onOperatorMachine(container("wireguard", COMPOSE_LABELS)))
            .isEqualTo(ContainerUpdateEligibility.UPDATABLE);
    }

    @Test
    void vaiersOwnStackIsRefusedEvenWhenItsLabelsWereUnreadable() {
        // Whose container it is does not depend on what its labels say.
        assertThat(onVaierServer(container("oauth2-proxy", Map.of())))
            .isEqualTo(ContainerUpdateEligibility.VAIER_OWN_STACK);
    }

    @Test
    void onlyUpdatableSaysVaierMayOfferTheAction() {
        assertThat(ContainerUpdateEligibility.UPDATABLE.updatable()).isTrue();
        assertThat(ContainerUpdateEligibility.NOT_COMPOSE_MANAGED.updatable()).isFalse();
        assertThat(ContainerUpdateEligibility.VAIER_OWN_STACK.updatable()).isFalse();
    }

    @Test
    void judgingLeavesEverythingElseAboutTheContainerAlone() {
        DockerService scraped = container("vaultwarden", COMPOSE_LABELS)
            .withUpdateAvailability(UpdateAvailability.UPDATE_AVAILABLE);

        DockerService judged = ContainerUpdateEligibility.judgeOperatorContainers(List.of(scraped), DockerCommandAccess.GRANTED).get(0);

        assertThat(judged.updateAvailable()).isEqualTo(UpdateAvailability.UPDATE_AVAILABLE);
        assertThat(judged.composeCoordinates()).isEqualTo(scraped.composeCoordinates());
        assertThat(judged.containerId()).isEqualTo(scraped.containerId());
        assertThat(judged.ports()).isEqualTo(scraped.ports());
        assertThat(scraped.updateEligibility()).isNull();
    }

    @Test
    void judgingAnEmptyScrapeIsAnEmptyList() {
        assertThat(ContainerUpdateEligibility.judgeOperatorContainers(List.of(), DockerCommandAccess.GRANTED)).isEmpty();
        assertThat(ContainerUpdateEligibility.judgeVaierServerContainers(List.of(), DockerCommandAccess.GRANTED)).isEmpty();
    }
}
