package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BackupRunTest {

    private BackupJob job() {
        return new BackupJob("colina-home", "Colina 27", "nas-borg",
            List.of("/home/geir"), List.of(), 7, 4, 6, "zstd,6", true, false);
    }

    @Test
    void nonZeroExitIsFailureZeroIsSuccess() {
        Instant start = Instant.parse("2026-07-08T02:00:00Z");
        Instant end = Instant.parse("2026-07-08T02:05:00Z");

        BackupRun ok = BackupRun.fromExitCode(job(), "run-1", start, end, 0, "done");
        assertThat(ok.status()).isEqualTo(BackupRunStatus.SUCCESS);
        assertThat(ok.isFailure()).isFalse();
        assertThat(ok.exitCode()).isEqualTo(0);
        assertThat(ok.startedAt()).isEqualTo(start);
        assertThat(ok.finishedAt()).isEqualTo(end);

        BackupRun bad = BackupRun.fromExitCode(job(), "run-2", start, end, 2, "boom");
        assertThat(bad.status()).isEqualTo(BackupRunStatus.FAILED);
        assertThat(bad.isFailure()).isTrue();
        assertThat(bad.exitCode()).isEqualTo(2);
    }

    @Test
    void exitOneIsWarningNeitherSuccessNorFailure() {
        Instant start = Instant.parse("2026-07-08T02:00:00Z");
        Instant end = Instant.parse("2026-07-08T02:05:00Z");

        BackupRun warn = BackupRun.fromExitCode(job(), "run-w", start, end, 1,
            "2 files skipped (permission denied)");

        // Borg exit 1 means the archive was created but some files were skipped: a WARNING, not a failure.
        assertThat(warn.status()).isEqualTo(BackupRunStatus.WARNING);
        assertThat(warn.exitCode()).isEqualTo(1);
        assertThat(warn.isFailure()).isFalse();
        // WARNING is a settled outcome (never re-polled) but non-paging.
        assertThat(warn.status().isTerminal()).isTrue();
        assertThat(warn.status().isFailure()).isFalse();
    }

    @Test
    void completedFromExitOneSettlesToWarning() {
        Instant started = Instant.parse("2026-07-08T02:00:00Z");
        Instant done = Instant.parse("2026-07-08T02:40:00Z");
        BackupRun running = BackupRun.started(job(), "run-w2", started);

        BackupRun warn = running.completedFrom(1, done, "1 file skipped");

        assertThat(warn.status()).isEqualTo(BackupRunStatus.WARNING);
        assertThat(warn.exitCode()).isEqualTo(1);
        assertThat(warn.isFailure()).isFalse();
        // Identity of the run is preserved across completion.
        assertThat(warn.runId()).isEqualTo("run-w2");
        assertThat(warn.finishedAt()).isEqualTo(done);
    }

    @Test
    void startedIsRunningWithNoOutcomeYet() {
        Instant now = Instant.parse("2026-07-08T02:00:00Z");

        BackupRun run = BackupRun.started(job(), "run-3", now);

        assertThat(run.status()).isEqualTo(BackupRunStatus.RUNNING);
        assertThat(run.status().isTerminal()).isFalse();
        assertThat(run.startedAt()).isEqualTo(now);
        assertThat(run.finishedAt()).isNull();
        assertThat(run.exitCode()).isNull();
        assertThat(run.jobName()).isEqualTo("colina-home");
    }

    @Test
    void completedFromMapsExitCode() {
        Instant started = Instant.parse("2026-07-08T02:00:00Z");
        Instant done = Instant.parse("2026-07-08T02:40:00Z");
        BackupRun running = BackupRun.started(job(), "run-9", started);

        BackupRun ok = running.completedFrom(0, done, "12 files, 3 GB");
        assertThat(ok.status()).isEqualTo(BackupRunStatus.SUCCESS);
        assertThat(ok.exitCode()).isEqualTo(0);
        assertThat(ok.finishedAt()).isEqualTo(done);
        assertThat(ok.summary()).isEqualTo("12 files, 3 GB");
        // Identity of the run is preserved across completion.
        assertThat(ok.runId()).isEqualTo("run-9");
        assertThat(ok.jobName()).isEqualTo("colina-home");
        assertThat(ok.startedAt()).isEqualTo(started);

        BackupRun bad = running.completedFrom(2, done, "borg exited 2");
        assertThat(bad.status()).isEqualTo(BackupRunStatus.FAILED);
        assertThat(bad.exitCode()).isEqualTo(2);
        assertThat(bad.isFailure()).isTrue();
    }

    @Test
    void failureSubjectAndBodyRenderJobMachineExitAndLink() {
        Instant start = Instant.parse("2026-07-08T02:00:00Z");
        Instant end = Instant.parse("2026-07-08T02:05:00Z");
        BackupRun failed = BackupRun.fromExitCode(job(), "run-1", start, end, 2,
            "borg: repository is locked\nterminating");

        assertThat(failed.failureSubject())
            .isEqualTo("[Vaier] Backup failed: colina-home on Colina 27");

        String body = failed.failureBody("example.com");
        assertThat(body).contains("colina-home");        // job
        assertThat(body).contains("Colina 27");          // machine
        assertThat(body).contains("nas-borg");           // repository
        assertThat(body).contains("2");                  // exit code
        assertThat(body).contains("2026-07-08T02:05:00Z"); // finishedAt
        assertThat(body).contains("repository is locked"); // summary tail
        assertThat(body).contains("vaier.example.com");    // link built from baseDomain
    }

    @Test
    void recoverySubjectAndBodyRenderTheAllClear() {
        Instant start = Instant.parse("2026-07-08T02:00:00Z");
        Instant end = Instant.parse("2026-07-08T02:40:00Z");
        BackupRun ok = BackupRun.fromExitCode(job(), "run-2", start, end, 0, "12 files, 3 GB");

        assertThat(ok.recoverySubject())
            .isEqualTo("[Vaier] Backup recovered: colina-home on Colina 27");
        assertThat(ok.recoveryBody("example.com"))
            .contains("colina-home").contains("Colina 27").contains("vaier.example.com");
    }

    @Test
    void diagnosticsStripsTrailingJsonStatsBlockFromAnIncompleteRunsSummary() {
        Instant start = Instant.parse("2026-07-13T00:07:47Z");
        Instant end = Instant.parse("2026-07-13T00:07:50Z");
        String summary = """
            /home/ubuntu/mqtt/data/mosquitto.db: open: [Errno 13] Permission denied: 'mosquitto.db'
            /home/ubuntu/pihole/etc-pihole/logrotate: open: [Errno 13] Permission denied: 'logrotate'
            {
                "archive": {
                    "command_line": ["/usr/bin/borg", "create"],
                    "duration": 2.820213,
                    "name": "ip-172-31-17-253-2026-07-13T00:07:47",
                    "stats": {"compressed_size": 369044822, "deduplicated_size": 3199530, "nfiles": 7341, "original_size": 1004101215}
                },
                "cache": {},
                "encryption": {"mode": "repokey-blake2"},
                "repository": {}
            }""";

        BackupRun warn = BackupRun.fromExitCode(job(), "run-diag-1", start, end, 1, summary);

        assertThat(warn.diagnostics()).isEqualTo(
            "/home/ubuntu/mqtt/data/mosquitto.db: open: [Errno 13] Permission denied: 'mosquitto.db'\n"
            + "/home/ubuntu/pihole/etc-pihole/logrotate: open: [Errno 13] Permission denied: 'logrotate'");
    }

    @Test
    void diagnosticsIsEmptyWhenSummaryIsOnlyTheJsonStatsBlock() {
        Instant start = Instant.parse("2026-07-13T00:07:47Z");
        Instant end = Instant.parse("2026-07-13T00:07:50Z");
        String summary = """
            {
                "archive": {"name": "ip-172-31-17-253-2026-07-13T00:07:47"},
                "repository": {}
            }""";

        BackupRun success = BackupRun.fromExitCode(job(), "run-diag-2", start, end, 0, summary);

        assertThat(success.diagnostics()).isEmpty();
    }

    @Test
    void diagnosticsIsTheWholeSummaryWhenThereIsNoJsonBlock() {
        Instant start = Instant.parse("2026-07-13T00:07:47Z");
        Instant end = Instant.parse("2026-07-13T00:07:48Z");

        BackupRun failed = BackupRun.fromExitCode(job(), "run-diag-3", start, end, 127, "sh: 1: borg: not found");

        assertThat(failed.diagnostics()).isEqualTo("sh: 1: borg: not found");
    }

    @Test
    void diagnosticsIsEmptyForNullOrBlankSummary() {
        Instant start = Instant.parse("2026-07-13T00:07:47Z");
        Instant end = Instant.parse("2026-07-13T00:07:48Z");

        BackupRun noSummary = BackupRun.fromExitCode(job(), "run-diag-4", start, end, 0, null);
        BackupRun blankSummary = BackupRun.fromExitCode(job(), "run-diag-5", start, end, 0, "   ");

        assertThat(noSummary.diagnostics()).isEmpty();
        assertThat(blankSummary.diagnostics()).isEmpty();
    }

    @Test
    void diagnosticsIsNotFooledByABraceInsideADiagnosticLine() {
        Instant start = Instant.parse("2026-07-13T00:07:47Z");
        Instant end = Instant.parse("2026-07-13T00:07:48Z");
        String summary = "/home/ubuntu/data/{weird}/file.db: open: [Errno 13] Permission denied";

        BackupRun failed = BackupRun.fromExitCode(job(), "run-diag-6", start, end, 2, summary);

        // No line is *structurally* "just" a JSON object start, so the summary is returned unchanged.
        assertThat(failed.diagnostics()).isEqualTo(summary);
    }

    @Test
    void diagnosticsKeepsBorgPruneLinesThatFollowTheJsonBlock() {
        Instant start = Instant.parse("2026-07-10T02:07:53Z");
        Instant end = Instant.parse("2026-07-10T02:09:04Z");
        // The real shape of a clean run in backup-runs.yml: borg create's JSON stats block, and *after* it
        // borg prune's retention report. The JSON therefore does NOT run to the end of the summary — only
        // the stats object is machine noise, the prune lines are human-readable and are kept.
        String summary = """
            {
                "archive": {"name": "nuc02-2026-07-10T02:07:59"},
                "repository": {}
            }
            Keeping archive (rule: daily #1):        nuc02-2026-07-10T02:07:59            Fri, 2026-07-10 02:08:02 [8201c77]
            Keeping archive (rule: daily #2):        nuc02-2026-07-09T13:13:24            Thu, 2026-07-09 13:13:27 [c333e07]""";

        BackupRun ok = BackupRun.fromExitCode(job(), "run-diag-7", start, end, 0, summary);

        assertThat(ok.diagnostics()).isEqualTo(
            "Keeping archive (rule: daily #1):        nuc02-2026-07-10T02:07:59            Fri, 2026-07-10 02:08:02 [8201c77]\n"
            + "Keeping archive (rule: daily #2):        nuc02-2026-07-09T13:13:24            Thu, 2026-07-09 13:13:27 [c333e07]");
    }

    @Test
    void diagnosticsKeepsTheLinesOnBothSidesOfTheJsonBlock() {
        Instant start = Instant.parse("2026-07-13T00:07:47Z");
        Instant end = Instant.parse("2026-07-13T00:07:48Z");
        String summary = "before the json\n{\n    \"a\": 1\n}\nafter the json";

        BackupRun failed = BackupRun.fromExitCode(job(), "run-diag-8", start, end, 2, summary);

        // Only the stats object is removed; every human-readable line survives, wherever it sits.
        assertThat(failed.diagnostics()).isEqualTo("before the json\nafter the json");
    }

    @Test
    void diagnosticsDropsAnUnterminatedJsonBlockButKeepsTheLinesBeforeIt() {
        Instant start = Instant.parse("2026-07-13T00:07:47Z");
        Instant end = Instant.parse("2026-07-13T00:07:48Z");
        // A truncated summary: the stats object opened but never closed at column 0.
        String summary = "/data/db: open: [Errno 13] Permission denied\n{\n    \"archive\": {";

        BackupRun failed = BackupRun.fromExitCode(job(), "run-diag-9", start, end, 2, summary);

        assertThat(failed.diagnostics()).isEqualTo("/data/db: open: [Errno 13] Permission denied");
    }

    @Test
    void staleRunningBecomesUnknownAfterGrace() {
        Instant started = Instant.parse("2026-07-08T02:00:00Z");
        Duration grace = Duration.ofHours(12);
        BackupRun running = BackupRun.started(job(), "run-10", started);

        // Inside the grace window it is not yet stale.
        assertThat(running.isStaleWhileRunning(started.plus(Duration.ofHours(11)), grace)).isFalse();
        // Past the grace window a still-RUNNING run is stale and can be moved to UNKNOWN.
        assertThat(running.isStaleWhileRunning(started.plus(Duration.ofHours(13)), grace)).isTrue();

        BackupRun unknown = running.asUnknown(started.plus(Duration.ofHours(13)), "no result file after grace");
        assertThat(unknown.status()).isEqualTo(BackupRunStatus.UNKNOWN);
        assertThat(unknown.isFailure()).isTrue();
        // A terminal run is never considered stale-while-running.
        assertThat(unknown.isStaleWhileRunning(started.plus(Duration.ofHours(99)), grace)).isFalse();
    }

    // --- an incomplete archive is a failure, not a decoration ------------------------------------------
    //
    // The Colina 27 case: a job with backupAsRoot=false over /home. borg could not read a great many files,
    // skipped every one of them, exited 1, and Vaier recorded the run as a non-failure — so nobody was told
    // the archive had holes in it. A run whose archive is missing source files is INCOMPLETE: terminal, a
    // failure, and it names what it lost.

    @Test
    void exitOneWithUnreadableFilesIsIncompleteNotAWarning() {
        Instant start = Instant.parse("2026-07-08T02:00:00Z");
        Instant end = Instant.parse("2026-07-08T02:05:00Z");
        String summary = """
            /home/nut-http/logs/2026-04-04-14-07-07.log: open: [Errno 13] Permission denied: '2026-04-04-14-07-07.log'
            /home/nut-http/logs/2026-04-05-01-02-03.log: open: [Errno 13] Permission denied: '2026-04-05-01-02-03.log'
            """;

        BackupRun run = BackupRun.fromExitCode(job(), "run-i1", start, end, 1, summary);

        assertThat(run.status()).isEqualTo(BackupRunStatus.INCOMPLETE);
        assertThat(run.status().isTerminal()).isTrue();
        // The whole point: an incomplete archive is a failure, so it pages admins and reads red in the UI.
        assertThat(run.isFailure()).isTrue();
        assertThat(run.exitCode()).isEqualTo(1);
        assertThat(run.unreadableFiles().total()).isEqualTo(2);
        assertThat(run.unreadableFiles().sample())
            .contains("/home/nut-http/logs/2026-04-04-14-07-07.log");
    }

    @Test
    void completedFromExitOneWithUnreadableFilesSettlesToIncomplete() {
        Instant started = Instant.parse("2026-07-08T02:00:00Z");
        Instant done = Instant.parse("2026-07-08T02:40:00Z");
        BackupRun running = BackupRun.started(job(), "run-i2", started);

        BackupRun run = running.completedFrom(1, done,
            "/var/lib/docker/volumes/db/_data: scandir: [Errno 13] Permission denied: '_data'");

        assertThat(run.status()).isEqualTo(BackupRunStatus.INCOMPLETE);
        assertThat(run.isFailure()).isTrue();
        // Identity of the run is preserved across completion, as for every other outcome.
        assertThat(run.runId()).isEqualTo("run-i2");
        assertThat(run.finishedAt()).isEqualTo(done);
    }

    @Test
    void exitZeroAndExitTwoAreUnchangedByTheUnreadableCheck() {
        Instant start = Instant.parse("2026-07-08T02:00:00Z");
        Instant end = Instant.parse("2026-07-08T02:05:00Z");
        String denials = "/data/db: open: [Errno 13] Permission denied: 'db'";

        // A clean run is a clean run — borg cannot exit 0 having skipped a file, and a stray line in the tail
        // must never demote one. And a run that never wrote an archive at all stays FAILED: that is a
        // different problem (no connection, no repository) with its own outcome, and it must not be reworded.
        assertThat(BackupRun.fromExitCode(job(), "run-i3", start, end, 0, denials).status())
            .isEqualTo(BackupRunStatus.SUCCESS);
        assertThat(BackupRun.fromExitCode(job(), "run-i4", start, end, 2, denials).status())
            .isEqualTo(BackupRunStatus.FAILED);
        assertThat(BackupRun.fromExitCode(job(), "run-i5", start, end, 2,
            "Connection closed by remote host").status()).isEqualTo(BackupRunStatus.FAILED);
    }

    @Test
    void exitOneWithNoUnreadableFileStaysAWarning() {
        Instant start = Instant.parse("2026-07-08T02:00:00Z");
        Instant end = Instant.parse("2026-07-08T02:05:00Z");

        // borg exits 1 for benign things too ("file changed while we backed it up"). Those wrote everything
        // they were asked to, so they stay WARNING — non-failing, non-paging, exactly as before.
        BackupRun warn = BackupRun.fromExitCode(job(), "run-i6", start, end, 1,
            "/home/geir/live.log: file changed while we backed it up");

        assertThat(warn.status()).isEqualTo(BackupRunStatus.WARNING);
        assertThat(warn.isFailure()).isFalse();
        assertThat(warn.unreadableFiles().any()).isFalse();
    }

    @Test
    void anIncompleteRunTellsAdminsPlainlyThatDataWasNotBackedUp() {
        Instant start = Instant.parse("2026-07-08T02:00:00Z");
        Instant end = Instant.parse("2026-07-08T02:05:00Z");
        String summary = """
            /home/nut-http/logs/a.log: open: [Errno 13] Permission denied: 'a.log'
            /home/nut-http/logs/b.log: open: [Errno 13] Permission denied: 'b.log'
            """;

        BackupRun run = BackupRun.fromExitCode(job(), "run-i7", start, end, 1, summary);

        // "failed" would be a lie (there IS an archive) and "warning" was the lie we are fixing. The subject
        // says the one thing that matters in a notification list: this backup did not get everything.
        assertThat(run.failureSubject())
            .isEqualTo("[Vaier] Backup incomplete: colina-home on Colina 27");
        String body = run.failureBody("example.com");
        assertThat(body).contains("2 files could not be read");
        assertThat(body).contains("are NOT in the archive");
        assertThat(body).contains("/home/nut-http/logs/a.log");
        assertThat(body).contains("Back up as root");
        assertThat(body).contains("vaier.example.com");
    }

    @Test
    void aGenuinelyFailedRunKeepsItsOwnSubject() {
        Instant start = Instant.parse("2026-07-08T02:00:00Z");
        Instant end = Instant.parse("2026-07-08T02:05:00Z");

        BackupRun failed = BackupRun.fromExitCode(job(), "run-i8", start, end, 2, "borg: repository is locked");

        assertThat(failed.failureSubject()).isEqualTo("[Vaier] Backup failed: colina-home on Colina 27");
        assertThat(failed.failureBody("example.com")).doesNotContain("could not be read");
    }

    // --- the one failure the operator can fix from where they are standing ------------------------------

    @Test
    void aRunRefusedForAMissingBorgClient_knowsItCanBeFixedByReadyingTheHost() {
        // This failure is not like the others: the host is fine, the credential is fine, the repository is
        // fine — borg simply is not installed there yet, and Vaier can install it. Whether a failure means
        // that is the domain's call, so the UI never has to read the message and guess.
        BackupRun run = BackupRun.borgMissing(job(), "run-b1", Instant.parse("2026-07-23T00:12:10Z"));

        assertThat(run.status()).isEqualTo(BackupRunStatus.FAILED);
        assertThat(run.needsClientReadying()).isTrue();
        assertThat(run.summary()).contains("Colina 27");
    }

    @Test
    void theRefusal_namesSomethingThatExists() {
        // It used to say "run Prepare client" — a button on a page that was deleted when the Explorer
        // absorbed it. A message pointing at a control nobody can find is worse than no message: it tells
        // the operator the fix is their fault to find.
        BackupRun run = BackupRun.borgMissing(job(), "run-b2", Instant.parse("2026-07-23T00:12:10Z"));

        assertThat(run.summary()).doesNotContain("run Prepare client");
        assertThat(run.summary()).as("it names where the operator already is").contains("Backup");
    }

    @Test
    void everyOtherFailure_isNotAReadyingProblem() {
        // A missing credential, a locked repository, a dead machine: real failures, none of them fixed by
        // installing borg. Offering to ready the host there would be a button that does nothing.
        Instant now = Instant.parse("2026-07-23T00:12:10Z");

        assertThat(BackupRun.failed(job(), "run-b3", now, "No stored credential for Colina 27")
            .needsClientReadying()).isFalse();
        assertThat(BackupRun.fromExitCode(job(), "run-b4", now, now, 2, "borg: repository is locked")
            .needsClientReadying()).isFalse();
        assertThat(BackupRun.started(job(), "run-b5", now).needsClientReadying()).isFalse();
    }
}
