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
     * The PBKDF2 work factor, named here because it is shared: the kit <em>prints</em> it in the command that
     * opens it, and an implementation that used a different number would produce a file its own instructions
     * could not open.
     *
     * <p>Far above OpenSSL's default of 10,000, and deliberately so. The kit's whole design puts copies of
     * the ciphertext on several machines, so the realistic attack is not against Vaier at all — it is someone
     * who has obtained one copy and grinds it offline at their leisure. Every additional copy is another
     * chance for that to happen, so the work factor is what keeps the number of copies from being a
     * liability. The cost is about half a second, once, to a person who has already lost their fleet.
     */
    int PBKDF2_ITERATIONS = 600_000;

    /**
     * The base64-armoured OpenSSL envelope for {@code plaintext} under {@code passphrase}. A fresh random
     * salt per call, so writing the same kit twice never yields the same bytes — a host that keeps an old
     * copy alongside a new one reveals nothing by the comparison.
     */
    String encrypt(String plaintext, String passphrase);
}
