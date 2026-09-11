package net.vaier.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a container's own health check says, read off the one string the container listing already carries
 * (#317). Docker writes the verdict into the status line — "Up 2 weeks (healthy)" — so the fleet scrape
 * that already lists every container has the answer in hand, and nothing has to inspect a container to
 * learn it.
 */
class ContainerHealthTest {

    @Test
    void aPassingHealthCheckReadsHealthy() {
        assertThat(ContainerHealth.fromStatus("Up 2 weeks (healthy)")).isEqualTo(ContainerHealth.HEALTHY);
    }

    @Test
    void aFailingHealthCheckReadsUnhealthy() {
        // "unhealthy" contains "healthy", so the order of the two tests is the whole correctness of this.
        assertThat(ContainerHealth.fromStatus("Up 3 minutes (unhealthy)"))
            .isEqualTo(ContainerHealth.UNHEALTHY);
    }

    @Test
    void aHealthCheckThatHasNotConcludedReadsStarting() {
        assertThat(ContainerHealth.fromStatus("Up 10 seconds (health: starting)"))
            .isEqualTo(ContainerHealth.STARTING);
    }

    @Test
    void aContainerWithNoHealthCheckAtAllReadsNone() {
        // The common case by far: most images declare none. "No health check" must never read as trouble.
        assertThat(ContainerHealth.fromStatus("Up 5 days")).isEqualTo(ContainerHealth.NONE);
        assertThat(ContainerHealth.fromStatus("Exited (0) 3 days ago")).isEqualTo(ContainerHealth.NONE);
        assertThat(ContainerHealth.fromStatus("Restarting (1) 5 seconds ago")).isEqualTo(ContainerHealth.NONE);
    }

    @Test
    void aStatusVaierCannotReadIsNoVerdict_neverAnAlarm() {
        // A daemon that words it differently, or does not word it at all, leaves Vaier with nothing to
        // say. Unknown is not "unhealthy": inventing a verdict out of an unread string is how a watcher
        // teaches people to ignore it.
        assertThat(ContainerHealth.fromStatus(null)).isEqualTo(ContainerHealth.NONE);
        assertThat(ContainerHealth.fromStatus("")).isEqualTo(ContainerHealth.NONE);
        assertThat(ContainerHealth.fromStatus("Up 4 hours (something else entirely)"))
            .isEqualTo(ContainerHealth.NONE);
    }

    @Test
    void theCaseTheDaemonUsesIsNotTheOperatorsProblem() {
        assertThat(ContainerHealth.fromStatus("UP 2 WEEKS (HEALTHY)")).isEqualTo(ContainerHealth.HEALTHY);
    }
}
