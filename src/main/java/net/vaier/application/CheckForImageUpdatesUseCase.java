package net.vaier.application;

import net.vaier.domain.UpdateCheckOutcome;

/**
 * Check <b>now</b> whether the fleet's images have updates available, because the operator asked (#57 slice 3).
 *
 * <p>The story: they get the rollup mail, SSH to the machine, {@code docker compose pull && up -d}, and then
 * want Vaier to agree — immediately, not in up to 24 hours. Until this existed, the yellow mark lingered on an
 * image they had already fixed, and a mark you know is wrong is a mark you learn to ignore. The whole feature
 * is only worth having if the operator trusts it, so it has to be able to be re-checked.
 *
 * <p><b>This checks; it never pulls.</b> Vaier is read-only about containers — it has no endpoint to pull an
 * image or restart anything, and this opens none. What it acts on is Vaier's own knowledge, which is exactly
 * why it is a legitimate control rather than one of the fake verbs the container Inspector deliberately
 * refuses to offer.
 *
 * <p>Distinct from {@link SweepImageUpdatesUseCase} in two ways that matter, both of which exist to stop the
 * check from confirming the very mark it was pressed to clear: it re-scrapes the containers first (the local
 * digest is up to 30s stale, and they clicked seconds after pulling), and it refuses every remembered registry
 * answer (a remembered answer predates their pull).
 */
public interface CheckForImageUpdatesUseCase {

    /**
     * Re-scrape, ask the registries afresh, and report what came of it.
     *
     * <p>Total — an unreachable or rate-limited registry leaves that image unknown rather than throwing. May
     * be coalesced: a check inside the rate-limit floor asks nothing and says so, rather than pretending. The
     * returned {@link UpdateCheckOutcome} is what the operator is told, so both of its facts are load-bearing.
     */
    UpdateCheckOutcome checkForImageUpdates();
}
