package net.vaier.domain;

import lombok.Builder;
import lombok.Data;

import java.util.regex.Pattern;

@Data
@Builder
public class VaierConfig {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

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

    private static void validateAcmeEmail(String acmeEmail) {
        requireNonBlank(acmeEmail, "acmeEmail");
        if (!EMAIL_PATTERN.matcher(acmeEmail).matches()) {
            throw new IllegalArgumentException("acmeEmail is not a valid email format");
        }
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
