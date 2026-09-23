package net.vaier.application;

import net.vaier.domain.IdentityProvider;
import net.vaier.domain.SignInApplyOutcome;

public interface AddSignInProviderUseCase {

    /** Stores the provider's credentials and makes them live; waits for Dex and the sign-in page to restart. */
    SignInApplyOutcome addSignInProvider(IdentityProvider provider, String clientId, String clientSecret);
}
