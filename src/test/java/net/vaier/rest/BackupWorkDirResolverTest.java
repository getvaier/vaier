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
