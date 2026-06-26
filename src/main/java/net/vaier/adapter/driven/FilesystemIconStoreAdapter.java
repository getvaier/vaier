package net.vaier.adapter.driven;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.Icon;
import net.vaier.domain.port.ForStoringIcons;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Filesystem-backed {@link ForStoringIcons}: a resolved icon is written to disk once and served
 * from there across restarts. Each key maps to two files named after the SHA-256 hash of the key —
 * {@code <hash>} holds the raw bytes and {@code <hash>.ct} holds the content-type. Best-effort,
 * like the geoip DB: if the cache directory can't be created/written, the store disables itself
 * and every operation degrades to a no-op rather than throwing.
 */
@Component
@Slf4j
public class FilesystemIconStoreAdapter implements ForStoringIcons {

    private static final String CONTENT_TYPE_SUFFIX = ".ct";

    @Value("${icon.cache.path:/icons}")
    private String cacheDir;

    private Path dir;
    private boolean enabled;

    public void setCacheDir(String cacheDir) {
        this.cacheDir = cacheDir;
    }

    @PostConstruct
    public void init() {
        dir = Path.of(cacheDir);
        try {
            Files.createDirectories(dir);
            if (!Files.isWritable(dir)) {
                throw new IOException("not writable");
            }
            enabled = true;
            log.info("Icon cache directory ready at {}", dir);
        } catch (Exception e) {
            enabled = false;
            log.warn("Icon cache disabled — cannot use directory {}: {}. Icons will be re-fetched each lookup.",
                cacheDir, e.getMessage());
        }
    }

    @Override
    public Optional<Icon> load(String key) {
        if (!enabled) return Optional.empty();
        Path bytesFile = fileFor(key);
        Path ctFile = dir.resolve(bytesFile.getFileName() + CONTENT_TYPE_SUFFIX);
        try {
            if (!Files.isReadable(bytesFile) || !Files.isReadable(ctFile)) return Optional.empty();
            byte[] body = Files.readAllBytes(bytesFile);
            if (body.length == 0) return Optional.empty();
            String contentType = Files.readString(ctFile, StandardCharsets.UTF_8);
            return Optional.of(new Icon(body, contentType));
        } catch (IOException e) {
            log.debug("Failed to load cached icon for key {}: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void store(String key, Icon icon) {
        if (!enabled) return;
        Path bytesFile = fileFor(key);
        Path ctFile = dir.resolve(bytesFile.getFileName() + CONTENT_TYPE_SUFFIX);
        try {
            writeAtomically(bytesFile, icon.body());
            writeAtomically(ctFile, icon.contentType().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.debug("Failed to persist icon for key {}: {}", key, e.getMessage());
        }
    }

    private void writeAtomically(Path target, byte[] content) throws IOException {
        // Write to a temp file then move into place, so a crash mid-write can't leave a half file.
        Path tmp = dir.resolve(target.getFileName() + ".tmp");
        Files.write(tmp, content);
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException atomicNotSupported) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path fileFor(String key) {
        return dir.resolve(hash(key));
    }

    private static String hash(String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed present on every JVM; this never happens.
            throw new IllegalStateException(e);
        }
    }
}
