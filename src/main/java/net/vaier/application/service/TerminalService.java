package net.vaier.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.ClearHostKeyUseCase;
import net.vaier.application.DeleteHostCredentialUseCase;
import net.vaier.application.GetHostCredentialUseCase;
import net.vaier.application.OpenTerminalSessionUseCase;
import net.vaier.application.OpenTerminalSessionUseCase.OpenedTerminal;
import net.vaier.application.RunRemoteCommandUseCase;
import net.vaier.application.SaveHostCredentialUseCase;
import net.vaier.application.SendHostPasswordUseCase;
import net.vaier.domain.AuthMethod;
import net.vaier.domain.CommandResult;
import net.vaier.domain.HostCredential;
import net.vaier.domain.HostCredentialView;
import net.vaier.domain.LanAnchor;
import net.vaier.domain.LanServer;
import net.vaier.domain.NoHostCredentialException;
import net.vaier.domain.NotFoundException;
import net.vaier.domain.PasswordPrompt;
import net.vaier.domain.PersistentShell;
import net.vaier.domain.SshTarget;
import net.vaier.domain.port.ForGettingPeerConfigurations;
import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;
import net.vaier.domain.port.ForOpeningSshSessions;
import net.vaier.domain.port.ForOpeningSshSessions.SshOutputListener;
import net.vaier.domain.port.ForOpeningSshSessions.SshSession;
import net.vaier.domain.port.ForPersistingHostCredentials;
import net.vaier.domain.port.ForPersistingLanServers;
import net.vaier.domain.port.ForResolvingVaierServerSshAddress;
import net.vaier.domain.port.ForRunningSshCommands;
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
    RunRemoteCommandUseCase,
    SendHostPasswordUseCase,
    ClearHostKeyUseCase {

    private final ForPersistingHostCredentials forPersistingHostCredentials;
    private final ForGettingPeerConfigurations forGettingPeerConfigurations;
    private final ForPersistingLanServers forPersistingLanServers;
    private final ForResolvingVaierServerSshAddress forResolvingVaierServerSshAddress;
    private final ForOpeningSshSessions forOpeningSshSessions;
    private final ForRunningSshCommands forRunningSshCommands;
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
    public OpenedTerminal openTerminal(String machineName, String paneId, SshOutputListener onOutput) {
        SshTarget target = buildTarget(machineName);
        // Probe first (a normal exec run, the same host-key trust as any command): is tmux installed on
        // this machine, and does the pane's session already exist? The domain reads it into a truthful
        // continuity, so the reconnect banner can say "reattached" only when it really was. This first
        // connection is also where an unpinned host is pinned on first use.
        CommandResult probe = forRunningSshCommands.run(target, PersistentShell.probeCommand(paneId));
        pinOnFirstUse(machineName, target, probe.hostKeyFingerprint());
        PersistentShell.Continuity continuity = PersistentShell.readProbe(probe.stdout());

        // Open the persistent shell: tmux attach-or-create for the pane, falling back to a plain login
        // shell when tmux is absent. The adapter enforces host-key trust and throws HostKeyMismatchException
        // on a changed key; other failures surface as SshAuth/SshConnect.
        SshSession session = forOpeningSshSessions.open(
            target, PersistentShell.attachOrCreateCommand(paneId), onOutput);

        log.info("Opened {} terminal session to {} ({}) for pane {}",
            continuity, machineName, target.host(), PersistentShell.sessionName(paneId));
        return new OpenedTerminal(session, continuity);
    }

    @Override
    public CommandResult run(String machineName, String command) {
        SshTarget target = buildTarget(machineName);
        // Same host-key trust as the shell path: a changed key throws HostKeyMismatchException.
        CommandResult result = forRunningSshCommands.run(target, command);

        pinOnFirstUse(machineName, target, result.hostKeyFingerprint());
        return result;
    }

    @Override
    public SendPasswordResult sendPassword(String machineName, SshSession session, String recentOutput) {
        try {
            Optional<HostCredential> credential = forPersistingHostCredentials.getByMachine(machineName);
            if (credential.isEmpty() || credential.get().authMethod() != AuthMethod.PASSWORD) {
                return SendPasswordResult.NO_PASSWORD_CREDENTIAL;
            }
            if (!PasswordPrompt.isAwaitingPassword(recentOutput)) {
                return SendPasswordResult.NOT_AT_PROMPT;
            }
            // The secret stays in-process: written straight into the SSH PTY, never returned or logged.
            session.write((credential.get().secret() + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return SendPasswordResult.SENT;
        } catch (RuntimeException e) {
            // Never surface the secret — log only the machine and the failure class.
            log.warn("Failed to send stored password to {}: {}", machineName, e.getClass().getSimpleName());
            return SendPasswordResult.FAILED;
        }
    }

    @Override
    public void clearHostKey(String machineName) {
        forTrackingHostKeys.clear(machineName);
        log.info("Cleared pinned host key for {}", machineName);
    }

    /**
     * Assemble the {@link SshTarget} for a machine — the one copy of the resolve-address + load-vault-
     * credential + read-pinned-fingerprint logic shared by {@link #openTerminal} and {@link #run}, so
     * both connect and TOFU-pin identically. The returned target carries the previously pinned
     * fingerprint (null when the host has never been pinned), which the callers use to decide whether to
     * pin on first use.
     */
    private SshTarget buildTarget(String machineName) {
        String host = resolveSshAddress(machineName);
        HostCredential credential = forPersistingHostCredentials.getByMachine(machineName)
            .orElseThrow(() -> new NoHostCredentialException(machineName));
        String pinned = forTrackingHostKeys.getFingerprint(machineName).orElse(null);
        return SshTarget.on(host, credential, pinned);
    }

    /**
     * Trust-on-first-use: if the target had nothing pinned and the connect presented a fingerprint,
     * record it so later connects can enforce it. Shared by the shell and exec paths.
     */
    private void pinOnFirstUse(String machineName, SshTarget target, String presentedFingerprint) {
        if (target.pinnedFingerprint() == null && presentedFingerprint != null) {
            forTrackingHostKeys.pin(machineName, presentedFingerprint);
        }
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
