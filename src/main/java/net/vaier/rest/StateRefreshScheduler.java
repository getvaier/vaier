package net.vaier.rest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.GetLanServerReachabilityUseCase;
import net.vaier.application.GetLanServerScrapeUseCase;
import net.vaier.application.GetMachinesUseCase;
import net.vaier.application.JudgeContainerStandingsUseCase;
import net.vaier.application.NotifyAdminsOfContainerGoneUseCase;
import net.vaier.application.RefreshContainerStateUseCase;
import net.vaier.application.RefreshLaunchpadVersionsUseCase;
import net.vaier.domain.ContainerStandingTracker.Verdict;
import net.vaier.domain.Machine;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The single place service/machine state is refreshed.
 *
 * <p>One scheduled tick re-scrapes every slow remote source — peer Docker daemons, LAN-server
 * Docker daemons, LAN-server reachability, launchpad version endpoints — into the caches that
 * REST endpoints read. No controller scrapes on a request thread, so the launchpad, services,
 * and machines pages stay fast no matter how slow or unreachable a remote host is.
 *
 * <p>And then, on the reading it has just taken, it asks what that reading <b>means</b> (#356): which
 * containers that were running are not running any more. That question costs no extra round-trip — the
 * containers are already in hand — and it is asked here rather than inside the scrape because a scrape
 * reports and a judgement decides, and those are different jobs. The domain owns the decision; this only
 * decides whom to tell.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StateRefreshScheduler {

    private final RefreshContainerStateUseCase containerState;
    private final GetLanServerScrapeUseCase lanServerScrape;
    private final GetLanServerReachabilityUseCase lanServerReachability;
    private final RefreshLaunchpadVersionsUseCase launchpadVersions;
    private final JudgeContainerStandingsUseCase containerStandings;
    private final NotifyAdminsOfContainerGoneUseCase containerNotifier;
    // Only ever asked when something actually moved: a name is for a mail, and a healthy fleet sends none.
    private final GetMachinesUseCase machines;

    @Scheduled(fixedDelay = 30_000, initialDelay = 5_000)
    public void refresh() {
        refreshStep("peer container discovery", containerState::refresh);
        refreshStep("LAN server container scrape", lanServerScrape::refreshAll);
        refreshStep("LAN server reachability", lanServerReachability::refreshAll);
        refreshStep("launchpad version probes", launchpadVersions::refreshLaunchpadVersions);
        refreshStep("container standings", this::judgeContainerStandings);
    }

    /**
     * What the scrape just taken means for every container Vaier watches (#356), and who hears about it.
     *
     * <p>The judgement is the domain's, through its use case; this switches on the verdict and nothing
     * more. Each mail sits in its own try/catch for the reason every rider on these rounds does: one
     * container's alert failing to send must never cost the rest of the fleet theirs.
     */
    private void judgeContainerStandings() {
        List<Verdict> verdicts = containerStandings.judgeContainerStandings();
        if (verdicts.isEmpty()) {
            return;
        }
        Map<String, String> names = machineNames();
        for (Verdict verdict : verdicts) {
            String machineName = names.getOrDefault(verdict.standing().machineId().value(),
                verdict.standing().machineId().value());
            tell(verdict, machineName);
        }
    }

    private void tell(Verdict verdict, String machineName) {
        try {
            switch (verdict.outcome()) {
                case ALERT -> containerNotifier
                    .notifyAdminsOfContainerGone(machineName, verdict.standing());
                case RECOVERED -> containerNotifier
                    .notifyAdminsOfContainerBack(machineName, verdict.standing());
            }
        } catch (Exception e) {
            log.warn("Could not tell admins that {} on {} changed: {}", verdict.standing().containerName(),
                machineName, e.getMessage());
        }
    }

    /** What each machine is called, for the mail. Identity is what a standing is filed under. */
    private Map<String, String> machineNames() {
        return machines.getAllMachines().stream()
            .collect(Collectors.toMap(machine -> machine.id().value(), Machine::name, (a, b) -> a));
    }

    /** Each source is refreshed in isolation so one failure doesn't starve the others. */
    private void refreshStep(String what, Runnable step) {
        try {
            step.run();
        } catch (Exception e) {
            log.debug("{} refresh failed: {}", what, e.getMessage());
        }
    }
}
