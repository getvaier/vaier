package net.vaier.rest;

import net.vaier.application.ResolveFileCoordinateUseCase;
import net.vaier.application.ResolveFileCoordinateUseCase.ResolvedFileCoordinate;
import net.vaier.domain.AuthMethod;
import net.vaier.domain.FileEntry;
import net.vaier.domain.HostCredential;
import net.vaier.domain.SshTarget;
import net.vaier.domain.Transfer;
import net.vaier.domain.TransferState;
import net.vaier.domain.port.ForBrowsingRemoteFiles;
import net.vaier.domain.port.ForBrowsingRemoteFiles.DirectoryListing;
import net.vaier.domain.port.ForBrowsingRemoteFiles.RemoteStat;
import net.vaier.domain.port.ForPublishingEvents;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Transfer runner drives the flat-memory relay: it resolves source and destination coordinates through
 * the Explorer's use case (never a second copy of the mapping), stats the source, streams a file or walks a
 * directory, tracks the transfer in memory and pushes progress + settled events on the {@code transfers}
 * SSE topic. Here the executor runs inline, so a started transfer has already settled when {@code
 * startTransfer} returns.
 */
@ExtendWith(MockitoExtension.class)
class TransferRunnerTest {

    @Mock ResolveFileCoordinateUseCase resolveFileCoordinate;
    @Mock ForBrowsingRemoteFiles files;
    @Mock ForPublishingEvents events;

    private TransferRunner runner;

    private static final Instant WHEN = Instant.parse("2026-07-15T10:00:00Z");

    private static SshTarget target(String host) {
        return SshTarget.on(host, new HostCredential(host, "root", AuthMethod.PASSWORD, "pw", null, false), "SHA256:x");
    }

    /** An executor that runs the task inline, so the async relay completes before startTransfer returns. */
    private static final class InlineExecutor extends AbstractExecutorService {
        @Override public void execute(Runnable command) { command.run(); }
        @Override public void shutdown() { }
        @Override public List<Runnable> shutdownNow() { return List.of(); }
        @Override public boolean isShutdown() { return true; }
        @Override public boolean isTerminated() { return true; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
    }

    @BeforeEach
    void setUp() {
        runner = new TransferRunner(resolveFileCoordinate, files, events, new InlineExecutor());
    }

    private void sourceResolves(String machine, String truePath, String at, String host, String sftpPath) {
        when(resolveFileCoordinate.resolve(machine, truePath, at))
            .thenReturn(new ResolvedFileCoordinate(target(host), sftpPath));
    }

    @Test
    void startTransfer_ofAFile_streamsItIntoTheDestinationDirectory_andSettlesDone() {
        sourceResolves("apalveien5", "/home/geir/notes.txt", null, "10.13.13.6", "/home/geir/notes.txt");
        // Destination is always the present: at is null.
        when(resolveFileCoordinate.resolve("colina27", "/backup", null))
            .thenReturn(new ResolvedFileCoordinate(target("10.13.13.3"), "/backup"));
        when(files.stat(any(), eq("/home/geir/notes.txt"))).thenReturn(new RemoteStat(false, 2048));
        when(files.copyFile(any(), any(), any(), any(), any())).thenReturn(2048L);

        Transfer t = runner.startTransfer("apalveien5", "/home/geir/notes.txt", null, "colina27", "/backup");

        // The dest directory is laid down, then the file is copied INTO it keeping its basename.
        verify(files).mkdirs(any(), eq("/backup"));
        verify(files).copyFile(any(), eq("/home/geir/notes.txt"), any(), eq("/backup/notes.txt"), any());

        Transfer settled = runner.getTransfers().stream().filter(x -> x.id().equals(t.id())).findFirst().orElseThrow();
        assertThat(settled.state()).isEqualTo(TransferState.DONE);
        assertThat(settled.totalBytes()).isEqualTo(2048L);
    }

    @Test
    void startTransfer_resolvesTheDestinationInThePresent_neverCarryingATimeCoordinate() {
        sourceResolves("nas", "/a/x.txt", "ab12", "10.0.0.9", "/mnt/ab12/a/x.txt");
        when(resolveFileCoordinate.resolve("nas", "/restore", null))
            .thenReturn(new ResolvedFileCoordinate(target("10.0.0.9"), "/restore"));
        when(files.stat(any(), any())).thenReturn(new RemoteStat(false, 10));
        when(files.copyFile(any(), any(), any(), any(), any())).thenReturn(10L);

        // A restore: source in the past (at=ab12), destination the live present.
        runner.startTransfer("nas", "/a/x.txt", "ab12", "nas", "/restore");

        verify(resolveFileCoordinate).resolve("nas", "/a/x.txt", "ab12");   // source may be the past
        verify(resolveFileCoordinate).resolve("nas", "/restore", null);      // destination is always present
    }

