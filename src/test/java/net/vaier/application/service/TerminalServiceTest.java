package net.vaier.application.service;

import net.vaier.application.OpenTerminalSessionUseCase.OpenedTerminal;
import net.vaier.application.SendHostPasswordUseCase;
import net.vaier.domain.AuthMethod;
import net.vaier.domain.ClaudeSignIn;
import net.vaier.domain.CommandResult;
import net.vaier.domain.FleetCredential;
import net.vaier.domain.FleetCredentialView;
import net.vaier.domain.HostCredential;
import net.vaier.domain.HostCredentialView;
import net.vaier.domain.LanAnchor;
import net.vaier.domain.MachineId;
import net.vaier.domain.NoHostCredentialException;
import net.vaier.domain.NotFoundException;
import net.vaier.domain.PersistentShell;
import net.vaier.domain.RunningShell;
import net.vaier.domain.SshAuthException;
import net.vaier.domain.SshConnectException;
import net.vaier.domain.SshCredentialDraft;
import net.vaier.domain.SshCredentialVerification;
import net.vaier.domain.SshServerPresence;
import net.vaier.domain.SshTarget;
import net.vaier.domain.TestMachineIds;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TerminalServiceTest {

    private static MachineId mid(String name) {
        return TestMachineIds.of(name);
    }

    @Mock ForPersistingHostCredentials forPersistingHostCredentials;
    @Mock ForPersistingFleetCredentials forPersistingFleetCredentials;
    @Mock ForResolvingSshTargets forResolvingSshTargets;
    @Mock ForOpeningSshSessions forOpeningSshSessions;
    @Mock ForRunningSshCommands forRunningSshCommands;
    @Mock ForTrackingHostKeys forTrackingHostKeys;
    @Mock ForVerifyingSshCredentials forVerifyingSshCredentials;
    @Mock ForCheckingSshServerPresence forCheckingSshServerPresence;
    @Mock ForGeneratingSshKeypairs forGeneratingSshKeypairs;

    // There is no name->id crossing left to arrange: every path into this service is handed an identity,
    // and nothing it does resolves a name to find anything.
    @Mock SshOutputListener onOutput;
    @Mock SshSession sshSession;

    @InjectMocks TerminalService service;

    private HostCredential passwordCred(String machine) {
        return new HostCredential(mid(machine), "root", AuthMethod.PASSWORD, "pw", null, false);
    }

    /** Stub the resolver port: a machine now becomes an SshTarget in one place (MachineSshTargetAdapter). */
    private void machineResolvesTo(String machine, String host, String pinnedFingerprint) {
        when(forResolvingSshTargets.resolve(mid(machine)))
            .thenReturn(SshTarget.on(host, passwordCred(machine), pinnedFingerprint));
    }

    // --- credential vault (slice 1, unchanged) ---

    @Test
    void saveHostCredential_persistsViaPort() {
        service.saveHostCredential(mid("nas"), new SshCredentialDraft(
            "admin", AuthMethod.PASSWORD, "s3cret", null));

        verify(forPersistingHostCredentials).save(new HostCredential(
            mid("nas"), "admin", AuthMethod.PASSWORD, "s3cret", null, false));
    }

    @Test
    void getHostCredential_returnsRedactedView_neverSecretBytes() {
        when(forPersistingHostCredentials.getByMachine(mid("nas"))).thenReturn(Optional.of(
            new HostCredential(mid("nas"), "admin", AuthMethod.PRIVATE_KEY, "-----BEGIN KEY-----", "keypass", false)));

        Optional<HostCredentialView> view = service.getHostCredential(mid("nas"));

        assertThat(view).contains(new HostCredentialView(mid("nas"), "admin", AuthMethod.PRIVATE_KEY, true, false));
    }

    // --- managed keypairs (#309) ---

    @Test
    void generateManagedKeypair_storesTheGeneratedPrivateKeyAsAManagedCredential() {
        when(forGeneratingSshKeypairs.generatePrivateKey(anyString())).thenReturn("PRIV");

        service.generateManagedKeypair(mid("nas"), "admin");

        verify(forPersistingHostCredentials).save(new HostCredential(
            mid("nas"), "admin", AuthMethod.PRIVATE_KEY, "PRIV", null, true));
    }

    @Test
    void generateManagedKeypair_returnsThePublicKeyToInstallOnTheHost() {
        when(forGeneratingSshKeypairs.generatePrivateKey(anyString())).thenReturn("PRIV");
        when(forGeneratingSshKeypairs.publicKeyFor(eq("PRIV"), eq(null), anyString()))
            .thenReturn("ssh-ed25519 AAAA vaier");

        assertThat(service.generateManagedKeypair(mid("nas"), "admin")).isEqualTo("ssh-ed25519 AAAA vaier");
    }

    @Test
    void generateManagedKeypair_replacesWhateverCredentialWasHeld() {
        // The vault holds one credential per machine, so this destroys the previous login. That is why the
        // operator has to confirm it at the driving edge — the service does not second-guess a decision
        // that has already been taken.
        when(forGeneratingSshKeypairs.generatePrivateKey(anyString())).thenReturn("PRIV");

        service.generateManagedKeypair(mid("nas"), "admin");

        ArgumentCaptor<HostCredential> saved = ArgumentCaptor.forClass(HostCredential.class);
        verify(forPersistingHostCredentials).save(saved.capture());
        assertThat(saved.getValue().managed()).isTrue();
        assertThat(saved.getValue().secret()).isEqualTo("PRIV");
    }

    @Test
    void getHostPublicKey_derivesItFromTheStoredPrivateKey() {
        when(forPersistingHostCredentials.getByMachine(mid("nas"))).thenReturn(Optional.of(
            new HostCredential(mid("nas"), "admin", AuthMethod.PRIVATE_KEY, "PRIV", null, true)));
        when(forGeneratingSshKeypairs.publicKeyFor(eq("PRIV"), eq(null), anyString()))
            .thenReturn("ssh-ed25519 AAAA vaier");

        assertThat(service.getHostPublicKey(mid("nas"))).contains("ssh-ed25519 AAAA vaier");
    }

    @Test
    void getHostPublicKey_passwordCredential_isEmpty() {
        when(forPersistingHostCredentials.getByMachine(mid("nas")))
            .thenReturn(Optional.of(passwordCred("nas")));

        assertThat(service.getHostPublicKey(mid("nas"))).isEmpty();
    }

    @Test
    void getHostPublicKey_noCredential_isEmpty() {
        when(forPersistingHostCredentials.getByMachine(mid("ghost"))).thenReturn(Optional.empty());

        assertThat(service.getHostPublicKey(mid("ghost"))).isEmpty();
    }

    @Test
    void getHostPublicKey_unreadableStoredKey_isEmpty_ratherThanFailingTheWholeDialog() {
        when(forPersistingHostCredentials.getByMachine(mid("nas"))).thenReturn(Optional.of(
            new HostCredential(mid("nas"), "admin", AuthMethod.PRIVATE_KEY, "junk", null, false)));
        when(forGeneratingSshKeypairs.publicKeyFor(eq("junk"), eq(null), anyString()))
            .thenThrow(new IllegalStateException("unreadable"));

        assertThat(service.getHostPublicKey(mid("nas"))).isEmpty();
    }

    @Test
    void deleteHostCredential_deletesViaPort() {
        service.deleteHostCredential(mid("nas"));
        verify(forPersistingHostCredentials).deleteByMachine(mid("nas"));
    }

    @Test
    void getSshServerPresence_readsViaPort() {
        when(forCheckingSshServerPresence.getPresence(mid("kitchen"))).thenReturn(SshServerPresence.ABSENT);

        assertThat(service.getSshServerPresence(mid("kitchen"))).isEqualTo(SshServerPresence.ABSENT);
    }

    // --- pre-registration SSH credential verify ("Add a machine" slice 2, Part A) ---

    @Test
    void verifySshCredential_success_isAuthenticatedWithFingerprint_andPersistsNothing() {
        // The probe reaches the host and the credential authenticates; the fingerprint is returned for
        // display only. A pre-registration test must never write to the vault or pin a host key.
        SshCredentialDraft draft =
            new SshCredentialDraft("root", AuthMethod.PASSWORD, "pw", null);
        when(forVerifyingSshCredentials.probe(any())).thenReturn("SHA256:abc");

        SshCredentialVerification result =
            service.verify("192.168.3.50", 22, draft);

        assertThat(result.reachable()).isTrue();
        assertThat(result.authenticated()).isTrue();
        assertThat(result.fingerprint()).isEqualTo("SHA256:abc");
        // The target carries the address, the draft's login, and no pin (nothing is trusted yet).
        ArgumentCaptor<SshTarget> target = ArgumentCaptor.forClass(SshTarget.class);
        verify(forVerifyingSshCredentials).probe(target.capture());
        assertThat(target.getValue().host()).isEqualTo("192.168.3.50");
        assertThat(target.getValue().port()).isEqualTo(22);
        assertThat(target.getValue().pinnedFingerprint()).isNull();
        verify(forPersistingHostCredentials, never()).save(any());
        verify(forTrackingHostKeys, never()).pin(any(), any());
    }

    @Test
    void verifySshCredential_wrongSecret_isNotAuthenticated_andPersistsNothing() {
        SshCredentialDraft draft =
            new SshCredentialDraft("root", AuthMethod.PASSWORD, "wrong", null);
        when(forVerifyingSshCredentials.probe(any()))
            .thenThrow(new SshAuthException("rejected"));

        SshCredentialVerification result =
            service.verify("192.168.3.50", 22, draft);

        assertThat(result.reachable()).isTrue();
        assertThat(result.authenticated()).isFalse();
        verify(forPersistingHostCredentials, never()).save(any());
        verify(forTrackingHostKeys, never()).pin(any(), any());
    }

    @Test
    void verifySshCredential_unreachableHost_isNotReachable_andPersistsNothing() {
        SshCredentialDraft draft =
            new SshCredentialDraft("root", AuthMethod.PASSWORD, "pw", null);
        when(forVerifyingSshCredentials.probe(any()))
            .thenThrow(new SshConnectException("no route to host"));

        SshCredentialVerification result =
            service.verify("192.168.3.50", 22, draft);

        assertThat(result.reachable()).isFalse();
        assertThat(result.authenticated()).isFalse();
        verify(forPersistingHostCredentials, never()).save(any());
        verify(forTrackingHostKeys, never()).pin(any(), any());
    }

    // --- terminal (slice 2) ---

    private CommandResult probe(String markerFingerprint, String stdout) {
        return new CommandResult(0, stdout, "", false, markerFingerprint);
    }

    @Test
    void openTerminal_peer_resolvesTunnelIp_opensPersistentShell_pinsOnFirstUse() {
        machineResolvesTo("nuc", "10.13.13.9", null);
        // The probe (a remote command) is the first connection — it presents the fingerprint and pins it.
        when(forRunningSshCommands.run(any(), any())).thenReturn(probe("SHA256:fresh", "VAIER_TMUX_NEW"));
        when(forOpeningSshSessions.open(any(), any(), any())).thenReturn(sshSession);

        OpenedTerminal result = service.openTerminal(mid("nuc"), "pane1", onOutput);

        assertThat(result.session()).isSameAs(sshSession);
        assertThat(result.continuity()).isEqualTo(PersistentShell.Continuity.NEW);
        // The shell command is the pane's tmux attach-or-create with the plain-shell fallback.
        ArgumentCaptor<SshTarget> target = ArgumentCaptor.forClass(SshTarget.class);
        ArgumentCaptor<String> command = ArgumentCaptor.forClass(String.class);
        verify(forOpeningSshSessions).open(target.capture(), command.capture(), any());
        assertThat(target.getValue().host()).isEqualTo("10.13.13.9");   // tunnel IP
        assertThat(command.getValue()).contains("tmux new-session -A -D -s 'vaier-pane1'");
        verify(forTrackingHostKeys).pin(mid("nuc"), "SHA256:fresh");         // TOFU pin on first use
    }

    @Test
    void openTerminal_existingTmuxSession_reportsReattached() {
        machineResolvesTo("nuc", "10.13.13.9", "SHA256:pinned");
        when(forRunningSshCommands.run(any(), any())).thenReturn(probe("SHA256:pinned", "VAIER_TMUX_ATTACH"));
        when(forOpeningSshSessions.open(any(), any(), any())).thenReturn(sshSession);

        OpenedTerminal result = service.openTerminal(mid("nuc"), "pane1", onOutput);

        assertThat(result.continuity()).isEqualTo(PersistentShell.Continuity.REATTACHED);
    }

    @Test
    void openTerminal_tmuxAbsent_reportsPlain() {
        machineResolvesTo("nuc", "10.13.13.9", "SHA256:pinned");
        when(forRunningSshCommands.run(any(), any())).thenReturn(probe("SHA256:pinned", "VAIER_TMUX_ABSENT"));
        when(forOpeningSshSessions.open(any(), any(), any())).thenReturn(sshSession);

        OpenedTerminal result = service.openTerminal(mid("nuc"), "pane1", onOutput);

        assertThat(result.continuity()).isEqualTo(PersistentShell.Continuity.PLAIN);
    }

    @Test
    void openTerminal_lanServer_resolvesLanAddress() {
        machineResolvesTo("nas", "192.168.3.50", "SHA256:pinned");
        when(forRunningSshCommands.run(any(), any())).thenReturn(probe("SHA256:pinned", "VAIER_TMUX_NEW"));
        when(forOpeningSshSessions.open(any(), any(), any())).thenReturn(sshSession);

        service.openTerminal(mid("nas"), "pane1", onOutput);

        ArgumentCaptor<SshTarget> target = ArgumentCaptor.forClass(SshTarget.class);
        verify(forOpeningSshSessions).open(target.capture(), any(), any());
        assertThat(target.getValue().host()).isEqualTo("192.168.3.50");
        assertThat(target.getValue().pinnedFingerprint()).isEqualTo("SHA256:pinned");
        verify(forTrackingHostKeys, never()).pin(any(), any());   // already pinned → no re-pin
    }

    @Test
    void openTerminal_vaierServer_resolvesHostAddress() {
        machineResolvesTo(LanAnchor.VAIER_SERVER_NAME, "172.17.0.1", null);
        when(forRunningSshCommands.run(any(), any())).thenReturn(probe("SHA256:host", "VAIER_TMUX_NEW"));
        when(forOpeningSshSessions.open(any(), any(), any())).thenReturn(sshSession);

        service.openTerminal(mid(LanAnchor.VAIER_SERVER_NAME), "pane1", onOutput);

        ArgumentCaptor<SshTarget> target = ArgumentCaptor.forClass(SshTarget.class);
        verify(forOpeningSshSessions).open(target.capture(), any(), any());
        assertThat(target.getValue().host()).isEqualTo("172.17.0.1");
    }

    @Test
    void openTerminal_noCredential_throwsNoHostCredential_andDoesNotOpen() {
        when(forResolvingSshTargets.resolve(mid("nuc"))).thenThrow(new NoHostCredentialException("nuc"));

        assertThatThrownBy(() -> service.openTerminal(mid("nuc"), "pane1", onOutput))
            .isInstanceOf(NoHostCredentialException.class);
        verify(forOpeningSshSessions, never()).open(any(), any(), any());
    }

    @Test
    void openTerminal_unknownMachine_throwsNotFound() {
        when(forResolvingSshTargets.resolve(mid("ghost")))
            .thenThrow(new NotFoundException("Machine not found: ghost"));

        assertThatThrownBy(() -> service.openTerminal(mid("ghost"), "pane1", onOutput))
            .isInstanceOf(NotFoundException.class);
    }

    @Test
    void clearHostKey_clearsViaPort() {
        service.clearHostKey(mid("nas"));
        verify(forTrackingHostKeys).clear(mid("nas"));
    }

    // --- remote command (slice 3 keystone reuse) ---

    @Test
    void run_resolvesTarget_fromSameAddressCredentialAndPinLogic() {
        machineResolvesTo("nuc", "10.13.13.9", "SHA256:pinned");
        when(forRunningSshCommands.run(any(), any()))
            .thenReturn(new CommandResult(0, "hello", "", false, "SHA256:pinned"));

        CommandResult result = service.run(mid("nuc"), "echo hello");

        assertThat(result.stdout()).isEqualTo("hello");
        ArgumentCaptor<SshTarget> target = ArgumentCaptor.forClass(SshTarget.class);
        verify(forRunningSshCommands).run(target.capture(), eq("echo hello"));
        assertThat(target.getValue().host()).isEqualTo("10.13.13.9");
        assertThat(target.getValue().pinnedFingerprint()).isEqualTo("SHA256:pinned");
        verify(forTrackingHostKeys, never()).pin(any(), any()); // already pinned → no re-pin
    }

    @Test
    void run_firstUseUnpinnedHost_pinsPresentedFingerprint() {
        machineResolvesTo("nuc", "10.13.13.9", null);
        when(forRunningSshCommands.run(any(), any()))
            .thenReturn(new CommandResult(0, "ok", "", false, "SHA256:fresh"));

        service.run(mid("nuc"), "echo ok");

        ArgumentCaptor<SshTarget> target = ArgumentCaptor.forClass(SshTarget.class);
        verify(forRunningSshCommands).run(target.capture(), any());
        assertThat(target.getValue().pinnedFingerprint()).isNull();
        verify(forTrackingHostKeys).pin(mid("nuc"), "SHA256:fresh"); // TOFU pin on first use
    }

    @Test
    void run_noCredential_throwsNoHostCredential_andDoesNotRun() {
        when(forResolvingSshTargets.resolve(mid("nuc"))).thenThrow(new NoHostCredentialException("nuc"));

        assertThatThrownBy(() -> service.run(mid("nuc"), "echo hi"))
            .isInstanceOf(NoHostCredentialException.class);
        verify(forRunningSshCommands, never()).run(any(), any());
    }

    // --- send stored password to a live prompt (feature: never via the browser) ---

    @Test
    void sendPassword_atPrompt_writesSecretNewline_exactlyOnce() {
        when(forPersistingHostCredentials.getByMachine(mid("nas"))).thenReturn(Optional.of(
            new HostCredential(mid("nas"), "root", AuthMethod.PASSWORD, "s3cret", null, false)));

        SendHostPasswordUseCase.SendPasswordResult result =
            service.sendPassword(mid("nas"), sshSession, "geir@nas's password: ");

        assertThat(result).isEqualTo(SendHostPasswordUseCase.SendPasswordResult.SENT);
        verify(sshSession).write("s3cret\n".getBytes(StandardCharsets.UTF_8));
        verify(sshSession, org.mockito.Mockito.times(1)).write(any());
    }

    @Test
    void sendPassword_result_neverCarriesTheSecret() {
        when(forPersistingHostCredentials.getByMachine(mid("nas"))).thenReturn(Optional.of(
            new HostCredential(mid("nas"), "root", AuthMethod.PASSWORD, "s3cret", null, false)));

        SendHostPasswordUseCase.SendPasswordResult result =
            service.sendPassword(mid("nas"), sshSession, "Password: ");

        assertThat(result.name()).doesNotContain("s3cret");
    }

    @Test
    void sendPassword_notAtPrompt_writesNothing() {
        when(forPersistingHostCredentials.getByMachine(mid("nas"))).thenReturn(Optional.of(
            new HostCredential(mid("nas"), "root", AuthMethod.PASSWORD, "s3cret", null, false)));

        SendHostPasswordUseCase.SendPasswordResult result =
            service.sendPassword(mid("nas"), sshSession, "geir@nas:~$ ");

        assertThat(result).isEqualTo(SendHostPasswordUseCase.SendPasswordResult.NOT_AT_PROMPT);
        verify(sshSession, never()).write(any());
    }

    @Test
    void sendPassword_keyAuthCredential_returnsNoPasswordCredential_writesNothing() {
        when(forPersistingHostCredentials.getByMachine(mid("nas"))).thenReturn(Optional.of(
            new HostCredential(mid("nas"), "root", AuthMethod.PRIVATE_KEY, "-----BEGIN KEY-----", null, true)));

        SendHostPasswordUseCase.SendPasswordResult result =
            service.sendPassword(mid("nas"), sshSession, "Password: ");

        assertThat(result).isEqualTo(SendHostPasswordUseCase.SendPasswordResult.NO_PASSWORD_CREDENTIAL);
        verify(sshSession, never()).write(any());
    }

    @Test
    void sendPassword_missingCredential_returnsNoPasswordCredential_writesNothing() {
        when(forPersistingHostCredentials.getByMachine(mid("nas"))).thenReturn(Optional.empty());

        SendHostPasswordUseCase.SendPasswordResult result =
            service.sendPassword(mid("nas"), sshSession, "Password: ");

        assertThat(result).isEqualTo(SendHostPasswordUseCase.SendPasswordResult.NO_PASSWORD_CREDENTIAL);
        verify(sshSession, never()).write(any());
    }

    @Test
    void sendPassword_throwingSession_returnsFailed_doesNotPropagate() {
        when(forPersistingHostCredentials.getByMachine(mid("nas"))).thenReturn(Optional.of(
            new HostCredential(mid("nas"), "root", AuthMethod.PASSWORD, "s3cret", null, false)));
        org.mockito.Mockito.doThrow(new RuntimeException("pipe broken")).when(sshSession).write(any());

        SendHostPasswordUseCase.SendPasswordResult result =
            service.sendPassword(mid("nas"), sshSession, "Password: ");

        assertThat(result).isEqualTo(SendHostPasswordUseCase.SendPasswordResult.FAILED);
    }

    // --- ending a shell (the leak fix): an explicit close must kill the tmux session -------------

    @Test
    void listShells_runsTheListCommand_andReadsTheShellsRunningThere() {
        machineResolvesTo("nuc", "10.13.13.9", "SHA256:pinned");
        when(forRunningSshCommands.run(any(), any()))
            .thenReturn(probe("SHA256:pinned", "VAIER_NOW 100\nvaier-p1\t90\t95\t0\tclaude\n"));

        List<RunningShell> shells = service.listShells(mid("nuc"));

        ArgumentCaptor<String> command = ArgumentCaptor.forClass(String.class);
        verify(forRunningSshCommands).run(any(), command.capture());
        assertThat(command.getValue()).contains("tmux list-sessions");
        assertThat(shells).extracting(RunningShell::paneId).containsExactly("p1");
        assertThat(shells.get(0).running()).isEqualTo("claude");
    }

    @Test
    void listShells_unreachableHost_readsAsNoShells_andNeverThrows() {
        // This runs on the open path of a terminal window; a host that is down or has no credential must
        // never keep the window from opening.
        when(forResolvingSshTargets.resolve(mid("nuc"))).thenThrow(new NoHostCredentialException("nuc"));

        assertThat(service.listShells(mid("nuc"))).isEmpty();
    }

    @Test
    void endTerminal_killsThePanesTmuxSession_onThatMachine() {
        machineResolvesTo("nuc", "10.13.13.9", "SHA256:pinned");
        when(forRunningSshCommands.run(any(), any())).thenReturn(probe("SHA256:pinned", ""));

        service.endTerminal(mid("nuc"), "pane1");

        // Without this the session lingers detached on the host forever, still running whatever was in it.
        ArgumentCaptor<SshTarget> target = ArgumentCaptor.forClass(SshTarget.class);
        ArgumentCaptor<String> command = ArgumentCaptor.forClass(String.class);
        verify(forRunningSshCommands).run(target.capture(), command.capture());
        assertThat(target.getValue().host()).isEqualTo("10.13.13.9");
        assertThat(command.getValue()).contains("tmux kill-session -t 'vaier-pane1'");
    }

    @Test
    void endTerminal_unreachableHost_doesNotThrow() {
        // Ending a shell is best-effort cleanup on a close path — a host that is down or has no credential
        // must not surface an error to the operator, who has already closed the pane and moved on.
        when(forResolvingSshTargets.resolve(mid("nuc"))).thenThrow(new NoHostCredentialException("nuc"));

        assertThatCode(() -> service.endTerminal(mid("nuc"), "pane1")).doesNotThrowAnyException();
        verify(forRunningSshCommands, never()).run(any(), any());
    }

    // ---- fleet credentials (the vault's other half) -----------------------------------------

    private static FleetCredential fleetCredential() {
        return FleetCredential.of("claude-oauth", "~/.claude/.credentials.json", "0600",
            "{\"token\":\"totally-secret-value\"}");
    }

    @Test
    void saveFleetCredential_storesItInTheVault() {
        when(forPersistingFleetCredentials.getByName("claude-oauth")).thenReturn(Optional.empty());

        service.saveFleetCredential(fleetCredential());

        verify(forPersistingFleetCredentials).save(fleetCredential());
    }

    @Test
    void saveFleetCredential_keepsTheDistributedStandingOfTheCredentialItReplaces() {
        when(forPersistingFleetCredentials.getByName("claude-oauth"))
            .thenReturn(Optional.of(fleetCredential().markDistributed()));

        service.saveFleetCredential(
            FleetCredential.of("claude-oauth", "~/.claude/.credentials.json", "0640", "new-content"));

        ArgumentCaptor<FleetCredential> saved = ArgumentCaptor.forClass(FleetCredential.class);
        verify(forPersistingFleetCredentials).save(saved.capture());
        assertThat(saved.getValue().distributed()).isTrue();
        assertThat(saved.getValue().mode()).isEqualTo("0640");
        assertThat(saved.getValue().content()).isEqualTo("new-content");
    }

    @Test
    void getFleetCredentials_returnsOnlyRedactedViews() {
        when(forPersistingFleetCredentials.getAll()).thenReturn(List.of(fleetCredential()));

        List<FleetCredentialView> views = service.getFleetCredentials();

        assertThat(views).hasSize(1);
        assertThat(views.get(0).name()).isEqualTo("claude-oauth");
        assertThat(views.get(0).hasSecret()).isTrue();
        assertThat(views.toString()).doesNotContain("totally-secret-value");
    }

    @Test
    void deleteFleetCredential_removesItFromTheVault() {
        service.deleteFleetCredential("claude-oauth");

        verify(forPersistingFleetCredentials).deleteByName("claude-oauth");
    }

    // --- Claude sign-in shell -----------------------------------------------------------------------

    /**
     * A Claude sign-in opens Anthropic's own binary, unmodified, in its own reserved persistent shell —
     * the same address resolution, the same vault credential and the same trust-on-first-use pin every
     * other remote shell gets.
     */
    @Test
    void openClaudeSignInShell_runsTheUnmodifiedCliInItsReservedShellAndPinsOnFirstUse() {
        machineResolvesTo("nuc", "10.13.13.9", null);
        when(sshSession.hostKeyFingerprint()).thenReturn("SHA256:fresh");
        when(forOpeningSshSessions.open(any(), any(), any())).thenReturn(sshSession);

        SshSession opened = service.openClaudeSignInShell(mid("nuc"), onOutput);

        assertThat(opened).isSameAs(sshSession);
        ArgumentCaptor<SshTarget> target = ArgumentCaptor.forClass(SshTarget.class);
        ArgumentCaptor<String> command = ArgumentCaptor.forClass(String.class);
        verify(forOpeningSshSessions).open(target.capture(), command.capture(), eq(onOutput));
        assertThat(target.getValue().host()).isEqualTo("10.13.13.9");
        assertThat(command.getValue()).isEqualTo(ClaudeSignIn.startCommand());
        verify(forTrackingHostKeys).pin(mid("nuc"), "SHA256:fresh");
    }

    /**
     * Anthropic's terms forbid a third party storing or intermediating Claude credentials, so a sign-in
     * writes to no store at all. Nothing about it is Vaier's to keep.
     */
    @Test
    void openClaudeSignInShell_persistsNothing() {
        machineResolvesTo("nuc", "10.13.13.9", "SHA256:pinned");
        when(sshSession.hostKeyFingerprint()).thenReturn("SHA256:pinned");
        when(forOpeningSshSessions.open(any(), any(), any())).thenReturn(sshSession);

        service.openClaudeSignInShell(mid("nuc"), onOutput);

        verify(forPersistingHostCredentials, never()).save(any());
        verify(forPersistingFleetCredentials, never()).save(any());
    }
}
