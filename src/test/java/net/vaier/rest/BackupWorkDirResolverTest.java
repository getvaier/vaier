package net.vaier.rest;

import net.vaier.application.RunRemoteCommandUseCase;
import net.vaier.domain.CommandResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BackupWorkDirResolverTest {

    RunRemoteCommandUseCase remoteCommand;
    BackupWorkDirResolver resolver;

    @BeforeEach
    void setUp() {
        remoteCommand = mock(RunRemoteCommandUseCase.class);
        resolver = new BackupWorkDirResolver(remoteCommand);
    }

    private CommandResult ok(String stdout) {
        return new CommandResult(0, stdout, "", false, "SHA256:x");
    }

    @Test
    void resolvesHomeIntoDotVaierBackup() {
        // The SSH user's $HOME is writable by that user, so the run's work dir is <home>/.vaier-backup.
        when(remoteCommand.run(eq("Colina 27"), any())).thenReturn(ok("/home/geir"));

        assertThat(resolver.workDirFor("Colina 27")).isEqualTo("/home/geir/.vaier-backup");
    }

    @Test
    void probesTheHomeVariableOverSsh() {
        when(remoteCommand.run(eq("Colina 27"), any())).thenReturn(ok("/home/geir"));

        resolver.workDirFor("Colina 27");

        // The probe prints $HOME with no trailing newline so the resolved path is exact.
        verify(remoteCommand).run(eq("Colina 27"), eq(BackupWorkDirResolver.HOME_PROBE));
    }

    @Test
    void cachesAResolvedHomeSoASecondCallDoesNotReprobe() {
        when(remoteCommand.run(eq("Colina 27"), any())).thenReturn(ok("/home/geir"));

        assertThat(resolver.workDirFor("Colina 27")).isEqualTo("/home/geir/.vaier-backup");
        assertThat(resolver.workDirFor("Colina 27")).isEqualTo("/home/geir/.vaier-backup");

        verify(remoteCommand, times(1)).run(eq("Colina 27"), any());
    }

    @Test
    void fallsBackToTmpWhenProbeTimesOut() {
        when(remoteCommand.run(eq("Colina 27"), any()))
            .thenReturn(new CommandResult(-1, "", "timeout", true, null));

        assertThat(resolver.workDirFor("Colina 27")).isEqualTo(BackupWorkDirResolver.FALLBACK_WORK_DIR);
        assertThat(BackupWorkDirResolver.FALLBACK_WORK_DIR).isEqualTo("/tmp/vaier-backup");
    }

    @Test
    void fallsBackToTmpWhenProbeExitsNonZero() {
        when(remoteCommand.run(eq("Colina 27"), any()))
            .thenReturn(new CommandResult(1, "", "boom", false, "SHA256:x"));

        assertThat(resolver.workDirFor("Colina 27")).isEqualTo(BackupWorkDirResolver.FALLBACK_WORK_DIR);
    }

    @Test
    void fallsBackToTmpWhenStdoutIsBlank() {
        when(remoteCommand.run(eq("Colina 27"), any())).thenReturn(ok("   "));

        assertThat(resolver.workDirFor("Colina 27")).isEqualTo(BackupWorkDirResolver.FALLBACK_WORK_DIR);
    }

    @Test
    void fallsBackToTmpWhenStdoutIsNotAnAbsolutePath() {
        // A non-absolute stdout (e.g. a stray "STARTED 1234" or an unexpanded var) must not become a work dir.
        when(remoteCommand.run(eq("Colina 27"), any())).thenReturn(ok("STARTED 1234"));

        assertThat(resolver.workDirFor("Colina 27")).isEqualTo(BackupWorkDirResolver.FALLBACK_WORK_DIR);
    }

    // --- homeFor: the SSH user's absolute home, which a "Back up as root" run must hand to sudo as HOME ---

    /**
     * A back-up-as-root run must set HOME to the SSH USER's home (root's ssh would otherwise look in /root for
     * the borg client key and the pinned NAS host key, and find neither). That home is resolved here, in the
     * orchestration, as an absolute literal — the same doctrine as the work dir.
     */
    @Test
    void homeForResolvesTheSshUsersAbsoluteHome() {
        when(remoteCommand.run(eq("Colina 27"), any())).thenReturn(ok("/home/geir"));

        assertThat(resolver.homeFor("Colina 27")).contains("/home/geir");
    }

    @Test
    void homeForAndWorkDirForShareOneProbeAndOneCache() {
        when(remoteCommand.run(eq("Colina 27"), any())).thenReturn(ok("/home/geir"));

        assertThat(resolver.workDirFor("Colina 27")).isEqualTo("/home/geir/.vaier-backup");
        assertThat(resolver.homeFor("Colina 27")).contains("/home/geir");

        verify(remoteCommand, times(1)).run(eq("Colina 27"), any());
    }

    /**
     * When the home cannot be resolved, homeFor is EMPTY — never a guess. The work dir can fall back to /tmp
     * (any user can write there), but there is no safe fallback for HOME: a wrong one sends root's ssh looking
     * for a key that is not there, and the run dies at the NAS with a misleading "Permission denied". The
     * orchestration must refuse the as-root run instead.
     */
    @Test
    void homeForIsEmptyWhenItCannotBeResolved_neverAGuess() {
        when(remoteCommand.run(eq("Colina 27"), any()))
            .thenReturn(new CommandResult(-1, "", "timeout", true, null));

        assertThat(resolver.homeFor("Colina 27")).isEmpty();
        // And it certainly never passes off the /tmp work-dir fallback as a home — the work dir may fall back,
        // the home may not.
        assertThat(resolver.workDirFor("Colina 27")).isEqualTo(BackupWorkDirResolver.FALLBACK_WORK_DIR);
        assertThat(resolver.homeFor("Colina 27")).isNotEqualTo(
            java.util.Optional.of(BackupWorkDirResolver.FALLBACK_WORK_DIR));
    }

    @Test
    void homeForIsEmptyWhenTheProbeReturnsANonAbsolutePath() {
        when(remoteCommand.run(eq("Colina 27"), any())).thenReturn(ok("STARTED 1234"));

        assertThat(resolver.homeFor("Colina 27")).isEmpty();
    }

    @Test
    void fallbackIsNotCachedSoALaterProbeCanStillResolve() {
        // A transient failure must never poison the cache: after a fallback, a later successful probe resolves.
        when(remoteCommand.run(eq("Colina 27"), any()))
            .thenReturn(new CommandResult(-1, "", "timeout", true, null))
            .thenReturn(ok("/home/geir"));

        assertThat(resolver.workDirFor("Colina 27")).isEqualTo(BackupWorkDirResolver.FALLBACK_WORK_DIR);
        assertThat(resolver.workDirFor("Colina 27")).isEqualTo("/home/geir/.vaier-backup");
    }
}
