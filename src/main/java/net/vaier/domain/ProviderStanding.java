package net.vaier.domain;

/** One identity provider as Settings shows it: where it comes from, and its client id. Never its secret. */
public record ProviderStanding(IdentityProvider provider, ProviderSource source, String clientId) {}
