package net.vaier.application;

public interface SetPersonalServiceCredentialUseCase {

    /** Hand {@code host} this login for the person signed in as {@code email}, instead of the shared one. */
    void setPersonalServiceCredential(String host, String email, String username, String password);
}
