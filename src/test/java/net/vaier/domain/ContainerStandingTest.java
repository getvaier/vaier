package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How one container reads on one scrape (#317), and which reading wins when more than one could be said.
 *
 * <p>#356 taught Vaier one word — <b>gone</b>. This adds the two other troubles the same scrape can
 * already see: a container whose own health check says <b>unhealthy</b>, and one Docker reports as
 * <b>restarting</b>, which is a restart loop and is the predictive case — it is trouble before it is gone.
 */
class ContainerStandingTest {

    private static DockerService container(String state, ContainerHealth health) {
        return DockerService.builder()
            .containerId("id")
            .containerName("webtrees")
            .image("ghcr.io/webtrees:2.1")
            .version("v")
            .ports(List.of())
            .networks(List.of())
            .state(state)
            .health(health)
            .build();
    }

    @Test
    void aRunningContainerWithNoHealthCheckIsSimplyRunning() {
        assertThat(ContainerStanding.read(container("running", ContainerHealth.NONE)))
            .contains(ContainerStanding.RUNNING);
    }

    @Test
    void aRunningContainerWhoseHealthCheckPassesIsRunning() {
        assertThat(ContainerStanding.read(container("running", ContainerHealth.HEALTHY)))
            .contains(ContainerStanding.RUNNING);
    }

    @Test
    void aRunningContainerWhoseHealthCheckFailsIsUnhealthy() {
        assertThat(ContainerStanding.read(container("running", ContainerHealth.UNHEALTHY)))
            .contains(ContainerStanding.UNHEALTHY);
    }

    @Test
    void aHealthCheckThatHasNotConcludedIsNoReadingAtAll() {
        // "health: starting" is the minute after a container comes up. It is transient by definition, and
        // withholding is the honest move: unknown is not "unhealthy", and it is not "fine" either.
        assertThat(ContainerStanding.read(container("running", ContainerHealth.STARTING))).isEmpty();
    }

    @Test
    void aContainerDockerReportsAsRestartingIsRestartLooping() {
        // The predictive case the issue asks for: a container that keeps dying and being restarted is
        // trouble before it is ever "gone", and on a restart policy of `always` it may never be gone at all.
        assertThat(ContainerStanding.read(container("restarting", ContainerHealth.NONE)))
            .contains(ContainerStanding.RESTARTING);
    }

    @Test
    void aRestartingContainerReadsRestarting_notGone_thoughItIsNotRunningEither() {
        // Precedence, and the one place it actually bites: `restarting` is not `running`, so the naive
        // reading would call a restart loop "gone" and say the least useful true thing about it.
        assertThat(ContainerStanding.read(container("restarting", ContainerHealth.UNHEALTHY)))
            .contains(ContainerStanding.RESTARTING);
    }

    @Test
    void aContainerThatIsNotRunningAtAllIsGone() {
        assertThat(ContainerStanding.read(container("exited", ContainerHealth.NONE)))
            .contains(ContainerStanding.GONE);
        assertThat(ContainerStanding.read(container("dead", ContainerHealth.NONE)))
            .contains(ContainerStanding.GONE);
        assertThat(ContainerStanding.read(container("created", ContainerHealth.NONE)))
            .contains(ContainerStanding.GONE);
    }

    @Test
    void goneIsTheWorstNews_thenRestartLooping_thenUnhealthy() {
        // The order decides what is worth a second email: worse news is news, better news waits for the
        // all-clear. An unhealthy container that finally exits has got worse and is said so; one that
        // comes back up unhealthy after being gone has got better, and only the all-clear is worth a mail.
        assertThat(ContainerStanding.GONE.isWorseThan(ContainerStanding.RESTARTING)).isTrue();
        assertThat(ContainerStanding.RESTARTING.isWorseThan(ContainerStanding.UNHEALTHY)).isTrue();
        assertThat(ContainerStanding.UNHEALTHY.isWorseThan(ContainerStanding.RUNNING)).isTrue();
        assertThat(ContainerStanding.UNHEALTHY.isWorseThan(ContainerStanding.GONE)).isFalse();
        assertThat(ContainerStanding.GONE.isWorseThan(ContainerStanding.GONE)).isFalse();
    }

    @Test
    void runningIsTheOnlyStandingThatIsNotTrouble() {
        assertThat(ContainerStanding.RUNNING.isTrouble()).isFalse();
        assertThat(ContainerStanding.UNHEALTHY.isTrouble()).isTrue();
        assertThat(ContainerStanding.RESTARTING.isTrouble()).isTrue();
        assertThat(ContainerStanding.GONE.isTrouble()).isTrue();
    }

    @Test
    void aRememberedStandingVaierCannotReadBackIsReadAsRunning() {
        // Tolerance errs quiet: a file written by a newer Vaier, or a corrupted one, must re-learn what is
        // running rather than turn into an inbox full of containers nobody has evidence about.
        assertThat(ContainerStanding.named("UNHEALTHY")).isEqualTo(ContainerStanding.UNHEALTHY);
        assertThat(ContainerStanding.named("GONE")).isEqualTo(ContainerStanding.GONE);
        assertThat(ContainerStanding.named("WHAT")).isEqualTo(ContainerStanding.RUNNING);
        assertThat(ContainerStanding.named(null)).isEqualTo(ContainerStanding.RUNNING);
    }

    @Test
    void aContainerScrapedBeforeHealthWasReadIsNotSuddenlyUnhealthy() {
        // Null health is a scrape that did not carry the fact, never a failing check.
        Optional<ContainerStanding> reading = ContainerStanding.read(container("running", null));

        assertThat(reading).contains(ContainerStanding.RUNNING);
    }
}
