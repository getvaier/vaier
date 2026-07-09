package net.vaier.domain;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-job backup-failure state, so the {@code BackupRunner} alerts admins only when a job <em>crosses</em>
 * from healthy to failing — not on every failing nightly run. It is the fleet-backup sibling of
 * {@link RemoteDiskPressureTracker}: each job gets its own independent transition state, so one job's
 * failure never disturbs another's.
 *
 * <p>Unlike the disk trackers, whose first observation is a null baseline (a disk merely observed full at
 * startup is not news), a job's baseline here is <b>assumed-healthy</b>: the first failing <em>result</em>
 * of a job is a discrete event worth paging on, so it crosses to failing. Restart-quietness is preserved
 * a different way — the runner only feeds this tracker when a run that is RUNNING settles to a terminal
 * state <em>this tick</em>; an already-terminal FAILED run is never re-polled and so never re-fed, meaning
 * a restart does not re-alert jobs that had already failed.
 */
public class BackupFailureTracker {

    /** Whether the latest transition crossed into failing, back to healthy, or stayed put. */
    public enum Transition { NONE, CROSSED_TO_FAILING, CROSSED_TO_HEALTHY }

    private final Map<String, JobFailureState> perJob = new ConcurrentHashMap<>();

    /**
     * Record whether {@code jobName}'s latest settled run was {@code failingNow} and report whether that
     * crossed a boundary since the job's previous settled run.
     */
    public Transition update(String jobName, boolean failingNow) {
        return perJob.computeIfAbsent(jobName, j -> new JobFailureState()).update(failingNow);
    }

    /** A single job's last-known failing/healthy flag, seeded to healthy so a first failure alerts. */
    private static final class JobFailureState {

        private boolean lastFailing = false;

        synchronized Transition update(boolean failingNow) {
            Transition transition = Transition.NONE;
            if (lastFailing != failingNow) {
                transition = failingNow ? Transition.CROSSED_TO_FAILING : Transition.CROSSED_TO_HEALTHY;
            }
            lastFailing = failingNow;
            return transition;
        }
    }
}
