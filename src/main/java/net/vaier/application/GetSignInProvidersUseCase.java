package net.vaier.application;

import net.vaier.domain.ProviderStanding;

import java.util.List;

public interface GetSignInProvidersUseCase {

    /** Each identity provider's standing, the redirect URI to register, and whether the first-run door is open. */
    SignInOverview getSignInProviders();

    record SignInOverview(List<ProviderStanding> providers, String redirectUri, boolean firstRunDoorOpen) {}
}
