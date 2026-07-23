package net.vaier.adapter.driven;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import net.vaier.domain.port.ForEncryptingSurvivalKits;
import org.springframework.stereotype.Component;

/**
 * Produces exactly what {@code openssl enc -aes-256-cbc -pbkdf2 -d -a} reads, so a survival kit opens with a
 * tool that is already on the machine of whoever needs it.
 *
 * <p>Reimplementing OpenSSL's envelope in Java rather than shelling out to the binary is deliberate: Vaier
 * runs in a slim container that need not carry an {@code openssl} CLI, and a kit that failed to be written
 * because of a missing tool would fail silently and be discovered on the worst day. The obligation this
 * creates — that Vaier's bytes and OpenSSL's expectations never drift apart — is discharged by a test that
 * runs the real binary against this output.
 *
 * <p>The envelope, which is OpenSSL's and must not be "improved":
 * <ul>
 *   <li>{@code "Salted__"} (8 ASCII bytes) followed by an 8-byte random salt</li>
 *   <li>key and IV from PBKDF2-HMAC-SHA256 over passphrase+salt, {@value #ITERATIONS} iterations, which the printed
 *       command spells out with {@code -iter} because it is far above OpenSSL's own default</li>
 *   <li>AES-256-CBC, PKCS#5 padding, over the whole of the above</li>
 *   <li>base64 in {@value #BASE64_LINE_LENGTH}-character lines, which is what {@code -a} expects</li>
 * </ul>
 */
@Component
public class OpensslEnvelopeAdapter implements ForEncryptingSurvivalKits {

    /** OpenSSL's salted-envelope magic; the first eight bytes of every file it writes with a password. */
    private static final byte[] MAGIC = "Salted__".getBytes(StandardCharsets.US_ASCII);
    private static final int SALT_LENGTH_BYTES = 8;
    private static final int KEY_LENGTH_BYTES = 32;
    private static final int IV_LENGTH_BYTES = 16;
    /** Shared with the printed command via the port; see {@link ForEncryptingSurvivalKits#PBKDF2_ITERATIONS}. */
    private static final int ITERATIONS = ForEncryptingSurvivalKits.PBKDF2_ITERATIONS;
    private static final int BASE64_LINE_LENGTH = 64;

    private final SecureRandom random = new SecureRandom();

    @Override
    public String encrypt(String plaintext, String passphrase) {
        byte[] salt = new byte[SALT_LENGTH_BYTES];
        random.nextBytes(salt);
        try {
            byte[] derived = deriveKeyAndIv(passphrase, salt);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(derived, 0, KEY_LENGTH_BYTES, "AES"),
                new IvParameterSpec(derived, KEY_LENGTH_BYTES, IV_LENGTH_BYTES));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] envelope = new byte[MAGIC.length + salt.length + ciphertext.length];
            System.arraycopy(MAGIC, 0, envelope, 0, MAGIC.length);
            System.arraycopy(salt, 0, envelope, MAGIC.length, salt.length);
            System.arraycopy(ciphertext, 0, envelope, MAGIC.length + salt.length, ciphertext.length);
            return Base64.getMimeEncoder(BASE64_LINE_LENGTH, new byte[] {'\n'}).encodeToString(envelope);
        } catch (GeneralSecurityException e) {
            // Never swallowed into an unencrypted fallback: a kit that quietly wrote itself in the clear
            // would look exactly like a protected one.
            throw new IllegalStateException("Failed to encrypt the survival kit", e);
        }
    }

    /** The 48 bytes OpenSSL derives for {@code -aes-256-cbc -pbkdf2}: 32 of key followed by 16 of IV. */
    private static byte[] deriveKeyAndIv(String passphrase, byte[] salt) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(passphrase.toCharArray(), salt, ITERATIONS,
            (KEY_LENGTH_BYTES + IV_LENGTH_BYTES) * 8);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }
}
