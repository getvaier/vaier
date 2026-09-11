package net.vaier.adapter.driven;

import net.vaier.domain.ContainerStanding;
import net.vaier.domain.MachineContainerStanding;
import net.vaier.domain.MachineId;
import net.vaier.domain.TestMachineIds;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContainerStandingFileAdapterTest {

    private static final MachineId APALVEIEN = TestMachineIds.of("apalveien");
    private static final MachineId NUC = TestMachineIds.of("nuc");
    private static final Instant NOON = Instant.parse("2026-09-11T12:00:00Z");

    @TempDir
    Path configDir;

    private ContainerStandingFileAdapter adapter() {
        return new ContainerStandingFileAdapter(configDir.toString());
    }

    private static MachineContainerStanding gone(MachineId machineId, String containerName) {
        return MachineContainerStanding.builder()
            .machineId(machineId)
            .containerName(containerName)
            .standing(ContainerStanding.GONE)
            .lastSeenRunning(NOON)
            .troubledSince(NOON.plusSeconds(60))
            .misses(2)
            .build();
    }

    @Test
    void anAbsentFile_isTheHealthyState_notAnError() {
        assertThat(adapter().standingsFor(APALVEIEN)).isEmpty();
        assertThat(adapter().all()).isEmpty();
        assertThat(adapter().bootOf(APALVEIEN)).isEmpty();
    }

    @Test
    void whatVaierSawRunningSurvivesARedeploy() {
        // The reason this port is on disk at all: the operator deploys several times a day, and a container
        // Vaier has forgotten it ever saw running is a container it will never report as gone.
        adapter().record(APALVEIEN, List.of(gone(APALVEIEN, "webtrees")));

        assertThat(adapter().standingsFor(APALVEIEN)).singleElement().satisfies(standing -> {
            assertThat(standing.machineId()).isEqualTo(APALVEIEN);
            assertThat(standing.containerName()).isEqualTo("webtrees");
            assertThat(standing.standing()).isEqualTo(ContainerStanding.GONE);
            assertThat(standing.lastSeenRunning()).isEqualTo(NOON);
            assertThat(standing.troubledSince()).isEqualTo(NOON.plusSeconds(60));
            assertThat(standing.misses()).isEqualTo(2);
        });
    }

    @Test
    void recordReplacesAMachinesStandings_soAForgottenContainerStaysForgotten() {
        adapter().record(APALVEIEN, List.of(gone(APALVEIEN, "webtrees"), gone(APALVEIEN, "mariadb")));

        adapter().record(APALVEIEN, List.of(gone(APALVEIEN, "webtrees")));

        assertThat(adapter().standingsFor(APALVEIEN))
            .extracting(MachineContainerStanding::containerName)
            .containsExactly("webtrees");
    }

    @Test
    void oneMachinesStandingsAreNeverAnothers() {
        adapter().record(APALVEIEN, List.of(gone(APALVEIEN, "webtrees")));
        adapter().record(NUC, List.of(gone(NUC, "roon")));

        assertThat(adapter().standingsFor(NUC))
            .extracting(MachineContainerStanding::containerName).containsExactly("roon");
        assertThat(adapter().all()).hasSize(2);
    }

    @Test
    void aMachinesBootInstantIsKeptBesideItsContainers_andStampedOnEachStanding() {
        adapter().record(APALVEIEN, List.of(gone(APALVEIEN, "webtrees")));
        adapter().recordBoot(APALVEIEN, NOON.plusSeconds(30));

        assertThat(adapter().bootOf(APALVEIEN)).contains(NOON.plusSeconds(30));
        // Read back onto every standing, so the card and the mail can both say the reboot explains it
        // without either of them having to go and ask a second port.
        assertThat(adapter().standingsFor(APALVEIEN)).singleElement()
            .satisfies(standing ->
                assertThat(standing.machineBootedAt()).isEqualTo(NOON.plusSeconds(30)));
    }

    @Test
    void aMalformedFile_isNotAnError_itJustMeansNothingIsRememberedYet() throws Exception {
        // Tolerant on load like the other file adapters. The tolerance errs quiet here rather than loud:
        // a lost memory means Vaier re-learns what is running and says nothing until it sees one stop.
        Files.writeString(configDir.resolve("container-standings.yml"), "\t: not: yaml: at: all\n[");

        assertThat(adapter().standingsFor(APALVEIEN)).isEmpty();
    }

    // --- the other two troubles, and the file that already holds one shape (#317) -----------------------

    @Test
    void anUnhealthyContainerIsRememberedAsUnhealthy_notMerelyAsTrouble() {
        // The standing is what the mail was sent on and what the badge draws. Flattening the three
        // troubles into one on the way to disk would lose which one the operator was told about.
        MachineContainerStanding unhealthy = gone(APALVEIEN, "webtrees").toBuilder()
            .standing(ContainerStanding.UNHEALTHY)
            .reading(ContainerStanding.UNHEALTHY)
            .build();

        adapter().record(APALVEIEN, List.of(unhealthy));

        assertThat(adapter().standingsFor(APALVEIEN)).singleElement().satisfies(standing -> {
            assertThat(standing.standing()).isEqualTo(ContainerStanding.UNHEALTHY);
            assertThat(standing.reading()).isEqualTo(ContainerStanding.UNHEALTHY);
        });
    }

    @Test
    void theTroubleBeingCountedSurvivesARedeployToo() {
        // A deploy lands in the middle of the two-scrape window often enough. Without the reading, the
        // count that comes back would belong to no particular trouble.
        MachineContainerStanding counting = MachineContainerStanding
            .seenRunning(APALVEIEN, "webtrees", NOON, null)
            .troubled(ContainerStanding.RESTARTING, NOON.plusSeconds(30), null);

        adapter().record(APALVEIEN, List.of(counting));

        assertThat(adapter().standingsFor(APALVEIEN)).singleElement().satisfies(standing -> {
            assertThat(standing.standing()).isEqualTo(ContainerStanding.RUNNING);
            assertThat(standing.reading()).isEqualTo(ContainerStanding.RESTARTING);
            assertThat(standing.misses()).isEqualTo(1);
            assertThat(standing.troubledSince()).isEqualTo(NOON.plusSeconds(30));
        });
    }

    @Test
    void aFileWrittenBeforeThereWereThreeTroubles_isStillRead() throws Exception {
        // #356 wrote `notRunningSince`, and there is one of these on every deployed Vaier. Upgrading must
        // not lose what it has watched run — that memory is the whole feature.
        Files.writeString(configDir.resolve("container-standings.yml"), """
            machines:
              %s:
                bootedAt: '2026-09-11T11:30:00Z'
                containers:
                  webtrees:
                    standing: GONE
                    lastSeenRunning: '2026-09-11T12:00:00Z'
                    notRunningSince: '2026-09-11T12:01:00Z'
                    misses: 2
            """.formatted(APALVEIEN.value()));

        assertThat(adapter().standingsFor(APALVEIEN)).singleElement().satisfies(standing -> {
            assertThat(standing.standing()).isEqualTo(ContainerStanding.GONE);
            assertThat(standing.troubledSince()).isEqualTo(NOON.plusSeconds(60));
            assertThat(standing.misses()).isEqualTo(2);
            // Nothing said about what was being read, so the standing itself is the honest answer.
            assertThat(standing.reading()).isEqualTo(ContainerStanding.GONE);
        });
    }

    @Test
    void aStandingThisVaierDoesNotKnow_readsAsRunning_neverAsAnAlert() throws Exception {
        Files.writeString(configDir.resolve("container-standings.yml"), """
            machines:
              %s:
                containers:
                  webtrees:
                    standing: SOMETHING_ELSE
                    lastSeenRunning: '2026-09-11T12:00:00Z'
            """.formatted(APALVEIEN.value()));

        assertThat(adapter().standingsFor(APALVEIEN)).singleElement()
            .satisfies(standing -> assertThat(standing.standing()).isEqualTo(ContainerStanding.RUNNING));
    }
}
