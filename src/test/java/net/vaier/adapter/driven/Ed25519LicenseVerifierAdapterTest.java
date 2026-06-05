package net.vaier.adapter.driven;

import net.vaier.domain.Edition;
import net.vaier.domain.License;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class Ed25519LicenseVerifierAdapterTest {

    private static final Instant ISSUED = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant EXPIRES = Instant.parse("2027-01-01T00:00:00Z");

    private final KeyPair keyPair = generate();
    private final Ed25519LicenseVerifierAdapter adapter =
        new Ed25519LicenseVerifierAdapter(keyPair.getPublic());

    @Test
    void verifiesAndParsesAGenuineToken() {
        License license = new License("Acme Ltd", Edition.ENTERPRISE, ISSUED, EXPIRES,
            Set.of("lan-scanner"));
        String token = mint(license, keyPair.getPrivate());

        Optional<License> verified = adapter.verify(token);

        assertThat(verified).isPresent();
        assertThat(verified.get().customer()).isEqualTo("Acme Ltd");
        assertThat(verified.get().edition()).isEqualTo(Edition.ENTERPRISE);
        assertThat(verified.get().issuedAt()).isEqualTo(ISSUED);
        assertThat(verified.get().expiresAt()).isEqualTo(EXPIRES);
        assertThat(verified.get().features()).containsExactly("lan-scanner");
    }

    @Test
    void parsesAPerpetualTokenWithNoExpiry() {
        License license = new License("Acme Ltd", Edition.ENTERPRISE, ISSUED, null, Set.of());
        String token = mint(license, keyPair.getPrivate());

        Optional<License> verified = adapter.verify(token);

        assertThat(verified).isPresent();
        assertThat(verified.get().expiresAt()).isNull();
    }

    @Test
    void rejectsATamperedPayload() {
        License license = new License("Acme Ltd", Edition.ENTERPRISE, ISSUED, EXPIRES, Set.of());
        String token = mint(license, keyPair.getPrivate());
        // Forge a Community → Enterprise upgrade by swapping the payload, keeping the old signature.
        License forged = new License("Acme Ltd", Edition.ENTERPRISE, ISSUED, EXPIRES, Set.of("everything"));
        String tampered = base64Url(payloadJson(forged)) + "." + token.split("\\.")[1];

        assertThat(adapter.verify(tampered)).isEmpty();
    }

    @Test
    void rejectsATokenSignedByAForeignKey() {
        License license = new License("Acme Ltd", Edition.ENTERPRISE, ISSUED, EXPIRES, Set.of());
        String token = mint(license, generate().getPrivate());

        assertThat(adapter.verify(token)).isEmpty();
    }

    @Test
    void rejectsMalformedAndBlankTokens() {
        assertThat(adapter.verify(null)).isEmpty();
        assertThat(adapter.verify("")).isEmpty();
        assertThat(adapter.verify("not-a-token")).isEmpty();
        assertThat(adapter.verify("only.two.but.garbage")).isEmpty();
    }

    // --- helpers that mirror the minting wire format ---

    private static String mint(License license, PrivateKey privateKey) {
        String payloadSegment = base64Url(payloadJson(license));
        byte[] signature = sign(payloadSegment.getBytes(StandardCharsets.UTF_8), privateKey);
        return payloadSegment + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
    }

    private static String payloadJson(License l) {
        String features = l.features().stream()
            .map(f -> "\"" + f + "\"")
            .reduce((a, b) -> a + "," + b).map(s -> "[" + s + "]").orElse("[]");
        String expires = l.expiresAt() == null ? "null" : "\"" + l.expiresAt() + "\"";
        return "{"
            + "\"customer\":\"" + l.customer() + "\","
            + "\"edition\":\"" + l.edition() + "\","
            + "\"issuedAt\":\"" + l.issuedAt() + "\","
            + "\"expiresAt\":" + expires + ","
            + "\"features\":" + features
            + "}";
    }

    private static String base64Url(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] sign(byte[] data, PrivateKey key) {
        try {
            Signature sig = Signature.getInstance("Ed25519");
            sig.initSign(key);
            sig.update(data);
            return sig.sign();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static KeyPair generate() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
