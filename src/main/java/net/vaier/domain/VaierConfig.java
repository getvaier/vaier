package net.vaier.domain;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class VaierConfig {
    private String domain;
    private String awsKey;
    private String awsSecret;
    private String acmeEmail;
    private String smtpHost;
    private Integer smtpPort;
    private String smtpUsername;
    private String smtpSender;
}
