package net.vaier.domain;

/**
 * One cross-machine copy in flight — the engine behind the Explorer's Clipboard (#321, slice 2). A file has
 * a coordinate (machine, path, point-in-time); the Clipboard carries it and the destination names the
 * operation: a different machine is a copy, and the same machine with a live source onto its own path would
 * be a no-op. Vaier sits at the VPN hub and is the only node with SSH to every machine, so a Transfer is an
 * SFTP read from the source relayed through Vaier's own JVM to an SFTP write on the destination.
 *
 * <p>This value object owns the <em>decisions</em>, not the relay:
 * <ul>
 *   <li><b>Both paths are absolute.</b> A source and a destination directory are normalised through
 *       {@link FileEntry#normalisePath}, so a relative or climbing path is refused before anything connects.</li>
 *   <li><b>A transfer onto its own file is refused.</b> The item is copied <em>into</em> the destination
 *       directory keeping its basename, so a live-source copy of {@code /a/b/c.txt} into {@code /a/b} on the
 *       same machine would land back on {@code /a/b/c.txt} — the same file. That is refused. But the same
 *       shape with the source in the <em>past</em> (an archive) is a <b>restore</b>, and is allowed: you can
 *       always paste an archived file back onto its own live path.</li>
 *   <li><b>It only moves forward.</b> A transfer starts {@code RUNNING} and settles exactly once; a settled
 *       transfer never transitions again.</li>
 * </ul>
 *
 * <p>The destination coordinate is always the present — a transfer's destination never carries a time
 * coordinate (you cannot paste into the past). The source may be the past; that is the restore case above.
 *
 * @param destPath the destination <em>directory</em> the item is copied into (its basename is preserved)
 * @param totalBytes the transfer's size once a pre-walk has measured it, or {@code null} while still unknown
 * @param error the failure reason when {@code state} is {@code FAILED}, otherwise {@code null}
 */
public record Transfer(String id, String sourceMachine, String sourcePath, String destMachine,
                       String destPath, TransferState state, long bytesCopied, Long totalBytes, String error) {

    /**
     * Begin a transfer of {@code sourcePath} on {@code sourceMachine} into the directory {@code destDir} on
     * {@code destMachine}. Both paths are normalised (a non-absolute or climbing path is refused), and a
     * live-source transfer onto its own file is refused as a no-op — while a past-source one is a restore and
     * is allowed.
     *
     * @param sourceLive whether the source is the live present ({@code true}) or a mounted archive
     *                   ({@code false}); only a live source can be a no-op onto its own path
     * @throws IllegalArgumentException when a path is not absolute, the source is the root, or the transfer is
     *                                  a no-op onto its own live file
     */
    public static Transfer starting(String id, String sourceMachine, String sourcePath, boolean sourceLive,
                                    String destMachine, String destDir) {
        String source = FileEntry.normalisePath(sourcePath);
        String directory = FileEntry.normalisePath(destDir);
        if ("/".equals(source)) {
            throw new IllegalArgumentException("A transfer's source must be a file or directory, not the root");
        }
        String finalDest = join(directory, basename(source));
        if (sourceLive && sourceMachine.equals(destMachine) && finalDest.equals(source)) {
            throw new IllegalArgumentException(
                "A transfer's source and destination are the same file: " + source);
        }
        return new Transfer(id, sourceMachine, source, destMachine, directory,
            TransferState.RUNNING, 0L, null, null);
    }

    /** The same transfer with its measured size — the denominator progress is reported against. */
    public Transfer withTotal(Long total) {
        return new Transfer(id, sourceMachine, sourcePath, destMachine, destPath, state, bytesCopied, total, error);
    }

    /** The same transfer advanced to {@code bytesCopied} bytes relayed so far. */
    public Transfer progressed(long copied) {
        return new Transfer(id, sourceMachine, sourcePath, destMachine, destPath, state, copied, totalBytes, error);
    }

    /** Settle the transfer as finished cleanly. Only a RUNNING transfer may settle. */
    public Transfer completed() {
        requireRunning();
        return new Transfer(id, sourceMachine, sourcePath, destMachine, destPath, TransferState.DONE,
            bytesCopied, totalBytes, null);
    }

    /** Settle the transfer as failed, carrying {@code reason}. Only a RUNNING transfer may settle. */
    public Transfer failed(String reason) {
        requireRunning();
        return new Transfer(id, sourceMachine, sourcePath, destMachine, destPath, TransferState.FAILED,
            bytesCopied, totalBytes, reason);
    }

    private void requireRunning() {
        if (state != TransferState.RUNNING) {
            throw new IllegalStateException("A settled transfer cannot transition again (was " + state + ")");
        }
    }

    /** The last segment of an absolute path — the basename the destination keeps. */
    private static String basename(String path) {
        int slash = path.lastIndexOf('/');
        return path.substring(slash + 1);
    }

    /** {@code name} placed inside the directory {@code dir} — the file's final destination path. */
    private static String join(String dir, String name) {
        return "/".equals(dir) ? "/" + name : dir + "/" + name;
    }
}
