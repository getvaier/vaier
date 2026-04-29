package net.vaier.rest;

import lombok.extern.slf4j.Slf4j;
import net.vaier.application.GetPeerConfigUseCase;
import net.vaier.application.GetVpnClientsUseCase;
import net.vaier.application.NotifyAdminsOfPeerTransitionUseCase;
import net.vaier.application.ResolveVpnPeerNameUseCase;
import net.vaier.domain.PeerConnectivityTracker;
import net.vaier.domain.PeerSnapshot;
import net.vaier.domain.MachineType;
import net.vaier.domain.VpnClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@Slf4j
public class PeerConnectivityWatcher {

    private final GetVpnClientsUseCase vpnClients;
    private final ResolveVpnPeerNameUseCase peerNameResolver;
    private final GetPeerConfigUseCase peerConfigs;
    private final NotifyAdminsOfPeerTransitionUseCase notifier;
    private final PeerConnectivityTracker tracker = new PeerConnectivityTracker();

    public PeerConnectivityWatcher(GetVpnClientsUseCase vpnClients,
                                   ResolveVpnPeerNameUseCase peerNameResolver,
                                   GetPeerConfigUseCase peerConfigs,
                                   NotifyAdminsOfPeerTransitionUseCase notifier) {
        this.vpnClients = vpnClients;
        this.peerNameResolver = peerNameResolver;
        this.peerConfigs = peerConfigs;
        this.notifier = notifier;
    }

    @Scheduled(fixedDelay = 30000)
    public void checkConnectivity() {
        try {
            List<PeerSnapshot> serverSnapshots = vpnClients.getClients().stream()
                    .map(this::toSnapshot)
                    .filter(s -> s != null && s.peerType().isVpnPeer() && s.peerType().isServerType())
                    .toList();
            for (PeerSnapshot transition : tracker.update(serverSnapshots)) {
                notifier.notifyAdmins(transition);
            }
        } catch (Exception e) {
            log.debug("Peer connectivity check failed: {}", e.getMessage());
        }
    }

    private PeerSnapshot toSnapshot(VpnClient client) {
        if (client.allowedIps() == null || client.allowedIps().isBlank()) return null;
        String peerIp = client.allowedIps().split(",")[0].split("/")[0].trim();
        if (peerIp.isEmpty()) return null;

        String name = peerNameResolver.resolvePeerNameByIp(peerIp);
        Optional<GetPeerConfigUseCase.PeerConfigResult> cfg = peerConfigs.getPeerConfigByIp(peerIp);
        MachineType type = cfg.map(GetPeerConfigUseCase.PeerConfigResult::peerType).orElse(MachineType.UBUNTU_SERVER);
        String lanAddress = cfg.map(GetPeerConfigUseCase.PeerConfigResult::lanAddress).orElse(null);

        long handshake = parseHandshake(client.latestHandshake());
        return new PeerSnapshot(name, type, client.isConnected(), handshake, lanAddress);
    }

    private long parseHandshake(String raw) {
        if (raw == null) return 0L;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
