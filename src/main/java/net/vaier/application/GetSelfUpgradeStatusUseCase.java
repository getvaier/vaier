package net.vaier.application;

import net.vaier.domain.SelfUpgradeStatus;

/** What Vaier knows about upgrading itself: whether there is a newer image, and how the last attempt went. */
public interface GetSelfUpgradeStatusUseCase {

    /** Whether the registry serves a newer image for the tag Vaier's own container runs. */
    boolean upgradeAvailable();

    /** The account the last upgrade left on the host, or {@link SelfUpgradeStatus#NONE} if there was none. */
    SelfUpgradeStatus lastUpgrade();
}
