package net.vaier.domain;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/** An identity provider Dex can federate, by its connector id, and where the operator registers an app with it. */
public enum IdentityProvider {
    GOOGLE("google", "Google", "https://console.cloud.google.com/apis/credentials"),
    GITHUB("github", "GitHub", "https://github.com/settings/developers");

    private final String connectorId;
    private final String displayName;
    private final String consoleUrl;

    IdentityProvider(String connectorId, String displayName, String consoleUrl) {
        this.connectorId = connectorId;
        this.displayName = displayName;
        this.consoleUrl = consoleUrl;
    }

    public String connectorId() { return connectorId; }
    public String displayName() { return displayName; }
    public String consoleUrl() { return consoleUrl; }

    /** The provider a Dex connector id names; the first-run password's {@code local} is not one. */
    public static Optional<IdentityProvider> fromConnectorId(String connectorId) {
        if (connectorId == null) return Optional.empty();
        String normalised = connectorId.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(p -> p.connectorId.equals(normalised)).findFirst();
    }
}
