package net.vaier.application;

import net.vaier.domain.PeerSnapshot;

public interface NotifyAdminsOfPeerTransitionUseCase {
    void notifyAdmins(PeerSnapshot snapshot);
}
