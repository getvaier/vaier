package net.vaier.rest;

import lombok.extern.slf4j.Slf4j;
import net.vaier.application.GetTransfersUseCase;
import net.vaier.application.ResolveFileCoordinateUseCase;
import net.vaier.application.ResolveFileCoordinateUseCase.ResolvedFileCoordinate;
import net.vaier.application.StartTransferUseCase;
import net.vaier.domain.FileEntry;
import net.vaier.domain.SshTarget;
import net.vaier.domain.Transfer;
import net.vaier.domain.port.ForBrowsingRemoteFiles;
import net.vaier.domain.port.ForPublishingEvents;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The Clipboard's engine: a cross-machine copy is an SFTP read from the source relayed through Vaier's own
 * JVM to an SFTP write on the destination (#321, slice 2). Vaier sits at the VPN hub and is the only node
 * with SSH to every machine, so this relay needs no host-to-host trust and nothing new in the network
 * model.
 *
 * <p>It lives in {@code rest/} beside {@link BackupRunner} because it is a web-layer orchestrator, but it
 * deliberately does <b>not</b> reuse the backup run store or the host-detached {@code nohup} pattern: a
 * cross-machine relay cannot be detached onto a host without host-to-host trust, and the backup store keys
 * one latest run per job, which a Transfer would clobber. So a Transfer runs on a background
 * {@link ExecutorService} and lives in an in-memory registry — an ephemeral live operation, not persisted
 * history. A Vaier restart simply loses in-flight transfers (acceptable for V1).
 *
 * <p>The coordinate mapping is not re-implemented here: source and destination are resolved through
 * {@link ResolveFileCoordinateUseCase} — the very mapping the Explorer browses with (the SFTP jail, and the
 * archive mount for a restore) — so the relay stays coordinate-agnostic. The <b>source</b> may be the past
 * (a restore); the <b>destination</b> is always the present (you cannot paste into the past), so it is
 * always resolved with a null time coordinate.
 *
 * <p>Progress is pushed on the {@code transfers} SSE topic, throttled to about one event every
 * {@link #PROGRESS_THROTTLE_MS} ms plus a final one, so a fast file cannot flood the topic — mirroring how
 * {@link BackupRunner} publishes on its own topic. The browser never polls; it consumes these events.
 */
@Component
@Slf4j
public class TransferRunner implements StartTransferUseCase, GetTransfersUseCase {

    /** The SSE topic the Explorer's Clipboard reacts to; the events it carries. */
    static final String TRANSFERS_TOPIC = "transfers";
    static final String PROGRESS_EVENT = "transfer-progress";
    static final String SETTLED_EVENT = "transfer-settled";

    /** At most one progress event per this many ms (plus a final one), so a fast copy cannot flood the topic. */
    static final long PROGRESS_THROTTLE_MS = 500;

    /** How many settled transfers to keep so a reconnecting browser can still repaint them. */
    static final int MAX_SETTLED = 20;

    private final ResolveFileCoordinateUseCase resolveFileCoordinate;
    private final ForBrowsingRemoteFiles files;
    private final ForPublishingEvents events;
    private final ExecutorService executor;

    /** The live + recently-settled transfers, and the order they started in (so the view is stable). */
    private final ConcurrentHashMap<String, Transfer> transfers = new ConcurrentHashMap<>();
    private final Deque<String> order = new ConcurrentLinkedDeque<>();
    private final Deque<String> settled = new ConcurrentLinkedDeque<>();
    /** Per-transfer last-publish clock, so progress throttling is independent per transfer. */
    private final ConcurrentHashMap<String, AtomicLong> lastPublishedAt = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public TransferRunner(ResolveFileCoordinateUseCase resolveFileCoordinate,
                          ForBrowsingRemoteFiles files,
                          ForPublishingEvents events) {
        // A small fixed pool of daemon threads: a relay holds two SSH sessions for its whole duration, so a
        // handful of concurrent transfers is plenty, and daemon threads never hold the JVM open on shutdown.
        this(resolveFileCoordinate, files, events, Executors.newFixedThreadPool(3, r -> {
            Thread thread = new Thread(r, "transfer-relay");
            thread.setDaemon(true);
            return thread;
        }));
    }

    /** Test seam: an injected executor (e.g. an inline one) makes the async relay deterministic. */
    TransferRunner(ResolveFileCoordinateUseCase resolveFileCoordinate,
                   ForBrowsingRemoteFiles files,
                   ForPublishingEvents events,
                   ExecutorService executor) {
        this.resolveFileCoordinate = resolveFileCoordinate;
        this.files = files;
        this.events = events;
        this.executor = executor;
    }

    /**
     * Validate and register the transfer synchronously (so a no-op or a bad path is a 400 at once), then run
     * the relay on the executor. The returned {@link Transfer} is {@code RUNNING}; it settles later.
     */
    @Override
    public Transfer startTransfer(String sourceMachine, String sourcePath, String at,
                                  String destMachine, String destPath) {
        boolean sourceLive = at == null || at.isBlank();
        String id = UUID.randomUUID().toString();
        // Domain validation up front: absolute paths, and no live no-op onto its own file. Throws → 400.
        Transfer transfer = Transfer.starting(id, sourceMachine, sourcePath, sourceLive, destMachine, destPath);
        register(transfer);
        log.info("Starting transfer {} : {}:{} -> {}:{}",
            id, LogSafe.forLog(sourceMachine), LogSafe.forLog(sourcePath),
            LogSafe.forLog(destMachine), LogSafe.forLog(destPath));
        executor.submit(() -> runTransfer(id, sourceMachine, sourcePath, at, destMachine, destPath));
        return transfer;
    }

    @Override
    public List<Transfer> getTransfers() {
        return order.stream().map(transfers::get).filter(java.util.Objects::nonNull).toList();
    }

    /**
     * The relay itself, on a background thread. Resolves both coordinates (source possibly in the past,
     * destination always in the present), stats the source, measures the whole transfer by a pre-walk so
     * progress has a denominator, then streams a file or walks a directory. Any failure settles the transfer
     * {@code FAILED} with the reason rather than throwing on the executor thread.
     */
    private void runTransfer(String id, String sourceMachine, String sourcePath, String at,
                             String destMachine, String destPath) {
        try {
            ResolvedFileCoordinate source = resolveFileCoordinate.resolve(sourceMachine, sourcePath, at);
            // The destination is always the present — never a time coordinate.
            ResolvedFileCoordinate dest = resolveFileCoordinate.resolve(destMachine, destPath, null);

            ForBrowsingRemoteFiles.RemoteStat stat = files.stat(source.target(), source.path());
            long total = stat.directory()
                ? walkSize(source.target(), source.path())
                : stat.sizeBytes();
            update(id, t -> t.withTotal(total));

            String destBase = join(dest.path(), basename(source.path()));
            if (stat.directory()) {
                copyDirectory(id, source.target(), source.path(), dest.target(), destBase);
            } else {
                files.mkdirs(dest.target(), dest.path());
                copyOneFile(id, source.target(), source.path(), dest.target(), destBase);
            }
            settle(id, Transfer::completed);
            log.info("Transfer {} finished", id);
        } catch (Exception e) {
            // The domain SSH exceptions carry an operator-readable sentence at the top level (e.g. "Could not
            // list … "), so that is the reason to record — not the deepest transport cause.
            String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.info("Transfer {} failed: {}", id, reason);
            settle(id, t -> t.failed(reason));
        }
    }

    /** Copy a directory into {@code destBase}: lay down the directory, then copy each entry under it. */
    private void copyDirectory(String id, SshTarget srcTarget, String srcPath,
                               SshTarget destTarget, String destBase) {
        files.mkdirs(destTarget, destBase);
        for (FileEntry entry : files.list(srcTarget, srcPath).entries()) {
            String childDest = join(destBase, entry.name());
            if (entry.directory()) {
                copyDirectory(id, srcTarget, entry.path(), destTarget, childDest);
            } else {
                copyOneFile(id, srcTarget, entry.path(), destTarget, childDest);
            }
        }
    }

    /**
     * Copy one file, folding its byte progress into the transfer's running total. The relay reports this
     * file's cumulative bytes; added to what earlier files already copied, that is the transfer's total
     * progress, published throttled.
     */
    private void copyOneFile(String id, SshTarget srcTarget, String srcPath,
                             SshTarget destTarget, String destPath) {
        long base = bytesCopiedSoFar(id);
        files.copyFile(srcTarget, srcPath, destTarget, destPath, fileBytes -> {
            long cumulative = base + fileBytes;
            update(id, t -> t.progressed(cumulative));
            publishProgressThrottled(id);
        });
    }

    /** The bytes this transfer has copied across all files so far. */
    private long bytesCopiedSoFar(String id) {
        Transfer transfer = transfers.get(id);
        return transfer == null ? 0 : transfer.bytesCopied();
    }

    /** The total size of a directory tree — a pre-walk so a directory transfer has a progress denominator. */
    private long walkSize(SshTarget target, String path) {
        long total = 0;
        Deque<String> stack = new ArrayDeque<>();
        stack.push(path);
        while (!stack.isEmpty()) {
            for (FileEntry entry : files.list(target, stack.pop()).entries()) {
                if (entry.directory()) {
                    stack.push(entry.path());
                } else {
                    total += entry.sizeBytes();
                }
            }
        }
        return total;
    }

    // --- registry + SSE ------------------------------------------------------------------------------

    private void register(Transfer transfer) {
        transfers.put(transfer.id(), transfer);
        order.addLast(transfer.id());
        lastPublishedAt.put(transfer.id(), new AtomicLong(0));
    }

    /** Apply {@code change} to the stored transfer, if it is still there. */
    private void update(String id, java.util.function.UnaryOperator<Transfer> change) {
        transfers.computeIfPresent(id, (k, t) -> change.apply(t));
    }

    /**
     * Settle the transfer to its terminal state, push a final progress event and the settled event, then cap
     * the settled tail so the registry cannot grow without bound.
     */
    private void settle(String id, java.util.function.UnaryOperator<Transfer> terminal) {
        update(id, terminal);
        publishProgress(id);
        Transfer transfer = transfers.get(id);
        if (transfer != null) {
            publish(SETTLED_EVENT, settledJson(transfer));
        }
        settled.addLast(id);
        capSettled();
    }

    /** Keep only the most recent {@link #MAX_SETTLED} settled transfers; evict the oldest beyond that. */
    private void capSettled() {
        while (settled.size() > MAX_SETTLED) {
            String evicted = settled.pollFirst();
            if (evicted != null) {
                transfers.remove(evicted);
                order.remove(evicted);
                lastPublishedAt.remove(evicted);
            }
        }
    }

    /** Publish a progress event only if enough time has passed since the last one for this transfer. */
    private void publishProgressThrottled(String id) {
        AtomicLong last = lastPublishedAt.get(id);
        if (last == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long previous = last.get();
        if (now - previous >= PROGRESS_THROTTLE_MS && last.compareAndSet(previous, now)) {
            publishProgress(id);
        }
    }

    private void publishProgress(String id) {
        Transfer transfer = transfers.get(id);
        if (transfer != null) {
            publish(PROGRESS_EVENT, progressJson(transfer));
        }
    }

    private void publish(String event, String data) {
        try {
            events.publish(TRANSFERS_TOPIC, event, data);
        } catch (Exception e) {
            log.debug("Publishing {} failed: {}", event, e.getMessage());
        }
    }

    /** {@code {"id":"…","bytesCopied":N,"totalBytes":N|null}} */
    private static String progressJson(Transfer t) {
        return "{\"id\":\"" + jsonEscape(t.id()) + "\",\"bytesCopied\":" + t.bytesCopied()
            + ",\"totalBytes\":" + (t.totalBytes() == null ? "null" : t.totalBytes()) + "}";
    }

    /** {@code {"id":"…","state":"DONE","error":"…"|null}} */
    private static String settledJson(Transfer t) {
        return "{\"id\":\"" + jsonEscape(t.id()) + "\",\"state\":\"" + t.state().name() + "\",\"error\":"
            + (t.error() == null ? "null" : "\"" + jsonEscape(t.error()) + "\"") + "}";
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String basename(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static String join(String dir, String name) {
        return "/".equals(dir) ? "/" + name : dir + "/" + name;
    }
}
