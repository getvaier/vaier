package net.vaier.application;

import net.vaier.domain.SelfUpgradeStatus;

/** Replace Vaier's own container with the newer image the registry is serving. */
public interface UpgradeVaierUseCase {

    /**
     * Start the upgrade. Returns as soon as the host has taken the work, because the caller is running inside
     * the container that is about to be replaced — waiting for the outcome is not something this process can
     * do. The outcome is read back afterwards through {@link GetSelfUpgradeStatusUseCase}.
     */
    SelfUpgradeStatus upgradeSelf();
}
