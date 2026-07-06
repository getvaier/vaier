package net.vaier.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.ClearHostKeyUseCase;
import net.vaier.application.DeleteHostCredentialUseCase;
import net.vaier.application.GetHostCredentialUseCase;
import net.vaier.application.OpenTerminalSessionUseCase;
import net.vaier.application.SaveHostCredentialUseCase;
import net.vaier.domain.HostCredential;
import net.vaier.domain.HostCredentialView;
import net.vaier.domain.LanAnchor;
import net.vaier.domain.LanServer;
import net.vaier.domain.NoHostCredentialException;
import net.vaier.domain.NotFoundException;
import net.vaier.domain.SshTarget;
import net.vaier.domain.port.ForGettingPeerConfigurations;
import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;
import net.vaier.domain.port.ForOpeningSshSessions;
import net.vaier.domain.port.ForOpeningSshSessions.SshOutputListener;
import net.vaier.domain.port.ForOpeningSshSessions.SshSession;
import net.vaier.domain.port.ForPersistingHostCredentials;
import net.vaier.domain.port.ForPersistingLanServers;
import net.vaier.domain.port.ForResolvingVaierServerSshAddress;
import net.vaier.domain.port.ForTrackingHostKeys;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * The remote-shell / credential-vault domain service. It stores, reads (redacted) and deletes host
 * credentials (slice 1), and opens live SSH terminal sessions (slice 2): resolving a machine's SSH
 * address (peer tunnel IP / LAN address / Vaier host), authenticating from the vault, and pinning the
 * host key on first use. Reads go through the domain's {@link HostCredential#toView() redaction} so
 * raw secrets never leave the process.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TerminalService implements
    SaveHostCredentialUseCase,
    GetHostCredentialUseCase,
    DeleteHostCredentialUseCase,
    OpenTerminalSessionUseCase,
    ClearHostKeyUseCase {

    private final ForPersistingHostCredentials forPersistingHostCredentials;
    private final ForGettingPeerConfigurations forGettingPeerConfigurations;
    private final ForPersistingLanServers forPersistingLanServers;
    private final ForResolvingVaierServerSshAddress forResolvingVaierServerSshAddress;
    private final ForOpeningSshSessions forOpeningSshSessions;
    private final ForTrackingHostKeys forTrackingHostKeys;

    @Override
    public void saveHostCredential(HostCredential credential) {
        forPersistingHostCredentials.save(credential);
    }

    @Override
    public Optional<HostCredentialView> getHostCredential(String machineName) {
        return forPersistingHostCredentials.getByMachine(machineName).map(HostCredential::toView);
    }

    @Override
    public void deleteHostCredential(String machineName) {
        forPersistingHostCredentials.deleteByMachine(machineName);
    }

    @Override
    public SshSession openTerminal(String machineName, SshOutputListener onOutput) {
        String host = resolveSshAddress(machineName);
        HostCredential credential = forPersistingHostCredentials.getByMachine(machineName)
            .orElseThrow(() -> new NoHostCredentialException(machineName));
        String pinned = forTrackingHostKeys.getFingerprint(machineName).orElse(null);

        SshTarget target = SshTarget.on(host, credential, pinned);
        // The adapter enforces host-key trust via the domain HostKeyTrust decision and throws
        // HostKeyMismatchException on a changed key; other failures surface as SshAuth/SshConnect.
        SshSession session = forOpeningSshSessions.open(target, onOutput);

        // Trust-on-first-use: if nothing was pinned, record the fingerprint the host presented.
        if (pinned == null && session.hostKeyFingerprint() != null) {
            forTrackingHostKeys.pin(machineName, session.hostKeyFingerprint());
        }
        log.info("Opened terminal session to {} ({})", machineName, host);
        return session;
    }

    @Override
    public void clearHostKey(String machineName) {
        forTrackingHostKeys.clear(machineName);
        log.info("Cleared pinned host key for {}", machineName);
    }

    /**
     * The SSH host for a machine — a domain decision by machine kind: a VPN peer's <b>tunnel IP</b>, a
     * LAN server's <b>lanAddress</b>, or the resolved host address for the <b>Vaier server</b> itself.
     * Throws {@link NotFoundException} when no machine bears the name.
     */
    private String resolveSshAddress(String machineName) {
        if (LanAnchor.VAIER_SERVER_NAME.equals(machineName)) {
            return forResolvingVaierServerSshAddress.resolve();
        }
        Optional<PeerConfiguration> peer = forGettingPeerConfigurations.getAllPeerConfigs().stream()
            .filter(p -> machineName.equals(p.name()))
            .findFirst();
        if (peer.isPresent()) {
            return peer.get().ipAddress();
        }
        return LanServer.findByName(machineName, forPersistingLanServers.getAll())
            .map(LanServer::lanAddress)
            .orElseThrow(() -> new NotFoundException("Machine not found: " + machineName));
    }
}
