package net.vaier.adapter.driven;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretCipherTest {

    @TempDir
    Path tempDir;

    private SecretCipher cipher() {
        return new SecretCipher(tempDir.toString());
    }

    @Test
    void roundTrips_multiLinePrivateKeyLikeString() {
        String pem = """
            -----BEGIN OPENSSH PRIVATE KEY-----
            b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtz
            c2gtZWQyNTUxOQAAACD= line two with spaces
            -----END OPENSSH PRIVATE KEY-----
            """;
        SecretCipher cipher = cipher();

        assertThat(cipher.decrypt(cipher.encrypt(pem))).isEqualTo(pem);
    }

    @Test
    void roundTrips_unicode() {
        String secret = "påsswørd-æøå-你好-🔐";
        SecretCipher cipher = cipher();

        assertThat(cipher.decrypt(cipher.encrypt(secret))).isEqualTo(secret);
    }

    @Test
    void encrypt_producesEnvelopeThatHidesPlaintext() {
        String secret = "super-secret-value";

        String encrypted = cipher().encrypt(secret);

        assertThat(encrypted).startsWith("enc:v1:");
        assertThat(encrypted).doesNotContain(secret);
    }

    @Test
    void encrypt_isNonDeterministic() {
        SecretCipher cipher = cipher();
        String secret = "same-input";

        assertThat(cipher.encrypt(secret)).isNotEqualTo(cipher.encrypt(secret));
    }

    @Test
    void decrypt_legacyPlaintext_isReturnedUnchanged() {
        assertThat(cipher().decrypt("plain-value")).isEqualTo("plain-value");
    }

    @Test
    void decrypt_null_returnsNull() {
        assertThat(cipher().decrypt(null)).isNull();
    }

    @Test
    void construction_touchesNoFilesystem_soWiringNeverDependsOnAWritableConfigDir() {
        // The full-context smoke test builds every bean with the default config dir (/vaier/config),
        // which is not writable on a CI runner. Resolving/generating the key must therefore be lazy:
        // merely constructing the cipher must not create (or require) the key file (#308 CI failure).
        Path keyFile = tempDir.resolve("vault.key");

        new SecretCipher(tempDir.toString());

        assertThat(Files.exists(keyFile)).isFalse();
    }

    @Test
    void keyFile_isWrittenWith0600Perms_onFirstUse() throws Exception {
        cipher().encrypt("anything");

        Path keyFile = tempDir.resolve("vault.key");
        assertThat(Files.exists(keyFile)).isTrue();
        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(keyFile);
        assertThat(perms).containsExactlyInAnyOrder(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    }

    @Test
    void keyIsStableAcrossInstances_secondInstanceDecryptsWhatFirstWrote() {
        SecretCipher first = cipher();
        String encrypted = first.encrypt("cross-instance");

        SecretCipher second = new SecretCipher(tempDir.toString());

        assertThat(second.decrypt(encrypted)).isEqualTo("cross-instance");
    }

    @Test
    void decrypt_tamperedCiphertext_throws() {
        SecretCipher cipher = cipher();
        String encrypted = cipher.encrypt("authentic");
        // Flip a character in the base64 body (after the enc:v1: prefix).
        char[] chars = encrypted.toCharArray();
        int idx = encrypted.length() - 3;
        chars[idx] = chars[idx] == 'A' ? 'B' : 'A';
        String tampered = new String(chars);

        assertThatThrownBy(() -> cipher.decrypt(tampered)).isInstanceOf(RuntimeException.class);
    }
}
