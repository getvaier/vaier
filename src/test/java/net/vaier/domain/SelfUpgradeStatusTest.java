package net.vaier.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** Reading back the account the upgrade script left on the host, after the process that started it is gone. */
class SelfUpgradeStatusTest {

    @Test
    void anUpgradeThatWorkedReadsAsSuccess_andCarriesWhatItCameFrom() {
        SelfUpgradeStatus s = SelfUpgradeStatus.parse(
            "run-7 UPGRADED 2026-07-23T10:04:11Z getvaier/vaier@sha256:abc");

        assertThat(s.outcome()).isEqualTo(SelfUpgradeStatus.Outcome.UPGRADED);
        assertThat(s.runId()).isEqualTo("run-7");
        assertThat(s.detail()).isEqualTo("getvaier/vaier@sha256:abc");
        assertThat(s.trouble()).as("a working upgrade is not news").isFalse();
    }

    @Test
    void aRollbackIsTrouble_becauseTheNewBuildDidNotComeUp() {
        // Vaier is running again, so nothing looks broken — which is exactly why this has to be said out
        // loud. Silence here would mean an upgrade quietly reverting every time and nobody ever knowing.
        SelfUpgradeStatus s = SelfUpgradeStatus.parse(
            "run-8 ROLLED_BACK 2026-07-23T10:09:02Z getvaier/vaier@sha256:old");

        assertThat(s.outcome()).isEqualTo(SelfUpgradeStatus.Outcome.ROLLED_BACK);
        assertThat(s.trouble()).isTrue();
    }

    @Test
    void aFailureIsTrouble_andSaysWhichStepFell() {
        SelfUpgradeStatus s = SelfUpgradeStatus.parse("run-9 FAILED 2026-07-23T10:00:00Z pull-failed");

        assertThat(s.outcome()).isEqualTo(SelfUpgradeStatus.Outcome.FAILED);
        assertThat(s.trouble()).isTrue();
        assertThat(s.detail()).isEqualTo("pull-failed");
    }

    @Test
    void noFileMeansNothingHasEverBeenUpgraded_notAFailure() {
        // A host that has never upgraded has no result file, and `cat` says so on stderr with empty stdout.
        // Reading that as a failed upgrade would put a permanent red mark on a healthy install.
        assertThat(SelfUpgradeStatus.parse(null).outcome()).isEqualTo(SelfUpgradeStatus.Outcome.NONE);
        assertThat(SelfUpgradeStatus.parse("   ").outcome()).isEqualTo(SelfUpgradeStatus.Outcome.NONE);
        assertThat(SelfUpgradeStatus.parse(null).trouble()).isFalse();
    }

    @Test
    void anUnreadableLineIsUnknown_ratherThanAGuess() {
        assertThat(SelfUpgradeStatus.parse("something else entirely").outcome())
            .isEqualTo(SelfUpgradeStatus.Outcome.UNKNOWN);
    }

    @Test
    void theComposeProjectIsReadOffTheContainer_notConfigured() {
        // Where the compose file lives is a fact about the running container, and asking the operator to
        // type it into an env var would be asking them for something Vaier can see. Docker stamps both the
        // working directory and the service name onto every container compose starts.
        String cmd = SelfUpgradeScript.inspectComposeLabels("abc123");
        assertThat(cmd).contains("com.docker.compose.project.working_dir");
        assertThat(cmd).contains("com.docker.compose.service");
        assertThat(cmd).contains("'abc123'");

        SelfUpgradeScript.ComposeLocation at =
            SelfUpgradeScript.parseComposeLabels("/home/ubuntu/vaier\tvaier\n").orElseThrow();
        assertThat(at.workingDir()).isEqualTo("/home/ubuntu/vaier");
        assertThat(at.service()).isEqualTo("vaier");
    }

    @Test
    void aContainerComposeDidNotStart_hasNoProjectToUpgradeIn() {
        // No labels means this container was started by hand, not by compose — there is no `docker compose
        // up` that would bring it back, so an upgrade would take Vaier down and leave it down.
        assertThat(SelfUpgradeScript.parseComposeLabels("\t\n")).isEmpty();
        assertThat(SelfUpgradeScript.parseComposeLabels("")).isEmpty();
        assertThat(SelfUpgradeScript.parseComposeLabels(null)).isEmpty();
    }
}
