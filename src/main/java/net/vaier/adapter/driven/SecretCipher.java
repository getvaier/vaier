package net.vaier.adapter.driven;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Symmetric encryption for secrets at rest — the credential vault's cipher. AES-256-GCM with a fresh
 * random IV per encryption. This is a persistence concern (how secrets sit on disk), not a domain
 * decision, so it lives in the driven-adapter layer and the domain never sees it.
 *
 * <p>Envelope: {@code encrypt(plaintext)} returns the literal prefix {@code enc:v1:} followed by
 * {@code Base64(IV ‖ ciphertext ‖ GCM-tag)}. {@code decrypt} reverses that; a stored value that is
 * <em>not</em> {@code enc:v1:}-prefixed is treated as legacy plaintext and returned unchanged, which
 * makes migrating existing plaintext secrets transparent (they get re-encrypted on the next save).
 *
 * <p>Master key resolution (lazy — happens on the first encrypt/decrypt of an envelope, never in the
 * constructor, so building this bean touches no filesystem): (a) env {@code VAIER_VAULT_KEY} (Base64 of
 * exactly 32 bytes) when set and valid; else (b) {@code ${configDir}/vault.key}; else (c) a fresh
 * 32-byte key generated with {@link SecureRandom}, written Base64 to {@code ${configDir}/vault.key}
 * with 0600 permissions.
 */
@Component
@Slf4j
public class SecretCipher {

    /** Envelope prefix marking a value produced by this cipher (version 1). */
    static final String PREFIX = "enc:v1:";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String KEY_ALGORITHM = "AES";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int KEY_LENGTH_BYTES = 32;
    private static final String KEY_FILE_NAME = "vault.key";
    private static final String ENV_KEY = "VAIER_VAULT_KEY";

    private final String configDir;
    private final SecureRandom random = new SecureRandom();
    private volatile SecretKeySpec key;

    public SecretCipher() {
        this(System.getenv().getOrDefault("VAIER_CONFIG_PATH", "/vaier/config"));
    }

    public SecretCipher(String configDir) {
        this.configDir = configDir;
    }

    /**
     * The master key, resolved (and generated + persisted if needed) on first use. Resolution is lazy
     * so that merely constructing this bean never touches the filesystem — wiring the application
     * context must not depend on {@code ${configDir}} being writable (the smoke test builds every bean
     * with the default {@code /vaier/config}, which a CI runner cannot create). Double-checked locking
     * keeps concurrent encrypt/decrypt from generating two different keys.
     */
    private SecretKeySpec key() {
        SecretKeySpec resolved = key;
        if (resolved == null) {
            synchronized (this) {
                resolved = key;
                if (resolved == null) {
                    resolved = resolveKey(configDir);
                    key = resolved;
                }
            }
        }
        return resolved;
    }

    /**
     * The {@code enc:v1:} envelope for {@code plaintext}, or null when {@code plaintext} is null (so a
     * nullable secret like a key passphrase encrypts to null rather than to an envelope of "").
     */
    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to encrypt secret", e);
        }
    }

    /**
     * The plaintext behind {@code stored}: null when {@code stored} is null; {@code stored} itself
     * (unchanged) when it is not {@code enc:v1:}-prefixed (legacy plaintext pass-through); otherwise
     * the decrypted value. Throws when an {@code enc:v1:} envelope fails GCM authentication (tampered
     * or wrong key) — a corrupted secret must never silently read as something else.
     */
    public String decrypt(String stored) {
        if (stored == null) {
            return null;
        }
        if (!stored.startsWith(PREFIX)) {
            return stored;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            byte[] iv = Arrays.copyOfRange(combined, 0, IV_LENGTH_BYTES);
            byte[] ciphertext = Arrays.copyOfRange(combined, IV_LENGTH_BYTES, combined.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to decrypt secret (authentication failed)", e);
        }
    }

    private static SecretKeySpec resolveKey(String configDir) {
        String envKey = System.getenv(ENV_KEY);
        if (envKey != null && !envKey.isBlank()) {
            byte[] fromEnv = decodeKey(envKey.trim());
            if (fromEnv != null) {
                log.info("Vault master key loaded from {} environment variable", ENV_KEY);
                return new SecretKeySpec(fromEnv, KEY_ALGORITHM);
            }
            log.warn("{} is set but not Base64 of exactly {} bytes; falling back to the key file",
                ENV_KEY, KEY_LENGTH_BYTES);
        }

        Path keyFile = Path.of(configDir, KEY_FILE_NAME);
        if (Files.exists(keyFile)) {
            try {
                byte[] fromFile = decodeKey(Files.readString(keyFile).trim());
                if (fromFile != null) {
                    return new SecretKeySpec(fromFile, KEY_ALGORITHM);
                }
                log.warn("{} does not contain a {}-byte Base64 key; regenerating", keyFile, KEY_LENGTH_BYTES);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to read vault key file " + keyFile, e);
            }
        }
        return generateAndPersistKey(keyFile);
    }

    /** Base64-decodes {@code value} to exactly {@link #KEY_LENGTH_BYTES}, or null when it is not that. */
    private static byte[] decodeKey(String value) {
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            return decoded.length == KEY_LENGTH_BYTES ? decoded : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static SecretKeySpec generateAndPersistKey(Path keyFile) {
        byte[] keyBytes = new byte[KEY_LENGTH_BYTES];
        new SecureRandom().nextBytes(keyBytes);
        try {
            Path parent = keyFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(keyFile, Base64.getEncoder().encodeToString(keyBytes));
            SecureFilePermissions.lockDownFile(keyFile);
            log.info("Generated a new vault master key at {}", keyFile);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to persist vault key to " + keyFile, e);
        }
        return new SecretKeySpec(keyBytes, KEY_ALGORITHM);
    }
}
