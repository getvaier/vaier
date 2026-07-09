package net.vaier.domain;

import net.vaier.domain.port.ForProbingTcp.ProbeResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rendering of the "backup server down / recovered" admin alerts, which lives on the {@link BackupServer}
 * entity (mirroring {@link BackupRun#failureBody}). The wording must distinguish the probe cause because
 * it changes what the operator does.
 */
class BackupServerDownAlertTest {

    private BackupServer server() {
        return new BackupServer("nas-borg", "NAS", "192.168.3.3", 8022,
            "borg", "home/borg/backups", "/volume1/docker/borg", false);
    }

    @Test
    void downBodyNamesTheContainerWhenRefused() {
        String body = server().downBody("example.com", ProbeResult.REFUSED);

        // REFUSED = host alive, nothing listening → the container is down, not the host.
        assertThat(body).contains("borg server container is down");
        assertThat(body).contains("192.168.3.3");
        assertThat(body).doesNotContain("is unreachable");
    }

    @Test
    void downBodyNamesUnreachabilityWhenUnreachable() {
        String body = server().downBody("example.com", ProbeResult.UNREACHABLE);

        // UNREACHABLE = host down / network / tunnel → name the host, not the container.
        assertThat(body).contains("192.168.3.3 is unreachable");
        assertThat(body).doesNotContain("borg server container is down");
    }

    @Test
    void downBodyContainsHostAndBaseDomainLink() {
        String body = server().downBody("example.com", ProbeResult.REFUSED);

        assertThat(body).contains("192.168.3.3");
        assertThat(body).contains("vaier.example.com");
    }

    @Test
    void downAndRecoverySubjectsDiffer() {
        assertThat(server().downSubject()).isNotEqualTo(server().recoverySubject());
        assertThat(server().downSubject()).contains("nas-borg");
        assertThat(server().recoverySubject()).contains("nas-borg");
    }

    @Test
    void recoveryBodyContainsHostAndBaseDomainLink() {
        String body = server().recoveryBody("example.com");

        assertThat(body).contains("192.168.3.3");
        assertThat(body).contains("vaier.example.com");
    }
}
