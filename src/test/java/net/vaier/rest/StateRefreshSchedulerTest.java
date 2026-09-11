package net.vaier.rest;

import net.vaier.application.GetLanServerReachabilityUseCase;
import net.vaier.application.GetLanServerScrapeUseCase;
import net.vaier.application.GetMachinesUseCase;
import net.vaier.application.JudgeContainerStandingsUseCase;
import net.vaier.application.NotifyAdminsOfContainerTroubleUseCase;
import net.vaier.application.RefreshContainerStateUseCase;
import net.vaier.application.RefreshLaunchpadVersionsUseCase;
import net.vaier.domain.ContainerStanding;
import net.vaier.domain.ContainerStandingTracker.Outcome;
import net.vaier.domain.ContainerStandingTracker.Verdict;
import net.vaier.domain.DeviceCategory;
import net.vaier.domain.Machine;
import net.vaier.domain.MachineContainerStanding;
import net.vaier.domain.MachineId;
import net.vaier.domain.MachineType;
import net.vaier.domain.TestMachineIds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StateRefreshSchedulerTest {

    private static final MachineId APALVEIEN = TestMachineIds.of("apalveien5");

    RefreshContainerStateUseCase containerState;
    GetLanServerScrapeUseCase lanServerScrape;
    GetLanServerReachabilityUseCase lanServerReachability;
    RefreshLaunchpadVersionsUseCase launchpadVersions;
    JudgeContainerStandingsUseCase containerStandings;
    NotifyAdminsOfContainerTroubleUseCase containerNotifier;
    GetMachinesUseCase machines;
    StateRefreshScheduler scheduler;

    @BeforeEach
    void setUp() {
        containerState = mock(RefreshContainerStateUseCase.class);
        lanServerScrape = mock(GetLanServerScrapeUseCase.class);
        lanServerReachability = mock(GetLanServerReachabilityUseCase.class);
        launchpadVersions = mock(RefreshLaunchpadVersionsUseCase.class);
        containerStandings = mock(JudgeContainerStandingsUseCase.class);
        containerNotifier = mock(NotifyAdminsOfContainerTroubleUseCase.class);
        machines = mock(GetMachinesUseCase.class);
        scheduler = new StateRefreshScheduler(containerState, lanServerScrape,
            lanServerReachability, launchpadVersions, containerStandings, containerNotifier, machines);
    }

    private static MachineContainerStanding standing(String containerName, ContainerStanding where) {
        return MachineContainerStanding.builder()
            .machineId(APALVEIEN)
            .containerName(containerName)
            .standing(where)
            .lastSeenRunning(Instant.parse("2026-09-11T09:15:00Z"))
            .troubledSince(Instant.parse("2026-09-11T09:47:00Z"))
            .build();
    }

    private static Machine machine(String name) {
        return new Machine(APALVEIEN, name, MachineType.UBUNTU_SERVER,
            "pubkey", "10.13.13.5/32", null, null, null, null, null,
            null, null, true, null, DeviceCategory.SERVER, null);
    }

    @Test
    void refresh_refreshesEveryStateSource() {
        scheduler.refresh();

        verify(containerState).refresh();
        verify(lanServerScrape).refreshAll();
        verify(lanServerReachability).refreshAll();
        verify(launchpadVersions).refreshLaunchpadVersions();
        // The containers were just re-read, so this is the moment to say what the reading means (#356).
        verify(containerStandings).judgeContainerStandings();
    }

    @Test
    void refresh_oneFailingStep_stillRunsTheRest() {
        doThrow(new RuntimeException("peer scrape boom")).when(containerState).refresh();

        scheduler.refresh();

        verify(lanServerScrape).refreshAll();
        verify(lanServerReachability).refreshAll();
        verify(launchpadVersions).refreshLaunchpadVersions();
    }

    @Test
    void refresh_aContainerInTroubleIsMailedAboutUnderItsMachinesName() {
        when(containerStandings.judgeContainerStandings())
            .thenReturn(List.of(new Verdict(Outcome.ALERT, standing("webtrees", ContainerStanding.GONE))));
        when(machines.getAllMachines()).thenReturn(List.of(machine("Apalveien 5")));

        scheduler.refresh();

        verify(containerNotifier).notifyAdminsOfContainerTrouble(eq("Apalveien 5"), any());
    }

    @Test
    void refresh_aContainerBackUpIsTheAllClear() {
        when(containerStandings.judgeContainerStandings())
            .thenReturn(List.of(
                new Verdict(Outcome.RECOVERED, standing("webtrees", ContainerStanding.RUNNING))));
        when(machines.getAllMachines()).thenReturn(List.of(machine("Apalveien 5")));

        scheduler.refresh();

        verify(containerNotifier).notifyAdminsOfContainerBack(eq("Apalveien 5"), any());
    }

    @Test
    void refresh_aHealthyFleetAsksNobodyForANameAndMailsNothing() {
        // Nothing moved, so the fleet is never even enumerated to resolve a name nobody needs.
        scheduler.refresh();

        verify(machines, never()).getAllMachines();
        verify(containerNotifier, never()).notifyAdminsOfContainerTrouble(any(), any());
    }

    @Test
    void refresh_aMailThatWillNotGoOutNeverTakesTheRoundDown() {
        when(containerStandings.judgeContainerStandings())
            .thenReturn(List.of(
                new Verdict(Outcome.ALERT, standing("webtrees", ContainerStanding.GONE)),
                new Verdict(Outcome.ALERT, standing("mariadb", ContainerStanding.GONE))));
        when(machines.getAllMachines()).thenReturn(List.of(machine("Apalveien 5")));
        doThrow(new RuntimeException("smtp down")).when(containerNotifier)
            .notifyAdminsOfContainerTrouble(any(), any());

        scheduler.refresh();

        // Both were attempted: each mail stands on its own, as every other rider on these rounds does.
        verify(containerNotifier, times(2)).notifyAdminsOfContainerTrouble(any(), any());
    }

    @Test
    void refresh_everyTroubleTravelsTheSameRoute_andTheStandingSaysWhichItIs() {
        // #317: unhealthy and restart-looping are not new machinery here. The scheduler switches on the
        // verdict and nothing else — which trouble it is, and the words for it, are the domain's.
        when(containerStandings.judgeContainerStandings()).thenReturn(List.of(
            new Verdict(Outcome.ALERT, standing("webtrees", ContainerStanding.UNHEALTHY)),
            new Verdict(Outcome.ALERT, standing("mariadb", ContainerStanding.RESTARTING))));
        when(machines.getAllMachines()).thenReturn(List.of(machine("Apalveien 5")));

        scheduler.refresh();

        verify(containerNotifier, times(2)).notifyAdminsOfContainerTrouble(eq("Apalveien 5"), any());
    }
}
