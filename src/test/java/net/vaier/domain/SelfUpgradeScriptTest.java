package net.vaier.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The script Vaier runs on its own host to replace itself.
 *
 * <p>Everything here exists because of one fact: the process asking for the upgrade dies in the middle of it.
 * A container cannot recreate itself — the moment {@code docker compose up -d} replaces it, whatever was
 * driving the upgrade is gone. So the work is handed to the host, detached, and it has to be able to finish,
 * judge itself and undo itself with nobody watching.
 *
 * <p>Which makes the rollback the feature, not a nicety. If a bad image comes up, the thing that is down is
 * the thing an operator would use to fix it. The script therefore records what was running before it touched
 * anything, and puts it back if the new one does not answer.
 */
class SelfUpgradeScriptTest {

    private static final String DIR = "/home/ubuntu/vaier";

    @Test
    void itRecordsWhatWasRunning_beforeItChangesAnything() {
        // Rollback is only possible if the previous image was pinned by digest first. A tag is not enough:
        // `getvaier/vaier:latest` means something different after the pull, so rolling "back" to it would
        // roll forward to the broken image again.
        String script = SelfUpgradeScript.generate(DIR, "vaier", "run-1", 90);

        int record = script.indexOf("PREVIOUS_IMAGE=");
        int pull = script.indexOf("compose pull");
        assertThat(record).as("the running image is captured").isPositive();
        assertThat(record).as("and captured before the pull").isLessThan(pull);
        assertThat(script).as("by digest, since the tag will move under us").contains(".RepoDigests");
    }

    @Test
    void itHealthChecksItself_andRollsBackWhenTheNewImageDoesNotAnswer() {
        String script = SelfUpgradeScript.generate(DIR, "vaier", "run-1", 90);

        // Not a TCP probe: the endpoint that answers is the one that reports the version, so "it came up" and
        // "it is the build we asked for" are the same check.
        assertThat(script).as("it waits for the new container to answer").contains("/settings/version");
        assertThat(script).as("bounded — a hang must not wait forever").contains("90");
        assertThat(script).as("and puts the old image back when it does not")
            .contains("$PREVIOUS_IMAGE");
        assertThat(script).contains("ROLLED_BACK");
    }

    @Test
    void itLeavesItsOwnAccountOnTheHost_becauseNobodyIsListeningWhenItFinishes() {
        // Vaier is restarting while this runs, so there is no in-memory state to settle against and no open
        // connection to report to: the result has to outlive both processes. Vaier reads this file when it
        // comes back up.
        String script = SelfUpgradeScript.generate(DIR, "vaier", "run-1", 90);

        assertThat(script).contains(SelfUpgradeScript.RESULT_FILE);
        assertThat(script).as("the outcome is written for every path out").contains("UPGRADED");
        assertThat(script).contains("FAILED");
    }

    @Test
    void itRunsDetached_soItSurvivesTheContainerItIsReplacing() {
        // Launched over SSH from inside the container being replaced. Without detaching, killing the
        // container kills the upgrade halfway — the worst possible moment.
        String launch = SelfUpgradeScript.launch(DIR, "run-1");

        assertThat(launch).contains("nohup");
        assertThat(launch).contains("setsid");
        assertThat(launch).endsWith("&");
    }

    @Test
    void theComposeProjectDirectoryIsQuoted() {
        // The directory is operator-configurable and lands in a shell command. Vaier quotes every path it
        // hands to a shell (see BorgCommand); this is no different.
        assertThat(SelfUpgradeScript.generate("/home/my server/vaier", "vaier", "run-1", 90))
            .contains("'/home/my server/vaier'");
    }
}
