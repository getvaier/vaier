package net.vaier.application;

import net.vaier.domain.ContainerStandingTracker;

import java.util.List;

/**
 * Judge what the fleet's latest container scrape means (#356): which containers that were running have
 * stopped, and which have come back. Driven by the state-refresh rounds, straight after the scrape it
 * reads, so no machine is asked anything extra.
 *
 * <p>It returns the verdicts rather than acting on them. The decisions — what counts as evidence, how many
 * misses are news, what to forget — belong to {@link ContainerStandingTracker}; whom to tell belongs to
 * the driving adapter that called this.
 */
public interface JudgeContainerStandingsUseCase {

    /** What this round's scrape means for every container Vaier watches. Empty when nothing moved. */
    List<ContainerStandingTracker.Verdict> judgeContainerStandings();
}
