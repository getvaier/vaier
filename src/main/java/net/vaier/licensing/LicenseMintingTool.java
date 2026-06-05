package net.vaier.licensing;

import net.vaier.adapter.driven.Ed25519LicenseVerifierAdapter;
import net.vaier.domain.Edition;
import net.vaier.domain.License;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Command-line tool for the licence issuer (not part of the running app's request path). Mints a
 * signed Enterprise licence token with the private key that matches the public key baked into
 * {@link Ed25519LicenseVerifierAdapter}, then self-verifies the result so a key mismatch surfaces
 * at mint time rather than on a customer's instance.
 *
 * <pre>
 * mvn -q compile exec:java -Dexec.mainClass=net.vaier.licensing.LicenseMintingTool \
 *   -Dexec.args="--private-key .secrets/vaier-license-private.pem --customer 'Acme Ltd' \
 *                --expires 2027-01-01 --features lan-scanner"
 * </pre>
 *
 * Prints the token to stdout; install it on an instance via {@code VAIER_LICENSE}.
 */
public final class LicenseMintingTool {

    private LicenseMintingTool() {}

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = parse(args);
        String privateKeyPath = require(opts, "private-key");
        String customer = require(opts, "customer");
        Edition edition = Edition.valueOf(opts.getOrDefault("edition", "ENTERPRISE").toUpperCase());
        Instant issuedAt = Instant.now();
        Instant expiresAt = opts.containsKey("perpetual") ? null : parseExpiry(require(opts, "expires"));
        Set<String> features = parseFeatures(opts.get("features"));

        License license = new License(customer, edition, issuedAt, expiresAt, features);
        PrivateKey privateKey = loadPrivateKey(Path.of(privateKeyPath));
        String token = Ed25519LicenseVerifierAdapter.encode(license, privateKey);

        boolean selfVerifies = new Ed25519LicenseVerifierAdapter().verify(token).isPresent();
        if (!selfVerifies) {
            System.err.println("WARNING: minted token does NOT verify against the embedded public key — "
                + "the private key does not match the baked-in public key.");
        }

        System.err.println("Issued to : " + customer);
        System.err.println("Edition   : " + edition);
        System.err.println("Expires   : " + (expiresAt == null ? "never (perpetual)" : expiresAt));
        System.err.println("Features  : " + (features.isEmpty() ? "(none)" : String.join(", ", features)));
        System.err.println("Verifies  : " + (selfVerifies ? "yes" : "NO"));
        System.err.println("--- token (set as VAIER_LICENSE) ---");
        System.out.println(token);
    }

    private static Instant parseExpiry(String raw) {
        if (raw.contains("T")) {
            return Instant.parse(raw);
        }
        return LocalDate.parse(raw).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static Set<String> parseFeatures(String raw) {
        Set<String> features = new LinkedHashSet<>();
        if (raw != null && !raw.isBlank()) {
            for (String f : raw.split(",")) {
                if (!f.isBlank()) features.add(f.trim());
            }
        }
        return features;
    }

    private static PrivateKey loadPrivateKey(Path pem) throws Exception {
        String content = Files.readString(pem)
            .replaceAll("-----BEGIN PRIVATE KEY-----", "")
            .replaceAll("-----END PRIVATE KEY-----", "")
            .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(content);
        return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> opts = new java.util.HashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--")) {
                String key = args[i].substring(2);
                boolean hasValue = i + 1 < args.length && !args[i + 1].startsWith("--");
                opts.put(key, hasValue ? args[++i] : "true");
            }
        }
        return opts;
    }

    private static String require(Map<String, String> opts, String key) {
        String value = opts.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required --" + key);
        }
        return value;
    }
}
