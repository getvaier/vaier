package net.vaier.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A Transfer is a live cross-machine copy in flight. It carries the coordinate the Clipboard held
 * (source machine + path) and the destination that names the operation (dest machine + directory),
 * and it owns the rules — a transfer onto its own file is refused, paths must be absolute, and a
 * transfer only moves forward from RUNNING to a terminal state. No orchestration, only decisions.
 */
class TransferTest {

    @Test
    void starting_isRunning_withNoBytesYet_andNormalisedPaths() {
        Transfer t = Transfer.starting("t1", "apalveien5", "/home//geir/./notes.txt", true,
            "colina27", "/backup");

        assertThat(t.id()).isEqualTo("t1");
        assertThat(t.sourceMachine()).isEqualTo("apalveien5");
        assertThat(t.sourcePath()).isEqualTo("/home/geir/notes.txt");
        assertThat(t.destMachine()).isEqualTo("colina27");
        assertThat(t.destPath()).isEqualTo("/backup");
        assertThat(t.state()).isEqualTo(TransferState.RUNNING);
        assertThat(t.bytesCopied()).isZero();
        assertThat(t.totalBytes()).isNull();
        assertThat(t.error()).isNull();
    }

    @Test
    void starting_aNonAbsoluteDestination_isRefused() {
        assertThatThrownBy(() -> Transfer.starting("t1", "a", "/x/y", true, "b", "relative/dir"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void starting_aNonAbsoluteSource_isRefused() {
        assertThatThrownBy(() -> Transfer.starting("t1", "a", "x/y", true, "b", "/dir"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void starting_ontoItsOwnFile_isRefused() {
        // /a/b/c.txt copied into /a/b would land back on /a/b/c.txt — the same file, on the same machine.
        assertThatThrownBy(() -> Transfer.starting("t1", "nas", "/a/b/c.txt", true, "nas", "/a/b"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void starting_ontoTheSamePathButADifferentMachine_isACopy_notANoOp() {
        // Same path, different machine: the whole point of a Transfer. Never refused.
        Transfer t = Transfer.starting("t1", "nas", "/a/b/c.txt", true, "colina27", "/a/b");
        assertThat(t.destMachine()).isEqualTo("colina27");
    }

    @Test
    void starting_restoringAnArchivedFileOntoItsOwnLivePath_isAllowed() {
        // Source in the past (sourceLive=false), destination the live present, same machine + same final
        // path: that is a restore, not a no-op. It is exactly what the past-as-a-coordinate design allows.
        Transfer t = Transfer.starting("t1", "nas", "/a/b/c.txt", false, "nas", "/a/b");
        assertThat(t.state()).isEqualTo(TransferState.RUNNING);
    }

    @Test
    void withTotal_setsTheDenominatorForProgress() {
        Transfer t = Transfer.starting("t1", "a", "/x", true, "b", "/y").withTotal(2048L);
        assertThat(t.totalBytes()).isEqualTo(2048L);
        assertThat(t.state()).isEqualTo(TransferState.RUNNING);
    }

    @Test
    void progressed_advancesBytesCopied() {
        Transfer t = Transfer.starting("t1", "a", "/x", true, "b", "/y").withTotal(2048L).progressed(1000L);
        assertThat(t.bytesCopied()).isEqualTo(1000L);
        assertThat(t.state()).isEqualTo(TransferState.RUNNING);
    }

    @Test
    void completed_movesToDone() {
        Transfer t = Transfer.starting("t1", "a", "/x", true, "b", "/y").withTotal(10L).progressed(10L).completed();
        assertThat(t.state()).isEqualTo(TransferState.DONE);
        assertThat(t.error()).isNull();
    }

    @Test
    void failed_movesToFailed_carryingTheReason() {
        Transfer t = Transfer.starting("t1", "a", "/x", true, "b", "/y").failed("connection reset");
        assertThat(t.state()).isEqualTo(TransferState.FAILED);
        assertThat(t.error()).isEqualTo("connection reset");
    }

    @Test
    void aSettledTransfer_doesNotTransitionAgain() {
        Transfer done = Transfer.starting("t1", "a", "/x", true, "b", "/y").completed();
        assertThatThrownBy(done::completed).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> done.failed("late")).isInstanceOf(IllegalStateException.class);
    }
}
