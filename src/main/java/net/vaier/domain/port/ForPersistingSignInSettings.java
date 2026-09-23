package net.vaier.domain.port;

import net.vaier.domain.SignInSettings;

/** The sign-in settings file dex-init and oauth2-proxy-init read. {@link SignInSettings#none()} when absent. */
public interface ForPersistingSignInSettings {
    SignInSettings read();

    void save(SignInSettings settings);
}
