package net.vaier.adapter.driven;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import lombok.extern.slf4j.Slf4j;

/**
 * A stamped copy of a served app, kept on disk beside its source as {@code <name>.stamped}. The host is
 * fixed for the life of the deployment, so the stamp is cut once — and cut again only when the source's
 * size or timestamp moves (an upgrade dropped a new build in) or the host changes. Anything that stops a
 * stamp being cut or kept serves the source as built: an app that has to be told an address beats none.
 */
@Slf4j
final class KeptStampedCopy {

    /** Writes {@code source} stamped with {@code host} to {@code target}; false when it cannot be stamped. */
    interface Stamper {
        boolean stamp(Path source, Path target, String host) throws IOException;
    }

    private static final String STAMPED_SUFFIX = ".stamped";

    private final Stamper stamper;

    /** What the kept copy was cut from. Guarded by this. */
    private Cut kept;

    KeptStampedCopy(Stamper stamper) {
        this.stamper = stamper;
    }

    /** The file to serve for {@code source} stamped with {@code host}. */
    synchronized Path servedCopyOf(Path source, String host) throws IOException {
        Cut current = new Cut(Files.size(source), Files.getLastModifiedTime(source).toMillis(), host, null);
        if (kept != null && kept.cutFrom(current) && Files.isRegularFile(kept.served())) {
            return kept.served();
        }
        Path served = stamp(source, host);
        kept = current.servedFrom(served);
        return served;
    }

    private Path stamp(Path source, String host) {
        Path target = source.resolveSibling(source.getFileName() + STAMPED_SUFFIX);
        Path scratch = null;
        try {
            scratch = Files.createTempFile(target.toAbsolutePath().getParent(), ".vaier-stamp", ".tmp");
            if (!stamper.stamp(source, scratch, host)) {
                Files.deleteIfExists(scratch);
                return source;
            }
            Files.move(scratch, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            log.info("{} stamped with {} and kept at {}", source.getFileName(), host, target);
            return target;
        } catch (IOException e) {
            log.warn("Cannot keep a stamped copy beside {} ({}); serving it as built", source, e.getMessage());
            deleteQuietly(scratch);
            return source;
        }
    }

    private static void deleteQuietly(Path scratch) {
        if (scratch == null) {
            return;
        }
        try {
            Files.deleteIfExists(scratch);
        } catch (IOException ignored) {
            // A stray temp file is harmless; the next cut writes a fresh one.
        }
    }

    private record Cut(long sourceSize, long sourceModified, String host, Path served) {

        boolean cutFrom(Cut source) {
            return sourceSize == source.sourceSize()
                && sourceModified == source.sourceModified()
                && host.equals(source.host());
        }

        Cut servedFrom(Path path) {
            return new Cut(sourceSize, sourceModified, host, path);
        }
    }
}