    @Test
    void startTransfer_ofADirectory_walksIt_mkdirsTheTree_andCopiesEachFile() {
        sourceResolves("apalveien5", "/home/geir/docs", null, "10.13.13.6", "/home/geir/docs");
        when(resolveFileCoordinate.resolve("colina27", "/backup", null))
            .thenReturn(new ResolvedFileCoordinate(target("10.13.13.3"), "/backup"));
        // /home/geir/docs is a directory holding a file and a subdirectory (with one file).
        when(files.stat(any(), eq("/home/geir/docs"))).thenReturn(new RemoteStat(true, 4096));
        when(files.list(any(), eq("/home/geir/docs"))).thenReturn(new DirectoryListing(List.of(
            FileEntry.in("/home/geir/docs", "a.txt", false, 100, WHEN),
            FileEntry.in("/home/geir/docs", "sub", true, 4096, WHEN)), "SHA256:x"));
        when(files.list(any(), eq("/home/geir/docs/sub"))).thenReturn(new DirectoryListing(List.of(
            FileEntry.in("/home/geir/docs/sub", "b.txt", false, 200, WHEN)), "SHA256:x"));
        lenient().when(files.copyFile(any(), any(), any(), any(), any())).thenReturn(0L);

        runner.startTransfer("apalveien5", "/home/geir/docs", null, "colina27", "/backup");

        // The item is copied INTO the destination keeping its basename: /backup/docs/...
        verify(files).mkdirs(any(), eq("/backup/docs"));
        verify(files).mkdirs(any(), eq("/backup/docs/sub"));
        verify(files).copyFile(any(), eq("/home/geir/docs/a.txt"), any(), eq("/backup/docs/a.txt"), any());
        verify(files).copyFile(any(), eq("/home/geir/docs/sub/b.txt"), any(), eq("/backup/docs/sub/b.txt"), any());

        Transfer settled = runner.getTransfers().getFirst();
        assertThat(settled.state()).isEqualTo(TransferState.DONE);
        // totalBytes is the pre-walk sum of every file, so progress has a denominator.
        assertThat(settled.totalBytes()).isEqualTo(300L);
    }

    @Test
    void startTransfer_publishesSettledOnTheTransfersTopic() {
        sourceResolves("a", "/x.txt", null, "h1", "/x.txt");
        when(resolveFileCoordinate.resolve("b", "/d", null))
            .thenReturn(new ResolvedFileCoordinate(target("h2"), "/d"));
        when(files.stat(any(), any())).thenReturn(new RemoteStat(false, 5));
        when(files.copyFile(any(), any(), any(), any(), any())).thenReturn(5L);

        Transfer t = runner.startTransfer("a", "/x.txt", null, "b", "/d");

        ArgumentCaptor<String> event = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> data = ArgumentCaptor.forClass(String.class);
        verify(events, atLeastOnce()).publish(eq("transfers"), event.capture(), data.capture());
        assertThat(event.getAllValues()).contains("transfer-settled");
        int idx = event.getAllValues().indexOf("transfer-settled");
        assertThat(data.getAllValues().get(idx)).contains(t.id()).contains("DONE");
    }

    @Test
    void startTransfer_whenTheRelayFails_settlesFailed_withTheReason() {
        sourceResolves("a", "/x.txt", null, "h1", "/x.txt");
        when(resolveFileCoordinate.resolve("b", "/d", null))
            .thenReturn(new ResolvedFileCoordinate(target("h2"), "/d"));
        when(files.stat(any(), any())).thenReturn(new RemoteStat(false, 5));
        when(files.copyFile(any(), any(), any(), any(), any()))
            .thenThrow(new net.vaier.domain.SshConnectException("connection reset", new RuntimeException()));

        Transfer t = runner.startTransfer("a", "/x.txt", null, "b", "/d");

        Transfer settled = runner.getTransfers().stream().filter(x -> x.id().equals(t.id())).findFirst().orElseThrow();
        assertThat(settled.state()).isEqualTo(TransferState.FAILED);
        assertThat(settled.error()).contains("connection reset");
    }

    @Test
    void startTransfer_aNoOpOntoItsOwnLiveFile_isRefused_beforeAnythingIsSubmitted() {
        // Same machine, live source, dest dir is the source's parent: refused up front (a 400 at the endpoint).
        assertThatThrownBy(() -> runner.startTransfer("nas", "/a/b/c.txt", null, "nas", "/a/b"))
            .isInstanceOf(IllegalArgumentException.class);
        verify(files, never()).copyFile(any(), any(), any(), any(), any());
        assertThat(runner.getTransfers()).isEmpty();
    }

    @Test
    void getTransfers_returnsLiveAndRecentlySettledTransfers() {
        sourceResolves("a", "/x.txt", null, "h1", "/x.txt");
        when(resolveFileCoordinate.resolve("b", "/d", null))
            .thenReturn(new ResolvedFileCoordinate(target("h2"), "/d"));
        when(files.stat(any(), any())).thenReturn(new RemoteStat(false, 5));
        when(files.copyFile(any(), any(), any(), any(), any())).thenReturn(5L);

        runner.startTransfer("a", "/x.txt", null, "b", "/d");
        runner.startTransfer("a", "/y.txt", null, "b", "/d");

        assertThat(runner.getTransfers()).hasSize(2);
    }
}
