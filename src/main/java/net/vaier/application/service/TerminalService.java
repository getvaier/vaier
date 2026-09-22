package net.vaier.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.ClearHostKeyUseCase;
import net.vaier.application.DeleteFleetCredentialUseCase;
import net.vaier.application.DeleteHostCredentialUseCase;
import net.vaier.application.EndTerminalSessionUseCase;
import net.vaier.application.ListPersistentShellsUseCase;
import net.vaier.application.GenerateManagedKeypairUseCase;
import net.vaier.application.GetFleetCredentialsUseCase;
import net.vaier.application.GetHostCredentialUseCase;
import net.vaier.application.GetHostPublicKeyUseCase;
import net.vaier.application.GetSshServerPresenceUseCase;
import net.vaier.application.OpenClaudeSignInShellUseCase;
import net.vaier.application.OpenTerminalSessionUseCase;
import net.vaier.application.OpenTerminalSessionUseCase.OpenedTerminal;
import net.vaier.application.RunRemoteCommandUseCase;
import net.vaier.application.SaveFleetCredentialUseCase;
import net.vaier.application.SaveHostCredentialUseCase;
import net.vaier.application.SendHostPasswordUseCase;
import net.vaier.application.VerifySshCredentialUseCase;
import net.vaier.domain.AuthMethod;
import net.vaier.domain.ClaudeSignIn;
import net.vaier.domain.CommandResult;
import net.vaier.domain.FleetCredential;
import net.vaier.domain.FleetCredentialView;
import net.vaier.domain.HostCredential;
import net.vaier.domain.HostCredentialView;
import net.vaier.domain.MachineId;
import net.vaier.domain.PasswordPrompt;
import net.vaier.domain.PersistentShell;
import net.vaier.domain.RunningShell;
import net.vaier.domain.SshCredentialDraft;
import net.vaier.domain.SshCredentialVerification;
import net.vaier.domain.SshServerPresence;
import net.vaier.domain.SshTarget;
import net.vaier.domain.port.ForCheckingSshServerPresence;
import net.vaier.domain.port.ForGeneratingSshKeypairs;
import net.vaier.domain.port.ForOpeningSshSessions;
import net.vaier.domain.port.ForOpeningSshSessions.SshOutputListener;
import net.vaier.domain.port.ForOpeningSshSessions.SshSession;
import net.vaier.domain.port.ForPersistingFleetCredentials;
import net.vaier.domain.port.ForPersistingHostCredentials;
import net.vaier.domain.port.ForResolvingSshTargets;
import net.vaier.domain.port.ForRunningSshCommands;
import net.vaier.domain.port.ForTrackingHostKeys;
import net.vaier.domain.port.ForVerifyingSshCredentials;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * The remote-shell / credential-vault domain service. It stores, reads (redacted) and deletes host
 * credentials (slice 1), and opens live SSH terminal sessions (slice 2): resolving a machine's SSH
 * address (peer tunnel IP / LAN address / Vaier host), authenticating from the vault, and pinning the
 * host key on first use. Reads go through the domain's {@link HostCredential#toView() redaction} so
 * raw secrets never leave the process.
 *
 * <p>Since the fleet-credential slice it owns the vault's other half too: a {@link FleetCredential} is a
 * secret that must exist <em>on</em> every machine, the mirror of a host credential Vaier uses to
 * <em>reach</em> one. Same vault, same cipher, same redaction discipline — which is exactly why it is a
 * method here rather than a service of its own. Distributing one is not: that needs the machine list this
 * service does not own, and it is composed at the driving edge by {@code rest.FleetCredentialDistributor}.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TerminalService implements
    SaveHostCredentialUseCase,
    GetHostCredentialUseCase,
    DeleteHostCredentialUseCase,
    OpenTerminalSessionUseCase,
    OpenClaudeSignInShellUseCase,
    EndTerminalSessionUseCase,
    ListPersistentShellsUseCase,
    RunRemoteCommandUseCase,
    SendHostPasswordUseCase,
    VerifySshCredentialUseCase,
    ClearHostKeyUseCase,
    GetSshServerPresenceUseCase,
    GenerateManagedKeypairUseCase,
    GetHostPublicKeyUseCase,
    SaveFleetCredentialUseCase,
    GetFleetCredentialsUseCase,
    DeleteFleetCredentialUseCase {

    private final ForPersistingHostCredentials forPersistingHostCredentials;
    private final ForPersistingFleetCredentials forPersistingFleetCredentials;
    private final ForResolvingSshTargets forResolvingSshTargets;
    private final ForOpeningSshSessions forOpeningSshSessions;
    private final ForRunningSshCommands forRunningSshCommands;
    private final ForTrackingHostKeys forTrackingHostKeys;
    private final ForVerifyingSshCredentials forVerifyingSshCredentials;
    private final ForCheckingSshServerPresence forCheckingSshServerPresence;
    private final ForGeneratingSshKeypairs forGeneratingSshKeypairs;

    @Override
    public void saveHostCredential(MachineId machineId, SshCredentialDraft draft) {
        // The draft knows how to become a vault credential; the machine's identity is what it needs, and
        // the caller already holds it — so a credential can no longer be filed against the wrong machine
        // by a name that was edited between the operator typing it and the save landing.
        forPersistingHostCredentials.save(draft.forMachine(machineId));
    }

    @Override
    public SshCredentialVerification verify(String address, int port, SshCredentialDraft credential) {
        // Orchestration only: the draft builds the pre-registration target (no pin), and the domain
        // maps the driven-port probe into the result. Nothing is persisted and nothing is pinned.
        return SshCredentialVerification.probe(credential.targetAt(address, port), forVerifyingSshCredentials);
    }

    @Override
    public Optional<HostCredentialView> getHostCredential(MachineId machineId) {
        return forPersistingHostCredentials.getByMachine(machineId).map(HostCredential::toView);
    }

    @Override
    public String generateManagedKeypair(MachineId machineId, String username) {
        // Orchestration only: the domain mints the credential — it decides key auth, no passphrase and
        // managed; which algorithm that is belongs to the port's adapter — and derives the public half.
        // This stores it and hands the public key back.
        HostCredential credential = HostCredential.generatedFor(machineId, username, forGeneratingSshKeypairs);
        forPersistingHostCredentials.save(credential);
        log.info("Generated a managed keypair for machine {} (user {})", machineId, username);
        return credential.publicKey(forGeneratingSshKeypairs);
    }

    @Override
    public Optional<String> getHostPublicKey(MachineId machineId) {
        return forPersistingHostCredentials.getByMachine(machineId)
            .map(credential -> {
                try {
                    return credential.publicKey(forGeneratingSshKeypairs);
                } catch (RuntimeException e) {
                    // A stored key Vaier cannot read has nothing to show, and this read exists only to
                    // populate a panel. Failing the whole credential dialog over it would hide the very
                    // controls the operator needs to replace that key.
                    log.warn("Could not derive the public key for machine {}: {}", machineId, e.toString());
                    return null;
                }
            });
    }

    @Override
    public void saveFleetCredential(FleetCredential credential) {
        // The domain decides what a save over a live credential inherits; this only reads the one being
        // replaced and hands it over. Nothing is distributed here — storing a secret is not pushing it.
        forPersistingFleetCredentials.save(credential.carryingStandingFrom(
            forPersistingFleetCredentials.getByName(credential.name())));
        log.info("Saved fleet credential {}", credential.name());
    }

    @Override
    public List<FleetCredentialView> getFleetCredentials() {
        return forPersistingFleetCredentials.getAll().stream().map(FleetCredential::toView).toList();
    }

    @Override
    public void deleteFleetCredential(String name) {
        forPersistingFleetCredentials.deleteByName(name);
        log.info("Deleted fleet credential {}", name);
    }

    @Override
    public void deleteHostCredential(MachineId machineId) {
        forPersistingHostCredentials.deleteByMachine(machineId);
    }

    @Override
    public OpenedTerminal openTerminal(MachineId machineId, String paneId, SshOutputListener onOutput) {
        SshTarget target = forResolvingSshTargets.resolve(machineId);
        // Probe first (a normal exec run, the same host-key trust as any command): is tmux installed on
        // this machine, and does the pane's session already exist? The domain reads it into a truthful
        // continuity, so the reconnect banner can say "reattached" only when it really was. This first
        // connection is also where an unpinned host is pinned on first use.
        CommandResult probe = forRunningSshCommands.run(target, PersistentShell.probeCommand(paneId));
        pinOnFirstUse(target, probe.hostKeyFingerprint());
        PersistentShell.Continuity continuity = PersistentShell.readProbe(probe.stdout());

        // Open the persistent shell: tmux attach-or-create for the pane, falling back to a plain login
        // shell when tmux is absent. The adapter enforces host-key trust and throws HostKeyMismatchException
        // on a changed key; other failures surface as SshAuth/SshConnect.
        SshSession session = forOpeningSshSessions.open(
            target, PersistentShell.attachOrCreateCommand(paneId), onOutput);

        // Logged by identity and address, never by name. A name here bought nothing a log reader needs —
        // the address says which machine — and buying it meant this service resolving one, which is the
        // last thing keeping a name->id registry alive.
        log.info("Opened {} terminal session to {} ({}) for pane {}",
            continuity, machineId, target.host(), PersistentShell.sessionName(paneId));
        return new OpenedTerminal(session, continuity);
    }

    @Override
    public SshSession openClaudeSignInShell(MachineId machineId, SshOutputListener onOutput) {
        SshTarget target = forResolvingSshTargets.resolve(machineId);
        // Anthropic's own binary, unmodified, in the shell the domain names — this service adds nothing to
        // the command and reads nothing out of the session. Everything a sign-in produces (the URL, the
        // code, the token) is Anthropic's, and none of it may be stored here or anywhere else in Vaier.
        SshSession session = forOpeningSshSessions.open(target, ClaudeSignIn.startCommand(), onOutput);
        pinOnFirstUse(target, session.hostKeyFingerprint());
        log.info("Opened a Claude sign-in shell on {} ({})", machineId, target.host());
        return session;
    }

    @Override
    public void endTerminal(MachineId machineId, String paneId) {
        // Best-effort: the operator has already closed the pane. A host that is down, has no credential, or
        // whose key no longer matches is not something they can act on from here — and leaving the session
        // behind on an unreachable host is no worse than the state we were already in. Log and move on.
        try {
            forRunningSshCommands.run(forResolvingSshTargets.resolve(machineId),
                PersistentShell.endCommand(paneId));
            log.info("Ended terminal session {} on {}", PersistentShell.sessionName(paneId), machineId);
        } catch (RuntimeException e) {
            log.warn("Could not end terminal session {} on {}: {}",
                PersistentShell.sessionName(paneId), machineId, e.toString());
        }
    }

    @Override
    public List<RunningShell> listShells(MachineId machineId) {
        // On the open path of a terminal window: a host that is down, has no credential, or has no tmux must
        // never keep the window from opening. Unreadable reads as no shells, and the domain decides what is ours.
        try {
            SshTarget target = forResolvingSshTargets.resolve(machineId);
            CommandResult result = forRunningSshCommands.run(target, PersistentShell.listCommand());
            pinOnFirstUse(target, result.hostKeyFingerprint());
            return PersistentShell.readShells(result.stdout());
        } catch (RuntimeException e) {
            log.warn("Could not list the shells running on {}: {}", machineId, e.toString());
            return List.of();
        }
    }

    @Override
    public CommandResult run(MachineId machineId, String command) {
        SshTarget target = forResolvingSshTargets.resolve(machineId);
        // Same host-key trust as the shell path: a changed key throws HostKeyMismatchException.
        CommandResult result = forRunningSshCommands.run(target, command);

        pinOnFirstUse(target, result.hostKeyFingerprint());
        return result;
    }

    @Override
    public SendPasswordResult sendPassword(MachineId machineId, SshSession session, String recentOutput) {
        try {
            Optional<HostCredential> credential = forPersistingHostCredentials.getByMachine(machineId);
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
            log.warn("Failed to send stored password to {}: {}", machineId, e.getClass().getSimpleName());
            return SendPasswordResult.FAILED;
        }
    }

    @Override
    public void clearHostKey(MachineId machineId) {
        forTrackingHostKeys.clear(machineId);
        log.info("Cleared pinned host key for {}", machineId);
    }

    @Override
    public SshServerPresence getSshServerPresence(MachineId machineId) {
        return forCheckingSshServerPresence.getPresence(machineId);
    }

    /**
     * Trust-on-first-use: if the target had nothing pinned and the connect presented a fingerprint,
     * record it so later connects can enforce it. Shared by the shell and exec paths.
     *
     * <p>The rule itself lives on {@link SshTarget#pinOnFirstUse} — every path that reaches a machine over
     * SSH (shell, exec, SFTP listing, disk reading) pins the same way, from one copy.
     */
    private void pinOnFirstUse(SshTarget target, String presentedFingerprint) {
        target.pinOnFirstUse(presentedFingerprint, forTrackingHostKeys);
    }
}
