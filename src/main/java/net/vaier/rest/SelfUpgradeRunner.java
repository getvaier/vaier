package net.vaier.rest;

import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.DiscoverVaierServerContainersUseCase;
import net.vaier.application.GetSelfUpgradeStatusUseCase;
import net.vaier.application.RunRemoteCommandUseCase;
import net.vaier.application.UpgradeVaierUseCase;
import net.vaier.domain.CommandResult;
import net.vaier.domain.DockerService;
import net.vaier.domain.LanAnchor;
import net.vaier.domain.SelfUpgrade;
import net.vaier.domain.SelfUpgradeScript;
import net.vaier.domain.SelfUpgradeStatus;
import org.springframework.stereotype.Component;

/**
 * Carries out Vaier's self-upgrade on its own host.
 *
 * <p>It sits in {@code rest/} beside {@link BackupRunner} and {@link BackupProvisioner} for the same reason
 * they do: it drives a detached process over SSH and reads its result back later, which is infrastructure
 * work, not a domain decision. Every decision it makes is asked of the domain — which container is Vaier
 * ({@link SelfUpgrade#findSelf}), whether there is anything to do ({@link SelfUpgrade#upgradeAvailable}),
 * what the host should run ({@link SelfUpgradeScript}) and what came back ({@link SelfUpgradeStatus#parse}).
 *
 * <p><b>SSH to its own host.</b> The same channel {@code RemoteDiskWatcher} uses to read the Vaier server's
 * disks and {@code BackupRunner} uses to back it up — proven daily, with root, by the nightly job. That is
 * what makes this possible at all: the upgrade has to outlive the container, so it cannot run inside it.
 */
@Component
@Slf4j
public class SelfUpgradeRunner implements UpgradeVaierUseCase, GetSelfUpgradeStatusUseCase {

    private final DiscoverVaierServerContainersUseCase containers;
    private final RunRemoteCommandUseCase remoteCommand;

    public SelfUpgradeRunner(DiscoverVaierServerContainersUseCase containers,
                             RunRemoteCommandUseCase remoteCommand) {
        this.containers = containers;
        this.remoteCommand = remoteCommand;
    }

    @Override
    public boolean upgradeAvailable() {
        try {
            return SelfUpgrade.upgradeAvailable(containers.discover());
        } catch (Exception e) {
            log.debug("Could not judge whether a Vaier upgrade is available: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public SelfUpgradeStatus lastUpgrade() {
        try {
            CommandResult result = remoteCommand.run(LanAnchor.VAIER_SERVER_NAME, SelfUpgradeScript.readResult());
            return SelfUpgradeStatus.parse(result.stdout());
        } catch (Exception e) {
            log.debug("Could not read the last self-upgrade result: {}", e.getMessage());
            return SelfUpgradeStatus.NONE;
        }
    }

    /**
     * Stage the upgrade script on the host and launch it detached. Every refusal below is a refusal to take
     * Vaier down: without its own container it does not know what to recreate, and without compose labels
     * there is no {@code docker compose up} that would bring it back — an upgrade there would stop Vaier and
     * leave it stopped.
     */
    @Override
    public SelfUpgradeStatus upgradeSelf() {
        String runId = "upgrade-" + System.currentTimeMillis();
        List<DockerService> found;
        try {
            found = containers.discover();
        } catch (Exception e) {
            return failed(runId, "cannot-read-own-containers");
        }
        Optional<DockerService> self = SelfUpgrade.findSelf(found);
        if (self.isEmpty()) {
            return failed(runId, "vaier-container-not-found");
        }

        Optional<SelfUpgradeScript.ComposeLocation> at;
        try {
            CommandResult labels = remoteCommand.run(LanAnchor.VAIER_SERVER_NAME,
                SelfUpgradeScript.inspectComposeLabels(self.get().containerId()));
            at = SelfUpgradeScript.parseComposeLabels(labels.stdout());
        } catch (Exception e) {
            log.warn("Could not reach the Vaier host to upgrade: {}", e.getMessage());
            return failed(runId, "host-unreachable");
        }
        if (at.isEmpty()) {
            return failed(runId, "not-started-by-compose");
        }

        String script = SelfUpgradeScript.generate(at.get().workingDir(), at.get().service(), runId,
            SelfUpgradeScript.DEFAULT_HEALTH_TIMEOUT_SECONDS);
        String path = SelfUpgradeScript.scriptPathFor(at.get().workingDir(), runId);
        try {
            CommandResult staged = remoteCommand.run(LanAnchor.VAIER_SERVER_NAME,
                SelfUpgradeScript.stage(script, path));
            if (staged.exitCode() != 0) {
                return failed(runId, "could-not-stage-script");
            }
            // From here the host owns it. This process is about to be replaced, so nothing after this line
            // can be relied on to run — which is why the script, not Vaier, decides how the upgrade ends.
            remoteCommand.run(LanAnchor.VAIER_SERVER_NAME, SelfUpgradeScript.launch(at.get().workingDir(), runId));
            log.info("Vaier self-upgrade {} launched on its own host", runId);
            return new SelfUpgradeStatus(runId, SelfUpgradeStatus.Outcome.NONE, null, "started");
        } catch (Exception e) {
            log.warn("Launching the Vaier self-upgrade failed: {}", e.getMessage());
            return failed(runId, "launch-failed");
        }
    }

    private SelfUpgradeStatus failed(String runId, String why) {
        log.warn("Vaier self-upgrade {} refused: {}", runId, why);
        return new SelfUpgradeStatus(runId, SelfUpgradeStatus.Outcome.FAILED, null, why);
    }
}
