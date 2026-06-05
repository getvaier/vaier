package net.vaier.adapter.driven;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.Edition;
import net.vaier.domain.License;
import net.vaier.domain.port.ForVerifyingLicense;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Verifies offline, Ed25519-signed licence tokens against a public key baked into the binary. No
 * network call is made — the licence is self-contained and validated locally, which suits Vaier's
 * self-hosted, no-database ethos.
 *
 * <p>Wire format (JWT-like but minimal): {@code base64url(payloadJson) "." base64url(signature)},
 * where the signature is over the ASCII bytes of the payload segment. The matching private key
 * (held only by the licence issuer) mints tokens via {@code LicenseMintingTool}, which reuses
 * {@link #encode}.
 */
@Component
@Slf4j
public class Ed25519LicenseVerifierAdapter implements ForVerifyingLicense {

    /** X.509 (SubjectPublicKeyInfo) encoding of the Vaier licence public key, base64. */
    static final String EMBEDDED_PUBLIC_KEY_X509_B64 =
        "MCowBQYDK2VwAyEAZ/rQnPWkImkraTUimQVj1GaCXdeM5aM8flItPRj6Xao=";

    private static final String SEPARATOR = ".";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PublicKey publicKey;

    public Ed25519LicenseVerifierAdapter() {
        this(loadEmbeddedPublicKey());
    }

    Ed25519LicenseVerifierAdapter(PublicKey publicKey) {
        this.publicKey = publicKey;
    }

    @Override
    public Optional<License> verify(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        int dot = token.indexOf(SEPARATOR);
        if (dot <= 0 || dot != token.lastIndexOf(SEPARATOR)) {
            return Optional.empty();
        }
        String payloadSegment = token.substring(0, dot);
        String signatureSegment = token.substring(dot + 1);
        try {
            byte[] signature = Base64.getUrlDecoder().decode(signatureSegment);
            if (!signatureValid(payloadSegment.getBytes(StandardCharsets.UTF_8), signature)) {
                return Optional.empty();
            }
            byte[] payload = Base64.getUrlDecoder().decode(payloadSegment);
            return Optional.of(parse(payload));
        } catch (RuntimeException e) {
            // Malformed base64, JSON, timestamps, or an unknown edition — an unverifiable token is
            // simply "not licensed"; never logged with the token contents.
            log.warn("Ignoring an unverifiable licence token");
            return Optional.empty();
        }
    }

    private boolean signatureValid(byte[] data, byte[] signature) {
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(data);
            return verifier.verify(signature);
        } catch (Exception e) {
            return false;
        }
    }

    private static License parse(byte[] payload) {
        try {
            JsonNode node = MAPPER.readTree(payload);
            String customer = node.path("customer").asText(null);
            Edition edition = Edition.valueOf(node.path("edition").asText());
            Instant issuedAt = parseInstant(node.get("issuedAt"));
            Instant expiresAt = parseInstant(node.get("expiresAt"));
            Set<String> features = new LinkedHashSet<>();
            JsonNode featureArray = node.get("features");
            if (featureArray != null) {
                featureArray.forEach(f -> features.add(f.asText()));
            }
            return new License(customer, edition, issuedAt, expiresAt, features);
        } catch (Exception e) {
            throw new IllegalArgumentException("Unparseable licence payload", e);
        }
    }

    private static Instant parseInstant(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return Instant.parse(node.asText());
    }

    /**
     * Builds a signed licence token for {@code license} using the issuer's private key. Lives here
     * so the wire format has a single owner shared by the verifier and the minting tool.
     */
    public static String encode(License license, PrivateKey privateKey) {
        try {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("customer", license.customer());
            node.put("edition", license.edition().name());
            node.put("issuedAt", license.issuedAt().toString());
            if (license.expiresAt() == null) {
                node.putNull("expiresAt");
            } else {
                node.put("expiresAt", license.expiresAt().toString());
            }
            ArrayNode features = node.putArray("features");
            license.features().forEach(features::add);

            String payloadSegment = base64Url(MAPPER.writeValueAsBytes(node));
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(privateKey);
            signer.update(payloadSegment.getBytes(StandardCharsets.UTF_8));
            return payloadSegment + SEPARATOR + base64Url(signer.sign());
        } catch (Exception e) {
            throw new RuntimeException("Failed to mint licence token", e);
        }
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static PublicKey loadEmbeddedPublicKey() {
        try {
            byte[] der = Base64.getDecoder().decode(EMBEDDED_PUBLIC_KEY_X509_B64);
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("Embedded licence public key is invalid", e);
        }
    }
}
