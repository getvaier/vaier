package net.vaier.domain;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The identity providers added from Settings, with their credentials, and whether the first-run door is
 * held open. dex-init and oauth2-proxy-init read the same facts from the file and apply the same door rule,
 * and a provider whose pair is set in {@code .env} is never taken from here.
 */
public record SignInSettings(Map<IdentityProvider, ProviderCredentials> providers, boolean firstRunDoorOpen) {

    public SignInSettings {
        providers = providers == null || providers.isEmpty() ? Map.of() : Map.copyOf(providers);
    }

    public static SignInSettings none() {
        return new SignInSettings(Map.of(), false);
    }

    public boolean hasAnyProvider() {
        return !providers.isEmpty();
    }

    /** Open while no provider exists anywhere, or while a provider added here has not yet let an admin in. */
    public boolean isFirstRunDoorOpen(Set<IdentityProvider> setInEnvironment) {
        return firstRunDoorOpen || (setInEnvironment.isEmpty() && providers.isEmpty());
    }

    /** Adds or replaces a provider. The door stays as it stood: closing it is an admin's sign-in, not a save. */
    public SignInSettings withProvider(IdentityProvider provider, ProviderCredentials credentials,
                                       Set<IdentityProvider> setInEnvironment) {
        if (setInEnvironment.contains(provider)) {
            throw new IllegalArgumentException(provider.displayName() + " is set in .env, which wins over "
                + "Settings — change it there.");
        }
        Map<IdentityProvider, ProviderCredentials> next = new EnumMap<>(IdentityProvider.class);
        next.putAll(providers);
        next.put(provider, credentials);
        return new SignInSettings(next, isFirstRunDoorOpen(setInEnvironment));
    }

    /** An admin signing in through a provider proves the provider works, so the first-run password can go. */
    public boolean closesFirstRunDoorOn(AccessEntry entry) {
        return firstRunDoorOpen && entry.isAdmin()
            && IdentityProvider.fromConnectorId(entry.getProvider()).isPresent();
    }

    public SignInSettings withFirstRunDoorClosed() {
        return new SignInSettings(providers, false);
    }

    /** Each provider in order, taking {@code .env}'s pair — given as its client id — over this file's. */
    public List<ProviderStanding> standings(Map<IdentityProvider, String> environmentClientIds) {
        return Arrays.stream(IdentityProvider.values()).map(p -> {
            if (environmentClientIds.containsKey(p)) {
                return new ProviderStanding(p, ProviderSource.ENVIRONMENT, environmentClientIds.get(p));
            }
            ProviderCredentials stored = providers.get(p);
            return stored == null
                ? new ProviderStanding(p, ProviderSource.NOT_CONFIGURED, null)
                : new ProviderStanding(p, ProviderSource.SETTINGS, stored.clientId());
        }).toList();
    }
}
