package net.vaier.rest;

import lombok.extern.slf4j.Slf4j;
import net.vaier.application.GetMachinesUseCase;
import net.vaier.application.GetPeerConfigUseCase;
import net.vaier.application.GetVpnClientsUseCase;
import net.vaier.application.NoticeMachinesBackOnUseCase;
import net.vaier.application.NotifyAdminsOfPeerTransitionUseCase;
import net.vaier.application.ResolveVpnPeerIdUseCase;
import net.vaier.domain.Machine;
import net.vaier.domain.PeerConnectivityTracker;
import net.vaier.domain.PeerSnapshot;
import net.vaier.domain.MachineType;
import net.vaier.domain.VpnClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@Slf4j
public class PeerConnectivityWatcher {

    private final GetVpnClientsUseCase vpnClients;
    private final ResolveVpnPeerIdUseCase peerIdResolver;
    private final GetPeerConfigUseCase peerConfigs;
    private final NotifyAdminsOfPeerTransitionUseCase notifier;
    private final GetMachinesUseCase machines;
    private final NoticeMachinesBackOnUseCase backOn;
    private final PeerConnectivityTracker tracker = new PeerConnectivityTracker();

    public PeerConnectivityWatcher(GetVpnClientsUseCase vpnClients,
                                   ResolveVpnPeerIdUseCase peerIdResolver,
                                   GetPeerConfigUseCase peerConfigs,
                                   NotifyAdminsOfPeerTransitionUseCase notifier,
                                   GetMachinesUseCase machines,
                                   NoticeMachinesBackOnUseCase backOn) {
        this.vpnClients = vpnClients;
        this.peerIdResolver = peerIdResolver;
        this.peerConfigs = peerConfigs;
        this.notifier = notifier;
        this.machines = machines;
        this.backOn = backOn;
    }

    @Scheduled(fixedDelay = 30000)
    public void checkConnectivity() {
        try {
            Set<String> switchedOffKeys = switchedOffPublicKeys();
            List<PeerSnapshot> serverSnapshots = new ArrayList<>();
            Set<String> switchedOffPeers = new HashSet<>();
            for (VpnClient client : vpnClients.getClients()) {
                PeerSnapshot snapshot = toSnapshot(client);
                if (snapshot == null || !snapshot.peerType().isVpnPeer() || !snapshot.peerType().isServerType()) {
                    continue;
                }
                serverSnapshots.add(snapshot);
                if (switchedOffKeys.contains(client.publicKey())) switchedOffPeers.add(snapshot.name());
            }
            for (PeerSnapshot transition : tracker.update(serverSnapshots, switchedOffPeers)) {
                notifier.notifyAdmins(transition);
            }
        } catch (Exception e) {
            log.debug("Peer connectivity check failed: {}", e.getMessage());
        }
        // After this tick's transitions, so a comeback is judged while its mark still stands.
        try {
            backOn.noticeMachinesBackOn();
        } catch (Exception e) {
            log.debug("Could not clear switched-off marks: {}", e.getMessage());
        }
    }

    /** The tunnel keys of servers switched off on purpose; a key is what a wg client and a machine share. */
    private Set<String> switchedOffPublicKeys() {
        return machines.getAllMachines().stream()
            .filter(Machine::isSwitchedOffOnPurpose)
            .map(Machine::publicKey)
            .filter(key -> key != null)
            .collect(Collectors.toSet());
    }

    private PeerSnapshot toSnapshot(VpnClient client) {
        if (client.allowedIps() == null || client.allowedIps().isBlank()) return null;
        String peerIp = client.vpnIp();
        if (peerIp.isEmpty()) return null;

        String name = peerIdResolver.resolvePeerIdByIp(peerIp);
        Optional<GetPeerConfigUseCase.PeerConfigResult> cfg = peerConfigs.getPeerConfigByIp(peerIp);
        MachineType type = cfg.map(GetPeerConfigUseCase.PeerConfigResult::peerType).orElse(MachineType.defaultType());
        String lanAddress = cfg.map(GetPeerConfigUseCase.PeerConfigResult::lanAddress).orElse(null);

        return new PeerSnapshot(name, type, client.isConnected(), client.latestHandshakeEpoch(), lanAddress);
    }
}
