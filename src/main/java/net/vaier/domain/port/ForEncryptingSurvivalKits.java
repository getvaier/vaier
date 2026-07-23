package net.vaier.domain.port;

/**
 * A driven port the domain calls to lock a {@link net.vaier.domain.SurvivalKit}'s contents with the one
 * passphrase the operator knows.
 *
 * <p>This is deliberately <em>not</em> the credential vault's cipher ({@code SecretCipher}). That one is
 * keyed by a file on the Vaier server, which is precisely the machine a survival kit assumes is gone. A kit
 * it could open would be a kit that dies with it.
 *
 * <p><b>The envelope is part of the contract, not an implementation detail.</b> The kit prints the command
 * that opens it on its own face — {@code openssl enc -aes-256-cbc -pbkdf2 -d -a} — so any implementation must
 * produce exactly what that command reads: OpenSSL's {@code Salted__} envelope, PBKDF2-HMAC-SHA256 key
 * derivation at OpenSSL's default iteration count, AES-256-CBC, base64-armoured in short lines. Whoever opens
 * a kit has lost their fleet; they cannot be asked to install anything, and Vaier is not there to be asked.
 */
public interface ForEncryptingSurvivalKits {

    /**
     * The base64-armoured OpenSSL envelope for {@code plaintext} under {@code passphrase}. A fresh random
     * salt per call, so writing the same kit twice never yields the same bytes — a host that keeps an old
     * copy alongside a new one reveals nothing by the comparison.
     */
    String encrypt(String plaintext, String passphrase);
}
