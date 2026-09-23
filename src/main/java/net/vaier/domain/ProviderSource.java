package net.vaier.domain;

/** Where an identity provider's credentials come from. {@code .env} wins over Settings. */
public enum ProviderSource {
    NOT_CONFIGURED,
    ENVIRONMENT,
    SETTINGS
}
