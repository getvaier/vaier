package net.vaier.domain;

import lombok.Builder;
import lombok.Data;

import java.util.Optional;

@Data
@Builder(toBuilder = true)
public class VaierConfig {

    private String domain;
    private String awsKey;
    private String awsSecret;
    private String acmeEmail;
    private String smtpHost;
    private Integer smtpPort;
    private String smtpUsername;
    private String smtpSender;

    public static void validateForSetup(String domain, String awsKey, String awsSecret, String acmeEmail) {
        requireNonBlank(domain, "domain");
        requireNonBlank(awsKey, "awsKey");
        requireNonBlank(awsSecret, "awsSecret");
        validateAcmeEmail(acmeEmail);
    }

    /** A copy with the AWS credentials replaced; every other field carries over unchanged. */
    public VaierConfig withAwsCredentials(String newAwsKey, String newAwsSecret) {
        return toBuilder()
            .awsKey(newAwsKey)
            .awsSecret(newAwsSecret)
            .build();
    }

    /** A copy with the SMTP settings replaced; every other field carries over unchanged. */
    public VaierConfig withSmtpSettings(String newSmtpHost, int newSmtpPort,
                                        String newSmtpUsername, String newSmtpSender) {
        return toBuilder()
            .smtpHost(newSmtpHost)
            .smtpPort(newSmtpPort)
            .smtpUsername(newSmtpUsername)
            .smtpSender(newSmtpSender)
            .build();
    }

    /** The AWS key with all but its last four characters masked, for display. */
    public String maskedAwsKey() {
        if (awsKey == null || awsKey.length() <= 4) {
            return awsKey;
        }
        return "****" + awsKey.substring(awsKey.length() - 4);
    }

    /**
     * The effective SMTP password: the freshly provided one when non-blank, otherwise the
     * previously stored one. Throws when neither is available.
     */
    public static String resolveSmtpPassword(String provided, Optional<String> stored) {
        if (provided != null && !provided.isBlank()) {
            return provided;
        }
        return stored
            .filter(p -> !p.isBlank())
            .orElseThrow(() -> new IllegalArgumentException("SMTP password is required"));
    }

    private static void validateAcmeEmail(String acmeEmail) {
        requireNonBlank(acmeEmail, "acmeEmail");
        if (!EmailFormat.isValid(acmeEmail)) {
            throw new IllegalArgumentException("acmeEmail is not a valid email format");
        }
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
